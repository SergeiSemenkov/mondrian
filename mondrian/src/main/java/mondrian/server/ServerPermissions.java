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

import mondrian.olap.MondrianDef;
import mondrian.rolap.RolapConnection;
import mondrian.rolap.RolapSchema;
import mondrian.xmla.DataSourcesConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eigenbase.xom.DOMWrapper;
import org.eigenbase.xom.Parser;
import org.eigenbase.xom.XOMUtil;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorization for the server's <em>administrative</em> operations: editing
 * schema files, editing the catalog registry (datasources.xml), reading log
 * files and installing license files.
 *
 * <p>These are granted by the same schema-level {@code <Role>} elements that
 * already control data access, through a {@code <ServerGrant>} child. A user
 * holds a capability if any role they are a member of (matched by
 * {@code <RoleMember>} against their user name or one of their groups) grants
 * it. Callers with no identity, and callers matching no role, are resolved
 * against the role named by the schema's {@code defaultRole} attribute.</p>
 *
 * <p>For backwards compatibility, a schema in which no role declares a
 * {@code <ServerGrant>} at all is treated as <em>unconfigured</em>: nothing is
 * enforced against it. Enforcement begins as soon as the first
 * {@code <ServerGrant>} appears.</p>
 */
public class ServerPermissions {

    private static final Logger LOGGER =
        LogManager.getLogger(ServerPermissions.class);

    private ServerPermissions() {
    }

    /** An administrative operation that a role may be granted. */
    public enum Capability {
        SCHEMA_READ("schema", "read", "write"),
        SCHEMA_WRITE("schema", "write"),
        DATABASE_READ("database", "read", "manage"),
        DATABASE_MANAGE("database", "manage"),
        LOGS_READ("logs", "read"),
        LICENSE_MANAGE("license", "manage");

        private final String attribute;
        private final String[] satisfyingValues;

        Capability(String attribute, String... satisfyingValues) {
            this.attribute = attribute;
            this.satisfyingValues = satisfyingValues;
        }

        /** Human-readable form used in error messages, e.g. {@code schema="write"}. */
        public String describe() {
            return attribute + "=\"" + satisfyingValues[0] + "\"";
        }

