/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2004-2005 TONBELLER AG
// Copyright (C) 2006-2017 Hitachi Vantara
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.calc.*;
import mondrian.mdx.ResolvedFunCall;
import mondrian.olap.*;
import mondrian.olap.fun.*;
import mondrian.rolap.sql.*;

import java.util.*;

/**
 * Creates a {@link mondrian.olap.NativeEvaluator} that evaluates NON EMPTY
 * CrossJoin in SQL. The generated SQL will join the dimension tables with
 * the fact table and return all combinations that have a
 * corresponding row in the fact table. The current context (slicer) is
 * used for filtering (WHERE clause in SQL). This very effective computes
 * queries like
 *
 * <pre>
 *   SELECT ...
 *   NON EMTPY Crossjoin(
 *       [product].[name].members,
 *       [customer].[name].members) ON ROWS
 *   FROM [Sales]
 *   WHERE ([store].[store #14])
 * </pre>
 *
 * where both, customer.name and product.name have many members, but the
 * resulting crossjoin only has few.
 *
 * <p>The implementation currently can not handle sets containting
 * parent/child hierarchies, ragged hierarchies, calculated members and
 * the ALL member. Otherwise all
 *
 * @author av
 * @since Nov 21, 2005
 */
public class RolapNativeCrossJoin extends RolapNativeSet {

    public RolapNativeCrossJoin() {
        super.setEnabled(
            MondrianProperties.instance().EnableNativeCrossJoin.get());
    }

    /**
     * Constraint that restricts the result to the current context.
     *
     * <p>If the current context contains calculated members, silently ignores
     * them. This means means that too many members are returned, but this does
     * not matter, because the {@link RolapConnection.NonEmptyResult} will
     * filter out these later.</p>
     */
    static class NonEmptyCrossJoinConstraint extends SetConstraint {
        NonEmptyCrossJoinConstraint(
            CrossJoinArg[] args,
            RolapEvaluator evaluator)
        {
            // Cross join ignores calculated members, including the ones from
            // the slicer.
            super(args, evaluator, false);
        }

        public RolapMember findMember(Object key) {
            for (CrossJoinArg arg : args) {
                if (arg instanceof MemberListCrossJoinArg) {
                    final MemberListCrossJoinArg crossJoinArg =
                        (MemberListCrossJoinArg) arg;
                    final List<RolapMember> memberList =
                        crossJoinArg.getMembers();
                    for (RolapMember rolapMember : memberList) {
                        if (key.equals(rolapMember.getKey())) {
                            return rolapMember;
                        }
                    }
                }
            }
            return null;
        }
    }

    protected boolean restrictMemberTypes() {
        return false;
    }

