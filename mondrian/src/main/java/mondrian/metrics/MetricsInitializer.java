/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2025 Sergei Semenkov
// All Rights Reserved.
*/

package mondrian.metrics;

import io.prometheus.client.hotspot.DefaultExports;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

public class MetricsInitializer implements ServletContextListener {

    /**
     * DefaultExports.initialize() registers hotspot exporters (e.g.
     * MemoryAllocationExports) that attach JMX NotificationListeners directly to
     * the JVM's GarbageCollectorMXBeans -- a JVM-global registration, not one tied
     * to this servlet context. This Prometheus client version exposes no
     * unregister/close hook, so those listeners outlive contextDestroyed() and
     * keep a hard reference to this context's WebappClassLoader. On every
     * redeploy/reload, that leaks the previous classloader and eventually the JVM's
     * background GC-notification thread calls into the dead classloader, throwing
     * "Illegal access: this web application instance has been stopped already".
     *
     * A plain static boolean here doesn't help: each redeploy gets a fresh
     * WebappClassLoader, so this class's static state resets too. A system
     * property lives on the JVM-wide System class instead, so it survives
     * redeploys and prevents re-registering (and re-leaking) a new set of
     * listeners each time. This doesn't reclaim whatever listener/classloader
     * already leaked from a prior deploy in this JVM -- only a JVM restart does
     * that -- but it stops the leak from compounding across further redeploys.
     */
    private static final String HOTSPOT_EXPORTS_INITIALIZED_PROPERTY =
        "mondrian.metrics.hotspotExportsInitialized";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        if (System.getProperty(HOTSPOT_EXPORTS_INITIALIZED_PROPERTY) == null) {
            DefaultExports.initialize();
            System.setProperty(HOTSPOT_EXPORTS_INITIALIZED_PROPERTY, "true");
        }
        ExecutionMetrics.updateMetrics();
    }
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
    }
}
