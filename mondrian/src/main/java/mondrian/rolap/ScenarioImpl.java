/*
* This software is subject to the terms of the Eclipse Public License v1.0
* Agreement, available at the following URL:
* http://www.eclipse.org/legal/epl-v10.html.
* You must accept the terms of that agreement to use this software.
*
* Copyright (c) 2002-2017 Hitachi Vantara..  All rights reserved.
*/

package mondrian.rolap;

import mondrian.calc.Calc;
import mondrian.calc.DummyExp;
import mondrian.calc.impl.GenericCalc;
import mondrian.mdx.ResolvedFunCall;
import mondrian.olap.*;
import mondrian.olap.type.ScalarType;
import mondrian.rolap.agg.CellRequest;

import org.olap4j.AllocationPolicy;
import org.olap4j.Scenario;
import org.olap4j.mdx.Syntax;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of {@link org.olap4j.Scenario}.
 *
 * @author jhyde
 * @since 24 April, 2009
 */
public final class ScenarioImpl implements Scenario {

    private final int id;

    private final List<WritebackCell> writebackCells =
        new ArrayList<WritebackCell>();

    private RolapMember member;

    private static int nextId;

    /**
     * Creates a ScenarioImpl.
     */
    public ScenarioImpl() {
        id = nextId++;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ScenarioImpl
            && id == ((ScenarioImpl) obj).id;
    }

    @Override
    public String toString() {
        return "scenario #" + id;
    }

    /**
     * Sets the value of a cell.
     *
     * @param connection Connection (not currently used)
     * @param members Coordinates of cell
     * @param newValue New value
     * @param currentValue Current value
     * @param allocationPolicy Allocation policy
     * @param allocationArgs Additional arguments of allocation policy
     */
    public void setCellValue(
        Connection connection,
        List<RolapMember> members,
        double newValue,
        double currentValue,
        AllocationPolicy allocationPolicy,
        Object[] allocationArgs)
    {
        Util.discard(connection); // for future use
        assert allocationPolicy != null;
        assert allocationArgs != null;
        switch (allocationPolicy) {
        case EQUAL_ALLOCATION:
        case EQUAL_INCREMENT:
            if (allocationArgs.length != 0) {
                throw Util.newError(
                    "Allocation policy " + allocationPolicy
                    + " takes 0 arguments; " + allocationArgs.length
                    + " were supplied");
            }
            break;
        default:
            throw Util.newError(
                "Allocation policy " + allocationPolicy + " is not supported");
        }

        // Compute the set of columns which are constrained by the cell's
        // coordinates.
        //
        // NOTE: This code is very similar to code in
        // RolapAggregationManager.makeCellRequest. Consider creating a
        // CellRequest then mining it. It will work better in the presence of
        // calculated members, compound members, parent-child hierarchies,
        // hierarchies whose default member is not the 'all' member, and so
        // forth.
        final RolapStoredMeasure measure = (RolapStoredMeasure) members.get(0);
        final RolapCube baseCube = measure.getCube();
        final RolapStar.Measure starMeasure =
            (RolapStar.Measure) measure.getStarMeasure();
        assert starMeasure != null;
        int starColumnCount = starMeasure.getStar().getColumnCount();
        final BitKey constrainedColumnsBitKey =
            BitKey.Factory.makeBitKey(starColumnCount);
        Object[] keyValues = new Object[starColumnCount];
        for (int i = 1; i < members.size(); i++) {
            Member member = members.get(i);
            for (RolapCubeMember m = (RolapCubeMember) member;
                m != null && !m.isAll();
                m = m.getParentMember())
            {
                final RolapCubeLevel level = m.getLevel();
                RolapStar.Column column = level.getBaseStarKeyColumn(baseCube);
                if (column != null) {
                    final int bitPos = column.getBitPosition();
                    keyValues[bitPos] = m.getKey();
                    constrainedColumnsBitKey.set(bitPos);
                }
                if (level.areMembersUnique()) {
                    break;
                }
            }
        }

        // Squish the values down. We want the compactKeyValues[i] to correspond
        // to the i'th set bit in the key. This is the same format used by
        // CellRequest.
        Object[] compactKeyValues =
            new Object[constrainedColumnsBitKey.cardinality()];
        int k = 0;
        for (int bitPos : constrainedColumnsBitKey) {
            compactKeyValues[k++] = keyValues[bitPos];
        }

        // Record the override.
        //
        // TODO: add a mechanism for persisting the overrides to a file.
        //
        // FIXME: make thread-safe
        for(RolapMember child: members) {
            RolapMember parent = child.getParentMember();
            while(parent != null) {
                affectedParentMembers.put(parent, parent);
                parent = parent.getParentMember();
            }
        }

        WritebackCell writebackCell =
            new WritebackCell(
                    baseCube,
                    new ArrayList<RolapMember>(members),
                    constrainedColumnsBitKey,
                    keyValues,
                    newValue,
                    currentValue,
                    allocationPolicy);
        writebackCells.add(writebackCell);
        writebackCellMap.put(members, writebackCell);
    }