    NativeEvaluator createEvaluator(
        RolapEvaluator evaluator,
        FunDef fun,
        Exp[] args)
    {
        if (!isEnabled()) {
            // native crossjoins were explicitly disabled, so no need
            // to alert about not using them
            return null;
        }
        RolapCube cube = evaluator.getCube();

        List<CrossJoinArg[]> allArgs =
            crossJoinArgFactory()
                .checkCrossJoin(evaluator, fun, args, false);

        // checkCrossJoinArg returns a list of CrossJoinArg arrays.  The first
        // array is the CrossJoin dimensions.  The second array, if any,
        // contains additional constraints on the dimensions. If either the list
        // or the first array is null, then native cross join is not feasible.
        if (allArgs == null || allArgs.isEmpty() || allArgs.get(0) == null) {
            // The static/eager-expansion recognition above failed. Excel's own PivotTable MDX
            // wraps every axis in Hierarchize/AddCalculatedMembers/DrilldownLevel/DrilldownMember
            // to represent drill state, and the evaluated result almost always mixes the All
            // member together with its real children (Excel wants the subtotal row shown
            // alongside the detail rows) - a shape even CrossJoinArgFactory.expandNonNative's own
            // "evaluate and wrap" fallback can't use, since MemberListCrossJoinArg.create requires
            // every member in the list to be from the same level. See context/native_query.md §7
            // for the production evidence this was written against.
            NativeEvaluator multiVariant =
                tryMultiVariantCrossJoin(evaluator, fun, args);
            if (multiVariant != null) {
                return multiVariant;
            }
            // Something in the arguments to the crossjoin prevented
            // native evaluation; may need to alert
            alertCrossJoinNonNative(
                evaluator,
                fun,
                "arguments not supported");
            return null;
        }

        CrossJoinArg[] cjArgs = allArgs.get(0);

        // check if all CrossJoinArgs are "All" members or Calc members
        // "All" members do not have relational expression, and Calc members
        // in the input could produce incorrect results.
        //
        // If NECJ only has AllMembers, or if there is at least one CalcMember,
        // then sql evaluation is not possible.
        int countNonNativeInputArg = 0;

        for (CrossJoinArg arg : cjArgs) {
            if (arg instanceof MemberListCrossJoinArg) {
                MemberListCrossJoinArg cjArg =
                    (MemberListCrossJoinArg)arg;
                if (cjArg.hasAllMember() || cjArg.isEmptyCrossJoinArg()) {
                    ++countNonNativeInputArg;
                }
                if (cjArg.hasCalcMembers()) {
                    countNonNativeInputArg = cjArgs.length;
                    break;
                }
            }
        }

        if (countNonNativeInputArg == cjArgs.length) {
            // If all inputs contain "All" members; or
            // if all inputs are MemberListCrossJoinArg with empty member list
            // content, then native evaluation is not feasible.
            alertCrossJoinNonNative(
                evaluator,
                fun,
                "either all arguments contain the ALL member, "
                + "or empty member lists, or one has a calculated member");
            return null;
        }

        if (isPreferInterpreter(cjArgs, true)) {
            // Native evaluation wouldn't buy us anything, so no
            // need to alert
            return null;
        }

        // Verify that args are valid
        List<RolapLevel> levels = new ArrayList<RolapLevel>();
        for (CrossJoinArg cjArg : cjArgs) {
            RolapLevel level = cjArg.getLevel();
            if (level != null) {
                // Only add non null levels. These levels have real
                // constraints.
                levels.add(level);
            }
        }

        if (SqlConstraintUtils.measuresConflictWithMembers(
                evaluator.getQuery().getMeasuresMembers(), cjArgs))
        {
            alertCrossJoinNonNative(
                evaluator,
                fun,
                "One or more calculated measures conflict with crossjoin args");
            return null;
        }

        if (cube.isVirtual()
            && !evaluator.getQuery().nativeCrossJoinVirtualCube())
        {
            // Something in the query at large (namely, some unsupported
            // function on the [Measures] dimension) prevented native
            // evaluation with virtual cubes; may need to alert
            alertCrossJoinNonNative(
                evaluator,
                fun,
                "not all functions on [Measures] dimension supported");
            return null;
        }

        if (!NonEmptyCrossJoinConstraint.isValidContext(
                evaluator,
                false,
                levels.toArray(new RolapLevel[levels.size()]),
                restrictMemberTypes()))
        {
            alertCrossJoinNonNative(
                evaluator,
                fun,
                "Slicer context does not support native crossjoin.");
            return null;
        }

        // join with fact table will always filter out those members
        // that dont have a row in the fact table
        if (!evaluator.isNonEmpty()) {
            return null;
        }

        LOGGER.debug("using native crossjoin");

        // Create a new evaluation context, eliminating any outer context for
        // the dimensions referenced by the inputs to the NECJ
        // (otherwise, that outer context would be incorrectly intersected
        // with the constraints from the inputs).
        final int savepoint = evaluator.savepoint();

        try {
            overrideContext(evaluator, cjArgs, null);

            // Use the combined CrossJoinArg for the tuple constraint,
            // which will be translated to the SQL WHERE clause.
            CrossJoinArg[] cargs = combineArgs(allArgs);

            // Now construct the TupleConstraint that contains both the CJ
            // dimensions and the additional filter on them. It will make a
            // copy of the evaluator.
            TupleConstraint constraint =
                buildConstraint(evaluator, fun, cargs);
            // Use the just the CJ CrossJoiArg for the evaluator context,
            // which will be translated to select list in sql.
            final SchemaReader schemaReader = evaluator.getSchemaReader();
            return new SetEvaluator(cjArgs, schemaReader, constraint);
        } finally {
            evaluator.restore(savepoint);
        }
    }

