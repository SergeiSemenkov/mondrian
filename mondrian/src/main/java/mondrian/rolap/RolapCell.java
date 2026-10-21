/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2005-2005 Julian Hyde
// Copyright (C) 2005-2017 Hitachi Vantara
// Copyright (C) 2021-2025 Sergei Semenkov
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.mdx.*;
import mondrian.olap.*;
import mondrian.olap.fun.AggregateFunDef;
import mondrian.olap.fun.SetFunDef;
import mondrian.resource.MondrianResource;
import mondrian.rolap.agg.*;
import mondrian.server.*;
import mondrian.server.monitor.SqlStatementEvent;
import mondrian.spi.Dialect;

import org.apache.logging.log4j.Logger;

import org.olap4j.AllocationPolicy;
import org.olap4j.Scenario;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * <code>RolapCell</code> implements {@link mondrian.olap.Cell} within a
 * {@link RolapResult}.
 */
public class RolapCell implements Cell {
    /**
     * @see mondrian.util.Bug#olap4jUpgrade Use
     * {@link mondrian.xmla.XmlaConstants}.ActionType.DRILLTHROUGH when present
     */
    private static final int MDACTION_TYPE_DRILLTHROUGH = 0x100;

    private final RolapResult result;
    protected final int[] pos;
    protected RolapResult.CellInfo ci;

    /**
     * Creates a RolapCell.
     *
     * @param result Result cell belongs to
     * @param pos Coordinates of cell
     * @param ci Cell information, containing value et cetera
     */
    RolapCell(RolapResult result, int[] pos, RolapResult.CellInfo ci) {
        this.result = result;
        this.pos = pos;
        this.ci = ci;
    }

    public List<Integer> getCoordinateList() {
        return new AbstractList<Integer>() {
            public Integer get(int index) {
                return pos[index];
            }

            public int size() {
                return pos.length;
            }
        };
    }

    public Object getValue() {
        if (ci.value == Util.nullValue) {
            return null;
        }
        return ci.value;
    }

    public String getCachedFormatString() {
        return ci.formatString;
    }

    public String getFormattedValue() {
        return ci.getFormatValue();
    }

    public boolean isNull() {
        return (ci.value == Util.nullValue);
    }

    public boolean isError() {
        return (ci.value instanceof Throwable);
    }

    public String getDrillThroughSQL(
        boolean extendedContext)
    {
        return getDrillThroughSQL(
            new ArrayList<OlapElement>(), extendedContext, 0);
    }

    public String getDrillThroughSQL(
            List<OlapElement> fields,
            boolean extendedContext)
    {
        return getDrillThroughSQL(fields, extendedContext, 0);
    }

    public String getDrillThroughSQL(
        List<OlapElement> fields,
        boolean extendedContext,
        int maxRowCount)
    {
        if (!MondrianProperties.instance()
            .EnableDrillThrough.get())
        {
            throw MondrianResource.instance()
                .DrillthroughDisabled.ex(
                    MondrianProperties.instance()
                        .EnableDrillThrough.getPath());
        }
        final Member[] currentMembers = getMembersForDrillThrough();
        // Create a StarPredicate to represent the compound slicer
        // (if necessary)
        // NOTE: the method buildDrillthroughSlicerPredicate modifies
        // the array of members, so it MUST be called before calling
        // RolapAggregationManager.makeDrillThroughRequest
        StarPredicate starPredicateSlicer =
            buildDrillthroughSlicerPredicate(
                currentMembers,
                result.getSlicerAxis());
        starPredicateSlicer = addSubcubePredicates(starPredicateSlicer);
        DrillThroughCellRequest cellRequest =
            RolapAggregationManager.makeDrillThroughRequest(
                currentMembers, extendedContext, result.getCube(),
                fields);
        if (cellRequest == null) {
            return null;
        }
        cellRequest.setMaxRowCount(maxRowCount);
        final RolapConnection connection =
            result.getExecution().getMondrianStatement()
                .getMondrianConnection();
        final RolapAggregationManager aggMgr =
            connection.getServer().getAggregationManager();
        return aggMgr.getDrillThroughSql(
            cellRequest,
            starPredicateSlicer,
            fields,
            false);
    }

