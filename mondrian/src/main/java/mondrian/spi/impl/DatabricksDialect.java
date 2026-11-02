/*
 * This software is subject to the terms of the Eclipse Public License v1.0
 * Agreement, available at the following URL:
 * http://www.eclipse.org/legal/epl-v10.html.
 * You must accept the terms of that agreement to use this software.
 *
 * Copyright (c) 2026 Sergei Semenkov.  All rights reserved.
 */
package mondrian.spi.impl;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import mondrian.spi.DialectUtil;

/**
 * Dialect for Databricks SQL (SQL warehouses, Spark SQL), through either
 * Databricks JDBC driver; both report the product name "SparkSQL".
 *
 * <p>Written for Unity Catalog metric views as fact tables: a measure is
 * declared with {@code aggregatorImplementation="MEASURE"}, so the engine
 * writes {@code MEASURE(`alias`.`column`)} where it would write
 * {@code sum(...)}. Nothing here is specific to metric views, though; the
 * same dialect serves ordinary Delta tables.
 *
 * <p>What a metric view rejects, and so what this dialect must keep the engine
 * from generating: aggregates over subqueries (the distinct-count rewrite that
 * a dialect without multiple COUNT DISTINCT gets) and grouping sets.
 */
public class DatabricksDialect extends JdbcDialectImpl {

    public static final JdbcDialectFactory FACTORY =
        new JdbcDialectFactory(
            DatabricksDialect.class,
            DatabaseProduct.DATABRICKS);

    public DatabricksDialect(Connection connection) throws SQLException {
        super(connection);
    }

    @Override
    public DatabaseProduct getDatabaseProduct() {
        return DatabaseProduct.DATABRICKS;
    }

    @Override
    protected String deduceIdentifierQuoteString(
        DatabaseMetaData databaseMetaData)
    {
        return "`";
    }

    // Several MEASURE(...) columns, additive or not, must stay in one flat
    // SELECT ... GROUP BY. Any "false" here sends the engine down the
    // subquery rewrite for distinct-count measures, which a metric view
    // refuses.

    @Override
    public boolean allowsCountDistinct() {
        return true;
    }

    @Override
    public boolean allowsMultipleCountDistinct() {
        return true;
    }

    @Override
    public boolean allowsCompoundCountDistinct() {
        return true;
    }

    @Override
    public boolean allowsCountDistinctWithOtherAggs() {
        return true;
    }

    @Override
    public boolean supportsGroupingSets() {
        return false;
    }

    @Override
    public boolean requiresAliasForFromQuery() {
        return true;
    }

    @Override
    public boolean allowsSelectNotInGroupBy() {
        return false;
    }

    @Override
    public boolean allowsOrderByAlias() {
        return true;
    }

    @Override
    public boolean requiresOrderByAlias() {
        return false;
    }

    @Override
    public boolean supportsMultiValueInExpr() {
        return true;
    }

    @Override
    public boolean allowsDdl() {
        return false;
    }

    @Override
    protected String generateOrderByNulls(
        String expr,
        boolean ascending,
        boolean collateNullsLast)
    {
        return generateOrderByNullsAnsi(expr, ascending, collateNullsLast);
    }

    @Override
    public String generateInline(
        List<String> columnNames,
        List<String> columnTypes,
        List<String[]> valueList)
    {
        return generateInlineGeneric(
            columnNames, columnTypes, valueList, null, false);
    }

    /**
     * Spark SQL string literals treat a backslash as an escape character, so a
     * literal backslash is doubled as well as the quote.
     */
    @Override
    public void quoteStringLiteral(StringBuilder buf, String s) {
        buf.append('\'');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\'' || c == '\\') {
                buf.append('\\');
            }
            buf.append(c);
        }
        buf.append('\'');
    }

    @Override
    public boolean allowsRegularExpressionInWhereClause() {
        return true;
    }

    @Override
    public String generateRegularExpression(String source, String javaRegex) {
        try {
            Pattern.compile(javaRegex);
        } catch (PatternSyntaxException e) {
            return null;
        }
        javaRegex = DialectUtil.cleanUnicodeAwareCaseFlag(javaRegex);
        final StringBuilder mappedFlags = new StringBuilder();
        javaRegex =
            extractEmbeddedFlags(
                javaRegex, new String[][] {{"i", "i"}}, mappedFlags);
        final boolean caseSensitive = !mappedFlags.toString().contains("i");
        final String operand = "cast(" + source + " as string)";
        final StringBuilder sb = new StringBuilder();
        sb.append(operand).append(" IS NOT NULL AND ");
        if (caseSensitive) {
            sb.append(operand).append(" RLIKE ");
            quoteStringLiteral(sb, javaRegex);
        } else {
            sb.append("UPPER(").append(operand).append(") RLIKE ");
            quoteStringLiteral(sb, javaRegex.toUpperCase());
        }
        return sb.toString();
    }
}

// End DatabricksDialect.java