    /** Bounds on {@link #tryMultiVariantCrossJoin}, see its own doc. */
    private static final int MAX_OPERANDS = 8;
    private static final int MAX_LEVEL_GROUPS_PER_OPERAND = 6;
    private static final long MAX_VARIANTS = 128;
    /**
     * Hard, non-configurable cap on how many members one split-out group in
     * {@link #resolveOperandStates} may contain. {@code MemberListCrossJoinArg.create}'s own size
     * guard ({@code isArgSizeSupported}, via {@code Util.checkCJResultLimit} below) relies on
     * {@code mondrian.rolap.maxConstraints} - a deployment-configurable property, and DPD's own
     * deployment sets it to 1000000000 (deliberately, for other queries) specifically to disable
     * that guard. Confirmed live: a "Customer Number" group (24,915 real members) produced a
     * single IN-clause over 266KB by itself, which ClickHouse rejected outright as a plain syntax
     * error (Code: 62) rather than a helpful size-limit message, since the query gets truncated
     * mid-token. This fallback must stay safe regardless of how a deployment has tuned
     * maxConstraints for its own unrelated queries, so it enforces its own independent, hardcoded
     * bound here rather than trusting that property alone.
     *
     * <p>The bound must also stay clear of the opposite failure mode: a group this code rejects
     * doesn't just skip that one dimension, it bails the whole multi-variant attempt for the
     * query and falls back to Mondrian's old cell-by-cell interpreted crossjoin evaluation -
     * which, for a 4-dimension crossjoin at real DPD cardinalities, either exhausts the JVM heap
     * (OutOfMemoryError, container killed by {@code -XX:+ExitOnOutOfMemoryError}) or hangs
     * spinning in {@code RolapResult.loadMembers}/{@code evaluateCurrent} for many minutes with
     * no progress - confirmed live via thread dump (100% CPU in
     * {@code RolapMemberBase.getPropertyFromMap} under {@code RolapResultShepherd$executor_1})
     * while the cap was set too low (2,000, then 10,000) to admit DPD's "Master Customer"
     * dimension (11,342 real members). 15,000 was chosen to clear Master Customer with real
     * margin while staying well under Customer Number's 24,915 (so that dimension - which really
     * would blow the IN-clause size limit above - still correctly falls back instead of
     * attempting a native group that's too large). Re-verify both real cardinalities
     * (`multi-variant crossjoin: bail, level ... has N members` at LOGGER.debug, temporarily
     * raised to LOGGER.info to observe without a log4j2.xml edit) before changing this constant
     * again - don't guess from SQL log row counts elsewhere, which can reflect a different,
     * smaller filtered subset rather than the dimension's real total cardinality.
     */
    private static final int MAX_MEMBERS_PER_OPERAND_STATE = 15000;