    private StarPredicate addSubcubePredicates(StarPredicate sourceStarPredicate) {
        Query query = result.getExecution().getMondrianStatement().getQuery();
        StarPredicate subcubePredicate = query.getSubcubePredicates(this.getDrillThroughBaseCube());

        List<StarPredicate> commonList = new ArrayList<StarPredicate>();
        if(subcubePredicate != null) {
            commonList.add(subcubePredicate);
        }

        if(sourceStarPredicate != null) {
            commonList.add(sourceStarPredicate);
        }

        if(commonList.size() > 0) {
            return new AndPredicate(commonList);
        }
        return null;
    }

    public int getDrillThroughCount() {
        final Member[] currentMembers = getMembersForDrillThrough();
        // Create a StarPredicate to represent the compound
        // slicer (if necessary)
        // NOTE: the method buildDrillthroughSlicerPredicate modifies
        // the array of members, so it MUST be called before calling
        // RolapAggregationManager.makeDrillThroughRequest
        StarPredicate starPredicateSlicer =
            buildDrillthroughSlicerPredicate(
                currentMembers,
                result.getSlicerAxis());

        starPredicateSlicer = addSubcubePredicates(starPredicateSlicer);

        DrillThroughCellRequest cellRequest =
            RolapAggregationManager.makeDrillThroughRequest(
                currentMembers, false, result.getCube(),
                Collections.<OlapElement>emptyList());
        if (cellRequest == null) {
            return -1;
        }
        final RolapConnection connection =
            result.getExecution().getMondrianStatement()
                .getMondrianConnection();
        final RolapAggregationManager aggMgr =
            connection.getServer().getAggregationManager();
        final String sql =
            aggMgr.getDrillThroughSql(
                cellRequest,
                starPredicateSlicer,
                new ArrayList<OlapElement>(),
                true);

        final SqlStatement stmt =
            RolapUtil.executeQuery(
                connection.getDataSource(),
                sql,
                new Locus(
                    new Execution(connection.getInternalStatement(), 0),
                    "RolapCell.getDrillThroughCount",
                    "Error while counting drill-through"));
        try {
            ResultSet rs = stmt.getResultSet();
            assert rs.getMetaData().getColumnCount() == 1;
            rs.next();
            ++stmt.rowCount;
            return rs.getInt(1);
        } catch (SQLException e) {
            throw stmt.handle(e);
        } finally {
            stmt.close();
        }
    }

    /**
     * This method handles the case of a compound slicer with more than one
     * {@link Position}. In this case, a simple array of {@link Member}s is not
     * sufficient to express the set of drill through rows. If the slicer axis
     * does have multiple positions, this method will do two things:
     * <ol>
     *  <li>Modify the passed-in array if any Member is overly restrictive.
     *  This can happen if the slicer specifies multiple members in the same
     *  hierarchy. In this scenario, the array of Members will contain an
     *  element for only the last selected member in the hierarchy. This method
     *  will replace that Member with the "All" Member from that hierarchy.
     *  </li>
     *  <li>Create a {@link StarPredicate} representing the Positions indicated
     *  by the slicer axis.
     *  </li>
     * </ol>
     *
     * @param membersForDrillthrough the array of Members returned by
     * {@link #getMembersForDrillThrough()}
     * @param slicerAxis the slicer {@link Axis}
     * @return an instance of <code>StarPredicate</code> representing all
     * of the the positions from the slicer if it has more than one,
     * or <code>null</code> otherwise.
     */
    private StarPredicate buildDrillthroughSlicerPredicate(
        Member[] membersForDrillthrough,
        Axis slicerAxis)
    {
        final List<StarPredicate> predicates = new ArrayList<StarPredicate>();
        // A member Aggregate({m1, m2, ...}) stands for the rows of any of its
        // members: un-constrain its hierarchy and OR the members instead, the
        // same as a set in the slicer
        for (int i = 0; i < membersForDrillthrough.length; i++) {
            final List<Member> members =
                aggregatedMembers(membersForDrillthrough[i]);
            if (members == null) {
                continue;
            }
            membersForDrillthrough[i] =
                membersForDrillthrough[i].getHierarchy().getAllMember();
            final List<StarPredicate> anyOf = new ArrayList<StarPredicate>();
            for (Member member : members) {
                anyOf.add(new AndPredicate(memberPredicates(member)));
            }
            predicates.add(new OrPredicate(anyOf));
        }
        final StarPredicate compoundSlicer =
            buildCompoundSlicerPredicate(membersForDrillthrough, slicerAxis);
        if (compoundSlicer != null) {
            predicates.add(compoundSlicer);
        }
        if (predicates.isEmpty()) {
            return null;
        }
        return predicates.size() == 1
            ? predicates.get(0)
            : new AndPredicate(predicates);
    }

