/*
 * This software is subject to the terms of the Eclipse Public License v1.0
 * Agreement, available at the following URL:
 * http://www.eclipse.org/legal/epl-v10.html.
 * You must accept the terms of that agreement to use this software.
 *
 * Copyright (C) 2021-2025 Sergei Semenkov
 */

package mondrian.spi.impl;

import mondrian.olap.Util;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Implementation of {@link mondrian.spi.Dialect} for ClickHouse
 */
public class ClickHouseDialect extends JdbcDialectImpl {

    public static final JdbcDialectFactory FACTORY =
            new JdbcDialectFactory(
                    ClickHouseDialect.class,
                    DatabaseProduct.CLICKHOUSE);

    /**
     * Creates a Db2OldAs400Dialect.
     *
     * @param connection Connection
     */
    public ClickHouseDialect(Connection connection) throws SQLException {
        super(connection);
        this.unlimitedValueList =
            raisesQueryLimits(connection.getMetaData().getURL());
    }

    /** ClickHouse's own defaults for the two settings an IN list runs into. */
    static final long DEFAULT_MAX_QUERY_SIZE = 262144;
    static final long DEFAULT_MAX_AST_ELEMENTS = 50000;

    private final boolean unlimitedValueList;

    /**
     * Whether an IN list may be any length. Below
     * {@code mondrian.rolap.maxConstraints} values it never matters; above it,
     * a dialect without unlimited lists makes the engine drop the list from
     * the SQL and evaluate the query outside the database. A role granting
     * more members than that lost its filter that way: 2,000 tail numbers on
     * OnTime fetched 237,435 rows and took 170 s where 1,000 took 33 s.
     *
     * <p>ClickHouse has no limit on the list as such, only on the query text
     * ({@code max_query_size}, 256 KiB) and its parsed tree
     * ({@code max_ast_elements}, 50,000), either of which a long list exceeds.
     * So the answer is yes only when the connect string raises both, as the
     * shipped ones do; settings raised in a server-side profile are not
     * visible here, and such a connection keeps the limit.</p>
     */
    @Override
    public boolean supportsUnlimitedValueList() {
        return unlimitedValueList;
    }

    static boolean raisesQueryLimits(String url) {
        return setting(url, "max_query_size") > DEFAULT_MAX_QUERY_SIZE
            && setting(url, "max_ast_elements") > DEFAULT_MAX_AST_ELEMENTS;
    }

    /** A numeric setting from the JDBC URL's query string, or -1. */
    static long setting(String url, String name) {
        if (url == null) {
            return -1;
        }
        final int q = url.indexOf('?');
        if (q < 0) {
            return -1;
        }
        for (String pair : url.substring(q + 1).split("[&;]")) {
            final int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(name)) {
                try {
                    return Long.parseLong(pair.substring(eq + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    public boolean requiresDrillthroughMaxRowsInLimit() {
        return true;
    }

    public void quoteStringLiteral(
            StringBuilder buf,
            String s)
    {
        buf.append('\'');

        String s0 = Util.replace(s, "\\", "\\\\");
        s0 = Util.replace(s0, "'", "\\'");
        buf.append(s0);

        buf.append('\'');
    }

    @Override
    public String getDefaultUnion() {
        return "union distinct";
    }
}

