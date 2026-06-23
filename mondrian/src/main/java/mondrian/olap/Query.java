/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 1998-2005 Julian Hyde
// Copyright (C) 2005-2021 Hitachi Vantara and others
// Copyright (C) 2021-2025 Sergei Semenkov
// All Rights Reserved.
*/
package mondrian.olap;

import mondrian.calc.*;
import mondrian.mdx.*;
import mondrian.olap.fun.ParameterFunDef;
import mondrian.olap.type.*;
import mondrian.resource.MondrianResource;
import mondrian.rolap.*;
import mondrian.rolap.agg.AndPredicate;
import mondrian.rolap.agg.MemberColumnPredicate;
import mondrian.rolap.agg.NotPredicate;
import mondrian.rolap.agg.OrPredicate;
import mondrian.rolap.agg.LiteralStarPredicate;
import mondrian.rolap.agg.ValueColumnPredicate;
import mondrian.server.*;
import mondrian.spi.ProfileHandler;
import mondrian.util.ArrayStack;

import org.apache.commons.collections.collection.CompositeCollection;

import org.olap4j.impl.*;
import org.olap4j.mdx.IdentifierSegment;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.*;

/**
 * <code>Query</code> is an MDX query.
 *
 * <p>It is created by calling {@link Connection#parseQuery},
 * and executed by calling {@link Connection#execute},
 * to return a {@link Result}.</p>
 *
 * <h3>Query control</h3>
 *
 * <p>Most queries are model citizens, executing quickly (often using cached
 * results from previous queries), but som queries take more time, or more
 * database resources, or more results, than is reasonable. Mondrian offers
 * three ways to control rogue queries:<ul>
 *
 * <li>You can set a query timeout by setting the
 *     {@link MondrianProperties#QueryTimeout} parameter. If the query
 *     takes longer to execute than the value of this parameter, the system
 *     will kill it.</li>
 *
 * <li>The {@link MondrianProperties#QueryLimit} parameter limits the number
 *     of cells returned by a query.</li>
 *
 * <li>At any time while a query is executing, another thread can cancel the
 *     query by calling
 *     {@link #getStatement()}.{@link Statement#cancel() cancel()}.
 *     The call to {@link Connection#execute(Query)}
 *     will throw an exception.</li>
 *
 * </ul>
 *
 * @author jhyde, 20 January, 1999
 */
public class Query extends QueryPart {

    private Formula[] formulas;

    /**
     * public-private: This must be public because it is still accessed in
     * rolap.RolapConnection
     */
    public QueryAxis[] axes;

    private QueryAxis slicerAxis;

    /**
     * Definitions of all parameters used in this query.
     */
    private final List<Parameter> parameters = new ArrayList<Parameter>();

    private final Map<String, Parameter> parametersByName =
        new HashMap<String, Parameter>();

    /**
     * Cell properties. Not currently used.
     */
    private final QueryPart[] cellProps;

    /**
     * Cube this query belongs to.
     */
    private final Cube cube;

    private Subcube subcube;

    private final Statement statement;
    public Calc[] axisCalcs;
    public Calc slicerCalc;

    /**
     * Set of FunDefs for which alerts about non-native evaluation
     * have already been posted.
     */
    Set<FunDef> alertedNonNativeFunDefs;

    /**
     * Unique list of members referenced from the measures dimension.
     * Will be used to determine if cross joins can be processed natively
     * for virtual cubes.
     */
    private Set<Member> measuresMembers;

    /**
     * If true, virtual cubes can be processed using native cross joins.
     * It defaults to true, unless functions are applied on measures.
     */
    private boolean nativeCrossJoinVirtualCube;

    /**
     * Used for virtual cubes.
     * Comtains a list of base cubes related to a virtual cube
     */
    private List<RolapCube> baseCubes;

    /**
     * If true, enforce validation even when ignoreInvalidMembers is set.
     */
    private boolean strictValidation;

    /**
     * How should the query be returned? Valid values are:
     *    ResultStyle.ITERABLE
     *    ResultStyle.LIST
     *    ResultStyle.MUTABLE_LIST
     * For java4, use LIST
     */
    private ResultStyle resultStyle =
        Util.Retrowoven ? ResultStyle.LIST : ResultStyle.ITERABLE;

    private Map<String, Object> evalCache = new HashMap<String, Object>();

    /**
     * List of aliased expressions defined in this query, and where they are
     * defined. There might be more than one aliased expression with the same
     * name.
     */
    private final List<ScopedNamedSet> scopedNamedSets =
        new ArrayList<ScopedNamedSet>();
    private boolean ownStatement;

  /**
   * Creates a Query.
   */
  public Query( Statement statement, Formula[] formulas, QueryAxis[] axes, String cubeName, QueryAxis slicerAxis,
                QueryPart[] cellProps, boolean strictValidation ) {
      this(
              statement,
              Util.lookupCube( statement.getSchemaReader(), cubeName, true ),
              formulas,
              new Subcube(cubeName, null, new QueryAxis[] {}, null),
              axes,
              slicerAxis,
              cellProps,
              new Parameter[0],
              strictValidation );
  }

  public Query( Statement statement, Formula[] formulas, QueryAxis[] axes, Subcube subcube, QueryAxis slicerAxis,
      QueryPart[] cellProps, boolean strictValidation ) {
    this( statement, Util.lookupCube( statement.getSchemaReader(), subcube.getCubeName(), true ), formulas, subcube, axes, slicerAxis, cellProps,
        new Parameter[0], strictValidation );
  }

    /**
     * Creates a Query.
     */
    public Query(
            Statement statement,
            Cube mdxCube,
            Formula[] formulas,
            QueryAxis[] axes,
            QueryAxis slicerAxis,
            QueryPart[] cellProps,
            Parameter[] parameters,
            boolean strictValidation)
    {
        this(
            statement,
            mdxCube,
            formulas,
            null,
            axes,
            slicerAxis,
            cellProps,
            parameters,
            strictValidation);

    }

    public Query(
        Statement statement,
        Cube mdxCube,
        Formula[] formulas,
        Subcube subcube,
        QueryAxis[] axes,
        QueryAxis slicerAxis,
        QueryPart[] cellProps,
        Parameter[] parameters,
        boolean strictValidation)
    {
        this.statement = statement;
        this.cube = mdxCube;
        this.formulas = formulas;
        this.subcube = subcube;
        this.axes = axes;
        normalizeAxes();
        this.slicerAxis = slicerAxis;
        this.cellProps = cellProps;
        this.parameters.addAll(Arrays.asList(parameters));
        this.measuresMembers = new HashSet<Member>();
        // assume, for now, that cross joins on virtual cubes can be
        // processed natively; as we parse the query, we'll know otherwise
        this.nativeCrossJoinVirtualCube = true;
        this.strictValidation = strictValidation;
        this.alertedNonNativeFunDefs = new HashSet<FunDef>();
        statement.setQuery(this);
        resolve();

        if (RolapUtil.PROFILE_LOGGER.isDebugEnabled()
            && statement.getProfileHandler() == null)
        {
            statement.enableProfiling(
                new ProfileHandler() {
                    public void explain(String plan, QueryTiming timing) {
                        if (timing != null) {
                            plan += "\n" + timing;
                        }
                        RolapUtil.PROFILE_LOGGER.debug(plan);
                    }
                }
            );
        }
    }

    /**
     * Sets the timeout in milliseconds of this Query.
     *
     * <p>Zero means no timeout.
     *
     * @param queryTimeoutMillis Timeout in milliseconds
     *
     * @deprecated This method will be removed in mondrian-4.0
     */
    public void setQueryTimeoutMillis(long queryTimeoutMillis) {
        statement.setQueryTimeoutMillis(queryTimeoutMillis);
    }

  public QueryPart[] getCellProperties() {
        return this.cellProps;
    }