    /**
     * Fallback for crossjoin operands that {@link CrossJoinArgFactory} cannot turn into a single
     * {@link CrossJoinArg} because their evaluated member list spans more than one level - the
     * shape Excel's PivotTable MDX generator always produces for a drilled-open pivot field
     * (see context/native_query.md §7 in the sibling emondrian-modules repo for the production
     * evidence this was written against).
     *
     * <h3>Why splitting by level and unioning is exact, not approximate</h3>
     *
     * <p>MDX {@code Crossjoin} is a plain cartesian product, and cartesian product distributes
     * over set union: for any partition of a set S into disjoint groups
     * {@code S = G1 u G2 u ... u Gn}, {@code Crossjoin(S, T) = Crossjoin(G1, T) u Crossjoin(G2,
     * T) u ... u Crossjoin(Gn, T)} for any T - this holds regardless of what S, T, or the Gi
     * actually are; it is not specific to All members or to this schema. NON EMPTY filtering
     * distributes the same way, since whether a tuple has fact data doesn't depend on which
     * group its member came from. So splitting every operand's evaluated list into same-level
     * groups (Excel's typical case: the All member as one singleton group, its real children as
     * another) and unioning the native result of every combination across every operand
     * reproduces exactly the same tuple set the fully-interpreted evaluator would have produced -
     * this is a decomposition of the same computation, not a different one. What downstream cell
     * evaluation does with each tuple (including the All-member "subtotal" tuples) is completely
     * unchanged; only how the tuple set itself gets constructed is different.
     *
     * <h3>Bounds and safety</h3>
     *
     * <p>Every operand's evaluated list must split into at most
     * {@link #MAX_LEVEL_GROUPS_PER_OPERAND} groups, and the total number of group combinations
     * across all operands (the product of each operand's group count) must not exceed
     * {@link #MAX_VARIANTS} - either limit being exceeded returns null (today's unmodified,
     * fully-interpreted behaviour), which bounds the number of SQL statements this fallback can
     * ever issue to a small constant regardless of how deeply an Excel pivot has been drilled.
     * Every combination is validated (the same checks {@link #createEvaluator} itself already
     * applies - {@link #isPreferInterpreter}, {@link SqlConstraintUtils#measuresConflictWithMembers},
     * the virtual-cube check, {@link NonEmptyCrossJoinConstraint#isValidContext}) <em>before</em>
     * this method commits to anything: if any single combination isn't valid, the whole attempt
     * returns null rather than partially applying - {@link NativeEvaluator} has no way to signal
     * "changed my mind, please fall back to interpreted" once returned, so every combination this
     * method's caller ends up executing must already be known-good.
     *
     * @return a {@link NativeEvaluator} that unions the per-combination native results, or null
     *         if this crossjoin's operands aren't a shape this fallback can handle (falls back to
     *         fully interpreted evaluation, exactly as before this method existed)
     */
    private NativeEvaluator tryMultiVariantCrossJoin(
        RolapEvaluator evaluator,
        FunDef fun,
        Exp[] args)
    {
        if (!MondrianProperties.instance().EnableNativeCrossJoinExpansion.get()) {
            return null;
        }
        if (args.length != 2) {
            return null;
        }
        if (!evaluator.isNonEmpty()) {
            // Same gate createEvaluator itself applies: joining to the fact table always filters
            // out members with no data, so this fallback (built entirely out of non-empty-join
            // machinery) isn't a valid substitute for a plain, not-non-empty crossjoin.
            return null;
        }

        List<Exp> operands = new ArrayList<Exp>();
        flattenCrossJoinOperands(args[0], operands);
        flattenCrossJoinOperands(args[1], operands);
        if (operands.size() > MAX_OPERANDS) {
            LOGGER.debug(
                "multi-variant crossjoin: bail, " + operands.size()
                + " flattened operands > MAX_OPERANDS=" + MAX_OPERANDS);
            return null;
        }

        List<List<CrossJoinArg>> operandStates =
            new ArrayList<List<CrossJoinArg>>();
        long variantCount = 1;
        boolean anySplit = false;
        for (Exp operand : operands) {
            List<CrossJoinArg> states = resolveOperandStates(evaluator, operand);
            if (states == null || states.isEmpty()) {
                LOGGER.debug(
                    "multi-variant crossjoin: bail, operand unresolvable: "
                    + operand);
                return null;
            }
            LOGGER.debug(
                "multi-variant crossjoin: operand resolved to "
                + states.size() + " state(s): " + operand);
            if (states.size() > 1) {
                anySplit = true;
            }
            operandStates.add(states);
            variantCount *= states.size();
            if (variantCount > MAX_VARIANTS) {
                LOGGER.debug(
                    "multi-variant crossjoin: bail, variantCount="
                    + variantCount + " > MAX_VARIANTS=" + MAX_VARIANTS);
                return null;
            }
        }
        if (!anySplit) {
            // Every operand resolved to exactly one state - checkCrossJoin() would already have
            // succeeded above in that case, so this call has nothing new to contribute. (Not
            // reached in practice, since createEvaluator only calls this method after
            // checkCrossJoin() has already failed - kept as a defensive no-op, not an error.)
            LOGGER.debug("multi-variant crossjoin: bail, no operand needed splitting");
            return null;
        }

        final RolapCube cube = evaluator.getCube();
        final int checkSavepoint = evaluator.savepoint();
        List<CrossJoinArg[]> variants;
        try {
            variants = enumerateVariants(operandStates);
            for (CrossJoinArg[] variant : variants) {
                if (isTrivialVariant(variant)) {
                    continue;
                }
                if (!isVariantValid(evaluator, cube, fun, variant)) {
                    LOGGER.debug(
                        "multi-variant crossjoin: bail, variant failed validity check: "
                        + java.util.Arrays.toString(variant));
                    return null;
                }
            }
        } finally {
            evaluator.restore(checkSavepoint);
        }

        LOGGER.debug(
            "using native crossjoin (multi-variant expansion, "
            + variants.size() + " variant(s))");

        return new MultiVariantSetEvaluator(evaluator, fun, variants);
    }

