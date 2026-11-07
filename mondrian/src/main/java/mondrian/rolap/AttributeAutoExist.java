/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.rolap;

import mondrian.calc.TupleList;
import mondrian.olap.Evaluator;
import mondrian.olap.Member;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-exist between two hierarchies of one dimension, as Analysis Services applies it: a member
 * of one exists with a member of the other when the dimension table has a row with both. In the
 * attribute model every attribute is a hierarchy of its own, so Exists, which relates members
 * only along one hierarchy, related none of them: a list of Store City under a Store State
 * filter listed every city.
 *
 * <p>The combinations are read once per instance (one per compiled Exists, so once per query)
 * through {@link SqlTupleReader} with no fact table: the two levels' columns from the dimension's
 * own table, which is one table for two attributes of it. Two hierarchies over different tables
 * come back as their crossjoin, which relates every pair, as before.
 */
public class AttributeAutoExist {

    private final Map<String, Set<String>> combinations = new HashMap<>();

    /**
     * Whether {@code a} and {@code b} exist together. Members that this does not apply to (one
     * dimension's members of the same hierarchy, an All member, a calculated member, different
     * dimensions) are related as before, so the answer is true.
     */
    public boolean exist(Evaluator evaluator, Member a, Member b) {
        if (!applies(a, b)) {
            return true;
        }
        RolapLevel levelA = (RolapLevel) a.getLevel();
        RolapLevel levelB = (RolapLevel) b.getLevel();
        String key = levelA.getUniqueName() + "\u0000" + levelB.getUniqueName();
        Set<String> pairs = combinations.get(key);
        if (pairs == null) {
            pairs = read(evaluator, levelA, levelB);
            combinations.put(key, pairs);
        }
        return pairs.contains(a.getUniqueName() + "\u0000" + b.getUniqueName());
    }

    public static boolean applies(Member a, Member b) {
        return a instanceof RolapMember && b instanceof RolapMember
            && !a.isAll() && !b.isAll() && !a.isCalculated() && !b.isCalculated()
            && !a.isNull() && !b.isNull()
            && a.getHierarchy() != b.getHierarchy()
            && a.getDimension().equals(b.getDimension())
            && !a.getDimension().isMeasures()
            && a.getLevel() instanceof RolapLevel && b.getLevel() instanceof RolapLevel
            && !((RolapLevel) a.getLevel()).isParentChild()
            && !((RolapLevel) b.getLevel()).isParentChild();
    }

    private static Set<String> read(Evaluator evaluator, RolapLevel levelA, RolapLevel levelB) {
        mondrian.olap.SchemaReader schemaReader = evaluator.getSchemaReader();
        SqlTupleReader reader = new SqlTupleReader(new DimensionTableConstraint(evaluator, levelA, levelB));
        // The member readers of the connection's role, as native set evaluation builds them.
        reader.addLevelMembers(levelA,
            levelA.getHierarchy().createMemberReader(schemaReader.getRole()).getMemberBuilder(), null);
        reader.addLevelMembers(levelB,
            levelB.getHierarchy().createMemberReader(schemaReader.getRole()).getMemberBuilder(), null);
        TupleList tuples = reader.readTuples(schemaReader.getDataSource(), null, null);
        Set<String> pairs = new HashSet<>();
        for (List<Member> tuple : tuples) {
            pairs.add(tuple.get(0).getUniqueName() + "\u0000" + tuple.get(1).getUniqueName());
        }
        return pairs;
    }

    /** No condition and no fact table: the levels' columns from their own table(s). */
    private static class DimensionTableConstraint extends DefaultTupleConstraint {
        private final Evaluator evaluator;
        private final List<Object> cacheKey;

        DimensionTableConstraint(Evaluator evaluator, RolapLevel a, RolapLevel b) {
            this.evaluator = evaluator;
            this.cacheKey = List.of(AttributeAutoExist.class, a.getUniqueName(), b.getUniqueName());
        }

        @Override
        public Evaluator getEvaluator() {
            return evaluator;
        }

        @Override
        public Object getCacheKey() {
            return cacheKey;
        }
    }
}

// End AttributeAutoExist.java
