/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Sergei Semenkov
// All Rights Reserved.
*/

package mondrian.xmla;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Servlet context listener that discovers {@link HttpServlet} implementations
 * from the {@code /modules} directory and registers them dynamically before
 * the web context finishes initializing.
 *
 * <p>All module JAR scanning, class loading and servlet registration logic
 * lives here. This listener is picked up automatically via the
 * {@code @WebListener} annotation — no {@code web.xml} entry is needed.</p>
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #contextInitialized} is invoked by the container early in
 *       startup, before the context is marked as fully initialized.</li>
 *   <li>The listener resolves {@code modulesPath} from the real path of
 *       {@code /modules} inside the deployed web application.</li>
 *   <li>Every {@code .jar} file found there is scanned; any non-abstract
 *       {@link HttpServlet} subclass is instantiated and registered.</li>
 *   <li>Each servlet is mapped using {@code @WebServlet(urlPatterns)} if the
 *       annotation is present on the class; otherwise it falls back to
 *       {@code /<SimpleClassName-lowercase>}. The servlet name is taken from
 *       {@code @WebServlet(name)} when set, otherwise a hash-based name is
 *       generated.</li>
 * </ol>
 * </p>
 */
@WebListener
public class MondrianModuleServletRegistrar implements ServletContextListener {

    private static final Logger LOGGER =
        LogManager.getLogger(MondrianModuleServletRegistrar.class);

    // -----------------------------------------------------------------------
    // Shared state (accessible by other components if needed)
    // -----------------------------------------------------------------------

    /** Absolute path to the modules directory, set during context init. */
    public static volatile String modulesPath = null;

    /** Class loader that covers all module JARs. */
    public static volatile URLClassLoader modulesLoader = null;

    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------

    /** jar-absolute-path → list of discovered servlet class names. */
    private final Map<String, List<String>> discoveredServletsByJar =
        new LinkedHashMap<String, List<String>>();

    // -----------------------------------------------------------------------
    // ServletContextListener
    // -----------------------------------------------------------------------

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext ctx = event.getServletContext();

        String resolved = ctx.getRealPath("/modules");
        modulesPath = resolved;

        LOGGER.info("MondrianModuleServletRegistrar: modulesPath=" + resolved);