    /**
     * Returns the members a calculated member stands for when it is
     * {@code Aggregate({m1, m2, ...})} over stored members of its own
     * hierarchy, the shape clients build for a multi-member filter; null for
     * any other member.
     */
    static List<Member> aggregatedMembers(Member member) {
        if (!member.isCalculated() || member.isMeasure()) {
            return null;
        }
        final Exp expr =
            RolapUtil.strip((RolapMember) member).getExpression();
        if (!(expr instanceof ResolvedFunCall)) {
            return null;
        }
        final ResolvedFunCall call = (ResolvedFunCall) expr;
        if (!(call.getFunDef() instanceof AggregateFunDef)
            || call.getArgCount() != 1
            || !(call.getArg(0) instanceof ResolvedFunCall))
        {
            return null;
        }
        final ResolvedFunCall set = (ResolvedFunCall) call.getArg(0);
        if (!(set.getFunDef() instanceof SetFunDef)
            || set.getArgCount() < 2)
        {
            return null;
        }
        final List<Member> members = new ArrayList<Member>();
        for (Exp arg : set.getArgs()) {
            if (!(arg instanceof MemberExpr)) {
                return null;
            }
            final Member m = ((MemberExpr) arg).getMember();
            if (m.isCalculated() || m.isAll()
                || !(m instanceof RolapCubeMember)
                || !m.getHierarchy().equals(member.getHierarchy()))
            {
                return null;
            }
            members.add(m);
        }
        return members;
    }

    /**
     * Returns a predicate per level from the member up to the All member.
     */
    private List<StarPredicate> memberPredicates(Member member) {
        final List<StarPredicate> predicates = new ArrayList<StarPredicate>();
        Member memberWalk = member;
        Level levelLast = null;
        while (memberWalk != null && ! memberWalk.isAll()) {
            // Only create a predicate for this member if we
            // are at a new level. This is for parent-child levels,
            // however it still suffers from the following bug:
            //  http://jira.pentaho.com/browse/MONDRIAN-318
            if (memberWalk.getLevel() != levelLast) {
                RolapCubeMember rolapCubeMember =
                    (RolapCubeMember) memberWalk;
                RolapStar.Column column =
                    rolapCubeMember.getLevel()
                        .getBaseStarKeyColumn(
                            getDrillThroughBaseCube());
                // Add a predicate for the member at this level
                predicates.add(
                    new MemberColumnPredicate(
                        column,
                        rolapCubeMember));
            }
            levelLast = memberWalk.getLevel();
            // Walk up the hierarchy
            memberWalk = memberWalk.getParentMember();
        }
        return predicates;
    }

    private StarPredicate buildCompoundSlicerPredicate(
        Member[] membersForDrillthrough,
        Axis slicerAxis)
    {
        List<Position> listOfPositions = slicerAxis.getPositions();
        // If the slicer has zero or one position(s),
        // then there is no need to do
        // anything; the array of Members is correct as-is
        if (listOfPositions.size() <= 1) {
            return null;
        }
        // First, iterate through the positions' members, un-constraining the
        // "membersForDrillthrough" array if any position member is not
        // in the array
        for (Position position : listOfPositions) {
            for (Member member : position) {
                RolapHierarchy rolapHierarchy =
                    (RolapHierarchy) member.getHierarchy();
                // Check if the membersForDrillthrough constraint is identical
                // to that of the position member
                if (!membersForDrillthrough[rolapHierarchy.getOrdinalInCube()]
                    .equals(member))
                {
                    // There is a discrepancy, so un-constrain the
                    // membersForDrillthrough array
                    membersForDrillthrough[rolapHierarchy.getOrdinalInCube()] =
                        rolapHierarchy.getAllMember();
                }
            }
        }
        // This is a list containing an AndPredicate for each position in the
        // slicer axis
        List<StarPredicate> listOfStarPredicatesForSlicerPositions =
            new ArrayList<StarPredicate>();
        // Now we re-iterate the positions' members,
        // creating the slicer constraint
        for (Position position : listOfPositions) {
            // This is a list of the predicates required to select the
            // current position (excluding the members of the position
            // that are already constrained in the membersForDrillthrough array)
            List<StarPredicate> listOfStarPredicatesForCurrentPosition =
                new ArrayList<StarPredicate>();
            // Iterate the members of the current position
            for (Member member : position) {
                RolapHierarchy rolapHierarchy =
                    (RolapHierarchy) member.getHierarchy();
                // If the membersForDrillthrough is already constraining to
                // this member, then there is no need to create additional
                // predicate(s) for this member
                if (!membersForDrillthrough[rolapHierarchy.getOrdinalInCube()]
                   .equals(member))
                {
                    // An Aggregate member was replaced by its own predicate
                    // in buildDrillthroughSlicerPredicate
                    if (aggregatedMembers(member) == null) {
                        listOfStarPredicatesForCurrentPosition.addAll(
                            memberPredicates(member));
                    }
                }
            }
            // AND together all of the predicates that specify
            // the current position
            StarPredicate starPredicateForCurrentSlicerPosition =
                new AndPredicate(listOfStarPredicatesForCurrentPosition);
            // Add this position's predicate to the list
            listOfStarPredicatesForSlicerPositions
                .add(starPredicateForCurrentSlicerPosition);
        }
        // OR together the predicates for all of the slicer's
        // positions and return
        return new OrPredicate(listOfStarPredicatesForSlicerPositions);
    }

