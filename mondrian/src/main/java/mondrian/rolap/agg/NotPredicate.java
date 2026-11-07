package mondrian.rolap.agg;

import mondrian.olap.Util;
import mondrian.rolap.BitKey;
import mondrian.rolap.RolapStar;
import mondrian.rolap.StarColumnPredicate;
import mondrian.rolap.StarPredicate;
import mondrian.rolap.sql.SqlQuery;

import java.util.Collection;
import java.util.List;

/**
 * A predicate that negates another predicate (logical NOT).
 */
public class NotPredicate extends AbstractColumnPredicate {

    private final StarPredicate predicate;

    public NotPredicate(StarPredicate predicate) {
        super(getFirstColumn(predicate));
        this.predicate = predicate;
    }

    /** The negated predicate. */
    public StarPredicate getPredicate() {
        return predicate;
    }

    private static RolapStar.Column getFirstColumn(StarPredicate predicate) {
        List<RolapStar.Column> cols = predicate.getConstrainedColumnList();
        return cols.isEmpty() ? null : cols.get(0);
    }

    @Override
    public BitKey getConstrainedColumnBitKey() {
        return predicate.getConstrainedColumnBitKey();
    }

    @Override
    public List<RolapStar.Column> getConstrainedColumnList() {
        return predicate.getConstrainedColumnList();
    }

    @Override
    public boolean evaluate(List<Object> valueList) {
        return !predicate.evaluate(valueList);
    }

    @Override
    public boolean equalConstraint(StarPredicate that) {
        if (!(that instanceof NotPredicate)) {
            return false;
        }
        return predicate.equalConstraint(((NotPredicate) that).predicate);
    }

    @Override
    public void values(Collection<Object> collection) {
        // NOT predicate does not enumerate specific values
    }

    @Override
    public boolean evaluate(Object value) {
        if (predicate instanceof StarColumnPredicate) {
            return !((StarColumnPredicate) predicate).evaluate(value);
        }
        throw Util.needToImplement(
            "evaluate for wrapped predicate type: "
            + predicate.getClass().getName());
    }

    @Override
    public StarColumnPredicate.Overlap intersect(StarColumnPredicate other) {
        // Conservative: assume partial overlap
        return new StarColumnPredicate.Overlap(false, this, 0f);
    }

    @Override
    public boolean mightIntersect(StarPredicate other) {
        // Conservative: assume intersection is possible
        return true;
    }

    @Override
    public StarColumnPredicate minus(StarPredicate predicate) {
        throw Util.needToImplement(this);
    }

    @Override
    public StarPredicate and(StarPredicate predicate) {
        return new AndPredicate(
            java.util.Arrays.asList(this, predicate));
    }

    @Override
    public StarPredicate or(StarPredicate predicate) {
        return new OrPredicate(
            java.util.Arrays.asList(this, predicate));
    }

    @Override
    public void toSql(SqlQuery sqlQuery, StringBuilder buf) {
        // Generate: NOT(<inner-sql>)
        buf.append("NOT (");
        // Delegate to the wrapped predicate; it knows how to render itself.
        predicate.toSql(sqlQuery, buf);
        buf.append(")");
    }

    public StarPredicate getInnerPredicate() {
        return predicate;
    }

    @Override
    public String toString() {
        return "NOT(" + predicate + ")";
    }

    @Override
    public StarColumnPredicate cloneWithColumn(RolapStar.Column column) {
        if (predicate instanceof StarColumnPredicate) {
            return new NotPredicate(
                ((StarColumnPredicate) predicate).cloneWithColumn(column));
        }
        throw Util.needToImplement(
            "cloneWithColumn for wrapped predicate type: "
            + predicate.getClass().getName());
    }

    @Override
    public void describe(StringBuilder buf) {
        buf.append("NOT(");
        predicate.describe(buf);
        buf.append(")");
    }
}