    /**
     * The value written back for the cell this request addresses, or null if none was.
     *
     * <p>This is what makes an {@code UPDATE CUBE} visible to ordinary queries. A writeback used to
     * be readable only through the {@code [Scenario]} hierarchy's calculated member, so a cube
     * without a dimension named {@code Scenario} accepted a write and then went on returning the
     * stored value for ever. Matching on the request's own coordinates instead means the override
     * is applied wherever the cell is read, with no special dimension required.
     *
     * <p>A request for the written cell itself is answered with the written value outright. A
     * request for a cell that <em>rolls it up</em> - fewer constrained columns, all agreeing - has
     * its stored total shifted by (new - old), since that total was computed before the write. That
     * shift is only sound for a measure that sums, so a count, min, max or distinct count keeps
     * showing its stored value; see context/writeback.md.
     *
     * @param request Cell request being served
     * @param cachedValue Value the segment cache holds, or null if not loaded yet
     * @return the value to report for this cell
     */
    public Object applyOverride(CellRequest request, Object cachedValue) {
        // Most queries touch no written-back cells at all; keep that path free of work.
        if (request == null || writebackCells.isEmpty()) {
            return cachedValue;
        }
        final BitKey requestBitKey = request.getConstrainedColumnsBitKey();
        final Object[] requestValues = request.getSingleValues();

        double delta = 0d;
        boolean haveDelta = false;

        for (WritebackCell cell : writebackCells) {
            if (cell.starMeasure != request.getMeasure()) {
                continue;
            }
            if (cell.constrainedColumnsBitKey.equals(requestBitKey)) {
                if (matches(cell, requestBitKey, requestValues)) {
                    // The written cell itself: answer with the value that was written, without
                    // needing the stored one at all.
                    return cell.newValue;
                }
            } else if (cell.constrainedColumnsBitKey.isSuperSetOf(requestBitKey)) {
                // The request is for a cell that rolls the written-back one up - fewer constrained
                // columns, all agreeing. Its stored total predates the write, so shift it by the
                // difference. Only valid for a measure that sums: a count, min, max or distinct
                // count cannot be adjusted by adding a delta, so those aggregates are left showing
                // the stored value (see context/writeback.md).
                if (matches(cell, requestBitKey, requestValues)
                    && request.getMeasure().getAggregator() == RolapAggregator.Sum)
                {
                    delta += cell.newValue - cell.currentValue;
                    haveDelta = true;
                }
            }
        }

        if (!haveDelta || delta == 0d) {
            return cachedValue;
        }
        if (cachedValue == null) {
            // Nothing loaded yet; let the caller fetch it and adjust on the next pass, rather than
            // inventing a total out of a delta alone.
            return null;
        }
        if (!(cachedValue instanceof Number)) {
            return cachedValue;
        }
        return ((Number) cachedValue).doubleValue() + delta;
    }

    /**
     * Whether a writeback cell agrees with a request on every column the request constrains.
     *
     * <p>The request's values arrive compacted in bit order, so they are walked alongside the set
     * bits of its key; the cell keeps its values indexed by bit position, which is what lets a
     * request constraining a subset of the columns be compared at all.
     */
    private static boolean matches(
        WritebackCell cell,
        BitKey requestBitKey,
        Object[] requestValues)
    {
        int i = 0;
        for (int bitPos : requestBitKey) {
            if (i >= requestValues.length) {
                return false;
            }
            if (!Util.equals(cell.keyValuesByBitPosition[bitPos], requestValues[i])) {
                return false;
            }
            i++;
        }
        return true;
    }

