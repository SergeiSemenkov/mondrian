// Copyright (c) 2021 Sergei Semenkov.  All rights reserved.

package mondrian.olap;

import java.io.PrintWriter;
import java.util.*;

public class DmvQuery extends QueryPart {
    private final String tableName;
    private final List<String> columns;
    private final Exp whereExpression;

    public DmvQuery(
            String tableName,
            List<String> columns,
            Exp whereExpression)
    {
        this.tableName = tableName;
        this.columns = columns;
        this.whereExpression = whereExpression;
    }

//    @Override
//    public void unparse(PrintWriter pw) {
//    }

//    @Override
//    public Object[] getChildren() {
//        return new Object[] {};
//    }

    public String getTableName() {
        return this.tableName;
    }

    /** The selected columns in order; empty for {@code SELECT *} or no column list. */
    public List<String> getColumns() {
        return this.columns;
    }

    public Exp getWhereExpression() {
        return this.whereExpression;
    }

}