    /**
     * Returns appropriate non-virtual cube that will be used
     * for retrieving base star key column.
     */
    public RolapCube getDrillThroughBaseCube() {
        if (result.getCube().isVirtual()) {
            Member[] membersForDrillThrough = this.getMembersForDrillThrough();
            for (Member m : membersForDrillThrough) {
                if (m instanceof RolapVirtualCubeMeasure) {
                    return ((RolapVirtualCubeMeasure) m).getCube();
                }
            }
        }
        return result.getCube();
    }

    /**
     * Returns whether it is possible to drill through this cell.
     * Drill-through is possible if the measure is a stored measure
     * and not possible for calculated measures.
     *
     * @return true if can drill through
     */
    public boolean canDrillThrough() {
        if (!MondrianProperties.instance()
            .EnableDrillThrough.get())
        {
            return false;
        }
        // get current members
        final Member[] currentMembers = getMembersForDrillThrough();
        if (containsCalcMembers(currentMembers)) {
            return false;
        }
        Cube x = chooseDrillThroughCube(currentMembers, result.getCube());
        return x != null;
    }

    private boolean containsCalcMembers(Member[] currentMembers) {
        // Any calculated members which are not measures, we can't drill
        // through. Trivial calculated members should have been converted
        // already. We allow simple calculated measures such as
        // [Measures].[Unit Sales] / [Measures].[Store Sales] provided that both
        // are from the same cube.
        for (int i = 1; i < currentMembers.length; i++) {
            final Member currentMember = currentMembers[i];
            if (currentMember.isCalculated()
                && aggregatedMembers(currentMember) == null)
            {
                return true;
            }
        }
        return false;
    }

    public static RolapCube chooseDrillThroughCube(
        Member[] currentMembers,
        RolapCube defaultCube)
    {
        if (defaultCube != null && defaultCube.isVirtual()) {
            List<RolapCube> cubes = new ArrayList<RolapCube>();
            for (RolapMember member : defaultCube.getMeasuresMembers()) {
                if (member instanceof RolapVirtualCubeMeasure) {
                    RolapVirtualCubeMeasure measure =
                        (RolapVirtualCubeMeasure) member;
                    cubes.add(measure.getCube());
                }
            }
            defaultCube = cubes.get(0);
            assert !defaultCube.isVirtual();
        }
        final DrillThroughVisitor visitor =
            new DrillThroughVisitor();
        try {
            for (Member member : currentMembers) {
                visitor.handleMember(member);
            }
        } catch (RuntimeException e) {
            if (e == DrillThroughVisitor.bomb) {
                // No cubes left
                return null;
            } else {
                throw e;
            }
        }
        return visitor.cube == null
             ? defaultCube
             : visitor.cube;
    }

    public Member[] getMembersForDrillThrough() {
        final Member[] currentMembers = result.getCellMembers(pos);

        // replace member if we're dealing with a trivial formula
        List<Member> memberList = Arrays.asList(currentMembers);
        for (int i = 0; i < currentMembers.length; i++) {
            replaceTrivialCalcMember(i, memberList);
        }
        return currentMembers;
    }