    /**
     * Checks whether the property name is present in the query.
     */
    public boolean hasCellProperty(String propertyName) {
        for (QueryPart cellProp : cellProps) {
            if (((CellProperty)cellProp).isNameEquals(propertyName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether any cell property present in the query
     */
    public boolean isCellPropertyEmpty() {
        return cellProps.length == 0;
    }

    /**
     * Adds a new formula specifying a set
     * to an existing query.
     */
    public void addFormula(Id id, Exp exp) {
        addFormula(
            new Formula(false, id, exp, new MemberProperty[0], null, null));
    }

    /**
     * Adds a new formula specifying a member
     * to an existing query.
     *
     * @param id Name of member
     * @param exp Expression for member
     * @param memberProperties Properties of member
     */
    public void addFormula(
        Id id,
        Exp exp,
        MemberProperty[] memberProperties)
    {
        addFormula(new Formula(true, id, exp, memberProperties, null, null));
    }

    /**
     * Adds a new formula specifying a member or a set
     * to an existing query; resolve is called after
     * the formula has been added.
     *
     * @param formula Formula to add to query
     */
    public void addFormula(Formula formula) {
        formulas = Util.append(formulas, formula);
        resolve();
    }

    /**
     * Adds some number of new formulas specifying members
     * or sets to an existing query; resolve is only called
     * once, after all the new members have been added to
     * the query.
     *
     * @param additions Formulas to add to query
     */
    public void addFormulas(Formula... additions) {
        formulas = Util.appendArrays(formulas, additions);
        resolve();
    }

    /**
     * Creates a validator for this query.
     *
     * @return Validator
     */
    public Validator createValidator() {
        return createValidator(
            statement.getSchema().getFunTable(),
            false,
            new HashMap<QueryPart, QueryPart>());
    }


    public Validator createValidator(
        Map<QueryPart, QueryPart> resolvedIdentifiers)
    {
        return createValidator(
            statement.getSchema().getFunTable(),
            false,
            resolvedIdentifiers);
    }

    /**
     * Creates a validator for this query that uses a given function table and
     * function validation policy.
     *
     * @param functionTable Function table
     * @param alwaysResolveFunDef Whether to always resolve function
     *     definitions (see {@link Validator#alwaysResolveFunDef()})
     * @return Validator
     */
    public Validator createValidator(
        FunTable functionTable,
        boolean alwaysResolveFunDef)
    {
        return new QueryValidator(
            functionTable,
            alwaysResolveFunDef,
            Query.this,
            new HashMap<QueryPart, QueryPart>());
    }


    public Validator createValidator(
        FunTable functionTable,
        boolean alwaysResolveFunDef,
        Map<QueryPart, QueryPart> resolvedIdentifiers)
    {
        return new QueryValidator(
            functionTable,
            alwaysResolveFunDef,
            Query.this,
            resolvedIdentifiers);
    }

    /**
     * @deprecated Please use {@link #clone}; this method will be removed in
     * mondrian-4.0
     */
    public Query safeClone() {
        return clone();
    }

    @SuppressWarnings({
        "CloneDoesntCallSuperClone",
        "CloneDoesntDeclareCloneNotSupportedException"
    })
    public Query clone() {
        return new Query(
            statement,
            cube,
            Formula.cloneArray(formulas),
            QueryAxis.cloneArray(axes),
            (slicerAxis == null) ? null : (QueryAxis) slicerAxis.clone(),
            cellProps,
            parameters.toArray(new Parameter[parameters.size()]),
            strictValidation);
    }

    public Connection getConnection() {
        return statement.getMondrianConnection();
    }

    /**
     * Issues a cancel request on this Query object.  Once the thread
     * running the query detects the cancel request, the query execution will
     * throw an exception. See <code>BasicQueryTest.testCancel</code> for an
     * example of usage of this method.
     *
     * @deprecated This method is deprecated and will be removed in mondrian-4.0
     */
    public void cancel() {
        try {
            statement.cancel();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Checks if either a cancel request has been issued on the query or
     * the execution time has exceeded the timeout value (if one has been
     * set).  Exceptions are raised if either of these two conditions are
     * met.  This method should be called periodically during query execution
     * to ensure timely detection of these events, particularly before/after
     * any potentially long running operations.
     *
     * @deprecated This method will be removed in mondrian-4.0
     */
    public void checkCancelOrTimeout() {
        final Execution execution0 = statement.getCurrentExecution();
        if (execution0 == null) {
            return;
        }
        execution0.checkCancelOrTimeout();
    }

    /**
     * Gets the query start time
     * @return start time
     *
     * @deprecated Use {@link Execution#getStartTime}. This method is deprecated
     *   and will be removed in mondrian-4.0
     */
    public long getQueryStartTime() {
        final Execution currentExecution = statement.getCurrentExecution();
        return currentExecution == null
            ? 0
            : currentExecution.getStartTime();
    }

    /**
     * Determines whether an alert for non-native evaluation needs
     * to be posted.
     *
     * @param funDef function type to alert for
     *
     * @return true if alert should be raised
     */
    public boolean shouldAlertForNonNative(FunDef funDef) {
        return alertedNonNativeFunDefs.add(funDef);
    }

    private void normalizeAxes() {
        for (int i = 0; i < axes.length; i++) {
            AxisOrdinal correctOrdinal =
                AxisOrdinal.StandardAxisOrdinal.forLogicalOrdinal(i);
            if (axes[i].getAxisOrdinal() != correctOrdinal) {
                for (int j = i + 1; j < axes.length; j++) {
                    if (axes[j].getAxisOrdinal() == correctOrdinal) {
                        // swap axes
                        QueryAxis temp = axes[i];
                        axes[i] = axes[j];
                        axes[j] = temp;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Performs type-checking and validates internal consistency of a query,
     * using the default resolver.
     *
     * <p>This method is called automatically when a query is created; you need
     * to call this method manually if you have modified the query's expression
     * tree in any way.
     */
    public void resolve() {
        // Before commencing validation, create all calculated members
        // and calculated sets
        createFormulaElements();
        Map<QueryPart, QueryPart> resolvedIdentifiers =
            new IdBatchResolver(this).resolve();
        final Validator validator = createValidator(resolvedIdentifiers);
        resolve(validator); // resolve self and children
        // Create a dummy result so we can use its evaluator
        final Evaluator evaluator = RolapUtil.createEvaluator(statement);
        ExpCompiler compiler =
            createCompiler(
                evaluator, validator, Collections.singletonList(resultStyle));
        compile(compiler);
    }

    private void createFormulaElements() {
        if (formulas != null) {
            // Resolving of formulas should be done in two parts
            // because formulas might depend on each other, so all calculated
            // mdx elements have to be defined during resolve.
            for (Formula formula : formulas) {
                formula.createElement(this);
            }
        }
    }

    /**
     * @return true if the relevant property for ignoring invalid members is
     * set to true for this query's environment (a different property is
     * checked depending on whether environment is schema load vs query
     * validation)
     */
    public boolean ignoreInvalidMembers()
    {
        MondrianProperties props = MondrianProperties.instance();
        final boolean load = ((RolapCube) getCube()).isLoadInProgress();
        return
            !strictValidation
            && (load
                ? props.IgnoreInvalidMembers.get()
                : props.IgnoreInvalidMembersDuringQuery.get());
    }

    /**
     * A Query's ResultStyle can only be one of the following:
     *   ResultStyle.ITERABLE
     *   ResultStyle.LIST
     *   ResultStyle.MUTABLE_LIST
     */
    public void setResultStyle(ResultStyle resultStyle) {
        switch (resultStyle) {
        case ITERABLE:
            // For java4, use LIST
            this.resultStyle = (Util.Retrowoven)
                ? ResultStyle.LIST : ResultStyle.ITERABLE;
            break;
        case LIST:
        case MUTABLE_LIST:
            this.resultStyle = resultStyle;
            break;
        default:
            throw ResultStyleException.generateBadType(
                ResultStyle.ITERABLE_LIST_MUTABLELIST,
                resultStyle);
        }
    }

    public ResultStyle getResultStyle() {
        return resultStyle;
    }

    public HashMap<Hierarchy, Calc> subcubeHierarchyCalcs = new HashMap<Hierarchy, Calc>();
    public HashMap<Hierarchy, HashMap<Member, Member>> subcubeHierarchies = new HashMap<Hierarchy, HashMap<Member, Member>>();

    /**
     * Generates compiled forms of all expressions.
     *
     * @param compiler Compiler
     */
    private void compile(ExpCompiler compiler) {

        if(this.subcube != null) {

            for(Hierarchy hierarchy : ((RolapCube) getCube()).getHierarchies()) {

                mondrian.olap.Level[] levels = hierarchy.getLevels();
                mondrian.olap.Level lastLevel = levels[levels.length - 1];
                mondrian.mdx.LevelExpr levelExpr = new mondrian.mdx.LevelExpr(lastLevel);
                Exp levelMembers = new UnresolvedFunCall(
                  "AllMembers",
                  Syntax.Property,
                  new Exp[] {levelExpr}
                );

                Exp resultExp = null;

                List<Exp> subcubeAxisExps = this.subcube.getAxisExps();
                ArrayList<Exp> hierarchyExps = new ArrayList<Exp>();
                for(int j = 0; j < subcubeAxisExps.size(); j++) {
                    Exp subcubeAxisExp = subcubeAxisExps.get(j);
                    subcubeAxisExp = subcubeAxisExp.accept(compiler.getValidator());
                    Hierarchy[] subcubeAxisHierarchies = collectHierarchies(subcubeAxisExp);
                    if(Arrays.asList(subcubeAxisHierarchies).contains(hierarchy)) {
                        hierarchyExps.add(subcubeAxisExp);
                    }
                }
                for(Exp hierarchyExp: hierarchyExps) {
                    Exp prevExp;
                    if(resultExp == null) {
                        prevExp = levelMembers;
                    }
                    else {
                        prevExp = resultExp;
                    }
                    Exp axisInBracesExp =
                            new UnresolvedFunCall(
                                    "{}", Syntax.Braces, new Exp[] {hierarchyExp});
                    if(hierarchyExps.size() > 1) {
                        resultExp = new UnresolvedFunCall(
                                "Exists",
                                Syntax.Function,
                                new Exp[] {prevExp, axisInBracesExp}
                        );
                    }
                    else {
                        resultExp = axisInBracesExp;
                    }
                }

                if(resultExp != null) {
                    mondrian.mdx.HierarchyExpr hierarchyExpr = new mondrian.mdx.HierarchyExpr(hierarchy);
                    Exp hierarchyAllMembersExp = new UnresolvedFunCall(
                            "AllMembers",
                            Syntax.Property,
                            new Exp[] {hierarchyExpr}
                    );

                    resultExp = new UnresolvedFunCall(
                            "Exists",
                            Syntax.Function,
                            new Exp[] {hierarchyAllMembersExp, resultExp}
                    );

                    resultExp = resultExp.accept(compiler.getValidator());

                    Calc calc = compiler.compileList(resultExp);
                    subcubeHierarchyCalcs.put(hierarchy, calc);

                }
            }
        }

        if (formulas != null) {
            for (Formula formula : formulas) {
                formula.compile();
            }
        }

        if (axes != null) {
            axisCalcs = new Calc[axes.length];
            for (int i = 0; i < axes.length; i++) {
                Exp prevSet = axes[i].getSet();
                if(axes[i].isNonEmpty()) {
                    ArrayList<Exp> memberExps = new ArrayList<Exp>();
                    for(Member member: this.measuresMembers) {
                        memberExps.add(new MemberExpr(member));
                    }
                    Exp[] nonEmptyArgs;
                    if(memberExps.size() > 0) {
                        Exp set2Exp = new UnresolvedFunCall(
                                "{}", Syntax.Braces, memberExps.toArray(new Exp[memberExps.size()]));
                        nonEmptyArgs = new Exp[]{prevSet, set2Exp};
                    }
                    else {
                        nonEmptyArgs = new Exp[]{prevSet};
                    }
                    axes[i].setSet(new mondrian.mdx.UnresolvedFunCall(
                            "NonEmpty",
                            mondrian.olap.Syntax.Function,
                            nonEmptyArgs
                    ).accept(compiler.getValidator()));
                }
                axisCalcs[i] = axes[i].compile(compiler, resultStyle);
                axes[i].setSet(prevSet);
            }
        }
        if (slicerAxis != null) {
            slicerCalc = slicerAxis.compile(compiler, resultStyle);
        }
    }

    /**
     * Performs type-checking and validates internal consistency of a query.
     *
     * @param validator Validator
     */
    public void resolve(Validator validator) {
        // Register all parameters.
        parameters.clear();
        parametersByName.clear();
        accept(new ParameterFinder());

        // Register all aliased expressions ('expr AS alias') as named sets.
        accept(new AliasedExpressionFinder());

        // Validate formulas.
        if (formulas != null) {
            for (Formula formula : formulas) {
                validator.validate(formula);
            }
        }

        // Validate axes.
        if (axes != null) {
            Set<Integer> axisNames = new HashSet<Integer>();
            for (QueryAxis axis : axes) {
                validator.validate(axis);
                if (!axisNames.add(axis.getAxisOrdinal().logicalOrdinal())) {
                    throw MondrianResource.instance().DuplicateAxis.ex(
                        axis.getAxisName());
                }
            }

            // Make sure that there are no gaps. If there are N axes, then axes
            // 0 .. N-1 should exist.
            int seekOrdinal =
                AxisOrdinal.StandardAxisOrdinal.COLUMNS.logicalOrdinal();
            for (QueryAxis axis : axes) {
                if (!axisNames.contains(seekOrdinal)) {
                    AxisOrdinal axisName =
                        AxisOrdinal.StandardAxisOrdinal.forLogicalOrdinal(
                            seekOrdinal);
                    throw MondrianResource.instance().NonContiguousAxis.ex(
                        seekOrdinal,
                        axisName.name());
                }
                ++seekOrdinal;
            }
        }
        if (slicerAxis != null) {
            slicerAxis.validate(validator);
        }

        // Make sure that no hierarchy is used on more than one axis.
        for (Hierarchy hierarchy : ((RolapCube) getCube()).getHierarchies()) {
            int useCount = 0;
            for (QueryAxis axis : allAxes()) {
                if (axis.getSet().getType().usesHierarchy(hierarchy, true)) {
                    ++useCount;
                }
            }
            if (useCount > 1) {
                throw MondrianResource.instance().HierarchyInIndependentAxes.ex(
                    hierarchy.getUniqueName());
            }
        }
    }

    @Override
    public void explain(PrintWriter pw) {
        final boolean profiling = getStatement().getProfileHandler() != null;
        final CalcWriter calcWriter = new CalcWriter(pw, profiling);
        for (Formula formula : formulas) {
            formula.getMdxMember(); // TODO:
        }
        if (slicerCalc != null) {
            pw.println("Axis (FILTER):");
            slicerCalc.accept(calcWriter);
            pw.println();
        }
        int i = -1;
        for (QueryAxis axis : axes) {
            ++i;
            pw.println("Axis (" + axis.getAxisName() + "):");
            axisCalcs[i].accept(calcWriter);
            pw.println();
        }
        pw.flush();
    }

    /**
     * Returns a collection of all axes, including the slicer as the first
     * element, if there is a slicer.
     *
     * @return Collection of all axes including slicer
     */
    private Collection<QueryAxis> allAxes() {
        if (slicerAxis == null) {
            return Arrays.asList(axes);
        } else {
            //noinspection unchecked
            return new CompositeCollection(
                new Collection[] {
                    Collections.singletonList(slicerAxis),
                    Arrays.asList(axes)});
        }
    }

    public void unparse(PrintWriter pw) {
        if (formulas != null) {
            for (int i = 0; i < formulas.length; i++) {
                if (i == 0) {
                    pw.print("with ");
                } else {
                    pw.print("  ");
                }
                formulas[i].unparse(pw);
                pw.println();
            }
        }
        pw.print("select ");
        if (axes != null) {
            for (int i = 0; i < axes.length; i++) {
                axes[i].unparse(pw);
                if (i < axes.length - 1) {
                    pw.println(",");
                    pw.print("  ");
                } else {
                    pw.println();
                }
            }
        }
        if (subcube != null) {
            pw.print("from ");
            subcube.unparse(pw);
        }
        if (slicerAxis != null) {
            pw.print("where ");
            slicerAxis.unparse(pw);
            pw.println();
        }
    }

    /** Returns the MDX query string. */
    public String toString() {
        resolve();
        return Util.unparse(this);
    }

    public Object[] getChildren() {
        // Chidren are axes, slicer, and formulas (in that order, to be
        // consistent with replaceChild).
        List<QueryPart> list = new ArrayList<QueryPart>();
        list.addAll(Arrays.asList(axes));
        if (slicerAxis != null) {
            list.add(slicerAxis);
        }
        list.addAll(Arrays.asList(formulas));
        return list.toArray();
    }

    public QueryAxis getSlicerAxis() {
        return slicerAxis;
    }

    public void setSlicerAxis(QueryAxis axis) {
        this.slicerAxis = axis;
    }

    /**
     * Adds a level to an axis expression.
     */
    public void addLevelToAxis(AxisOrdinal axis, Level level) {
        assert axis != null;
        axes[axis.logicalOrdinal()].addLevel(level);
    }

    /**
     * Returns the hierarchies in an expression.
     *
     * <p>If the expression's type is a dimension with several hierarchies,
     * assumes that the expression yields a member of the first (default)
     * hierarchy of the dimension.
     *
     * <p>For example, the expression
     * <blockquote><code>Crossjoin(
     *   Hierarchize(
     *     Union(
     *       {[Time].LastSibling}, [Time].LastSibling.Children)),
     *       {[Measures].[Unit Sales], [Measures].[Store Cost]})</code>
     * </blockquote>
     *
     * has type <code>{[Time.Monthly], [Measures]}</code> even though
     * <code>[Time].LastSibling</code> might return a member of either
     * [Time.Monthly] or [Time.Weekly].
     */
    private Hierarchy[] collectHierarchies(Exp queryPart) {
        Type exprType = queryPart.getType();
        if (exprType instanceof SetType) {
            exprType = ((SetType) exprType).getElementType();
        }
        if (exprType instanceof TupleType) {
            final Type[] types = ((TupleType) exprType).elementTypes;
            ArrayList<Hierarchy> hierarchyList = new ArrayList<Hierarchy>();
            for (Type type : types) {
                hierarchyList.add(getTypeHierarchy(type));
            }
            return hierarchyList.toArray(new Hierarchy[hierarchyList.size()]);
        }
        return new Hierarchy[] {getTypeHierarchy(exprType)};
    }

    private Hierarchy getTypeHierarchy(final Type type) {
        Hierarchy hierarchy = type.getHierarchy();
        if (hierarchy != null) {
            return hierarchy;
        }
        final Dimension dimension = type.getDimension();
        if (dimension != null) {
            return dimension.getHierarchy();
        }
        return null;
    }

    /**
     * Assigns a value to the parameter with a given name.
     *
     * @throws RuntimeException if there is not parameter with the given name
     */
    public void setParameter(final String parameterName, final Object value) {
        // Need to resolve query before we set parameters, in order to create
        // slots to store them in. (This code will go away when parameters
        // belong to prepared statements.)
        if (parameters.isEmpty()) {
            resolve();
        }

        final Parameter param =
            getSchemaReader(false).getParameter(parameterName);
        if (param == null) {
            throw MondrianResource.instance().UnknownParameter.ex(
                parameterName);
        }
        if (!param.isModifiable()) {
            throw MondrianResource.instance().ParameterIsNotModifiable.ex(
                parameterName, param.getScope().name());
        }
        final Object value2 =
        Locus.execute(
            new Execution(statement, 0),
            "Query.quickParse",
            new Locus.Action<Object>() {
                public Object execute() {
                    return quickParse(
                        parameterName, param.getType(), value, Query.this);
                }
            }
        );
        param.setValue(value2);
    }

    /**
     * Converts a value into something appropriate for a given type.
     *
     * <p>Viz:
     * <ul>
     * <li>For numerics, takes number or string and returns a {@link Number}.
     * <li>For strings, takes string, or calls {@link Object#toString()} on any
     *     other type
     * <li>For members, takes member or string
     * <li>For sets of members, requires a list of members or strings and
     *     converts each element to a member.
     * </ul>
     *
     * @param type Type
     * @param value Value
     * @param query Query
     * @return Value of appropriate type
     * @throws NumberFormatException If value needs to be a number but isn't
     */
    private static Object quickParse(
        String parameterName,
        Type type,
        Object value,
        Query query)
        throws NumberFormatException
    {
        int category = TypeUtil.typeToCategory(type);
        switch (category) {
        case Category.Numeric:
            if (value instanceof Number || value == null) {
                return value;
            }
            if (value instanceof String) {
                String s = (String) value;
                try {
                    return new Integer(s);
                } catch (NumberFormatException e) {
                    return new Double(s);
                }
            }
            throw Util.newInternal(
                "Invalid value '" + value + "' for parameter '" + parameterName
                + "', type " + type);
        case Category.String:
            if (value == null) {
                return null;
            }
            return value.toString();
        case Category.Set:
            if (value instanceof String) {
                value = IdentifierParser.parseIdentifierList((String) value);
            }
            if (!(value instanceof List)) {
                throw Util.newInternal(
                    "Invalid value '" + value + "' for parameter '"
                    + parameterName + "', type " + type);
            }
            List<Member> expList = new ArrayList<Member>();
            final List list = (List) value;
            final SetType setType = (SetType) type;
            final Type elementType = setType.getElementType();
            for (Object o : list) {
                // In keeping with MDX semantics, null members are omitted from
                // lists.
                if (o == null) {
                    continue;
                }
                final Member member =
                    (Member) quickParse(parameterName, elementType, o, query);
                expList.add(member);
            }
            return expList;
        case Category.Member:
            if (value == null) {
                // Setting a member parameter to null is the same as setting to
                // the null member of the hierarchy. May not be equivalent to
                // the default value of the parameter, nor the same as the all
                // member.
                if (type.getHierarchy() != null) {
                    value = type.getHierarchy().getNullMember();
                } else if (type.getDimension() != null) {
                    value = type.getDimension().getHierarchy().getNullMember();
                }
            }
            if (value instanceof String) {
                value = Util.parseIdentifier((String) value);
            }
            if (value instanceof List
                && Util.canCast((List) value, Id.Segment.class))
            {
                final List<Id.Segment> segmentList = Util.cast((List) value);
                final OlapElement olapElement = Util.lookup(query, segmentList);
                if (olapElement instanceof Member) {
                    value = olapElement;
                }
            }
            if (value instanceof List
                && Util.canCast((List) value, IdentifierSegment.class))
            {
                final List<IdentifierSegment> olap4jSegmentList =
                    Util.cast((List) value);
                final List<Id.Segment> segmentList =
                    Util.convert(olap4jSegmentList);
                final OlapElement olapElement = Util.lookup(query, segmentList);
                if (olapElement instanceof Member) {
                    value = olapElement;
                }
            }
            if (value instanceof Member) {
                if (type.isInstance(value)) {
                    return value;
                }
            }
            throw Util.newInternal(
                "Invalid value '" + value + "' for parameter '"
                + parameterName + "', type " + type);
        default:
            throw Category.instance.badValue(category);
        }
    }

    /**
     * Swaps the x- and y- axes.
     * Does nothing if the number of axes != 2.
     */
    public void swapAxes() {
        if (axes.length == 2) {
            Exp e0 = axes[0].getSet();
            boolean nonEmpty0 = axes[0].isNonEmpty();
            Exp e1 = axes[1].getSet();
            boolean nonEmpty1 = axes[1].isNonEmpty();
            axes[1].setSet(e0);
            axes[1].setNonEmpty(nonEmpty0);
            axes[0].setSet(e1);
            axes[0].setNonEmpty(nonEmpty1);
            // showSubtotals ???
        }
    }

    /**
     * Returns the parameters defined in this query.
     */
    public Parameter[] getParameters() {
        return parameters.toArray(new Parameter[parameters.size()]);
    }

    public Cube getCube() {
        return cube;
    }

    public Subcube getSubcube() {
        return subcube;
    }

    /**
     * Returns a schema reader.
     *
     * @param accessControlled If true, schema reader returns only elements
     * which are accessible to the statement's current role
     *
     * @return schema reader
     */
    public SchemaReader getSchemaReader(boolean accessControlled) {
        final Role role;
        if (accessControlled) {
            // full access control
            role = getConnection().getRole();
        } else {
            role = null;
        }
        final SchemaReader cubeSchemaReader = cube.getSchemaReader(role);
        return new QuerySchemaReader(cubeSchemaReader, Query.this);
    }

    /**
     * Looks up a member whose unique name is <code>memberUniqueName</code>
     * from cache. If the member is not in cache, returns null.
     */
    public Member lookupMemberFromCache(String memberUniqueName) {
        // first look in defined members
        for (Member member : getDefinedMembers()) {
            if (Util.equalName(member.getUniqueName(), memberUniqueName)
                || Util.equalName(
                    getUniqueNameWithoutAll(member),
                    memberUniqueName))
            {
                return member;
            }
        }
        return null;
    }

    private String getUniqueNameWithoutAll(Member member) {
        // build unique string
        Member parentMember = member.getParentMember();
        if ((parentMember != null) && !parentMember.isAll()) {
            return Util.makeFqName(
                getUniqueNameWithoutAll(parentMember),
                member.getName());
        } else {
            return Util.makeFqName(member.getHierarchy(), member.getName());
        }
    }

    /**
     * Looks up a named set.
     */
    private NamedSet lookupNamedSet(Id.Segment segment) {
        if (!(segment instanceof Id.NameSegment)) {
            return null;
        }
        Id.NameSegment nameSegment = (Id.NameSegment) segment;
        for (Formula formula : formulas) {
            if (!formula.isMember()
                && formula.getElement() != null
                && formula.getName().equals(nameSegment.getName()))
            {
                return (NamedSet) formula.getElement();
            }
        }
        return null;
    }

    /**
     * Creates a named set defined by an alias.
     */
    public ScopedNamedSet createScopedNamedSet(
        String name,
        QueryPart scope,
        Exp expr)
    {
        final ScopedNamedSet scopedNamedSet =
            new ScopedNamedSet(
                name, scope, expr);
        scopedNamedSets.add(scopedNamedSet);
        return scopedNamedSet;
    }

    /**
     * Looks up a named set defined by an alias.
     *
     * @param nameParts Multi-part identifier for set
     * @param scopeList Parse tree node where name is used (last in list) and
     */
    ScopedNamedSet lookupScopedNamedSet(
        List<Id.Segment> nameParts,
        ArrayStack<QueryPart> scopeList)
    {
        if (nameParts.size() != 1) {
            return null;
        }
        if (!(nameParts.get(0) instanceof Id.NameSegment)) {
            return null;
        }
        String name = ((Id.NameSegment) nameParts.get(0)).getName();
        ScopedNamedSet bestScopedNamedSet = null;
        int bestScopeOrdinal = -1;
        for (ScopedNamedSet scopedNamedSet : scopedNamedSets) {
            if (Util.equalName(scopedNamedSet.name, name)) {
                int scopeOrdinal = scopeList.indexOf(scopedNamedSet.scope);
                if (scopeOrdinal > bestScopeOrdinal) {
                    bestScopedNamedSet = scopedNamedSet;
                    bestScopeOrdinal = scopeOrdinal;
                }
            }
        }
        return bestScopedNamedSet;
    }

    /**
     * Returns an array of the formulas used in this query.
     */
    public Formula[] getFormulas() {
        return formulas;
    }

    /**
     * Returns an array of this query's axes.
     */
    public QueryAxis[] getAxes() {
        return axes;
    }

    /**
     * Remove a formula from the query. If <code>failIfUsedInQuery</code> is
     * true, checks and throws an error if formula is used somewhere in the
     * query.
     */
    public void removeFormula(String uniqueName, boolean failIfUsedInQuery) {
        Formula formula = findFormula(uniqueName);
        if (failIfUsedInQuery && formula != null) {
            OlapElement mdxElement = formula.getElement();
            // search the query tree to see if this formula expression is used
            // anywhere (on the axes or in another formula)
            Walker walker = new Walker(this);
            while (walker.hasMoreElements()) {
                Object queryElement = walker.nextElement();
                if (!queryElement.equals(mdxElement)) {
                    continue;
                }
                // mdxElement is used in the query. lets find on on which axis
                // or formula
                String formulaType = formula.isMember()
                    ? MondrianResource.instance().CalculatedMember.str()
                    : MondrianResource.instance().CalculatedSet.str();

                int i = 0;
                Object parent = walker.getAncestor(i);
                Object grandParent = walker.getAncestor(i + 1);
                while ((parent != null) && (grandParent != null)) {
                    if (grandParent instanceof Query) {
                        if (parent instanceof Axis) {
                            throw MondrianResource.instance()
                                .MdxCalculatedFormulaUsedOnAxis.ex(
                                    formulaType,
                                    uniqueName,
                                    ((QueryAxis) parent).getAxisName());

                        } else if (parent instanceof Formula) {
                            String parentFormulaType =
                                ((Formula) parent).isMember()
                                    ? MondrianResource.instance()
                                          .CalculatedMember.str()
                                    : MondrianResource.instance()
                                          .CalculatedSet.str();
                            throw MondrianResource.instance()
                                .MdxCalculatedFormulaUsedInFormula.ex(
                                    formulaType, uniqueName, parentFormulaType,
                                    ((Formula) parent).getUniqueName());

                        } else {
                            throw MondrianResource.instance()
                                .MdxCalculatedFormulaUsedOnSlicer.ex(
                                    formulaType, uniqueName);
                        }
                    }
                    ++i;
                    parent = walker.getAncestor(i);
                    grandParent = walker.getAncestor(i + 1);
                }
                throw MondrianResource.instance()
                    .MdxCalculatedFormulaUsedInQuery.ex(
                        formulaType, uniqueName, Util.unparse(this));
            }
        }

        // remove formula from query
        List<Formula> formulaList = new ArrayList<Formula>();
        for (Formula formula1 : formulas) {
            if (!formula1.getUniqueName().equalsIgnoreCase(uniqueName)) {
                formulaList.add(formula1);
            }
        }

        // it has been found and removed
        this.formulas = formulaList.toArray(new Formula[formulaList.size()]);
    }

    /**
     * Returns whether a formula can safely be removed from the query. It can be
     * removed if the member or set it defines it not used anywhere else in the
     * query, including in another formula.
     *
     * @param uniqueName Unique name of the member or set defined by the formula
     * @return whether the formula can safely be removed
     */
    public boolean canRemoveFormula(String uniqueName) {
        Formula formula = findFormula(uniqueName);
        if (formula == null) {
            return false;
        }

        OlapElement mdxElement = formula.getElement();
        // Search the query tree to see if this formula expression is used
        // anywhere (on the axes or in another formula).
        Walker walker = new Walker(this);
        while (walker.hasMoreElements()) {
            Object queryElement = walker.nextElement();
            if (queryElement instanceof MemberExpr
                && ((MemberExpr) queryElement).getMember().equals(mdxElement))
            {
                return false;
            }
            if (queryElement instanceof NamedSetExpr
                && ((NamedSetExpr) queryElement).getNamedSet().equals(
                    mdxElement))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Looks up a calculated member or set defined in this Query.
     *
     * @param uniqueName Unique name of calculated member or set
     * @return formula defining calculated member, or null if not found
     */
    public Formula findFormula(String uniqueName) {
        for (Formula formula : formulas) {
            if (formula.getUniqueName().equalsIgnoreCase(uniqueName)) {
                return formula;
            }
        }
        return null;
    }

    /**
     * Finds formula by name and renames it to new name.
     */
    public void renameFormula(String uniqueName, String newName) {
        Formula formula = findFormula(uniqueName);
        if (formula == null) {
            throw MondrianResource.instance().MdxFormulaNotFound.ex(
                "formula", uniqueName, Util.unparse(this));
        }
        formula.rename(newName);
    }

    List<Member> getDefinedMembers() {
        List<Member> definedMembers = new ArrayList<Member>();
        for (final Formula formula : formulas) {
            if (formula.isMember()
                && formula.getElement() != null
                && getConnection().getRole().canAccess(formula.getElement()))
            {
                definedMembers.add((Member) formula.getElement());
            }
        }
        return definedMembers;
    }

    /**
     * Finds axis by index and sets flag to show empty cells on that axis.
     */
    public void setAxisShowEmptyCells(int axis, boolean showEmpty) {
        if (axis >= axes.length) {
            throw MondrianResource.instance().MdxAxisShowSubtotalsNotSupported
                .ex(axis);
        }
        axes[axis].setNonEmpty(!showEmpty);
    }

    /**
     * Returns <code>Hierarchy[]</code> used on <code>axis</code>. It calls
     * {@link #collectHierarchies}.
     */
    public Hierarchy[] getMdxHierarchiesOnAxis(AxisOrdinal axis) {
        if (axis.logicalOrdinal() >= axes.length) {
            throw MondrianResource.instance().MdxAxisShowSubtotalsNotSupported
                .ex(axis.logicalOrdinal());
        }
        QueryAxis queryAxis =
            axis.isFilter()
            ? slicerAxis
            : axes[axis.logicalOrdinal()];
        return collectHierarchies(queryAxis.getSet());
    }

    public Hierarchy[] getMdxHierarchiesOnAxis(QueryAxis axis) {
        if(axis == null) {
            return new Hierarchy[0];
        }
        return collectHierarchies(axis.getSet());
    }

    /**
     * Compiles an expression, using a cached compiled expression if available.
     *
     * @param exp Expression
     * @param scalar Whether expression is scalar
     * @param resultStyle Preferred result style; if null, use query's default
     *     result style; ignored if expression is scalar
     * @return compiled expression
     */
    public Calc compileExpression(
        Exp exp,
        boolean scalar,
        ResultStyle resultStyle)
    {
        // REVIEW: Set query on a connection's shared internal statement is
        // not re-entrant.
        statement.setQuery(this);
        Evaluator evaluator = RolapEvaluator.create(statement);
        final Validator validator = createValidator();
        List<ResultStyle> resultStyleList;
        resultStyleList =
            Collections.singletonList(
                resultStyle != null ? resultStyle : this.resultStyle);
        final ExpCompiler compiler =
            createCompiler(
                evaluator, validator, resultStyleList);
        if (scalar) {
            return compiler.compileScalar(exp, false);
        } else {
            return compiler.compile(exp);
        }
    }

    public ExpCompiler createCompiler() {
        // REVIEW: Set query on a connection's shared internal statement is
        // not re-entrant.
        statement.setQuery(this);
        Evaluator evaluator = RolapEvaluator.create(statement);
        Validator validator = createValidator();
        return createCompiler(
            evaluator,
            validator,
            Collections.singletonList(resultStyle));
    }

    private ExpCompiler createCompiler(
        final Evaluator evaluator,
        final Validator validator,
        List<ResultStyle> resultStyleList)
    {
        ExpCompiler compiler =
            ExpCompiler.Factory.getExpCompiler(
                evaluator,
                validator,
                resultStyleList);

        final int expDeps =
            MondrianProperties.instance().TestExpDependencies.get();
        final ProfileHandler profileHandler = statement.getProfileHandler();
        if (profileHandler != null) {
            // Cannot test dependencies and profile at the same time. Profiling
            // trumps.
            compiler = RolapUtil.createProfilingCompiler(compiler);
        } else if (expDeps > 0) {
            compiler = RolapUtil.createDependencyTestingCompiler(compiler);
        }
        return compiler;
    }

    /**
     * Keeps track of references to members of the measures dimension
     *
     * @param olapElement potential measure member
     */
    public void addMeasuresMembers(OlapElement olapElement)
    {
        if (olapElement instanceof Member) {
            Member member = (Member) olapElement;
            if (member.isMeasure()) {
                measuresMembers.add(member);
            }
        }
    }

    /**
     * @return set of members from the measures dimension referenced within
     * this query
     */
    public Set<Member> getMeasuresMembers() {
        return Collections.unmodifiableSet(measuresMembers);
    }

    /**
     * Indicates that the query cannot use native cross joins to process
     * this virtual cube
     */
    public void setVirtualCubeNonNativeCrossJoin() {
        nativeCrossJoinVirtualCube = false;
    }

    /**
     * @return true if the query can use native cross joins on a virtual
     * cube
     */
    public boolean nativeCrossJoinVirtualCube() {
        return nativeCrossJoinVirtualCube;
    }

    /**
     * Saves away the base cubes related to the virtual cube
     * referenced in this query
     *
     * @param baseCubes set of base cubes
     */
    public void setBaseCubes(List<RolapCube> baseCubes) {
        this.baseCubes = baseCubes;
    }

    /**
     * return the set of base cubes associated with the virtual cube referenced
     * in this query
     *
     * @return set of base cubes
     */
    public List<RolapCube> getBaseCubes() {
        return baseCubes;
    }

    public Object accept(MdxVisitor visitor) {
        Object o = visitor.visit(this);

        if (visitor.shouldVisitChildren()) {
            // visit formulas
            for (Formula formula : formulas) {
                formula.accept(visitor);
            }
            // visit axes
            for (QueryAxis axis : axes) {
                axis.accept(visitor);
            }
            if (slicerAxis != null) {
                slicerAxis.accept(visitor);
            }
        }
        return o;
    }

    /**
     * Put an Object value into the evaluation cache with given key.
     * This is used by Calc's to store information between iterations
     * (rather than re-generate each time).
     *
     * @param key the cache key
     * @param value the cache value
     */
    public void putEvalCache(String key, Object value) {
        evalCache.put(key, value);
    }

    /**
     * Gets the Object associated with the value.
     *
     * @param key the cache key
     * @return the cached value or null.
     */
    public Object getEvalCache(String key) {
        return evalCache.get(key);
    }

    /**
     * Remove all entries in the evaluation cache
     */
    public void clearEvalCache() {
        evalCache.clear();
    }

    /**
     * Closes this query.
     *
     * <p>Releases any resources held. Writes statistics to log if profiling
     * is enabled.
     *
     * <p>This method is idempotent.
     *
     * @deprecated This method will be removed in mondrian-4.0.
     */
    public void close() {
        if (ownStatement) {
            statement.close();
        }
    }

    public Statement getStatement() {
        return statement;
    }

    /**
     * Sets that the query owns its statement; therefore it will need to
     * close it when the query is closed.
     *
     * @param ownStatement Whether the statement belongs to the query
     */
    public void setOwnStatement(boolean ownStatement) {
        this.ownStatement = ownStatement;
    }

    /**
     * Source of metadata within the scope of a query.
     *
     * <p>Note especially that {@link #getCalculatedMember(java.util.List)}
     * returns the calculated members defined in this query. It does not
     * perform access control; all calculated members defined in a query are
     * visible to everyone.
     */
    private static class QuerySchemaReader
        extends DelegatingSchemaReader
        implements NameResolver.Namespace
    {
        private final Query query;

        public QuerySchemaReader(SchemaReader cubeSchemaReader, Query query) {
            super(cubeSchemaReader);
            this.query = query;
        }

        public SchemaReader withoutAccessControl() {
            return new QuerySchemaReader(
                schemaReader.withoutAccessControl(), query);
        }

        public Member getMemberByUniqueName(
            List<Id.Segment> uniqueNameParts,
            boolean failIfNotFound,
            MatchType matchType)
        {
            final String uniqueName = Util.implode(uniqueNameParts);
            Member member = query.lookupMemberFromCache(uniqueName);
            if (member == null) {
                // Not a calculated member in the query, so go to the cube.
                member = schemaReader.getMemberByUniqueName(
                    uniqueNameParts, failIfNotFound, matchType);
            }
            if (!failIfNotFound && member == null) {
                return null;
            }
            if (getRole().canAccess(member)) {
                return member;
            } else {
                return null;
            }
        }

        @Override
        public List<Member> getLevelMembers(
                Level level,
                boolean includeCalculated)
        {
            return getLevelMembers(level, includeCalculated, null);
        }

        @Override
        public List<Member> getLevelMembers(
                Level level,
                Evaluator context)
        {
            return getLevelMembers(level, false, context);
        }

        public List<Member> getLevelMembers(
            Level level,
            boolean includeCalculated,
            Evaluator context)
        {
            List<Member> members = super.getLevelMembers(level, false, context);
            if (includeCalculated) {
                members = Util.addLevelCalculatedMembers(this, level, members);
            }

            Hierarchy hierarchy = level.getHierarchy();
            if(query.subcubeHierarchies.containsKey(hierarchy)) {
                ArrayList<Member> newMembers = new ArrayList<Member>();
                HashMap<Member, Member> subcubeMembers = query.subcubeHierarchies.get(hierarchy);
                for (int i = 0; i < members.size(); i++) {
                    Member sourceMember = members.get(i);
                    if(subcubeMembers.containsKey(sourceMember)) {
                        newMembers.add(subcubeMembers.get(sourceMember));
                    }
                }
                members = newMembers;
            }

            return members;
        }

        public List<Member> getMemberChildren(Member member) {
            //Must be RolapMember, not LimitedRollupMember
            Member rolapMember = query.getRolapMember(member);
            return query.getSubcubeMembers(super.getMemberChildren(rolapMember), false);
        }

        public List<Member> getMemberChildren(List<Member> members) {
            //Must be RolapMember, not LimitedRollupMember
            List<Member> rolapMembers = query.getRolapMembers(members);
            return query.getSubcubeMembers(super.getMemberChildren(rolapMembers), false);
        }

        public List<Member> getMemberChildren(Member member, Evaluator context) {
            //Must be RolapMember, not LimitedRollupMember
            Member rolapMember = query.getRolapMember(member);
            return query.getSubcubeMembers(super.getMemberChildren(rolapMember, context), false);
        }

        public List<Member> getMemberChildren(
                List<Member> members, Evaluator context)
        {
            //Must be RolapMember, not LimitedRollupMember
            List<Member> rolapMembers = query.getRolapMembers(members);
            return query.getSubcubeMembers(super.getMemberChildren(rolapMembers, context), false);
        }

        public Map<? extends Member, Access> getMemberChildrenWithDetails(
                Member member,
                Evaluator evaluator)
        {
            //Must be RolapMember, not LimitedRollupMember
            Member rolapMember = query.getRolapMember(member);
            Map<Member, Access> sourceMembers = (Map<Member, Access>)super.getMemberChildrenWithDetails(rolapMember, evaluator);
            HashMap<Member, Access> newMembers = new HashMap<Member, Access>();
            for(Map.Entry<Member, Access> entry : sourceMembers.entrySet()) {
                Member subcubeMember = query.getSubcubeMember(entry.getKey(), false);
                if(subcubeMember != null) {
                    newMembers.put(subcubeMember, entry.getValue());
                }
            }

            return newMembers;
        }

        public Member getCalculatedMember(List<Id.Segment> nameParts) {
            for (final Formula formula : query.formulas) {
                if (!formula.isMember()) {
                    continue;
                }
                Member member = (Member) formula.getElement();
                if (member == null) {
                    continue;
                }
                if (!Util.matches(member, nameParts)) {
                    continue;
                }
                if (!query.getConnection().getRole().canAccess(member)) {
                    continue;
                }
                return member;
            }
            return null;
        }



        public List<Member> getCalculatedMembers(Hierarchy hierarchy) {
            List<Member> result = new ArrayList<Member>();
            // Add calculated members in the cube.
            final List<Member> calculatedMembers =
                super.getCalculatedMembers(hierarchy);
            result.addAll(calculatedMembers);
            // Add calculated members defined in the query.
            for (Member member : query.getDefinedMembers()) {
                if (member.getHierarchy().equals(hierarchy)) {
                    result.add(member);
                }
            }
            return result;
        }

        public List<Member> getCalculatedMembers(Level level) {
            List<Member> hierarchyMembers =
                getCalculatedMembers(level.getHierarchy());
            List<Member> result = new ArrayList<Member>();
            for (Member member : hierarchyMembers) {
                if (member.getLevel().equals(level)) {
                    result.add(member);
                }
            }
            return result;
        }

        public List<Member> getCalculatedMembers() {
            return query.getDefinedMembers();
        }

        public OlapElement getElementChild(OlapElement parent, Id.Segment s)
        {
            return getElementChild(parent, s, MatchType.EXACT);
        }

        public OlapElement getElementChild(
            OlapElement parent,
            Id.Segment s,
            MatchType matchType)
        {
            // first look in cube
            OlapElement mdxElement =
                schemaReader.getElementChild(parent, s, matchType);
            if (mdxElement != null) {
                return mdxElement;
            }
            // then look in defined members (fixes MONDRIAN-77)

            // then in defined sets
            if (!(s instanceof Id.NameSegment)) {
                return null;
            }
            String name = ((Id.NameSegment) s).getName();
            for (Formula formula : query.formulas) {
                if (formula.isMember()) {
                    continue;       // have already done these
                }
                Id id = formula.getIdentifier();
                if (id.getSegments().size() == 1
                    && id.getSegments().get(0).matches(name))
                {
                    return formula.getNamedSet();
                }
            }

            return mdxElement;
        }

        @Override
        public OlapElement lookupCompoundInternal(
            OlapElement parent,
            List<Id.Segment> names,
            boolean failIfNotFound,
            int category,
            MatchType matchType)
        {
            if (matchType == MatchType.EXACT) {
                OlapElement oe = lookupCompound(
                    parent, names, failIfNotFound, category,
                    MatchType.EXACT_SCHEMA);
                if (oe != null) {
                    return oe;
                }
            }
            // First look to ourselves.
            switch (category) {
            case Category.Unknown:
            case Category.Member:
                if (parent == query.cube) {
                    final Member calculatedMember = getCalculatedMember(names);
                    if (calculatedMember != null) {
                        return calculatedMember;
                    }
                }
            }
            switch (category) {
            case Category.Unknown:
            case Category.Set:
                if (parent == query.cube) {
                    final NamedSet namedSet = getNamedSet(names);
                    if (namedSet != null) {
                        return namedSet;
                    }
                }
            }
            // Then delegate to the next reader.
            OlapElement olapElement = super.lookupCompoundInternal(
                parent, names, failIfNotFound, category, matchType);
            if (olapElement instanceof Member) {
                Member member = (Member) olapElement;
                final Formula formula = (Formula)
                    member.getPropertyValue(Property.FORMULA.name);
                if (formula != null) {
                    // This is a calculated member defined against the cube.
                    // Create a free-standing formula using the same
                    // expression, then use the member defined in that formula.
                    final Formula formulaClone = (Formula) formula.clone();
                    formulaClone.createElement(query);
                    formulaClone.accept(query.createValidator());
                    olapElement = formulaClone.getMdxMember();
                }
            }
            return olapElement;
        }

        public NamedSet getNamedSet(List<Id.Segment> nameParts) {
            if (nameParts.size() != 1) {
                return null;
            }
            return query.lookupNamedSet(nameParts.get(0));
        }

        public Parameter getParameter(String name) {
            // Look for a parameter defined in the query.
            for (Parameter parameter : query.parameters) {
                if (parameter.getName().equals(name)) {
                    return parameter;
                }
            }

            // Look for a parameter defined in this statement.
            if (Util.lookup(RolapConnectionProperties.class, name) != null) {
                Object value = query.statement.getProperty(name);
                // TODO: Don't assume it's a string.
                // TODO: Create expression which will get the value from the
                //  statement at the time the query is executed.
                Literal defaultValue =
                    Literal.createString(String.valueOf(value));
                return new ConnectionParameterImpl(name, defaultValue);
            }

            return super.getParameter(name);
        }

        public OlapElement lookupChild(
            OlapElement parent,
            IdentifierSegment segment,
            MatchType matchType)
        {
            // ignore matchType
            return lookupChild(parent, segment);
        }

        public OlapElement lookupChild(
            OlapElement parent,
            IdentifierSegment segment)
        {
            // Only look for calculated members and named sets defined in the
            // query.
            for (Formula formula : query.getFormulas()) {
                if (NameResolver.matches(formula, parent, segment)) {
                    return formula.getElement();
                }
            }

            //Must be RolapMember, not LimitedRollupMember
            OlapElement parentOlapElement = parent;
            if(parent != null && parent instanceof RolapMember) {
                parentOlapElement = query.getRolapMember((RolapMember)parent);
            }
            OlapElement child = null;
            for (NameResolver.Namespace namespace : this.getNamespaces()) {
                if(namespace != this) {
                    child = namespace.lookupChild(parentOlapElement, segment);
                    if (child != null) {
                        break;
                    }
                }
            }

            if(child != null && child instanceof RolapMember) {
                return query.getSubcubeMember((RolapMember)child, true);
            }

            return null;
        }

        public Member getHierarchyDefaultMember(Hierarchy hierarchy) {
            Member member = super.getHierarchyDefaultMember(hierarchy);
            return query.getSubcubeMember(member, true);
        }

        public List<NameResolver.Namespace> getNamespaces() {
            final List<NameResolver.Namespace> list =
                new ArrayList<NameResolver.Namespace>();
            list.add(this);
            list.addAll(super.getNamespaces());
            return list;
        }
    }

    private static class ConnectionParameterImpl
        extends ParameterImpl
    {
        public ConnectionParameterImpl(String name, Literal defaultValue) {
            super(name, defaultValue, "Connection property", new StringType());
        }

        public Scope getScope() {
            return Scope.Connection;
        }

        public void setValue(Object value) {
            throw MondrianResource.instance().ParameterIsNotModifiable.ex(
                getName(), getScope().name());
        }
    }

    /**
     * Implementation of {@link mondrian.olap.Validator} that works within a
     * particular query.
     *
     * <p>It's unlikely that we would want a validator that is
     * NOT within a particular query, but by organizing the code this way, with
     * the majority of the code in {@link mondrian.olap.ValidatorImpl}, the
     * dependencies between Validator and Query are explicit.
     */
    private static class QueryValidator extends ValidatorImpl {
        private final boolean alwaysResolveFunDef;
        private Query query;
        private final SchemaReader schemaReader;

        /**
         * Creates a QueryValidator.
         *
         * @param functionTable Function table
         * @param alwaysResolveFunDef Whether to always resolve function
         *     definitions (see {@link #alwaysResolveFunDef()})
         * @param query Query
         */
        public QueryValidator(
            FunTable functionTable, boolean alwaysResolveFunDef, Query query,
            Map<QueryPart, QueryPart> resolvedIdentifiers)
        {
            super(functionTable, resolvedIdentifiers);
            this.alwaysResolveFunDef = alwaysResolveFunDef;
            this.query = query;
            this.schemaReader = new ScopedSchemaReader(this, true);
        }

        public SchemaReader getSchemaReader() {
            return schemaReader;
        }

        protected void defineParameter(Parameter param) {
            final String name = param.getName();
            query.parameters.add(param);
            query.parametersByName.put(name, param);
        }

        public Query getQuery() {
            return query;
        }

        public boolean alwaysResolveFunDef() {
            return alwaysResolveFunDef;
        }

        public ArrayStack<QueryPart> getScopeStack() {
            return stack;
        }
    }

    /**
     * Schema reader that depends on the current scope during the validation
     * of a query. Depending on the scope, different calculated sets may be
     * visible. The scope is represented by the expression stack inside the
     * validator.
     */
    private static class ScopedSchemaReader
        extends DelegatingSchemaReader
        implements NameResolver.Namespace
    {
        private final QueryValidator queryValidator;
        private final boolean accessControlled;

        /**
         * Creates a ScopedSchemaReader.
         *
         * @param queryValidator Validator that is being used to validate the
         *     query
         * @param accessControlled Access controlled
         */
        private ScopedSchemaReader(
            QueryValidator queryValidator,
            boolean accessControlled)
        {
            super(queryValidator.getQuery().getSchemaReader(accessControlled));
            this.queryValidator = queryValidator;
            this.accessControlled = accessControlled;
        }

        public SchemaReader withoutAccessControl() {
            if (!accessControlled) {
                return this;
            }
            return new ScopedSchemaReader(queryValidator, false);
        }

        public List<NameResolver.Namespace> getNamespaces() {
            final List<NameResolver.Namespace> list =
                new ArrayList<NameResolver.Namespace>();
            list.add(this);
            list.addAll(super.getNamespaces());
            return list;
        }

        @Override
        public OlapElement lookupCompoundInternal(
            OlapElement parent,
            final List<Id.Segment> names,
            boolean failIfNotFound,
            int category,
            MatchType matchType)
        {
            switch (category) {
            case Category.Set:
            case Category.Unknown:
                final ScopedNamedSet namedSet =
                    queryValidator.getQuery().lookupScopedNamedSet(
                        names, queryValidator.getScopeStack());
                if (namedSet != null) {
                    return namedSet;
                }
            }
            return super.lookupCompoundInternal(
                parent, names, failIfNotFound, category, matchType);
        }

        public OlapElement lookupChild(
            OlapElement parent,
            IdentifierSegment segment,
            MatchType matchType)
        {
            // ignore matchType
            return lookupChild(parent, segment);
        }

        public OlapElement lookupChild(
            OlapElement parent,
            IdentifierSegment segment)
        {
            if (!(parent instanceof Cube)) {
                return null;
            }
            return queryValidator.getQuery().lookupScopedNamedSet(
                Collections.singletonList(Util.convert(segment)),
                queryValidator.getScopeStack());
        }
    }

    public static class ScopedNamedSet implements NamedSet {
        private final String name;
        private final QueryPart scope;
        private Exp expr;

        /**
         * Creates a ScopedNamedSet.
         *
         * @param name Name
         * @param scope Scope of named set (the function call that encloses
         *     the 'expr AS name', often GENERATE or FILTER)
         * @param expr Expression that defines the set
         */
        private ScopedNamedSet(String name, QueryPart scope, Exp expr) {
            this.name = name;
            this.scope = scope;
            this.expr = expr;
        }

        public String getName() {
            return name;
        }

        public String getNameUniqueWithinQuery() {
            return System.identityHashCode(this) + "";
        }

        public boolean isDynamic() {
            return true;
        }

        public Exp getExp() {
            return expr;
        }

        public void setExp(Exp expr) {
            this.expr = expr;
        }

        public void setName(String newName) {
            throw new UnsupportedOperationException();
        }

        public Type getType() {
            return expr.getType();
        }

        public Map<String, Annotation> getAnnotationMap() {
            return Collections.emptyMap();
        }

        public NamedSet validate(Validator validator) {
            Exp newExpr = expr.accept(validator);
            final Type type = newExpr.getType();
            if (type instanceof MemberType
                || type instanceof TupleType)
            {
                newExpr =
                    new UnresolvedFunCall(
                        "{}", Syntax.Braces, new Exp[] {newExpr})
                    .accept(validator);
            }
            this.expr = newExpr;
            return this;
        }

        public String getUniqueName() {
            return name;
        }

        public String getDescription() {
            throw new UnsupportedOperationException();
        }

        public OlapElement lookupChild(
            SchemaReader schemaReader, Id.Segment s, MatchType matchType)
        {
            throw new UnsupportedOperationException();
        }

        public String getQualifiedName() {
            throw new UnsupportedOperationException();
        }

        public String getCaption() {
            throw new UnsupportedOperationException();
        }

        public boolean isVisible() {
            throw new UnsupportedOperationException();
        }

        public Hierarchy getHierarchy() {
            throw new UnsupportedOperationException();
        }

        public Dimension getDimension() {
            throw new UnsupportedOperationException();
        }

        public String getLocalized(LocalizedProperty prop, Locale locale) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Visitor that locates and registers parameters.
     */
    private class ParameterFinder extends MdxVisitorImpl {
        public Object visit(ParameterExpr parameterExpr) {
            Parameter parameter = parameterExpr.getParameter();
            if (!parameters.contains(parameter)) {
                parameters.add(parameter);
                parametersByName.put(parameter.getName(), parameter);
            }
            return null;
        }

        public Object visit(UnresolvedFunCall call) {
            if (call.getFunName().equals("Parameter")) {
                // Is there already a parameter with this name?
                String parameterName =
                    ParameterFunDef.getParameterName(call.getArgs());
                if (parametersByName.get(parameterName) != null) {
                    throw MondrianResource.instance()
                        .ParameterDefinedMoreThanOnce.ex(parameterName);
                }

                Type type =
                    ParameterFunDef.getParameterType(call.getArgs());

                // Create a temporary parameter. We don't know its
                // type yet. The default of NULL is temporary.
                Parameter parameter = new ParameterImpl(
                    parameterName, Literal.nullValue, null, type);
                parameters.add(parameter);
                parametersByName.put(parameterName, parameter);
            }
            return null;
        }
    }

    /**
     * Visitor that locates and registers all aliased expressions
     * ('expr AS alias') as named sets. The resulting named sets have scope,
     * therefore they can only be seen and used within that scope.
     */
    private class AliasedExpressionFinder extends MdxVisitorImpl {
        @Override
        public Object visit(QueryAxis queryAxis) {
            registerAlias(queryAxis, queryAxis.getSet());
            return super.visit(queryAxis);
        }

        public Object visit(UnresolvedFunCall call) {
            registerAliasArgs(call);
            return super.visit(call);
        }

        public Object visit(ResolvedFunCall call) {
            registerAliasArgs(call);
            return super.visit(call);
        }

        /**
         * Registers all arguments of a function that are named sets.
         *
         * @param call Function call
         */
        private void registerAliasArgs(FunCall call) {
            for (Exp exp : call.getArgs()) {
                registerAlias((QueryPart) call, exp);
            }
        }

        /**
         * Registers a named set if an expression is of the form "expr AS
         * alias".
         *
         * @param parent Parent node
         * @param exp Expression that may be an "AS"
         */
        private void registerAlias(QueryPart parent, Exp exp) {
            if (exp instanceof FunCall) {
                FunCall call2 = (FunCall) exp;
                if (call2.getSyntax() == Syntax.Infix
                    && call2.getFunName().equals("AS"))
                {
                    // Scope is the function enclosing the 'AS' expression.
                    // For example, in
                    //    Filter(Time.Children AS s, x > y)
                    // the scope of the set 's' is the Filter function.
                    assert call2.getArgCount() == 2;
                    if (call2.getArg(1) instanceof Id) {
                        final Id id = (Id) call2.getArg(1);
                        createScopedNamedSet(
                            ((Id.NameSegment) id.getSegments().get(0))
                                .getName(),
                            parent,
                            call2.getArg(0));
                    } else if (call2.getArg(1) instanceof NamedSetExpr) {
                        NamedSetExpr set = (NamedSetExpr) call2.getArg(1);
                        createScopedNamedSet(
                            set.getNamedSet().getName(),
                            parent,
                            call2.getArg(0));
                    }
                }
            }
        }
    }

    public void replaceSubcubeMembers() {
        for(QueryAxis queryAxis: this.axes) {
            Exp exp = queryAxis.getSet();
            queryAxis.setSet(replaceSubcubeMember(exp));
        }
        if(this.slicerAxis != null) {
            Exp exp = this.slicerAxis.getSet();
            this.slicerAxis.setSet(replaceSubcubeMember(exp));
        }
        for(Formula formula: this.formulas) {
            Exp exp = formula.getExpression();
            if(exp != null){
                formula.setExpression(replaceSubcubeMember(exp));
            }
            exp = formula.getExpression();
            if(exp != null){
                formula.setExpression(replaceSubcubeMember(exp));
            }
        }
    }

    private List<Member> getSubcubeMembers(List<Member> members, boolean addNullMember) {
        ArrayList<Member> newMembers = new ArrayList<Member>();
        for(Member sourceMember: members) {
            Member subcubeMember = this.getSubcubeMember(sourceMember, addNullMember);
            if(subcubeMember != null) {
                newMembers.add(subcubeMember);
            }
        }
        return newMembers;
    }

    private List<Member> getRolapMembers(List<Member> members) {
        ArrayList<Member> newMembers = new ArrayList<Member>();
        for(Member sourceMember: members) {
            newMembers.add(this.getRolapMember(sourceMember));
        }
        return newMembers;
    }

    private Member getRolapMember(Member member) {
        if(member == null || !(member instanceof mondrian.rolap.RolapHierarchy.LimitedRollupMember)) {
            return member;
        }
        else {
            return ((RolapHierarchy.LimitedRollupMember)member).getSourceMember();
        }
    }

    private Member getSubcubeMember(Member member, boolean addNullMember) {
        Hierarchy hierarchy = ((RolapMember)member).getHierarchy();
        if(this.subcubeHierarchies.containsKey(hierarchy)) {
            HashMap<Member, Member> subcubeMembers = this.subcubeHierarchies.get(hierarchy);
            if(subcubeMembers.containsKey(member)) {
                return subcubeMembers.get(member);
            }
            else if(addNullMember) {
                return hierarchy.getNullMember();
            }
            return null;
        }
        return member;
    }

    private Exp replaceSubcubeMember(Exp exp) {
        if(exp instanceof MemberExpr) {
            MemberExpr memberExpr = (MemberExpr)exp;
            Member subcubeMember = this.getSubcubeMember(memberExpr.getMember(), true);
            return new MemberExpr(subcubeMember);
        }
        if(exp instanceof ResolvedFunCall) {
            ResolvedFunCall resolvedFunCall = (ResolvedFunCall) exp;
            for (int i = 0; i < resolvedFunCall.getArgs().length; i++) {
                resolvedFunCall.getArgs()[i] = replaceSubcubeMember(resolvedFunCall.getArgs()[i]);
            }
        }
        return exp;
    }

    public HashMap<String, String> columnAliases = new HashMap<>();

    public StarPredicate getSubcubePredicates(RolapCube baseCube) {
        List<StarPredicate> listOfSubcubeSets = new ArrayList<StarPredicate>();

        Subcube subcube = this.getSubcube();
        while (subcube != null) {
            QueryAxis[] axes = subcube.getAxes();

            for (QueryAxis axis : axes) {
                Exp axisExp = axis.getSet();
                // Resolve the expression tree before processing to ensure all function calls
                // are properly resolved (e.g., UnresolvedFunCall becomes ResolvedFunCall)
                // This is important for proper validation in isSupportedNonEmptyFilterCondition
                final Validator validator = createValidator();
                axisExp = axisExp.accept(validator);
                addSetExpressionToSubcubePredicates(
                    baseCube, listOfSubcubeSets, axisExp, false);
            }

            subcube = subcube.getSubcube();
        }

        StarPredicate andPredicate = null;
        if (listOfSubcubeSets.size() > 0) {
            andPredicate = new AndPredicate(listOfSubcubeSets);
        }

        List<StarPredicate> commonList = new ArrayList<StarPredicate>();
        if (andPredicate != null) {
            commonList.add(andPredicate);
        }

        if (commonList.size() > 0) {
            return new AndPredicate(commonList);
        }
        return null;
    }

    private void addSetExpressionToSubcubePredicates(
        RolapCube baseCube,
        List<StarPredicate> listOfSubcubeSets,
        Exp exp,
        boolean negated)
    {
        if (!(exp instanceof FunCall)) {
            throw new UnsupportedOperationException(
                "Unsupported expression type in subcube axis: "
                + exp.getClass().getName() + " -> " + exp);
        }

        FunCall funCall = (FunCall) exp;
        String funName = funCall.getFunName();

        if (funName.equals("()")) {
            for (Exp argExp : funCall.getArgs()) {
                addSetExpressionToSubcubePredicates(
                    baseCube, listOfSubcubeSets, argExp, negated);
            }
        } else if (funName.equals("{}")) {
            addSetToSubcubePredicates(baseCube, listOfSubcubeSets, funCall, negated);
        } else if (funName.equals("-")) {
            addSetExpressionToSubcubePredicates(
                baseCube, listOfSubcubeSets, funCall.getArg(0), !negated);
        } else if (funName.equalsIgnoreCase("Head")) {
            if (funCall.getArgCount() < 1 || funCall.getArgCount() > 2) {
                throw new UnsupportedOperationException(
                    "Head in subcube must have 1 or 2 args: " + funCall);
            }

            int headCount = Integer.MAX_VALUE;
            if (funCall.getArgCount() == 2) {
                headCount = extractNonNegativeHeadCount(funCall.getArg(1));
                if (headCount == 0) {
                    if (!negated) {
                        listOfSubcubeSets.add(LiteralStarPredicate.FALSE);
                    }
                    // NOT Head(set, 0) means unrestricted set; add nothing.
                    return;
                }
            }

            Exp headSetExp = funCall.getArg(0);
            if (headCount != Integer.MAX_VALUE
                && headSetExp instanceof FunCall
                && ((FunCall) headSetExp).getFunName().equals("{}"))
            {
                headSetExp = truncateSetFunCall((FunCall) headSetExp, headCount);
            }
            addSetExpressionToSubcubePredicates(
                baseCube, listOfSubcubeSets, headSetExp, negated);
        } else if (funName.equalsIgnoreCase("CrossJoin")) {
            if (funCall.getArgCount() != 2) {
                throw new UnsupportedOperationException(
                    "CrossJoin in subcube must have 2 args: " + funCall);
            }
            addSetExpressionToSubcubePredicates(
                baseCube, listOfSubcubeSets, funCall.getArg(0), negated);
            addSetExpressionToSubcubePredicates(
                baseCube, listOfSubcubeSets, funCall.getArg(1), negated);
        } else if (funName.equalsIgnoreCase("Union")) {
            if (funCall.getArgCount() != 2 && funCall.getArgCount() != 3) {
                throw new UnsupportedOperationException(
                    "Union in subcube must have 2 or 3 args: " + funCall);
            }

            List<StarPredicate> leftPredicates = new ArrayList<StarPredicate>();
            List<StarPredicate> rightPredicates = new ArrayList<StarPredicate>();

            addSetExpressionToSubcubePredicates(
                baseCube, leftPredicates, funCall.getArg(0), negated);
            addSetExpressionToSubcubePredicates(
                baseCube, rightPredicates, funCall.getArg(1), negated);

            if (!leftPredicates.isEmpty() && !rightPredicates.isEmpty()) {
                StarPredicate leftPredicate =
                    leftPredicates.size() == 1
                        ? leftPredicates.get(0)
                        : new AndPredicate(leftPredicates);
                StarPredicate rightPredicate =
                    rightPredicates.size() == 1
                        ? rightPredicates.get(0)
                        : new AndPredicate(rightPredicates);

                if (negated) {
                    listOfSubcubeSets.add(leftPredicate);
                    listOfSubcubeSets.add(rightPredicate);
                } else {
                    listOfSubcubeSets.add(
                        new OrPredicate(Arrays.asList(leftPredicate, rightPredicate)));
                }
            }
        } else if (funName.equalsIgnoreCase("Filter")) {
            addFilterToSubcubePredicates(
                baseCube, listOfSubcubeSets, funCall, negated);
        } else if (funName.equalsIgnoreCase("AllMembers")
            || funName.equalsIgnoreCase("Members")
            || funName.equalsIgnoreCase("Children")
            || funName.equalsIgnoreCase("Descendants"))
        {
            // These functions mean "all/descendants of members in this
            // hierarchy" — no dimension restriction to add as a predicate.
            // Simply skip; the dimension is unrestricted.
        } else {
            throw new UnsupportedOperationException(
                "Unsupported function in subcube axis: " + funName);
        }
    }

    private int extractNonNegativeHeadCount(Exp countExp) {
        if (!(countExp instanceof Literal)
            || ((Literal) countExp).getCategory() != Category.Numeric)
        {
            throw new UnsupportedOperationException(
                "Head count in subcube must be a numeric literal: " + countExp);
        }
        int count = ((Literal) countExp).getIntValue();
        if (count < 0) {
            throw new UnsupportedOperationException(
                "Head count in subcube cannot be negative: " + count);
        }
        return count;
    }

    private Exp truncateSetFunCall(FunCall setFunCall, int count) {
        Exp[] setArgs = setFunCall.getArgs();
        if (count >= setArgs.length) {
            return setFunCall;
        }
        return new UnresolvedFunCall(
            "{}",
            Syntax.Braces,
            Arrays.copyOf(setArgs, count));
    }

    private void addFilterToSubcubePredicates(
        RolapCube baseCube,
        List<StarPredicate> listOfSubcubeSets,
        FunCall filterFunCall,
        boolean negated)
    {
        if (filterFunCall.getArgCount() != 2) {
            throw new UnsupportedOperationException(
                "Filter in subcube must have 2 args: " + filterFunCall);
        }

        final Exp setExp = filterFunCall.getArg(0);
        final Exp conditionExp = filterFunCall.getArg(1);

        // Keep supported conditions explicit to avoid silently accepting
        // non-pushdownable filter semantics.
        if (!isSupportedNonEmptyFilterCondition(baseCube, conditionExp)) {
            throw new UnsupportedOperationException(
                "Unsupported Filter condition in subcube axis: " + conditionExp);
        }

        addSetExpressionToSubcubePredicates(
            baseCube, listOfSubcubeSets, setExp, negated);

        StarPredicate conditionPredicate =
            createConditionSubcubePredicate(baseCube, conditionExp);
        if (conditionPredicate != null) {
            listOfSubcubeSets.add(
                negated
                    ? new NotPredicate(conditionPredicate)
                    : conditionPredicate);
        }
    }

    /**
     * Recursively validates whether a Filter condition can be converted into
     * a subcube {@link StarPredicate}.
     */
    private boolean isSupportedNonEmptyFilterCondition(
        RolapCube baseCube,
        Exp conditionExp)
    {
        if (createConditionSubcubePredicate(baseCube, conditionExp) != null) {
            return true;
        }
        if (conditionExp instanceof Literal) {
            return ((Literal) conditionExp).getCategory() == Category.Logical;
        }
        if (!(conditionExp instanceof FunCall)) {
            return false;
        }
        FunCall fc = (FunCall) conditionExp;
        String name = fc.getFunName();

        if (name.equalsIgnoreCase("IsEmpty")) {
            return fc.getArgCount() == 1;
        }
        if (name.equalsIgnoreCase("NOT") && fc.getArgCount() == 1) {
            return isSupportedNonEmptyFilterCondition(baseCube, fc.getArg(0));
        }
        if ((name.equalsIgnoreCase("OR") || name.equalsIgnoreCase("AND"))
            && fc.getArgCount() == 2)
        {
            return isSupportedNonEmptyFilterCondition(baseCube, fc.getArg(0))
                && isSupportedNonEmptyFilterCondition(baseCube, fc.getArg(1));
        }
        if ((name.equals("=") || name.equals("<>"))
            && fc.getArgCount() == 2)
        {
            return isSupportedFilterConditionOperand(baseCube, fc.getArg(0))
                && isSupportedFilterConditionOperand(baseCube, fc.getArg(1));
        }
        // parenthesized expression — single-arg tuple wrapper "(expr)"
        if (name.equals("()") && fc.getArgCount() == 1) {
            return isSupportedNonEmptyFilterCondition(baseCube, fc.getArg(0));
        }
        return false;
    }

    private boolean isSupportedFilterConditionOperand(
        RolapCube baseCube,
        Exp exp)
    {
        if (createCurrentMemberPropertyValuePredicate(baseCube, exp, "") != null) {
            return true;
        }
        if (exp instanceof Literal) {
            Literal literal = (Literal) exp;
            return literal.getCategory() == Category.String
                || literal.getCategory() == Category.Logical
                || literal.getCategory() == Category.Numeric;
        }

        if (isCurrentMemberStringPropertyExpression(exp)) {
            return true;
        }

        if (exp instanceof FunCall) {
            return isSupportedNonEmptyFilterCondition(baseCube, exp);
        }

        return false;
    }

    private StarPredicate createConditionSubcubePredicate(
        RolapCube baseCube,
        Exp conditionExp)
    {
        if (conditionExp instanceof Literal) {
            Literal literal = (Literal) conditionExp;
            if (literal.getCategory() == Category.Logical) {
                return Boolean.TRUE.equals(literal.getValue())
                    ? LiteralStarPredicate.TRUE
                    : LiteralStarPredicate.FALSE;
            }
            return null;
        }

        if (!(conditionExp instanceof FunCall)) {
            return null;
        }

        FunCall fc = (FunCall) conditionExp;
        String name = fc.getFunName();

        if (name.equals("()") && fc.getArgCount() == 1) {
            return createConditionSubcubePredicate(baseCube, fc.getArg(0));
        }

        if (name.equalsIgnoreCase("NOT") && fc.getArgCount() == 1) {
            StarPredicate innerPredicate =
                createConditionSubcubePredicate(baseCube, fc.getArg(0));
            return innerPredicate == null
                ? null
                : new NotPredicate(innerPredicate);
        }

        if ((name.equalsIgnoreCase("AND") || name.equalsIgnoreCase("OR"))
            && fc.getArgCount() == 2)
        {
            StarPredicate leftPredicate =
                createConditionSubcubePredicate(baseCube, fc.getArg(0));
            StarPredicate rightPredicate =
                createConditionSubcubePredicate(baseCube, fc.getArg(1));
            if (leftPredicate == null || rightPredicate == null) {
                return null;
            }
            return name.equalsIgnoreCase("AND")
                ? new AndPredicate(Arrays.asList(leftPredicate, rightPredicate))
                : new OrPredicate(Arrays.asList(leftPredicate, rightPredicate));
        }

        if (name.equalsIgnoreCase("IsEmpty") && fc.getArgCount() == 1) {
            return createCurrentMemberPropertyValuePredicate(
                baseCube,
                fc.getArg(0),
                RolapUtil.sqlNullValue);
        }

        if ((name.equals("=") || name.equals("<>")) && fc.getArgCount() == 2) {
            StarPredicate predicate =
                createComparisonSubcubePredicate(
                    baseCube,
                    fc.getArg(0),
                    fc.getArg(1));

            if (predicate == null) {
                predicate = createBooleanComparisonSubcubePredicate(
                    baseCube,
                    fc.getArg(0),
                    fc.getArg(1),
                    name.equals("<>"));
            } else if (name.equals("<>")) {
                predicate = new NotPredicate(predicate);
            }

            return predicate;
        }

        return null;
    }

    private StarPredicate createComparisonSubcubePredicate(
        RolapCube baseCube,
        Exp leftExp,
        Exp rightExp)
    {
        String literalValue = getStringLiteralValue(rightExp);
        if (literalValue != null) {
            StarPredicate predicate = createCurrentMemberPropertyValuePredicate(
                baseCube,
                leftExp,
                literalValue);
            if (predicate != null) {
                return predicate;
            }
        }

        literalValue = getStringLiteralValue(leftExp);
        if (literalValue != null) {
            return createCurrentMemberPropertyValuePredicate(
                baseCube,
                rightExp,
                literalValue);
        }

        return null;
    }

    private StarPredicate createBooleanComparisonSubcubePredicate(
        RolapCube baseCube,
        Exp leftExp,
        Exp rightExp,
        boolean notEquals)
    {
        Boolean leftBoolean = evalConstantBoolean(leftExp);
        Boolean rightBoolean = evalConstantBoolean(rightExp);

        if (leftBoolean != null && rightBoolean != null) {
            boolean result = leftBoolean.equals(rightBoolean);
            if (notEquals) {
                result = !result;
            }
            return result
                ? LiteralStarPredicate.TRUE
                : LiteralStarPredicate.FALSE;
        }

        StarPredicate leftPredicate =
            createConditionSubcubePredicate(baseCube, leftExp);
        if (leftPredicate != null && rightBoolean != null) {
            boolean negatePredicate = rightBoolean ^ notEquals;
            return negatePredicate
                ? leftPredicate
                : new NotPredicate(leftPredicate);
        }

        StarPredicate rightPredicate =
            createConditionSubcubePredicate(baseCube, rightExp);
        if (rightPredicate != null && leftBoolean != null) {
            boolean negatePredicate = leftBoolean ^ notEquals;
            return negatePredicate
                ? rightPredicate
                : new NotPredicate(rightPredicate);
        }

        return null;
    }

    private Boolean evalConstantBoolean(Exp exp) {
        if (exp instanceof Literal) {
            Literal literal = (Literal) exp;
            if (literal.getCategory() == Category.Logical) {
                return Boolean.TRUE.equals(literal.getValue());
            }
            if (literal.getCategory() == Category.Numeric
                && literal.getValue() instanceof Number)
            {
                return ((Number) literal.getValue()).doubleValue() != 0D;
            }
            return null;
        }

        if (!(exp instanceof FunCall)) {
            return null;
        }

        FunCall funCall = (FunCall) exp;
        if (funCall.getFunName().equals("()") && funCall.getArgCount() == 1) {
            return evalConstantBoolean(funCall.getArg(0));
        }
        if (funCall.getFunName().equals("=") && funCall.getArgCount() == 2) {
            Object leftValue = getLiteralValue(funCall.getArg(0));
            Object rightValue = getLiteralValue(funCall.getArg(1));
            if (leftValue != null && rightValue != null) {
                return leftValue.equals(rightValue);
            }
        }
        if (funCall.getFunName().equals("<>") && funCall.getArgCount() == 2) {
            Object leftValue = getLiteralValue(funCall.getArg(0));
            Object rightValue = getLiteralValue(funCall.getArg(1));
            if (leftValue != null && rightValue != null) {
                return !leftValue.equals(rightValue);
            }
        }
        return null;
    }

    private Object getLiteralValue(Exp exp) {
        return exp instanceof Literal
            ? ((Literal) exp).getValue()
            : null;
    }

    private String getStringLiteralValue(Exp exp) {
        if (!(exp instanceof Literal)) {
            return null;
        }
        Literal literal = (Literal) exp;
        if (literal.getCategory() != Category.String
            && literal.getCategory() != Category.Numeric)
        {
            return null;
        }
        Object value = literal.getValue();
        return value == null ? null : String.valueOf(value);
    }

    private boolean isCurrentMemberStringPropertyExpression(Exp exp) {
        if (!(exp instanceof FunCall)) {
            return false;
        }
        FunCall propertyFunCall = (FunCall) exp;
        if ((!propertyFunCall.getFunName().equalsIgnoreCase("Name")
            && !propertyFunCall.getFunName().equalsIgnoreCase("Caption"))
            || propertyFunCall.getArgCount() != 1)
        {
            return false;
        }
        return getCurrentMemberComparisonLevel(propertyFunCall.getArg(0)) != null;
    }

    private StarPredicate createCurrentMemberPropertyValuePredicate(
        RolapCube baseCube,
        Exp exp,
        Object value)
    {
        RolapStar.Column column = getCurrentMemberPropertyColumn(baseCube, exp);
        return column == null ? null : new ValueColumnPredicate(column, value);
    }

    private RolapStar.Column getCurrentMemberPropertyColumn(
        RolapCube baseCube,
        Exp exp)
    {
        if (!(exp instanceof FunCall)) {
            return null;
        }

        FunCall propertyFunCall = (FunCall) exp;
        String propertyName = propertyFunCall.getFunName();
        if (!propertyName.equalsIgnoreCase("Name")
            && !propertyName.equalsIgnoreCase("Caption"))
        {
            return null;
        }
        if (propertyFunCall.getArgCount() != 1) {
            return null;
        }

        RolapCubeLevel level =
            getCurrentMemberComparisonLevel(propertyFunCall.getArg(0));
        if (level == null) {
            return null;
        }

        if (baseCube == null) {
            return level.getStarKeyColumn();
        }

        RolapStar.Column keyColumn = level.getBaseStarKeyColumn(baseCube);
        if (keyColumn == null) {
            return null;
        }

        MondrianDef.Expression expression =
            propertyName.equalsIgnoreCase("Caption")
                ? (level.getCaptionExp() != null
                    ? level.getCaptionExp()
                    : (level.getNameExp() != null
                        ? level.getNameExp()
                        : level.getKeyExp()))
                : (level.getNameExp() != null
                    ? level.getNameExp()
                    : level.getKeyExp());

        if (expression == null || expression.equals(level.getKeyExp())) {
            return keyColumn;
        }

        RolapStar.Column propertyColumn =
            keyColumn.getTable().lookupColumnByExpression(expression);
        return propertyColumn == null ? keyColumn : propertyColumn;
    }

    private RolapCubeLevel getCurrentMemberComparisonLevel(Exp exp) {
        if (!(exp instanceof FunCall)) {
            return null;
        }

        FunCall currentMemberFunCall = (FunCall) exp;
        if (!currentMemberFunCall.getFunName().equalsIgnoreCase("CurrentMember")
            || currentMemberFunCall.getArgCount() != 1)
        {
            return null;
        }

        Exp levelExp = currentMemberFunCall.getArg(0);
        if (levelExp instanceof LevelExpr) {
            Level level = ((LevelExpr) levelExp).getLevel();
            return level instanceof RolapCubeLevel
                ? (RolapCubeLevel) level
                : null;
        }

        Hierarchy hierarchy = null;
        if (levelExp instanceof HierarchyExpr) {
            hierarchy = ((HierarchyExpr) levelExp).getHierarchy();
        } else if (levelExp instanceof DimensionExpr) {
            hierarchy = ((DimensionExpr) levelExp).getDimension().getHierarchy();
        }

        if (!(hierarchy instanceof RolapCubeHierarchy)) {
            return null;
        }

        Level[] levels = hierarchy.getLevels();
        for (int i = levels.length - 1; i >= 0; i--) {
            if (!levels[i].isAll() && levels[i] instanceof RolapCubeLevel) {
                return (RolapCubeLevel) levels[i];
            }
        }

        return null;
    }


    private void addSetToSubcubePredicates(
        RolapCube baseCube,
        List<StarPredicate> listOfSubcubeSets,
        FunCall setFunCall,
        boolean negated)
    {
        List<StarPredicate> listOfSubcubeMembers = new ArrayList<StarPredicate>();

        for (Exp setArgExp : setFunCall.getArgs()) {
            Member memberFromSubcube = null;

            // Handle MemberExpr directly
            if (setArgExp instanceof MemberExpr) {
                MemberExpr memberExpr = (MemberExpr) setArgExp;
                memberFromSubcube = memberExpr.getMember();
            }
            // Handle Id expressions (resolve to Member)
            else if (setArgExp instanceof Id) {
                Id id = (Id) setArgExp;
                memberFromSubcube = getSchemaReader(false)
                    .withLocus().getMemberByUniqueName(
                        Util.parseIdentifier(id.toString()),
                        true,
                        mondrian.olap.MatchType.EXACT);
            }
            else {
                throw new UnsupportedOperationException(
                    "Unsupported expression type in subcube set: "
                    + setArgExp.getClass().getName() + " -> " + setArgExp);
            }

            if (memberFromSubcube != null) {
                List<StarPredicate> memberAndList = new ArrayList<StarPredicate>();

                Member memberWalk = memberFromSubcube;
                Level levelLast = null;
                while (memberWalk != null && !memberWalk.isAll()) {
                    if (memberWalk.getLevel() != levelLast) {
                        RolapCubeMember rolapCubeMember = (RolapCubeMember) memberWalk;
                        RolapStar.Column column =
                            rolapCubeMember.getLevel().getBaseStarKeyColumn(baseCube);
                        memberAndList.add(
                            new MemberColumnPredicate(column, rolapCubeMember));
                    }
                    levelLast = memberWalk.getLevel();
                    memberWalk = memberWalk.getParentMember();
                }
                if (memberAndList.size() > 0) {
                    listOfSubcubeMembers.add(new AndPredicate(memberAndList));
                }
            }
        }

        if (listOfSubcubeMembers.size() > 0) {
            StarPredicate orPredicate = new OrPredicate(listOfSubcubeMembers);
            if (negated) {
                listOfSubcubeSets.add(new NotPredicate(orPredicate));
            } else {
                listOfSubcubeSets.add(orPredicate);
            }
        }
    }

}

// End Query.java