        boolean satisfiedBy(MondrianDef.ServerGrant grant) {
            final String value = valueOf(grant);
            if (value == null) {
                return false;
            }
            for (String satisfying : satisfyingValues) {
                if (satisfying.equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }

        private String valueOf(MondrianDef.ServerGrant grant) {
            if (attribute.equals("schema")) {
                return grant.schema;
            } else if (attribute.equals("database")) {
                return grant.database;
            } else if (attribute.equals("logs")) {
                return grant.logs;
            } else {
                return grant.license;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------

    /**
     * The caller, as far as role matching is concerned: a user name plus a way
     * to test membership of a named group. Membership is tested by name rather
     * than by enumerating groups so that the container's own realm can answer
     * it ({@link HttpServletRequest#isUserInRole}) without this class needing
     * to know how groups are stored.
     */
    public interface Identity {
        /** The authenticated user name, or null if the caller is anonymous. */
        String getUser();

        /**
         * Whether this caller is the user named {@code name}, or belongs to a
         * group of that name. Comparison is case-insensitive.
         */
        boolean isMember(String name);
    }

    /** An identity with no user name and no groups. Matches no role member. */
    public static final Identity ANONYMOUS = new Identity() {
        public String getUser() {
            return null;
        }

        public boolean isMember(String name) {
            return false;
        }
    };

    /**
     * Builds an identity from an explicit user name and group list, as XML/A
     * requests carry it (see
     * {@link mondrian.xmla.XmlaRequest#getAuthenticatedUser}).
     *
     * @param user Authenticated user name; may be null
     * @param groups Group names; may be null
     */
    public static Identity identity(String user, String[] groups) {
        final List<String> names = new ArrayList<String>();
        if (user != null && !user.trim().isEmpty()) {
            names.add(user.trim());
        }
        if (groups != null) {
            for (String group : groups) {
                if (group != null && !group.trim().isEmpty()) {
                    names.add(group.trim());
                }
            }
        }
        if (names.isEmpty()) {
            return ANONYMOUS;
        }
        final String userName = user;
        return new Identity() {
            public String getUser() {
                return userName;
            }

            public boolean isMember(String name) {
                for (String candidate : names) {
                    if (candidate.equalsIgnoreCase(name)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * System property that opts in to trusting the
     * {@code X-Forwarded-User}/{@code X-Forwarded-Groups} request headers.
     *
     * <p>Off by default, and deliberately so: those headers are only as
     * trustworthy as the proxy in front of this server, which must
     * <em>overwrite</em> rather than pass through any copy a client supplies.
     * Where nothing strips them, any client could set them and name itself any
     * user it likes, which would make every grant below meaningless. Set this
     * to {@code true} only once such a proxy is in place.</p>
     *
     * <p>This is the servlet-side equivalent of choosing to register
     * {@code ForwardedAuthenticationXmlaRequestCallback} for XML/A: both are
     * opt-in, so the two paths trust the same things.</p>
     */
    public static final String TRUST_FORWARDED_HEADERS_PROPERTY =
        "emondrian.security.trustForwardedUser";

    /**
     * Builds an identity from a plain servlet request, for the endpoints that
     * are not XML/A ({@code /logs}, the license module). The container's own
     * authentication is used; the {@code X-Forwarded-*} headers are consulted
     * only when {@link #TRUST_FORWARDED_HEADERS_PROPERTY} says a trusted proxy
     * sets them.
     */
    public static Identity identity(HttpServletRequest request) {
        if (request == null) {
            return ANONYMOUS;
        }
        final Principal principal = request.getUserPrincipal();
        if (principal != null) {
            final String userName = principal.getName();
            return new Identity() {
                public String getUser() {
                    return userName;
                }

                public boolean isMember(String name) {
                    return (userName != null && userName.equalsIgnoreCase(name))
                        || request.isUserInRole(name);
                }
            };
        }
        if (!trustForwardedHeaders()) {
            return ANONYMOUS;
        }
        return identity(
            request.getHeader("X-Forwarded-User"),
            splitGroups(request.getHeader("X-Forwarded-Groups")));
    }

    /** Whether the X-Forwarded-* headers may be believed. */
    private static boolean trustForwardedHeaders() {
        return Boolean.parseBoolean(
            System.getProperty(TRUST_FORWARDED_HEADERS_PROPERTY, "false"));
    }

    /** Splits a comma-separated group header into names. Null-safe. */
    public static String[] splitGroups(String header) {
        if (header == null || header.trim().isEmpty()) {
            return new String[0];
        }
        final String[] parts = header.split(",");
        final List<String> groups = new ArrayList<String>();
        for (String part : parts) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                groups.add(trimmed);
            }
        }
        return groups.toArray(new String[groups.size()]);
    }

    // -----------------------------------------------------------------------
    // Role matching
    // -----------------------------------------------------------------------

    /**
     * Returns the roles of {@code xmlSchema} that {@code identity} is a member
     * of, by matching {@code <RoleMember name="..."/>} against the caller's
     * user name and groups.
     *
     * <p>If the caller matches no role, the schema's {@code defaultRole} is
     * returned instead, so that anonymous and unrecognized callers are governed
     * by whatever that role permits. Returns an empty list if there is no match
     * and no default role.</p>
     *
     * <p>This is the matching that {@code XmlaHandler} performs when choosing a
     * connection's data roles; it lives here so that data access and
     * administrative access resolve identically.</p>
     */
    public static List<MondrianDef.Role> matchRoles(
        MondrianDef.Schema xmlSchema,
        Identity identity)
    {
        return matchRoles(xmlSchema, identity, true);
    }

    /**
     * As {@link #matchRoles(MondrianDef.Schema, Identity)}, but with control
     * over the {@code defaultRole} fallback.
     *
     * <p>Data access passes {@code false}: the engine already substitutes the
     * schema's default role for a connection that names none
     * ({@link mondrian.rolap.RolapSchema#getDefaultRole}), so returning it here
     * as well would be redundant. Administrative access passes {@code true},
     * since there is no such substitution behind it.</p>
     */
    public static List<MondrianDef.Role> matchRoles(
        MondrianDef.Schema xmlSchema,
        Identity identity,
        boolean applyDefaultRole)
    {
        final List<MondrianDef.Role> matched = new ArrayList<MondrianDef.Role>();
        if (xmlSchema == null || xmlSchema.roles == null) {
            return matched;
        }
        if (identity != null) {
            for (MondrianDef.Role role : xmlSchema.roles) {
                if (role.members == null) {
                    continue;
                }
                for (MondrianDef.RoleMember member : role.members) {
                    if (member.name != null
                        && identity.isMember(member.name.trim()))
                    {
                        matched.add(role);
                        break;
                    }
                }
            }
        }
        if (applyDefaultRole
            && matched.isEmpty()
            && xmlSchema.defaultRole != null)
        {
            for (MondrianDef.Role role : xmlSchema.roles) {
                if (xmlSchema.defaultRole.equals(role.name)) {
                    matched.add(role);
                    break;
                }
            }
        }
        return matched;
    }

    /** Names of the roles {@code identity} resolves to, for {@code XmlaHandler}. */
    public static List<String> matchRoleNames(
        MondrianDef.Schema xmlSchema,
        Identity identity,
        boolean applyDefaultRole)
    {
        final List<String> names = new ArrayList<String>();
        for (MondrianDef.Role role
            : matchRoles(xmlSchema, identity, applyDefaultRole))
        {
            names.add(role.name);
        }
        return names;
    }

    // -----------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------

    /** The outcome of evaluating one schema. */
    private enum Decision {
        /** A matching role grants the capability. */
        GRANTED,
        /** The schema uses ServerGrant, but not in a way that grants this. */
        DENIED,
        /** The schema declares no ServerGrant at all; nothing to enforce. */
        UNCONFIGURED
    }

    private static Decision decide(
        MondrianDef.Schema xmlSchema,
        Identity identity,
        Capability capability)
    {
        if (xmlSchema == null || xmlSchema.roles == null) {
            return Decision.UNCONFIGURED;
        }
        boolean anyServerGrant = false;
        for (MondrianDef.Role role : xmlSchema.roles) {
            if (role.serverGrant != null) {
                anyServerGrant = true;
                break;
            }
        }
        if (!anyServerGrant) {
            return Decision.UNCONFIGURED;
        }
        for (MondrianDef.Role role : matchRoles(xmlSchema, identity)) {
            if (role.serverGrant != null
                && capability.satisfiedBy(role.serverGrant))
            {
                return Decision.GRANTED;
            }
        }
        return Decision.DENIED;
    }

    /**
     * Whether {@code identity} holds {@code capability} according to a single
     * schema. Used for the per-catalog capabilities (reading and writing that
     * catalog's schema XML).
     */
    public static boolean isGranted(
        MondrianDef.Schema xmlSchema,
        Identity identity,
        Capability capability)
    {
        return decide(xmlSchema, identity, capability) != Decision.DENIED;
    }

    /** As {@link #isGranted(MondrianDef.Schema, Identity, Capability)}. */
    public static boolean isGranted(
        RolapSchema schema,
        Identity identity,
        Capability capability)
    {
        return schema == null
            || isGranted(schema.getXMLSchema(), identity, capability);
    }

    /**
     * Whether {@code identity} holds {@code capability} according to any
     * catalog known to {@code repository}. Used for the server-wide
     * capabilities, which have no single schema to consult: the catalog
     * registry, the log files and the license files all sit above any one
     * catalog.
     */
    public static boolean isGrantedByAnyCatalog(
        Repository repository,
        RolapConnection connection,
        Identity identity,
        Capability capability)
    {
        if (repository == null) {
            return true;
        }
        boolean anyConfigured = false;
        try {
            for (String databaseName
                : repository.getDatabaseNames(connection))
            {
                for (String catalogName
                    : repository.getCatalogNames(connection, databaseName))
                {
                    final Map<String, RolapSchema> schemas =
                        repository.getRolapSchemas(
                            connection, databaseName, catalogName);
                    for (RolapSchema schema : schemas.values()) {
                        switch (decide(
                            schema.getXMLSchema(), identity, capability))
                        {
                        case GRANTED:
                            return true;
                        case DENIED:
                            anyConfigured = true;
                            break;
                        default:
                            break;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // A catalog that cannot be loaded cannot grant anything. Deciding
            // on the catalogs that did load is the safe reading; failing open
            // on a broken catalog would be a way around the check.
            LOGGER.warn(
                "ServerPermissions: could not enumerate every catalog while "
                + "checking " + capability.describe(), e);
        }
        return !anyConfigured;
    }

    /**
     * Whether {@code identity} holds {@code capability} according to any
     * catalog listed in the web application's {@code /WEB-INF/datasources.xml}.
     *
     * <p>This is the variant for endpoints that have no OLAP connection to work
     * from — {@code /logs} and the license servlet. It reads the role
     * declarations straight out of the schema files rather than loading the
     * catalogs, so it answers the same way on a server that has not served a
     * query yet as on a warm one.</p>
     */
    public static boolean isGrantedByAnyCatalog(
        ServletContext context,
        Identity identity,
        Capability capability)
    {
        boolean anyConfigured = false;
        for (MondrianDef.Schema xmlSchema : schemasOf(context)) {
            switch (decide(xmlSchema, identity, capability)) {
            case GRANTED:
                return true;
            case DENIED:
                anyConfigured = true;
                break;
            default:
                break;
            }
        }
        return !anyConfigured;
    }

    // -----------------------------------------------------------------------
    // Reading schemas without a connection
    // -----------------------------------------------------------------------

    /** Cache of parsed schema files, keyed by path; invalidated by mtime/size. */
    private static final Map<String, CachedSchema> SCHEMA_CACHE =
        new ConcurrentHashMap<String, CachedSchema>();

    private static class CachedSchema {
        final long lastModified;
        final long length;
        final MondrianDef.Schema schema;

        CachedSchema(long lastModified, long length, MondrianDef.Schema schema) {
            this.lastModified = lastModified;
            this.length = length;
            this.schema = schema;
        }
    }

    /**
     * Parses every schema file named by {@code /WEB-INF/datasources.xml}. Only
     * the role declarations are of interest, so the files are parsed as XML
     * rather than loaded as catalogs: no SQL connection is opened and no
     * validation is performed, which keeps this usable from a servlet that has
     * no OLAP connection of its own.
     */
    private static List<MondrianDef.Schema> schemasOf(ServletContext context) {
        final List<MondrianDef.Schema> schemas =
            new ArrayList<MondrianDef.Schema>();
        if (context == null) {
            return schemas;
        }
        final DataSourcesConfig.DataSources dataSources =
            dataSourcesOf(context);
        if (dataSources == null || dataSources.dataSources == null) {
            return schemas;
        }
        for (DataSourcesConfig.DataSource dataSource
            : dataSources.dataSources)
        {
            if (dataSource.catalogs == null
                || dataSource.catalogs.catalogs == null)
            {
                continue;
            }
            for (DataSourcesConfig.Catalog catalog
                : dataSource.catalogs.catalogs)
            {
                final MondrianDef.Schema schema =
                    schemaOf(context, catalog.definition);
                if (schema != null) {
                    schemas.add(schema);
                }
            }
        }
        return schemas;
    }

    private static DataSourcesConfig.DataSources dataSourcesOf(
        ServletContext context)
    {
        InputStream in = null;
        try {
            in = context.getResourceAsStream("/WEB-INF/datasources.xml");
            if (in == null) {
                return null;
            }
            final Parser parser = XOMUtil.createDefaultParser();
            final DOMWrapper def = parser.parse(in);
            return new DataSourcesConfig.DataSources(def);
        } catch (Exception e) {
            LOGGER.warn(
                "ServerPermissions: could not read /WEB-INF/datasources.xml", e);
            return null;
        } finally {
            close(in);
        }
    }

    private static MondrianDef.Schema schemaOf(
        ServletContext context,
        String definition)
    {
        if (definition == null || definition.trim().isEmpty()) {
            return null;
        }
        String path = definition.trim();
        // Catalog definitions are webapp-relative ("/WEB-INF/schema/X.xml") in
        // the deployments this serves, but an absolute file path is accepted
        // too, as DriverManager-style connect strings use one.
        if (path.startsWith("/")) {
            final String realPath = context.getRealPath(path);
            if (realPath != null) {
                path = realPath;
            }
        }
        final File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        final CachedSchema cached = SCHEMA_CACHE.get(file.getPath());
        if (cached != null
            && cached.lastModified == file.lastModified()
            && cached.length == file.length())
        {
            return cached.schema;
        }
        InputStream in = null;
        try {
            final long lastModified = file.lastModified();
            final long length = file.length();
            in = new FileInputStream(file);
            final Parser parser = XOMUtil.createDefaultParser();
            final MondrianDef.Schema schema =
                new MondrianDef.Schema(parser.parse(in));
            SCHEMA_CACHE.put(
                file.getPath(),
                new CachedSchema(lastModified, length, schema));
            return schema;
        } catch (Exception e) {
            LOGGER.warn(
                "ServerPermissions: could not read schema file " + path, e);
            return null;
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * The message to report when a capability is missing. Names the capability
     * in the form it is declared in the schema, so that whoever sees it knows
     * what to grant.
     */
    public static String denialMessage(Identity identity, Capability capability) {
        final String who = identity == null || identity.getUser() == null
            ? "The anonymous user"
            : "User '" + identity.getUser() + "'";
        return who
            + " is not granted "
            + capability.describe()
            + " by any role. Add a <ServerGrant "
            + capability.describe()
            + "/> to a <Role> the user is a member of, or to the schema's "
            + "defaultRole.";
    }
}

// End ServerPermissions.java
