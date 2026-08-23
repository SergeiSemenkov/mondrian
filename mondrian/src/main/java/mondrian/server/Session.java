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

    // Concurrent, because the reaper below walks this map on the timer thread
    // while request threads are adding and removing sessions.
    final static Map<String, Session> sessions =
            new java.util.concurrent.ConcurrentHashMap<String, Session>();

    static java.util.Timer timer = new Timer(true);
    static java.util.TimerTask timerTask = new java.util.TimerTask() {
        public void run() {
            // An exception thrown out of run() kills the timer thread for the
            // life of the JVM -- silently, since Timer has nowhere to report it
            // -- and idle sessions are then never reaped again. Nothing in here
            // is worth that, so everything is caught and logged.
            try {
                sweep();
            } catch (Throwable t) {
                LOGGER.error("Session reaper failed; sessions may not be reaped", t);
            }
        }

        private void sweep() {
            final long timeoutSeconds =
                    mondrian.olap.MondrianProperties.instance().IdleOrphanSessionTimeout.get();
            final java.time.LocalDateTime now = java.time.LocalDateTime.now();
            List<String> toRemove = new ArrayList<String>();
            for(Map.Entry<String, Session> entry : sessions.entrySet()) {
                Session session = entry.getValue();
                java.time.LocalDateTime checkInTime = session.checkInTime;
                if(checkInTime == null) {
                    // Cannot happen now that the constructor sets it, but a
                    // null here used to kill the timer thread outright.
                    continue;
                }
                java.time.Duration duration =
                        java.time.Duration.between(checkInTime, now);
                if(duration.getSeconds() > timeoutSeconds) {
                    toRemove.add(entry.getKey());
                }
            }
            for(String sessionId : toRemove) {
                try {
                    closeInternal(sessionId);
                } catch (Throwable t) {
                    // One session that will not close must not strand the rest.
                    LOGGER.error(
                            "Could not close idle session \"" + sessionId + "\"", t);
                }
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
        // Set here rather than after the map insert below: the reaper reads
        // this field as soon as the session is reachable, and used to find it
        // null in that window.
        this.checkInTime = java.time.LocalDateTime.now();
    }
    public static Session create(String sessionId) throws OlapException
    {
        // The map is concurrent and so rejects a null key outright; sessions
        // are identified by id, and one with no id is not a session.
        if(sessionId == null) {
            throw new mondrian.xmla.XmlaException(
                    "XMLAnalysisError",
                    "0xc10c000a",
                    "Session id must not be null.",
                    new OlapException("Session id must not be null.")
            );
        }

        Session session = new Session(sessionId);

        // putIfAbsent rather than containsKey-then-put: two requests naming the
        // same session id would otherwise both pass the check and one would
        // silently replace the other's session.
        if(sessions.putIfAbsent(sessionId, session) != null) {
            throw new mondrian.xmla.XmlaException(
                    "XMLAnalysisError",
                    "0xc10c000a",
                    "Session with id \"" + sessionId + "\" already exists.",
                    new OlapException("Session with id \"" + sessionId + "\" already exists.")
            );
        }

        mondrian.metrics.SessionMetrics.setSessionCount(sessions.size());

        return session;
    }

    public static Session getWithoutCheck(String sessionId)
    {
        // A connection made without a sessionId asks with null, and must get
        // null back. ConcurrentHashMap.get(null) throws where HashMap.get(null)
        // did not, so the guard is load-bearing, not defensive.
        if(sessionId == null) {
            return null;
        }
        return sessions.get(sessionId);
    }

    public static Session get(String sessionId) throws OlapException
    {
        // One lookup, not containsKey-then-get: the reaper can remove the
        // session between the two, and this method must not return null.
        Session session = getWithoutCheck(sessionId);
        if(session == null) {
            throw new mondrian.xmla.XmlaException(
                    "XMLAnalysisError",
                    "0xc10c000a",
                    "Session with id \"" + sessionId + "\" does not exists.",
                    new SessionNotFoundException("Session with id \"" + sessionId + "\" does not exist")
            );
        }
        return session;
    }

    // Written by request threads, read by the timer thread.
    volatile java.time.LocalDateTime checkInTime = null;

    public static void checkIn(String sessionId) throws OlapException
    {
        Session session = get(sessionId);
        session.checkInTime = java.time.LocalDateTime.now();
    }

    static void closeInternal(String sessionId)
    {
        if(sessionId == null) {
            return;
        }

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
        if(session != null && session.segmentCacheManager != null) {
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