    /**
     * Unwraps nested {@code Crossjoin}/{@code NonEmptyCrossJoin} calls into their flat list of
     * operands, the way Excel's MDX generator nests an N-way pivot crossjoin as
     * {@code Crossjoin(Crossjoin(Crossjoin(A, B), C), D)}.
     */
    private static void flattenCrossJoinOperands(Exp exp, List<Exp> into) {
        if (exp instanceof ResolvedFunCall) {
            final ResolvedFunCall funCall = (ResolvedFunCall) exp;
            final FunDef fun = funCall.getFunDef();
            final Exp[] args = funCall.getArgs();
            if ((fun != null)
                && ("Crossjoin".equalsIgnoreCase(fun.getName())
                    || "NonEmptyCrossJoin".equalsIgnoreCase(fun.getName()))
                && args.length == 2)
            {
                flattenCrossJoinOperands(args[0], into);
                flattenCrossJoinOperands(args[1], into);
                return;
            }
        }
        into.add(exp);
    }

    /**
     * Resolves one crossjoin operand into its list of alternative same-level states. A single
     * state means this operand didn't need splitting (either it was already a shape
     * {@link CrossJoinArgFactory#checkCrossJoinArg} recognizes on its own, or its evaluated
     * member list happened to already be single-level); more than one state means it had to be
     * split by level (Excel's typical "All member plus its real children" shape).
     *
     * @return states, or null if this operand can't be resolved at all (a tuple-valued
     *         expression, a level split with too many distinct levels, or any other shape this
     *         fallback doesn't cover) - the caller treats that as "give up on the whole
     *         multi-variant attempt", not "skip this operand", since one unresolvable operand
     *         makes the whole crossjoin unresolvable here.
     */
    private List<CrossJoinArg> resolveOperandStates(
        RolapEvaluator evaluator,
        Exp operand)
    {
        List<CrossJoinArg[]> direct =
            crossJoinArgFactory().checkCrossJoinArg(evaluator, operand);
        if (direct != null && direct.size() == 1 && direct.get(0) != null
            && direct.get(0).length == 1)
        {
            return Collections.singletonList(direct.get(0)[0]);
        }

        // Not a statically-recognized shape (or one that also carries its own predicate args,
        // which this fallback doesn't try to preserve) - evaluate it and split the result by
        // level. Same recursion guard CrossJoinArgFactory.expandNonNative uses, since evaluating
        // `operand` can itself re-enter native evaluator lookup for sub-expressions.
        if (!evaluator.getActiveNativeExpansions().add(operand)) {
            LOGGER.debug("multi-variant crossjoin: bail, recursion guard: " + operand);
            return null;
        }
        try {
            final ExpCompiler compiler = evaluator.getQuery().createCompiler();
            final ListCalc listCalc = compiler.compileList(operand);
            if (listCalc == null) {
                LOGGER.debug("multi-variant crossjoin: bail, no ListCalc for: " + operand);
                return null;
            }
            final TupleList tupleList = listCalc.evaluateList(evaluator);
            if (tupleList.getArity() != 1) {
                // A tuple set spanning more than one hierarchy - out of scope for this fallback.
                LOGGER.debug(
                    "multi-variant crossjoin: bail, arity=" + tupleList.getArity()
                    + " for: " + operand);
                return null;
            }
            Util.checkCJResultLimit(tupleList.size());

            final LinkedHashMap<RolapLevel, List<RolapMember>> byLevel =
                new LinkedHashMap<RolapLevel, List<RolapMember>>();
            for (Member m : tupleList.slice(0)) {
                if (!(m instanceof RolapMember)) {
                    LOGGER.debug(
                        "multi-variant crossjoin: bail, non-RolapMember " + m
                        + " for: " + operand);
                    return null;
                }
                final RolapMember rm = (RolapMember) m;
                final RolapLevel level = rm.getLevel();
                List<RolapMember> group = byLevel.get(level);
                if (group == null) {
                    group = new ArrayList<RolapMember>();
                    byLevel.put(level, group);
                }
                group.add(rm);
            }
            if (byLevel.isEmpty() || byLevel.size() > MAX_LEVEL_GROUPS_PER_OPERAND) {
                LOGGER.debug(
                    "multi-variant crossjoin: bail, byLevel.size()=" + byLevel.size()
                    + " for: " + operand);
                return null;
            }

            final List<CrossJoinArg> states = new ArrayList<CrossJoinArg>();
            for (Map.Entry<RolapLevel, List<RolapMember>> entry : byLevel.entrySet()) {
                List<RolapMember> group = entry.getValue();
                if (group.size() > MAX_MEMBERS_PER_OPERAND_STATE) {
                    LOGGER.debug(
                        "multi-variant crossjoin: bail, level " + entry.getKey()
                        + " has " + group.size() + " members > MAX_MEMBERS_PER_OPERAND_STATE="
                        + MAX_MEMBERS_PER_OPERAND_STATE + " for: " + operand);
                    return null;
                }
                final CrossJoinArg arg =
                    MemberListCrossJoinArg.create(
                        evaluator, group, restrictMemberTypes(), false);
                if (arg == null) {
                    LOGGER.debug(
                        "multi-variant crossjoin: bail, MemberListCrossJoinArg.create "
                        + "returned null for level " + entry.getKey() + " (" + group.size()
                        + " members) for: " + operand);
                    return null;
                }
                states.add(arg);
            }
            return states;
        } finally {
            evaluator.getActiveNativeExpansions().remove(operand);
        }
    }

