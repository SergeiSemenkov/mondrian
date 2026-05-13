/*
 * This software is subject to the terms of the Eclipse Public License v1.0
 * Agreement, available at the following URL:
 * http://www.eclipse.org/legal/epl-v10.html.
 * You must accept the terms of that agreement to use this software.
 *
 * Copyright (c) 2021-2025 Sergei Semenkov.  All rights reserved.
 */

package mondrian.server;

import java.util.*;

import mondrian.xmla.XmlaException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.olap4j.OlapException;

import mondrian.rolap.*;
import mondrian.rolap.agg.SegmentCacheManager;
import mondrian.rolap.agg.SegmentCacheWorker;
import mondrian.olap.MondrianServer;
import org.olap4j.Scenario;

public class Session
{
    private static final Logger LOGGER = LogManager.getLogger(Session.class);
    final static Map<String, Session> sessions = new HashMap<String, Session>();

    static java.util.Timer timer = new Timer(true);
    static java.util.TimerTask timerTask = new java.util.TimerTask() {
        public void run() {
            List<String> toRemove = new ArrayList<String>();
            for(Map.Entry<String, Session> entry : sessions.entrySet()) {
                Session session = entry.getValue();
                java.time.Duration duration = java.time.Duration.between(
                        session.checkInTime,
                        java.time.LocalDateTime.now());
                if(duration.getSeconds() >
                        mondrian.olap.MondrianProperties.instance().IdleOrphanSessionTimeout.get()) {
                    toRemove.add(entry.getKey());
                }
            }
            for(String sessionId : toRemove) {
                closeInternal(sessionId);
            }
        }
    };
    static
    {
        timer.scheduleAtFixedRate(timerTask, 0, 60*1000);
    }

    String sessionId;
    Session(String sessionId)
    {
        this.sessionId = sessionId;
    }
    public static Session create(String sessionId) throws OlapException
    {
        if(sessions.containsKey(sessionId)) {
            throw new mondrian.xmla.XmlaException(
                    "XMLAnalysisError",
                    "0xc10c000a",
                    "Session with id \"" + sessionId + "\" already exists.",
                    new OlapException("Session with id \"" + sessionId + "\" already exists.")
            );
        }

        Session session = new Session(sessionId);

        sessions.put(sessionId, session);
        session.checkInTime = java.time.LocalDateTime.now();

        mondrian.metrics.SessionMetrics.setSessionCount(sessions.size());

        return session;
    }

    public static Session getWithoutCheck(String sessionId)
    {
        return sessions.get(sessionId);
    }

    public static Session get(String sessionId) throws OlapException
    {
        if(!sessions.containsKey(sessionId)) {
            throw new mondrian.xmla.XmlaException(
                    "XMLAnalysisError",
                    "0xc10c000a",
                    "Session with id \"" + sessionId + "\" does not exists.",
                    new SessionNotFoundException("Session with id \"" + sessionId + "\" does not exist")
            );
        }
        return sessions.get(sessionId);
    }

    java.time.LocalDateTime checkInTime = null;

    public static void checkIn(String sessionId) throws OlapException
    {
        Session session = get(sessionId);
        session.checkInTime = java.time.LocalDateTime.now();
    }

    static void closeInternal(String sessionId)
    {
        List<RolapSchema> rolapSchemas = RolapSchemaPool.instance().getRolapSchemas();
        for(RolapSchema rolapSchema: rolapSchemas) {
            final String rolapSchemaSessionId = rolapSchema.getInternalConnection().getConnectInfo().get("sessionId");
            if(sessionId.equals(rolapSchemaSessionId)) {
                RolapSchemaPool.instance().remove(rolapSchema);
            }
        }

        Session session = sessions.get(sessionId);
        shutdownCacheManager(session);

        for(MondrianServerImpl mondrianServerImpl: mondrian.server.MondrianServerImpl.getServers()) {
            for(Statement statement: mondrianServerImpl.getStatements(sessionId)) {
                mondrianServerImpl.removeStatement(statement);
            }
        }

        sessions.remove(sessionId);
        mondrian.metrics.SessionMetrics.setSessionCount(sessions.size());
    }

    static void shutdownCacheManager(Session session) {
        if(session.segmentCacheManager != null) {
            // Send a shutdown command and wait for it to return.
            session.segmentCacheManager.shutdown();
            // Now we can cleanup.
            for (SegmentCacheWorker worker : session.segmentCacheManager.segmentCacheWorkers) {
                worker.shutdown();
            }
        }
    }

    public static void shutdown()
    {
        for(Map.Entry<String, Session> entry : sessions.entrySet()) {
            shutdownCacheManager(entry.getValue());
        }
    }

    public static void close(String sessionId) throws OlapException
    {
        Session session = Session.get(sessionId);

        closeInternal(sessionId);
    }

    private SegmentCacheManager segmentCacheManager = null;

    public SegmentCacheManager getOrCreateSegmentCacheManager(MondrianServer server){
        if(this.segmentCacheManager == null) {
            this.segmentCacheManager = new SegmentCacheManager(server);
        }
        return this.segmentCacheManager;
    }

    private Scenario scenario = null;

    public void setScenario(Scenario scenario) {
        this.scenario = scenario;
    }

    public Scenario getScenario() {
        return this.scenario;
    }

    public static void ResetAllCaches() {
        for(Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            shutdownCacheManager(session);
            session.segmentCacheManager = null;
        }
    }

    public static class SessionNotFoundException extends Exception {
        public SessionNotFoundException(String faultString)
        {
            super(faultString);
        }
    }
}