        discoverServlets(resolved);
        registerDiscoveredServlets(ctx);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        closeModulesLoader();
    }

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    /**
     * Scans all JARs in {@code path} for {@link HttpServlet} implementations
     * and caches their class names for later registration.
     *
     * @param path absolute path to the modules directory
     */
    public synchronized void discoverServlets(String path) {
        discoveredServletsByJar.clear();
        closeModulesLoader();

        if (path == null || path.isEmpty()) {
            LOGGER.debug("modulesPath not set — skipping servlet discovery.");
            return;
        }

        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            LOGGER.debug("modulesPath does not exist: " + path);
            return;
        }

        File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LOGGER.debug("No JAR files found in modulesPath: " + path);
            return;
        }

        try {
            URL[] urls = new URL[jars.length];
            for (int i = 0; i < jars.length; i++) {
                urls[i] = jars[i].toURI().toURL();
            }
            modulesLoader = new URLClassLoader(
                urls,
                Thread.currentThread().getContextClassLoader());
        } catch (Exception e) {
            LOGGER.warn("Failed to build class loader for modules: " + e.getMessage(), e);
            return;
        }

        int total = 0;
        for (File jar : jars) {
            try {
                List<String> names = scanJarForServlets(jar);
                if (!names.isEmpty()) {
                    discoveredServletsByJar.put(jar.getAbsolutePath(), names);
                    total += names.size();
                    LOGGER.info("Found " + names.size()
                        + " HttpServlet(s) in " + jar.getName());
                }
            } catch (Exception e) {
                LOGGER.warn("Error scanning JAR for servlets: " + jar.getName(), e);
            }
        }

        if (total > 0) {
            LOGGER.info("Startup scan: found " + total
                + " HttpServlet implementation(s) across " + jars.length + " module(s).");
        } else {
            LOGGER.debug("No HttpServlet implementations found in "
                + jars.length + " module(s).");
        }
    }

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    /**
     * Registers all previously discovered servlets into {@code ctx}.
     * Must be called while {@code ctx} is still being initialized (i.e.
     * from {@link #contextInitialized}).
     *
     * @param ctx the servlet context
     * @return number of servlets successfully registered
     */
    public int registerDiscoveredServlets(ServletContext ctx) {
        if (ctx == null) {
            LOGGER.warn("registerDiscoveredServlets: ServletContext is null — skipping.");
            return 0;
        }

        int count = 0;
        for (Map.Entry<String, List<String>> entry : discoveredServletsByJar.entrySet()) {
            File jar = new File(entry.getKey());
            for (String className : entry.getValue()) {
                try {
                    count += registerServletFromClassName(ctx, className, jar);
                } catch (Exception e) {
                    LOGGER.warn("Failed to register servlet: " + className, e);
                }
            }
        }

        if (count > 0) {
            LOGGER.info("Registered " + count
                + " HttpServlet(s) from module discovery cache.");
        }
        return count;
    }

    /**
     * Instantiates and registers the servlet identified by {@code className}.
     *
     * @return 1 if registered successfully, 0 otherwise
     */
    private int registerServletFromClassName(
            ServletContext ctx,
            String className,
            File jar) throws Exception {

        Class<?> clazz = loadClass(className);
        if (clazz == null || !HttpServlet.class.isAssignableFrom(clazz)) {
            return 0;
        }

        HttpServlet servlet =
            (HttpServlet) clazz.getDeclaredConstructor().newInstance();

        // Prefer @WebServlet annotation values when present.
        WebServlet webServletAnnotation = clazz.getAnnotation(WebServlet.class);

        String servletName;
        String[] urlPatterns;

        if (webServletAnnotation != null) {
            // Use annotation name if non-empty, otherwise fall back to generated name.
            String annotationName = webServletAnnotation.name();
            servletName = (annotationName != null && !annotationName.isEmpty())
                ? annotationName
                : generateServletName(className);

            // urlPatterns() takes priority over value(); use whichever is non-empty.
            String[] annotationPatterns = webServletAnnotation.urlPatterns();
            if (annotationPatterns == null || annotationPatterns.length == 0) {
                annotationPatterns = webServletAnnotation.value();
            }
            urlPatterns = (annotationPatterns != null && annotationPatterns.length > 0)
                ? annotationPatterns
                : new String[]{"/" + clazz.getSimpleName().toLowerCase()};
        } else {
            servletName = generateServletName(className);
            urlPatterns  = new String[]{"/" + clazz.getSimpleName().toLowerCase()};
        }

        // @WebServlet(asyncSupported=...) is only honored by the container for
        // servlets it discovers itself via classpath/web-fragment scanning at
        // deploy time -- it is silently ignored for servlets registered
        // programmatically via ServletContext.addServlet() (Servlet 3.x dynamic
        // registration), which is what this registrar does. Without explicitly
        // propagating it to the ServletRegistration.Dynamic below, any module
        // servlet that calls request.startAsync() (e.g. an SSE transport) fails
        // at request time with "A filter or servlet of the current chain does
        // not support asynchronous operations", even though its own annotation
        // says asyncSupported = true.
        boolean asyncSupported =
            webServletAnnotation != null && webServletAnnotation.asyncSupported();

        registerServlet(ctx, servletName, servlet, asyncSupported, urlPatterns);

        LOGGER.info("Registered HttpServlet from module: "
            + servletName
            + "  class=" + className
            + "  jar=" + jar.getName()
            + "  patterns=" + Arrays.toString(urlPatterns)
            + (webServletAnnotation != null ? "  (from @WebServlet)" : "  (derived from class name)"));

        return 1;
    }

    /**
     * Adds a servlet to the context using Servlet 3.x dynamic registration.
     *
     * @param ctx         the servlet context
     * @param name        unique registration name
     * @param servlet     servlet instance
     * @param urlPatterns one or more URL patterns
     */
    public static void registerServlet(
            ServletContext ctx,
            String name,
            HttpServlet servlet,
            String... urlPatterns) {
        registerServlet(ctx, name, servlet, false, urlPatterns);
    }

    /**
     * Adds a servlet to the context using Servlet 3.x dynamic registration.
     *
     * @param ctx             the servlet context
     * @param name            unique registration name
     * @param servlet         servlet instance
     * @param asyncSupported  whether to mark this registration as async-capable
     *                        (must be true for any servlet that calls
     *                        {@code request.startAsync()}, e.g. an SSE transport)
     * @param urlPatterns     one or more URL patterns
     */
    public static void registerServlet(
            ServletContext ctx,
            String name,
            HttpServlet servlet,
            boolean asyncSupported,
            String... urlPatterns) {

        if (ctx == null) {
            LOGGER.warn("registerServlet: ServletContext is null — cannot register '"
                + name + "'.");
            return;
        }

        ServletRegistration.Dynamic reg;
        try {
            reg = ctx.addServlet(name, servlet);
        } catch (IllegalStateException e) {
            LOGGER.warn(
                "Cannot register servlet '" + name
                + "': context already initialized. "
                + "Dynamic registration must happen inside contextInitialized().");
            return;
        }

        if (reg == null) {
            LOGGER.warn("Servlet '" + name + "' already exists — skipping.");
            return;
        }

        reg.setAsyncSupported(asyncSupported);

        Set<String> conflicts = reg.addMapping(urlPatterns);
        if (!conflicts.isEmpty()) {
            LOGGER.warn("Servlet '" + name
                + "' has conflicting URL mappings: " + conflicts);
        } else {
            LOGGER.info("Servlet '" + name + "' registered at "
                + Arrays.toString(urlPatterns));
        }
    }

    // -----------------------------------------------------------------------
    // JAR scanning helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the fully-qualified class names of all concrete
     * {@link HttpServlet} subclasses found in {@code jar}.
     */
    private List<String> scanJarForServlets(File jar) throws Exception {
        List<String> result = new ArrayList<String>();

        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (!entryName.endsWith(".class") || entryName.contains("$")) {
                    continue;
                }

                String className = entryName.replace('/', '.').replace(".class", "");

                try {
                    Class<?> clazz = loadClass(className);
                    if (clazz != null && isHttpServletImpl(clazz)) {
                        result.add(className);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Could not load class: " + className, e);
                    }
                } catch (Exception e) {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Error inspecting class: " + className, e);
                    }
                }
            }
        }
        return result;
    }

    private Class<?> loadClass(String className) throws ClassNotFoundException {
        if (modulesLoader != null) {
            return modulesLoader.loadClass(className);
        }
        return Class.forName(className);
    }

    /**
     * Returns {@code true} for concrete, non-interface subclasses of
     * {@link HttpServlet} (excluding {@link HttpServlet} itself).
     */
    private static boolean isHttpServletImpl(Class<?> clazz) {
        if (!HttpServlet.class.isAssignableFrom(clazz)) return false;
        if (clazz.equals(HttpServlet.class))             return false;
        if (clazz.isInterface())                          return false;
        if (java.lang.reflect.Modifier.isAbstract(
                clazz.getModifiers()))                    return false;
        return true;
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /**
     * Builds a unique servlet name from a fully-qualified class name.
     * Example: {@code com.acme.FooServlet} → {@code FooServlet_1234}.
     */
    public static String generateServletName(String className) {
        String simple = className.contains(".")
            ? className.substring(className.lastIndexOf('.') + 1)
            : className;
        int hash = Math.abs(className.hashCode()) % 10000;
        return simple + "_" + hash;
    }

    private static void closeModulesLoader() {
        URLClassLoader loader = modulesLoader;
        modulesLoader = null;
        if (loader != null) {
            try {
                loader.close();
            } catch (Exception e) {
                LOGGER.warn("Could not close modules class loader.", e);
            }
        }
    }
}
