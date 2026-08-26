// Copyright (c) 2021 Sergei Semenkov.  All rights reserved.

package mondrian.olap;

import java.io.PrintWriter;
import java.util.List;

public class Update extends QueryPart {
    private final String cubeName;
    private List<UpdateClause> updateClauses;

    Update(String cubeName, List<UpdateClause> updateClauses)
    {
        this.cubeName = cubeName;
        this.updateClauses = updateClauses;
    }

    @Override
    public void unparse(PrintWriter pw) {
        pw.print("UPDATE CUBE [" + cubeName + "]");
    }

    @Override
    public Object[] getChildren() {
        return new Object[] {cubeName};
    }

    public String getCubeName() {
        return cubeName;
    }

    public List<UpdateClause> getUpdateClauses() {
        return this.updateClauses;
    }

    public enum Allocation {
        NO_ALLOCATION,
        USE_EQUAL_ALLOCATION,
        USE_EQUAL_INCREMENT,
        USE_WEIGHTED_ALLOCATION,
        USE_WEIGHTED_INCREMENT
    }

    public static class UpdateClause extends QueryPart {
        private final Exp tuple;
        private Exp value;
        private Allocation allocation;
        private Exp weight;

        public UpdateClause(Exp tuple, Exp value, Allocation allocation, Exp weight) {
            this.tuple = tuple;
            this.value = value;
            this.allocation = allocation;
            this.weight = weight;
        }

        public Exp getTupleExp() {
            return this.tuple;
        }

        public Exp getValueExp() {
            return this.value;
        }

        /**
         * The allocation policy the statement asked for.
         *
         * <p>The grammar has always parsed this, but nothing could read it back, so the executor
         * hardcoded equal allocation and every {@code USE_...} clause was silently ignored. The
         * writeback module reads it here.
         */
        public Allocation getAllocation() {
            return this.allocation;
        }

        /** The {@code BY <expr>} weight of a weighted allocation, or null if none was given. */
        public Exp getWeightExp() {
            return this.weight;
        }
    }
}