    public java.util.Map<List<RolapMember>, WritebackCell> writebackCellMap =
            new java.util.HashMap<List<RolapMember>, WritebackCell>();

    public java.util.Map<RolapMember,RolapMember> affectedParentMembers =
            new java.util.HashMap<RolapMember,RolapMember>();

    public String getId() {
        return Integer.toString(id);
    }

    /**
     * Returns the scenario inside a calculated member in the scenario
     * dimension. For example, applied to [Scenario].[1], returns the Scenario
     * object representing scenario #1.
     *
     * @param member Wrapper member
     * @return Wrapped scenario
     */
    static Scenario forMember(final RolapMember member) {
        if (isScenario(member.getHierarchy())) {
            final Formula formula = ((RolapCalculatedMember) member)
                .getFormula();
            final ResolvedFunCall resolvedFunCall =
                (ResolvedFunCall) formula.getExpression();
            final Calc calc = resolvedFunCall.getFunDef()
                .compileCall(null, null);
            return ((ScenarioCalc) calc).getScenario();
        } else {
            return null;
        }
    }

    /**
     * Registers this Scenario with a Schema, creating a calulated member
     * [Scenario].[{id}] for each cube that has writeback enabled. (Currently
     * a cube has writeback enabled iff it has a dimension called "Scenario".)
     *
     * @param schema Schema
     */
    void register(RolapSchema schema) {
        // Add a value to the [Scenario] dimension of every cube that has
        // writeback enabled.
        for (RolapCube cube : schema.getCubeList()) {
            for (RolapHierarchy hierarchy : cube.getHierarchies()) {
                if (isScenario(hierarchy)) {
                    member =
                        cube.createCalculatedMember(
                            hierarchy,
                            getId() + "",
                            new ScenarioCalc(this));
                    assert member != null;
                }
            }
        }
    }

    /**
     * Returns whether a hierarchy is the [Scenario] hierarchy.
     *
     * <p>TODO: use a flag
     *
     * @param hierarchy Hierarchy
     * @return Whether hierarchy is the scenario hierarchy
     */
    public static boolean isScenario(Hierarchy hierarchy) {
        return hierarchy.getName().equals("Scenario");
    }

    /**
     * Returns the number of atomic cells that contribute to the current
     * cell.
     *
     * @param evaluator Evaluator
     * @return Number of atomic cells in the current cell
     */
    private static double evaluateAtomicCellCount(RolapEvaluator evaluator) {
        final int savepoint = evaluator.savepoint();
        try {
            evaluator.setContext(
                evaluator.getCube().getAtomicCellCountMeasure());
            final Object o = evaluator.evaluateCurrent();
            return ((Number) o).doubleValue();
        } finally {
            evaluator.restore(savepoint);
        }
    }

    /**
     * Computes the number of atomic cells in a cell identified by a list
     * of members.
     *
     * <p>The method may be expensive. If the value is not in the cache,
     * computes it immediately using a database access. It uses an aggregate
     * table if applicable, and puts the value into the cache.
     *
     * <p>Compare with {@link #evaluateAtomicCellCount(RolapEvaluator)}, which
     * gets the value from the cache but may lie (and generate a cache miss) if
     * the value is not present.
     *
     * @param cube Cube
     * @param memberList Coordinate members of cell
     * @return Number of atomic cells in cell
     */
    private static double computeAtomicCellCount(
        RolapCube cube, List<RolapMember> memberList)
    {
        // Implementation generates and executes a recursive MDX query. This
        // may not be the most efficient implementation, but achieves the
        // design goals of (a) immediacy, (b) cache use, (c) aggregate table
        // use.
        final StringBuilder buf = new StringBuilder();
        buf.append("select from ");
        buf.append(cube.getUniqueName());
        int k = 0;
        for (Member member : memberList) {
            if (member.isMeasure()) {
                member = cube.factCountMeasure;
                assert member != null
                    : "fact count measure is required for writeback cubes";
            }
            if (!member.equals(member.getHierarchy().getDefaultMember())) {
                if (k++ > 0) {
                    buf.append(", ");
                } else {
                    buf.append(" where (");
                }
                buf.append(member.getUniqueName());
            }
        }
        if (k > 0) {
            buf.append(")");
        }
        final String mdx = buf.toString();
        final RolapConnection connection =
            cube.getSchema().getInternalConnection();
        final Query query = connection.parseQuery(mdx);
        final Result result = connection.execute(query);
        final Object o = result.getCell(new int[0]).getValue();
        return o instanceof Number
            ? ((Number) o).doubleValue()
            : 0d;
    }