    private void replaceTrivialCalcMember(int i, List<Member> members) {
        Member member = members.get(i);
        if (!member.isCalculated()) {
            return;
        }
        member = RolapUtil.strip((RolapMember) member);
        // if "cm" is a calc member defined by
        // "with member cm as m" then
        // "cm" is equivalent to "m"
        final Exp expr = member.getExpression();
        if (expr instanceof MemberExpr) {
            members.set(
                i,
                ((MemberExpr) expr).getMember());
            return;
        }
        // "Aggregate({m})" is equivalent to "m"
        if (expr instanceof ResolvedFunCall) {
            ResolvedFunCall call = (ResolvedFunCall) expr;
            if (call.getFunDef() instanceof AggregateFunDef) {
                final Exp[] args = call.getArgs();
                if (args[0] instanceof ResolvedFunCall) {
                    final ResolvedFunCall arg0 = (ResolvedFunCall) args[0];
                    if (arg0.getFunDef() instanceof SetFunDef) {
                        if (arg0.getArgCount() == 1
                            && arg0.getArg(0) instanceof MemberExpr)
                        {
                            final MemberExpr memberExpr =
                                (MemberExpr) arg0.getArg(0);
                            members.set(i, memberExpr.getMember());
                        }
                    }
                }
            }
        }
    }

    /**
     * Generates an executes a SQL statement to drill through this cell.
     *
     * <p>Throws if this cell is not drillable.
     *
     * <p>Enforces limits on the starting and last row.
     *
     * <p>If tabFields is not null, returns the specified columns. (This option
     * is deprecated.)
     *
     * @param maxRowCount Maximum number of rows to retrieve, <= 0 if unlimited
     * @param firstRowOrdinal Ordinal of row to skip to (1-based), or 0 to
     *   start from beginning
     * @param fields            List of field expressions to return as the
     *                          result set columns.
     * @param extendedContext   If true, add non-constraining columns to the
     *                          query for levels below each current member.
     *                          This additional context makes the drill-through
     *                          queries easier for humans to understand.
     * @param logger Logger. If not null and debug is enabled, log SQL here
     * @return executed SQL statement
     */
    public SqlStatement drillThroughInternal(
        int maxRowCount,
        int firstRowOrdinal,
        List<OlapElement> fields,
        boolean extendedContext,
        Logger logger)
    {
        if (!canDrillThrough()) {
            throw Util.newError("Cannot do DrillThrough operation on the cell");
        }

        // Generate SQL.
        String sql = getDrillThroughSQL(fields, extendedContext,  maxRowCount);
        if (logger != null && logger.isDebugEnabled()) {
            logger.debug("drill through sql: " + sql);
        }

        // Choose the appropriate scrollability. If we need to start from an
        // offset row, it is useful that the cursor is scrollable, but not
        // essential.
        final Statement statement =
            result.getExecution().getMondrianStatement();
        final Execution execution = new Execution(statement, 0);
        final Connection connection = statement.getMondrianConnection();
        int resultSetType = ResultSet.TYPE_SCROLL_INSENSITIVE;
        int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;
        final Schema schema = statement.getSchema();
        Dialect dialect = ((RolapSchema) schema).getDialect();
        if (!dialect.supportsResultSetConcurrency(
                resultSetType, resultSetConcurrency)
            || firstRowOrdinal <= 1)
        {
            // downgrade to non-scroll cursor, since we can
            // fake absolute() via forward fetch
            resultSetType = ResultSet.TYPE_FORWARD_ONLY;
        }
        return
            RolapUtil.executeQuery(
                connection.getDataSource(),
                sql,
                null,
                maxRowCount,
                firstRowOrdinal,
                new SqlStatement.StatementLocus(
                    execution,
                    "RolapCell.drillThrough",
                    "Error in drill through",
                    SqlStatementEvent.Purpose.DRILL_THROUGH, 0),
                resultSetType,
                resultSetConcurrency,
                null);
    }

