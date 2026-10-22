/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Sergei Semenkov
// All Rights Reserved.
*/

package mondrian.metrics;

import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;
import mondrian.rolap.RolapConnection;
import mondrian.rolap.RolapSchema;
import mondrian.rolap.RolapSchemaPool;
import mondrian.server.MondrianServerImpl;
import mondrian.server.Session;

import java.util.*;

/**
 * Who is connected right now: open connections and XML/A sessions per user
 * and catalog, and the number of distinct users behind them.
 *
 * <p>Computed from the server's own bookkeeping at scrape time instead of
 * kept as gauges that are raised and lowered: a connection can disappear
 * without close() (the server holds them weakly) and a session can be reaped,
 * and a gauge maintained by increments misses both and drifts. A label set
 * that has gone away also disappears from the next scrape on its own.</p>
 *
 * <p>Each schema's internal connection is left out. It is the engine's own,
 * opened to load the schema, and lives as long as the schema does; it carries
 * the connect info of whichever request loaded the schema, so counting it
 * would credit that user with a connection they do not have.</p>
 */
public class ActivityCollector extends Collector {

    private static final String NULL = "null";

    private static ActivityCollector instance;

    /** Registers once per class loader, i.e. once per deployment of the webapp. */
    public static synchronized void install() {
        if (instance == null) {
            instance = new ActivityCollector().register();
        }
    }

    @Override
    public List<MetricFamilySamples> collect() {
        final Set<String> users = new HashSet<String>();

        final Set<RolapConnection> internal =
            Collections.newSetFromMap(new IdentityHashMap<RolapConnection, Boolean>());
        for (RolapSchema schema : RolapSchemaPool.instance().getRolapSchemas()) {
            internal.add(schema.getInternalConnection());
        }
        final Map<List<String>, Integer> connections = new HashMap<List<String>, Integer>();
        for (MondrianServerImpl server : MondrianServerImpl.getServers()) {
            for (RolapConnection connection : server.getConnections()) {
                if (connection == null || internal.contains(connection)) {
                    continue;
                }
                final String user = orNull(connection.getUserId());
                users.add(user);
                increment(connections, Arrays.asList(user, orNull(connection.getCatalogName())));
            }
        }

        final Map<List<String>, Integer> sessions = new HashMap<List<String>, Integer>();
        for (Session session : Session.getSessions()) {
            final String user = orNull(session.getUserName());
            users.add(user);
            increment(sessions, Arrays.asList(user, orNull(session.getCurrentDatabase())));
        }

        final GaugeMetricFamily connectionFamily = new GaugeMetricFamily(
            "emondrian_connections",
            "Open connections per user and catalog, not counting the schemas' internal ones",
            Arrays.asList("user", "catalog"));
        for (Map.Entry<List<String>, Integer> e : connections.entrySet()) {
            connectionFamily.addMetric(e.getKey(), e.getValue());
        }
        final GaugeMetricFamily sessionFamily = new GaugeMetricFamily(
            "emondrian_sessions",
            "Open XML/A sessions per user and the catalog of their last request "
                + "(\"null\" until a session has carried a request); includes the one-request "
                + "session a request without BeginSession gets while it runs",
            Arrays.asList("user", "catalog"));
        for (Map.Entry<List<String>, Integer> e : sessions.entrySet()) {
            sessionFamily.addMetric(e.getKey(), e.getValue());
        }
        final GaugeMetricFamily userFamily = new GaugeMetricFamily(
            "emondrian_active_users",
            "Distinct users with an open connection or XML/A session "
                + "(unauthenticated requests count as one user, \"null\")",
            users.size());

        return Arrays.<MetricFamilySamples>asList(connectionFamily, sessionFamily, userFamily);
    }

    private static String orNull(String s) {
        return s == null ? NULL : s;
    }

    private static void increment(Map<List<String>, Integer> map, List<String> key) {
        final Integer n = map.get(key);
        map.put(key, n == null ? 1 : n + 1);
    }
}