    /**
     * Returns the member of the [Scenario] dimension that represents this
     * scenario. Including that member in the slicer will automatically use
     * this scenario.
     *
     * <p>The result is not null, provided that {@link #register(RolapSchema)}
     * has been called.
     *
     * @return Scenario member
     */
    public RolapMember getMember() {
        return member;
    }

    /**
     * Created by a call to
     * {@link org.olap4j.Cell#setValue(Object, org.olap4j.AllocationPolicy, Object...)},
     * records that a cell's value has been changed.
     *
     * <p>From this, other cell values can be modified as they are read into
     * cache. Only the cells specifically modified by the client have a
     * {@code CellValueOverride}.
     *
     * <p>In future, a {@link ScenarioImpl} could be persisted by
     * serializing all {@code WritebackCell}s to a file.
     */
    private static class WritebackCell {
        private final double newValue;
        private final double currentValue;
        private final AllocationPolicy allocationPolicy;
        private Member[] membersByOrdinal;
        private final double atomicCellCount;
        public List<RolapMember> members;

        /**
         * The cell's coordinates in the form a {@link CellRequest} carries them - which is what
         * lets an override be matched against a request as it is served from the segment cache.
         * These were computed and then discarded before writeback values were made visible to
         * ordinary queries.
         */
        private final BitKey constrainedColumnsBitKey;
        /** Key values indexed by star column bit position, not compacted. */
        private final Object[] keyValuesByBitPosition;
        private final RolapStar.Measure starMeasure;

        /**
         * Creates a WritebackCell.
         *
         * @param cube Cube
         * @param members Members that form context
         * @param constrainedColumnsBitKey Bitmap of columns which have values
         * @param keyValuesByBitPosition Values indexed by star column bit position
         * @param newValue New value
         * @param currentValue Current value
         * @param allocationPolicy Allocation policy
         */
        WritebackCell(
            RolapCube cube,
            List<RolapMember> members,
            BitKey constrainedColumnsBitKey,
            Object[] keyValuesByBitPosition,
            double newValue,
            double currentValue,
            AllocationPolicy allocationPolicy)
        {
            assert keyValuesByBitPosition.length
                >= constrainedColumnsBitKey.cardinality();
            Util.discard(cube); // not used currently
            this.constrainedColumnsBitKey = constrainedColumnsBitKey;
            this.keyValuesByBitPosition = keyValuesByBitPosition;
            this.starMeasure =
                (RolapStar.Measure)
                    ((RolapStoredMeasure) members.get(0)).getStarMeasure();
            this.members = members;
            this.newValue = newValue;
            this.currentValue = currentValue;
            this.allocationPolicy = allocationPolicy;
            this.atomicCellCount = computeAtomicCellCount(cube, members);

            // Build the array of members by ordinal. If a member is not
            // specified for a particular dimension, use the 'all' member (not
            // necessarily the same as the default member).
            final List<RolapHierarchy> hierarchyList = cube.getHierarchies();
            this.membersByOrdinal = new Member[hierarchyList.size()];
            for (int i = 0; i < membersByOrdinal.length; i++) {
                membersByOrdinal[i] = hierarchyList.get(i).getDefaultMember();
            }
            for (RolapMember member : members) {
                final RolapHierarchy hierarchy = member.getHierarchy();
                if (isScenario(hierarchy)) {
                    assert member.isAll();
                }
                // REVIEW The following works because Measures is the only
                // dimension whose members do not belong to RolapCubeDimension,
                // just a regular RolapDimension, but has ordinal 0.
                final int ordinal = hierarchy.getOrdinalInCube();
                membersByOrdinal[ordinal] = member;
            }
        }

        /**
         * Returns the amount by which the cell value has increased with this
         * override.
         *
         * @return Amount by which value has increased
         */
        public double getOffset() {
            return newValue - currentValue;
        }