    public Object getPropertyValue(String propertyName) {
        final boolean matchCase =
            MondrianProperties.instance().CaseSensitive.get();
        Property property = Property.lookup(propertyName, matchCase);
        Object defaultValue = null;
        String formatString = null;
        if (property != null) {
            switch (property.ordinal) {
            case Property.CELL_ORDINAL_ORDINAL:
                return result.getCellOrdinal(pos);
            case Property.VALUE_ORDINAL:
                return getValue();
            case Property.FORMAT_STRING_ORDINAL:
                if (ci.formatString == null) {
                    final Evaluator evaluator = result.getRootEvaluator();
                    final int savepoint = evaluator.savepoint();
                    try {
                        result.populateEvaluator(evaluator, pos);
                        ci.formatString = evaluator.getFormatString();
                    } finally {
                        evaluator.restore(savepoint);
                    }
                }
                return ci.formatString;
            case Property.FORMATTED_VALUE_ORDINAL:
                return getFormattedValue();
            case Property.FONT_FLAGS_ORDINAL:
                defaultValue = 0;
                break;
            case Property.SOLVE_ORDER_ORDINAL:
                defaultValue = 0;
                break;
            case Property.ACTION_TYPE_ORDINAL:
                return canDrillThrough() ? MDACTION_TYPE_DRILLTHROUGH : 0;
            case Property.DRILLTHROUGH_COUNT_ORDINAL:
                return canDrillThrough() ? getDrillThroughCount() : -1;
            case Property.BACK_COLOR_ORDINAL:
                formatString = (String)getPropertyValue(Property.FORMAT_STRING.getName());
                if(formatString == null) {
                    return null;
                }
                for(String part: formatString.split(";")) {
                    String[] pair = part.split("=");
                    if(pair.length == 2
                            && pair[0] != null
                            && pair[0].toUpperCase().equals(Property.BACK_COLOR.getName())) {
                        return pair[1];
                    }
                }
                return null;
// To option when backColor belongs to real measure
//                Object backColor = null;
//                //final RolapEvaluator rolapEvaluator = (RolapEvaluator)result.getEvaluator(pos);
//                final RolapEvaluator rolapEvaluator = (RolapEvaluator)result.getRootEvaluator();
//                final int savepoint = rolapEvaluator.savepoint();
//                try {
//                    result.populateEvaluator(rolapEvaluator, pos);
//                    org.apache.log4j.Logger LOGGER = org.apache.log4j.Logger.getLogger( RolapCell.class );
//                    backColor = rolapEvaluator.getBackColor();
//                } finally {
//                    rolapEvaluator.restore(savepoint);
//                }
//                return backColor;
            case Property.FORE_COLOR_ORDINAL:
                formatString = (String)getPropertyValue(Property.FORMAT_STRING.getName());
                if(formatString == null) {
                    return null;
                }
                for(String part: formatString.split(";")) {
                    String[] pair = part.split("=");
                    if(pair.length == 2
                            && pair[0] != null
                            && pair[0].toUpperCase().equals(Property.FORE_COLOR.getName())) {
                        return pair[1];
                    }
                }
                return null;
            default:
                // fall through
            }
        }
        final Evaluator evaluator = result.getRootEvaluator();
        final int savepoint = evaluator.savepoint();
        try {
            result.populateEvaluator(evaluator, pos);
            return evaluator.getProperty(propertyName, defaultValue);
        } finally {
            evaluator.restore(savepoint);
        }
    }

    public Member getContextMember(Hierarchy hierarchy) {
        return result.getMember(pos, hierarchy);
    }

    public void setValue(
        Scenario scenario,
        Object newValue,
        AllocationPolicy allocationPolicy,
        Object... allocationArgs)
    {
        if (allocationPolicy == null) {
            // user error
            throw Util.newError(
                "Allocation policy must not be null");
        }
        final RolapMember[] members = result.getCellMembers(pos);
        for (int i = 0; i < members.length; i++) {
            Member member = members[i];
            if (ScenarioImpl.isScenario(member.getHierarchy())) {
                scenario =
                    (Scenario) member.getPropertyValue(Property.SCENARIO.name);
                members[i] = (RolapMember) member.getHierarchy().getAllMember();
            } else if (member.isCalculated()) {
                throw Util.newError(
                    "Cannot write to cell: one of the coordinates ("
                    + member.getUniqueName()
                    + ") is a calculated member");
            }
        }
        if (scenario == null) {
            throw Util.newError("No active scenario");
        }
        if (allocationArgs == null) {
            allocationArgs = new Object[0];
        }
        final Object currentValue = getValue();
        double doubleCurrentValue;
        if (currentValue == null) {
            doubleCurrentValue = 0d;
        } else if (currentValue instanceof Number) {
            doubleCurrentValue = ((Number) currentValue).doubleValue();
        } else {
            // Cell is not a number. Likely it is a string or a
            // MondrianEvaluationException. Do not attempt to change the value
            // in this case. (REVIEW: Is this the correct behavior?)
            return;
        }
        double doubleNewValue = ((Number) newValue).doubleValue();
        ((ScenarioImpl) scenario).setCellValue(
            result.getExecution().getMondrianStatement()
                .getMondrianConnection(),
            Arrays.asList(members),
            doubleNewValue,
            doubleCurrentValue,
            allocationPolicy,
            allocationArgs);
    }