    /** Cartesian product of every operand's state list, in nested-loop (first operand slowest,
     * last fastest) order. */
    private static List<CrossJoinArg[]> enumerateVariants(
        List<List<CrossJoinArg>> operandStates)
    {
        List<CrossJoinArg[]> variants = new ArrayList<CrossJoinArg[]>();
        enumerateVariants(operandStates, 0, new CrossJoinArg[operandStates.size()], variants);
        return variants;
    }

    private static void enumerateVariants(
        List<List<CrossJoinArg>> operandStates,
        int operandIndex,
        CrossJoinArg[] combination,
        List<CrossJoinArg[]> into)
    {
        if (operandIndex == combination.length) {
            into.add(combination.clone());
            return;
        }
        for (CrossJoinArg state : operandStates.get(operandIndex)) {
            combination[operandIndex] = state;
            enumerateVariants(operandStates, operandIndex + 1, combination, into);
        }
    }

    /**
     * Whether every operand in this combination is pinned to exactly one specific member -
     * typically the "everything collapsed to its All member" combination. No join is needed for
     * this case; it's exactly one concrete tuple, built directly without SQL.
     */
    private static boolean isTrivialVariant(CrossJoinArg[] variant) {
        for (CrossJoinArg arg : variant) {
            if (!(arg instanceof MemberListCrossJoinArg)
                || ((MemberListCrossJoinArg) arg).getMembers().size() != 1)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The same validity checks {@link #createEvaluator} itself applies to a combined
     * {@code CrossJoinArg[]}, factored out so {@link #tryMultiVariantCrossJoin} can apply them to
     * every generated combination before committing to any of them.
     */
    private boolean isVariantValid(
        RolapEvaluator evaluator,
        RolapCube cube,
        FunDef fun,
        CrossJoinArg[] cjArgs)
    {
        int countNonNativeInputArg = 0;
        for (CrossJoinArg arg : cjArgs) {
            if (arg instanceof MemberListCrossJoinArg) {
                MemberListCrossJoinArg cjArg = (MemberListCrossJoinArg) arg;
                if (cjArg.hasAllMember() || cjArg.isEmptyCrossJoinArg()) {
                    ++countNonNativeInputArg;
                }
                if (cjArg.hasCalcMembers()) {
                    return false;
                }
            }
        }
        if (countNonNativeInputArg == cjArgs.length) {
            return false;
        }
        if (isPreferInterpreter(cjArgs, true)) {
            return false;
        }
        if (SqlConstraintUtils.measuresConflictWithMembers(
                evaluator.getQuery().getMeasuresMembers(), cjArgs))
        {
            return false;
        }
        if (cube.isVirtual() && !evaluator.getQuery().nativeCrossJoinVirtualCube()) {
            return false;
        }
        List<RolapLevel> levels = new ArrayList<RolapLevel>();
        for (CrossJoinArg cjArg : cjArgs) {
            RolapLevel level = cjArg.getLevel();
            if (level != null) {
                levels.add(level);
            }
        }
        return NonEmptyCrossJoinConstraint.isValidContext(
            evaluator, false, levels.toArray(new RolapLevel[levels.size()]),
            restrictMemberTypes());
    }

    /**
     * Executes a {@link #tryMultiVariantCrossJoin}-produced plan: runs the ordinary native
     * crossjoin machinery once per pre-validated combination (or builds the single tuple directly
     * for a fully-pinned combination - see {@link #isTrivialVariant}) and concatenates every
     * combination's result into one tuple list. Every combination was already validated eagerly
     * in {@link #tryMultiVariantCrossJoin}, so nothing here can discover mid-way that it needs to
     * fall back to something else.
     */
    private class MultiVariantSetEvaluator implements NativeEvaluator {
        private final RolapEvaluator evaluator;
        private final FunDef fun;
        private final List<CrossJoinArg[]> variants;

        MultiVariantSetEvaluator(
            RolapEvaluator evaluator,
            FunDef fun,
            List<CrossJoinArg[]> variants)
        {
            this.evaluator = evaluator;
            this.fun = fun;
            this.variants = variants;
        }

        public Object execute(ResultStyle desiredResultStyle) {
            final int arity = variants.get(0).length;
            final TupleList result = TupleCollections.createList(arity);
            for (CrossJoinArg[] variant : variants) {
                executeOneVariant(variant, result);
            }
            return result;
        }

        private void executeOneVariant(CrossJoinArg[] variant, TupleList result) {
            if (isTrivialVariant(variant)) {
                final List<Member> tuple = new ArrayList<Member>(variant.length);
                for (CrossJoinArg arg : variant) {
                    tuple.add(((MemberListCrossJoinArg) arg).getMembers().get(0));
                }
                result.add(tuple);
                return;
            }

            final int savepoint = evaluator.savepoint();
            try {
                overrideContext(evaluator, variant, null);
                final CrossJoinArg[] cargs =
                    combineArgs(Collections.singletonList(variant));
                final TupleConstraint constraint =
                    buildConstraint(evaluator, fun, cargs);
                final SchemaReader schemaReader = evaluator.getSchemaReader();
                final SetEvaluator sev =
                    new SetEvaluator(variant, schemaReader, constraint);
                final Object r = sev.execute(ResultStyle.LIST);
                if (r instanceof TupleList) {
                    result.addAll((TupleList) r);
                }
            } finally {
                evaluator.restore(savepoint);
            }
        }
    }

    private Set<Member> getCJArgMembers(CrossJoinArg[] cjArgs) {
        Set<Member> members = new HashSet<Member>();
         for (CrossJoinArg arg : cjArgs) {
             if (arg.getMembers() != null) {
                 members.addAll(arg.getMembers());
             }
         }
         return members;
    }


    CrossJoinArg[] combineArgs(
        List<CrossJoinArg[]> allArgs)
    {
        CrossJoinArg[] cjArgs = allArgs.get(0);
        if (allArgs.size() == 2) {
            CrossJoinArg[] predicateArgs = allArgs.get(1);
            if (predicateArgs != null) {
                // Combine the CJ and the additional predicate args.
                return Util.appendArrays(cjArgs, predicateArgs);
            }
        }
        return cjArgs;
    }

    private TupleConstraint buildConstraint(
        final RolapEvaluator evaluator,
        final FunDef fun,
        final CrossJoinArg[] cargs)
    {
        CrossJoinArg[] myArgs;
        if (safeToConstrainByOtherAxes(fun)) {
            myArgs = buildArgs(evaluator, cargs);
        } else {
            myArgs = cargs;
        }
        return new NonEmptyCrossJoinConstraint(myArgs, evaluator);
    }

    private CrossJoinArg[] buildArgs(
        final RolapEvaluator evaluator, final CrossJoinArg[] cargs)
    {
        Set<CrossJoinArg> joinArgs =
            crossJoinArgFactory().buildConstraintFromAllAxes(evaluator);
        joinArgs.addAll(Arrays.asList(cargs));
        return joinArgs.toArray(new CrossJoinArg[joinArgs.size()]);
    }

    private boolean safeToConstrainByOtherAxes(final FunDef fun) {
        return !(fun instanceof NonEmptyCrossJoinFunDef);
    }

    private void alertCrossJoinNonNative(
        RolapEvaluator evaluator,
        FunDef fun,
        String reason)
    {
        if (!(fun instanceof NonEmptyCrossJoinFunDef)) {
            // Only alert for an explicit NonEmptyCrossJoin,
            // since query authors use that to indicate that
            // they expect it to be "wicked fast"
            return;
        }
        if (!evaluator.getQuery().shouldAlertForNonNative(fun)) {
            return;
        }
        RolapUtil.alertNonNative("NonEmptyCrossJoin", reason);
    }
}

// End RolapNativeCrossJoin.java