        /**
         * Returns the position of this writeback cell relative to another
         * co-ordinate.
         *
         * <p>Assumes that {@code members} contains an entry for each dimension
         * in the cube.
         *
         * @param members Co-ordinates of another cell
         * @return Relation of this writeback cell to other co-ordinate, never
         * null
         */
        CellRelation getRelationTo(Member[] members) {
            int aboveCount = 0;
            int belowCount = 0;
            for (int i = 0; i < members.length; i++) {
                Member thatMember = members[i];
                Member thisMember = membersByOrdinal[i];
                // FIXME: isChildOrEqualTo is very inefficient. It should use
                // level depth as a guideline, at least.
                if (thatMember.isChildOrEqualTo(thisMember)) {
                    if (thatMember.equals(thisMember)) {
                        // thisMember equals member
                    } else {
                        // thisMember is ancestor of member
                        ++aboveCount;
                        if (belowCount > 0) {
                            return CellRelation.NONE;
                        }
                    }
                } else if (thisMember.isChildOrEqualTo(thatMember)) {
                    // thisMember is descendant of member
                    ++belowCount;
                    if (aboveCount > 0) {
                        return CellRelation.NONE;
                    }
                } else {
                    return CellRelation.NONE;
                }
            }
            assert aboveCount == 0 || belowCount == 0;
            if (aboveCount > 0) {
                return CellRelation.ABOVE;
            } else if (belowCount > 0) {
                return CellRelation.BELOW;
            } else {
                return CellRelation.EQUAL;
            }
        }
    }

    /**
     * Decribes the relationship between two cells.
     */
    enum CellRelation {
        ABOVE,
        EQUAL,
        BELOW,
        NONE
    }

    /**
     * Compiled expression to implement a [Scenario].[{name}] calculated member.
     *
     * <p>When evaluated, replaces the value of a cell with the value overridden
     * by a writeback value, per
     * {@link org.olap4j.Cell#setValue(Object, org.olap4j.AllocationPolicy, Object...)},
     * and modifies the values of ancestors or descendants of such cells
     * according to the allocation policy.
     */
    private static class ScenarioCalc extends GenericCalc {
        private final ScenarioImpl scenario;

        /**
         * Creates a ScenarioCalc.
         *
         * @param scenario Scenario whose writeback values should be substituted
         * for the values stored in the database.
         */
        public ScenarioCalc(ScenarioImpl scenario) {
            super(new DummyExp(new ScalarType()));
            this.scenario = scenario;
        }

        /**
         * Returns the Scenario this writeback cell belongs to.
         *
         * @return Scenario, never null
         */
        private Scenario getScenario() {
            return scenario;
        }