    /**
     * Visitor that walks over a cell's expression and checks whether the
     * cell should allow drill-through. If not, throws the {@link #bomb}
     * exception.
     *
     * <p>Examples:</p>
     * <ul>
     * <li>Literal 1 is drillable</li>
     * <li>Member [Measures].[Unit Sales] is drillable</li>
     * <li>Calculated member with expression [Measures].[Unit Sales] +
     *     1 is drillable</li>
     * <li>Calculated member with expression
     *     ([Measures].[Unit Sales], [Time].PrevMember) is not drillable</li>
     * </ul>
     */
    private static class DrillThroughVisitor extends MdxVisitorImpl {
        static final RuntimeException bomb = new RuntimeException();
        RolapCube cube = null;

        DrillThroughVisitor() {
        }

        public Object visit(MemberExpr memberExpr) {
            handleMember(memberExpr.getMember());
            return null;
        }

        public Object visit(ResolvedFunCall call) {
            final FunDef def = call.getFunDef();
            final Exp[] args = call.getArgs();
            final String name = def.getName();
            if (name.equals("+")
                || name.equals("-")
                || name.equals("/")
                || name.equals("*")
                || name.equals("CoalesceEmpty")
                // Allow parentheses but don't allow tuple
                || name.equals("()") && args.length == 1)
            {
                return null;
            }
            throw bomb;
        }

        public void handleMember(Member member) {
            if (member instanceof RolapStoredMeasure) {
                // If this member is in a different cube that previous members
                // we've seen, we cannot drill through.
                final RolapCube cube = ((RolapStoredMeasure) member).getCube();
                if (this.cube == null) {
                    this.cube = cube;
                } else if (this.cube != cube) {
                    // this measure lives in a different cube than previous
                    // measures we have seen
                    throw bomb;
                }
            } else if (member instanceof RolapCubeMember) {
                handleMember(((RolapCubeMember) member).member);
            } else if (member
                instanceof RolapHierarchy.RolapCalculatedMeasure)
            {
                RolapHierarchy.RolapCalculatedMeasure measure =
                    (RolapHierarchy.RolapCalculatedMeasure) member;
                measure.getFormula().getExpression().accept(this);
            } else if (member instanceof RolapMember) {
                // regular RolapMember - fine
            } else {
                // don't know what this is!
                throw bomb;
            }
        }

        public Object visit(NamedSetExpr namedSetExpr) {
            throw Util.newInternal("not valid here: " + namedSetExpr);
        }

        public Object visit(Literal literal) {
            return null; // literals are drillable
        }

        public Object visit(Query query) {
            throw Util.newInternal("not valid here: " + query);
        }

        public Object visit(QueryAxis queryAxis) {
            throw Util.newInternal("not valid here: " + queryAxis);
        }

        public Object visit(Formula formula) {
            throw Util.newInternal("not valid here: " + formula);
        }

        public Object visit(UnresolvedFunCall call) {
            throw Util.newInternal("expected resolved expression");
        }

        public Object visit(Id id) {
            throw Util.newInternal("expected resolved expression");
        }

        public Object visit(ParameterExpr parameterExpr) {
            // Not valid in general; might contain complex expression
            throw bomb;
        }

        public Object visit(DimensionExpr dimensionExpr) {
            // Not valid in general; might be part of complex expression
            throw bomb;
        }

        public Object visit(HierarchyExpr hierarchyExpr) {
            // Not valid in general; might be part of complex expression
            throw bomb;
        }

        public Object visit(LevelExpr levelExpr) {
            // Not valid in general; might be part of complex expression
            throw bomb;
        }
    }
}

// End RolapCell.java
