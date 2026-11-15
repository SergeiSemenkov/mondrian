/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Sergei Semenkov
// All Rights Reserved.
*/
package mondrian.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Guards the {@code X-Forwarded-*} identity headers behind an authentication
 * gateway ({@link ServerPermissions#TRUST_FORWARDED_HEADERS_PROPERTY}).
 *
 * <p>With trust on, a request that names a caller in those headers but does
 * not carry the gateway's secret ({@link ServerPermissions#FORWARDED_SECRET_HEADER},
 * compared in constant time with {@link ServerPermissions#FORWARDED_SECRET_ENV})
 * is refused with 403: it came around the gateway, from the host or another
 * container. A request without them passes as an anonymous one, as before
 * (health checks, the Dashboard's own document checks). A request with the
 * right secret is marked trusted ({@link ServerPermissions#FORWARDED_TRUSTED_ATTRIBUTE}).
 *
 * <p>The secret header itself is hidden from everything after this filter,
 * trusted or not, so no servlet, log or module can read or repeat it.
 *
 * <p>Registered for every path by {@code MondrianModuleServletRegistrar}, ahead
 * of the filters declared in {@code web.xml}. Without trust it only hides the
 * secret header.
 */
public class ForwardedHeadersFilter implements Filter {

    private static final Logger LOGGER =
        LogManager.getLogger(ForwardedHeadersFilter.class);

    /** At most one warning a minute, so a probe cannot flood the log. */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final AtomicLong lastWarning = new AtomicLong();

    @Override
    public void doFilter(
        ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException
    {
        if (!(request instanceof HttpServletRequest)
            || !(response instanceof HttpServletResponse))
        {
            chain.doFilter(request, response);
            return;
        }
        final HttpServletRequest http = (HttpServletRequest) request;
        if (ServerPermissions.trustForwardedHeaders()) {
            final boolean trusted = ServerPermissions.forwardedSecretConfigured()
                && ServerPermissions.secretMatches(
                    http.getHeader(ServerPermissions.FORWARDED_SECRET_HEADER));
            if (trusted) {
                http.setAttribute(
                    ServerPermissions.FORWARDED_TRUSTED_ATTRIBUTE, Boolean.TRUE);
            } else {
                http.removeAttribute(ServerPermissions.FORWARDED_TRUSTED_ATTRIBUTE);
                if (ServerPermissions.carriesForwardedIdentity(http)) {
                    warn(http);
                    final HttpServletResponse out = (HttpServletResponse) response;
                    out.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    out.setContentType("text/plain; charset=UTF-8");
                    out.getWriter().write("Forwarded identity headers are accepted only from"
                        + " this server's authentication gateway.\n");
                    return;
                }
            }
        }
        chain.doFilter(new WithoutSecret(http), response);
    }

    private void warn(HttpServletRequest request) {
        final long now = System.currentTimeMillis();
        final long last = lastWarning.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarning.compareAndSet(last, now)) {
            LOGGER.warn("Refused a request naming a caller in X-Forwarded-* headers without the"
                + " gateway's secret: " + request.getMethod() + " " + request.getRequestURI()
                + " from " + request.getRemoteAddr()
                + (ServerPermissions.forwardedSecretConfigured() ? ""
                    : " (" + ServerPermissions.FORWARDED_SECRET_ENV + " is not set)")
                + ". Further refusals in the next minute are not logged.");
        }
    }

    /** The request without the secret header, whatever its spelling. */
    static final class WithoutSecret extends HttpServletRequestWrapper {
        WithoutSecret(HttpServletRequest request) {
            super(request);
        }

        private static boolean isSecret(String name) {
            return name != null
                && name.equalsIgnoreCase(ServerPermissions.FORWARDED_SECRET_HEADER);
        }

        @Override
        public String getHeader(String name) {
            return isSecret(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isSecret(name)
                ? Collections.<String>emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            final List<String> names = new ArrayList<String>();
            final Enumeration<String> all = super.getHeaderNames();
            while (all != null && all.hasMoreElements()) {
                final String name = all.nextElement();
                if (!isSecret(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }
    }
}