        public Object evaluate(Evaluator evaluator) {
            final int savepoint = evaluator.savepoint();
            final Member defaultMember =
                    scenario.member.getHierarchy().getDefaultMember();
            try {
                ArrayList<RolapMember> evaluatorMembers = new ArrayList<RolapMember>();
                for(Member member: evaluator.getMembers()) {
                    if(member.getHierarchy() == scenario.member.getHierarchy()) {
                        evaluatorMembers.add((RolapMember)defaultMember);
                    }
                    else {
                        evaluatorMembers.add((RolapMember)member);
                    }
                }

                WritebackCell writebackCellFromMap = scenario.writebackCellMap.get(evaluatorMembers);

                if(writebackCellFromMap != null) {
                    return writebackCellFromMap.newValue;
                }
                else {
                    RolapMember firstMemberWithChildren = null;
                    for(Member member: evaluator.getMembers()) {
                        if(this.scenario.affectedParentMembers.get(member) != null) {
                            ArrayList<RolapMember> children = new ArrayList<RolapMember>();
                            ((RolapHierarchy) member.getHierarchy()).getMemberReader().getMemberChildren((RolapMember) member, children);
                            if (children.size() > 0) {
                                firstMemberWithChildren = (RolapMember) member;
                                break;
                            }
                        }
                    }

                    if(firstMemberWithChildren != null) {
                        Exp ae = evaluator.getQuery().getConnection().parseExpression(
                                "Aggregate("
                                        + firstMemberWithChildren.getUniqueName().toString()
                                        + ".Children)");

                        mondrian.calc.ExpCompiler compiler = evaluator.getQuery().createCompiler();
                        Calc calc = compiler.compileScalar(ae.accept(compiler.getValidator()), false);

                        final Object o = calc.evaluate(evaluator);
                        return o;

//                Validator validator =
//                        Util.createSimpleValidator(mondrian.olap.fun.BuiltinFunTable.instance());
//
//                ae = ae.accept(validator);

//                final Object o2 = ((CalcExp)ae).evaluateScalar(evaluator);

//                Exp setExpr = query.getConnection()
//                        .parseExpression(
//                                "{"
//                                        + memberFormula.getIdentifier().toString()
//                                        + "}");
//                mondrian.mdx.UnresolvedFunCall ce = new mondrian.mdx.UnresolvedFunCall(
//                        "Children",
//                        Syntax.Property,
//                        new Exp[] {new mondrian.mdx.MemberExpr(firstMemberWithChildren)});
//                UnresolvedFunCall se = new UnresolvedFunCall(
//                        "Set",
//                        Syntax.Property,
//                        new Exp[] {new MemberExpr(firstMemberWithChildren)});
//
//
//                Validator validator =
//                        Util.createSimpleValidator(BuiltinFunTable.instance());
//                aggregateChildrenExpression = fc.accept(validator);
                    }
                    else {
                        evaluator.setContext(defaultMember);
                        final Object o = evaluator.evaluateCurrent();
                        return o;
                    }
                }
            } finally {
                evaluator.restore(savepoint);
            }

//            // Evaluate current member in the given scenario by expanding in
//            // terms of the writeback cells.
//
//            // First, evaluate in the null scenario.
//            final Member defaultMember =
//                scenario.member.getHierarchy().getDefaultMember();
//            final int savepoint = evaluator.savepoint();
//            try {
//                evaluator.setContext(defaultMember);
//                final Object o = evaluator.evaluateCurrent();
//
//                Object d =
//                    o instanceof Number
//                        ? ((Number) o).doubleValue()
//                        : 0d;
//
//                // Look for writeback cells which are equal to, ancestors of,
//                // or descendants of, the current cell. Modify the value
//                // accordingly.
//                //
//                // It is possible that the value is modified by several
//                // writebacks. If so, order is important.
//                int changeCount = 0;
//                for (ScenarioImpl.WritebackCell writebackCell
//                    : scenario.writebackCellMap.values())
//                {
//                    CellRelation relation =
//                        writebackCell.getRelationTo(evaluator.getMembers());
//                    switch (relation) {
//                    case ABOVE:
//                        // This cell is below the writeback cell. Value is
//                        // determined by allocation policy.
//                        double atomicCellCount =
//                        evaluateAtomicCellCount((RolapEvaluator) evaluator);
//                        if (atomicCellCount == 0d) {
//                            // Sometimes the value comes back zero if the cache
//                            // is not ready. Switch to 1, which at least does
//                            // not give divide-by-zero. We will be invoked again
//                            // for the correct answer when the cache has been
//                            // populated.
//                            atomicCellCount = 1d;
//                        }
//                        switch (writebackCell.allocationPolicy) {
//                        case EQUAL_ALLOCATION:
//                            d = writebackCell.newValue
//                            * atomicCellCount
//                            / writebackCell.atomicCellCount;
//                            break;
//                        case EQUAL_INCREMENT:
//                            d += writebackCell.getOffset()
//                            * atomicCellCount
//                            / writebackCell.atomicCellCount;
//                            break;
//                        default:
//                            throw Util.unexpected(
//                                writebackCell.allocationPolicy);
//                        }
//                        ++changeCount;
//                        break;
//                    case EQUAL:
//                        // This cell is the writeback cell. Value is the value
//                        // written back.
//                        d = writebackCell.newValue;
//                        ++changeCount;
//                        break;
//                    case BELOW:
//                        // This cell is above the writeback cell. Value is the
//                        // current value plus the change in the writeback cell.
//                        d = (double)d + writebackCell.getOffset();
//                        ++changeCount;
//                        break;
//                    case NONE:
//                        // Writeback cell is unrelated. It has no effect on
//                        // cell's value.
//                        break;
//                    default:
//                        throw Util.unexpected(relation);
//                    }
//                }
//                // Don't create a new object if value has not changed.
//                if (changeCount == 0) {
//                    return o;
//                } else {
//                    return d;
//                }
//            } finally {
//                evaluator.restore(savepoint);
//            }
        }
    }
}

// End ScenarioImpl.java
