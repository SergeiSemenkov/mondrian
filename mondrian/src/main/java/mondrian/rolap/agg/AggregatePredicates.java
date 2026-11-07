/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.rolap.agg;

import mondrian.rolap.RolapStar;
import mondrian.rolap.StarColumnPredicate;
import mondrian.rolap.StarPredicate;
import mondrian.rolap.aggmatcher.AggStar;
import mondrian.rolap.sql.SqlQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Compound predicates (a subselect's, the shape every Power BI slicer gives) rewritten onto an
 * aggregate table's own columns, so that a cell load under a subselect can read the aggregate
 * table instead of the fact table. Each constrained star column becomes a stand-in with the same
 * bit position that renders as the aggregate's column (joining the aggregate's dimension table
 * when the level is not collapsed into it).
 *
 * <p>A predicate this cannot rewrite - a column the aggregate does not carry, a tuple predicate,
 * a type not listed here - makes the whole list null, and the caller falls back to the fact
 * table. Rendering against the base star's columns beside an aggregate table is the cartesian
 * product engine .77 stopped in native SQL; here it is never attempted.
 */
public final class AggregatePredicates {

    private AggregatePredicates() {
    }

    /** {@code predicates} on {@code aggStar}'s columns, or null when one of them cannot be. */
    public static List<StarPredicate> onAggregate(List<StarPredicate> predicates, AggStar aggStar) {
        List<StarPredicate> rewritten = new ArrayList<StarPredicate>();
        for (StarPredicate predicate : predicates) {
            StarPredicate onAggregate = rewrite(predicate, aggStar);
            if (onAggregate == null) {
                return null;
            }
            rewritten.add(onAggregate);
        }
        return rewritten;
    }

    private static StarPredicate rewrite(StarPredicate predicate, AggStar aggStar) {
        if (predicate instanceof LiteralStarPredicate) {
            return predicate;
        }
        if (predicate instanceof AndPredicate || predicate instanceof OrPredicate) {
            List<StarPredicate> children = new ArrayList<StarPredicate>();
            for (StarPredicate child : ((ListPredicate) predicate).getChildren()) {
                StarPredicate rewritten = rewrite(child, aggStar);
                if (rewritten == null) {
                    return null;
                }
                children.add(rewritten);
            }
            return predicate instanceof AndPredicate
                ? new AndPredicate(children)
                : new OrPredicate(children);
        }
        if (predicate instanceof NotPredicate) {
            StarPredicate inner = rewrite(((NotPredicate) predicate).getPredicate(), aggStar);
            return inner == null ? null : new NotPredicate(inner);
        }
        // Value (and Member, its subclass) and List predicates; Range and Minus have no SQL form.
        if (predicate instanceof ValueColumnPredicate
            || predicate instanceof ListColumnPredicate)
        {
            StarColumnPredicate columnPredicate = (StarColumnPredicate) predicate;
            RolapStar.Column column = onAggregate(columnPredicate.getConstrainedColumn(), aggStar);
            if (column == null) {
                return null;
            }
            for (RolapStar.Column constrained : predicate.getConstrainedColumnList()) {
                if (constrained.getBitPosition() != column.getBitPosition()) {
                    // A column predicate over several columns: not one this can map.
                    return null;
                }
            }
            return columnPredicate.cloneWithColumn(column);
        }
        // MemberTuplePredicate, Range, Minus and anything else: the fact table.
        return null;
    }

    private static RolapStar.Column onAggregate(RolapStar.Column column, AggStar aggStar) {
        if (column == null || column.getBitPosition() < 0
            || !aggStar.getLevelBitKey().get(column.getBitPosition()))
        {
            return null;
        }
        AggStar.Table.Column aggColumn = aggStar.lookupColumn(column.getBitPosition());
        return aggColumn == null ? null : new AggregateColumn(column, aggColumn);
    }

    /** A star column rendered as the aggregate table's column. */
    private static final class AggregateColumn extends RolapStar.Column {
        private final AggStar.Table.Column aggColumn;

        AggregateColumn(RolapStar.Column original, AggStar.Table.Column aggColumn) {
            super(original);
            this.aggColumn = aggColumn;
        }

        @Override
        public String generateExprString(SqlQuery query) {
            aggColumn.getTable().addToFrom(query, false, true);
            return aggColumn.generateExprString(query);
        }
    }
}

// End AggregatePredicates.java
