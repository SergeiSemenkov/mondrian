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
import mondrian.olap.MondrianProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorization for the server's <em>administrative</em> operations: editing
 * schema files, editing the catalog registry (datasources.xml), reading log
 * files and installing license files.
 *
 * <p>These are granted at two levels, because they have two different scopes:</p>
 *
 * <ul>
 * <li><b>Reading and writing one catalog's schema XML</b> is scoped to that
 * catalog, and is granted by the schema's own roles, with a
 * {@code schemaAccess} attribute on {@code <Role>}.</li>
 *
 * <li><b>Reading logs, editing datasources.xml and installing licenses</b> are
 * server-wide, and are configured in {@code mondrian.properties} (or as
 * {@code -Dmondrian.security.*} system properties, which
 * {@link mondrian.olap.MondrianPropertiesBase#populate} copies in), under
 * {@code mondrian.security.role.<role>.<members|schema|database|logs|license>}
 * plus {@code mondrian.security.defaultRole}.</li>
 * </ul>
 *
 * <p>The split is not cosmetic. A schema file is itself editable through the
 * server -- by {@code Alter ObjectType=Schema} and by the MCP
 * {@code save_schema} tool -- so a server-wide right declared in one would be a
 * right its holder could grant themselves, and (before this split) could grant
 * to everyone by writing it onto the schema's {@code defaultRole}. Server-wide
 * rights therefore live only where no endpoint can write them.</p>
 *
 * <p>A user holds a capability if any role they are a member of grants it.
 * Callers with no identity, and callers matching no role, are resolved against
 * the default role -- {@code mondrian.security.defaultRole} at server level, the
 * schema's {@code defaultRole} attribute at catalog level.</p>
 *
 * <p>For backwards compatibility, an unconfigured capability is not enforced:
 * with no {@code mondrian.security.role.*} property set, the server-wide
 * capabilities are open, and with no role declaring {@code schemaAccess} at
 * either level, schema access is open. Enforcement begins as soon as the first
 * grant appears.</p>
 */
public class ServerPermissions {

    private static final Logger LOGGER =
        LogManager.getLogger(ServerPermissions.class);

    private ServerPermissions() {
    }

    /** An administrative operation that a role may be granted. */
    public enum Capability {
        SCHEMA_READ("schema", true, "read", "write"),
        SCHEMA_WRITE("schema", true, "write"),
        DATABASE_READ("database", false, "read", "manage"),
        DATABASE_MANAGE("database", false, "manage"),
        LOGS_READ("logs", false, "read"),
        LICENSE_MANAGE("license", false, "manage");

        private final String attribute;
        private final boolean catalogScoped;
        private final String[] satisfyingValues;

        Capability(
            String attribute,
            boolean catalogScoped,
            String... satisfyingValues)
        {
            this.attribute = attribute;
            this.catalogScoped = catalogScoped;
            this.satisfyingValues = satisfyingValues;
        }

        /** The property/attribute name this capability is granted under. */
        public String getAttribute() {
            return attribute;
        }

        /** The least value that satisfies this capability. */
        public String getRequiredValue() {
            return satisfyingValues[0];
        }

        /**
         * Whether this capability is scoped to a single catalog (and so can be
         * granted by a schema), rather than server-wide.
         */
        public boolean isCatalogScoped() {
            return catalogScoped;
        }

        /** Human-readable form used in error messages, e.g. {@code schema="write"}. */
        public String describe() {
            return attribute + "=\"" + satisfyingValues[0] + "\"";
        }

        /** Whether a granted value, as written in a schema or a property, suffices. */
        boolean satisfiedBy(String value) {
            if (value == null) {
                return false;
            }
            final String trimmed = value.trim();
            for (String satisfying : satisfyingValues) {
                if (satisfying.equalsIgnoreCase(trimmed)) {
                    return true;
                }
            }
            return false;
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
    // Server-level configuration
    // -----------------------------------------------------------------------

    /** Prefix of the properties that declare a server-level role. */
    public static final String ROLE_PROPERTY_PREFIX = "mondrian.security.role.";

    /** Property naming the role that callers matching no other role get. */
    public static final String DEFAULT_ROLE_PROPERTY =
        "mondrian.security.defaultRole";

    /** Property suffix listing a server-level role's members. */
    private static final String MEMBERS_SUFFIX = "members";

    private static final Set<String> KNOWN_SUFFIXES = Set.of(
        MEMBERS_SUFFIX, "schema", "database", "logs", "license");

    /**
     * Keys already reported as malformed. A permission check runs per request,
     * so without this a single typo would be logged forever.
     */
    private static final Set<String> REPORTED_BAD_KEYS =
        ConcurrentHashMap.newKeySet();

    /** One server-level role: its members and the values it grants. */
    private static class ServerRole {
        final String name;
        final List<String> members = new ArrayList<String>();
        final Map<String, String> grants = new LinkedHashMap<String, String>();

        ServerRole(String name) {
            this.name = name;
        }
    }

    /**
     * Reads the {@code mondrian.security.*} properties.
     *
     * <p>Read afresh on every check rather than cached: the properties are
     * loaded once by {@link MondrianProperties} and the checks happen only on
     * administrative requests, so a cache here would buy nothing and could
     * answer with a stale rule, which is the worst failure mode a permission
     * check has.</p>
     */
    private static Map<String, ServerRole> serverRoles() {
        final Map<String, ServerRole> roles =
            new LinkedHashMap<String, ServerRole>();
        final MondrianProperties properties = MondrianProperties.instance();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(ROLE_PROPERTY_PREFIX)) {
                continue;
            }
            final String remainder =
                key.substring(ROLE_PROPERTY_PREFIX.length());
            final int dot = remainder.lastIndexOf('.');
            final String roleName = dot < 0 ? "" : remainder.substring(0, dot);
            final String suffix = dot < 0 ? "" : remainder.substring(dot + 1);
            if (roleName.isEmpty()
                || roleName.indexOf('.') >= 0
                || !KNOWN_SUFFIXES.contains(suffix))
            {
                if (REPORTED_BAD_KEYS.add(key)) {
                    LOGGER.error(
                        "ServerPermissions: ignoring property '" + key
                        + "'. Server-level grants are declared as "
                        + ROLE_PROPERTY_PREFIX + "<role>.<"
                        + String.join("|", KNOWN_SUFFIXES)
                        + ">, and <role> may not contain a dot.");
                }
                continue;
            }
            final String value = properties.getProperty(key);
            ServerRole role = roles.get(roleName);
            if (role == null) {
                role = new ServerRole(roleName);
                roles.put(roleName, role);
            }
            if (MEMBERS_SUFFIX.equals(suffix)) {
                for (String member : splitGroups(value)) {
                    role.members.add(member);
                }
            } else {
                role.grants.put(suffix, value);
            }
        }
        return roles;
    }

    /**
     * The server-level roles {@code identity} is a member of, falling back to
     * the role named by {@link #DEFAULT_ROLE_PROPERTY} when it matches none --
     * so that anonymous and unrecognized callers are governed by whatever that
     * role permits, exactly as a schema's {@code defaultRole} governs them for
     * data.
     */
    private static List<ServerRole> matchServerRoles(
        Map<String, ServerRole> roles,
        Identity identity)
    {
        final List<ServerRole> matched = new ArrayList<ServerRole>();
        if (identity != null) {
            for (ServerRole role : roles.values()) {
                for (String member : role.members) {
                    if (identity.isMember(member)) {
                        matched.add(role);
                        break;
                    }
                }
            }
        }
        if (matched.isEmpty()) {
            final String defaultRole = MondrianProperties.instance()
                .getProperty(DEFAULT_ROLE_PROPERTY);
            if (defaultRole != null && !defaultRole.trim().isEmpty()) {
                final ServerRole role = roles.get(defaultRole.trim());
                if (role != null) {
                    matched.add(role);
                }
            }
        }
        return matched;
    }

    // -----------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------

    /** The outcome of evaluating one level. */
    private enum Decision {
        /** A matching role grants the capability. */
        GRANTED,
        /** The capability is configured at this level, but not granted. */
        DENIED,
        /** Nothing at this level speaks to the capability. */
        UNCONFIGURED
    }

    /**
     * Decides {@code capability} from the {@code mondrian.security.*}
     * properties.
     *
     * <p>The server-wide capabilities are enforced as soon as any server-level
     * role is declared at all: they are the operations that manage the server,
     * so once an operator has said who administers it, everyone else is not an
     * administrator. {@code schema} is different -- it also has a per-catalog
     * level -- so it is enforced here only when some role actually declares
     * it.</p>
     */
    private static Decision decideAtServerLevel(
        Identity identity,
        Capability capability)
    {
        final Map<String, ServerRole> roles = serverRoles();
        if (roles.isEmpty()) {
            return Decision.UNCONFIGURED;
        }
        if (capability.isCatalogScoped()) {
            boolean declared = false;
            for (ServerRole role : roles.values()) {
                if (role.grants.containsKey(capability.getAttribute())) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                return Decision.UNCONFIGURED;
            }
        }
        for (ServerRole role : matchServerRoles(roles, identity)) {
            if (capability.satisfiedBy(
                    role.grants.get(capability.getAttribute())))
            {
                return Decision.GRANTED;
            }
        }
        return Decision.DENIED;
    }

    /**
     * Whether {@code identity} holds a server-wide {@code capability}: reading
     * logs, editing the catalog registry, installing licenses.
     */
    public static boolean isGrantedAtServerLevel(
        Identity identity,
        Capability capability)
    {
        return decideAtServerLevel(identity, capability) != Decision.DENIED;
    }

    // -----------------------------------------------------------------------
    // Role matching within a schema
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

    /**
     * Decides {@code capability} from one schema's own roles, i.e. from the
     * {@code schemaAccess} attribute of {@code <Role>}. Only the catalog-scoped
     * capabilities can be granted this way.
     */
    private static Decision decideAtCatalogLevel(
        MondrianDef.Schema xmlSchema,
        Identity identity,
        Capability capability)
    {
        if (!capability.isCatalogScoped()
            || xmlSchema == null
            || xmlSchema.roles == null)
        {
            return Decision.UNCONFIGURED;
        }
        boolean declared = false;
        for (MondrianDef.Role role : xmlSchema.roles) {
            if (role.schemaAccess != null) {
                declared = true;
                break;
            }
        }
        if (!declared) {
            return Decision.UNCONFIGURED;
        }
        for (MondrianDef.Role role : matchRoles(xmlSchema, identity)) {
            if (capability.satisfiedBy(role.schemaAccess)) {
                return Decision.GRANTED;
            }
        }
        return Decision.DENIED;
    }

    /**
     * Whether {@code identity} holds {@code capability} on the catalog described
     * by {@code xmlSchema}.
     *
     * <p>The two levels compose the way roles already do: the most permissive
     * answer wins, so a schema may grant access the server-level configuration
     * does not, and vice versa. Access is left unenforced only when
     * <em>neither</em> level configures the capability -- which means a
     * server-level {@code schema} grant also decides catalogs whose own schema
     * says nothing, including ones added later.</p>
     */
    public static boolean isGranted(
        MondrianDef.Schema xmlSchema,
        Identity identity,
        Capability capability)
    {
        final Decision catalog =
            decideAtCatalogLevel(xmlSchema, identity, capability);
        if (catalog == Decision.GRANTED) {
            return true;
        }
        final Decision server = decideAtServerLevel(identity, capability);
        if (server == Decision.GRANTED) {
            return true;
        }
        return catalog == Decision.UNCONFIGURED
            && server == Decision.UNCONFIGURED;
    }

    /** As {@link #isGranted(MondrianDef.Schema, Identity, Capability)}. */
    public static boolean isGranted(
        RolapSchema schema,
        Identity identity,
        Capability capability)
    {
        return isGranted(
            schema == null ? null : schema.getXMLSchema(),
            identity,
            capability);
    }

    /**
     * Whether {@code identity} holds {@code capability} on the catalog named
     * {@code catalogName} in the web application's
     * {@code /WEB-INF/datasources.xml}.
     *
     * <p>This is the variant for endpoints that have no OLAP connection to work
     * from -- the MCP schema tools. It reads the role declarations straight out
     * of the schema file rather than loading the catalog, so it answers the same
     * way on a server that has not served a query yet as on a warm one.</p>
     *
     * <p>If the schema file cannot be read, the server-level configuration
     * decides alone: an unreadable file must not silently grant what it might
     * have denied, but neither should it lock an administrator out of the very
     * tool they would use to repair it.</p>
     */
    public static boolean isGrantedForCatalog(
        ServletContext context,
        String catalogName,
        Identity identity,
        Capability capability)
    {
        final MondrianDef.Schema xmlSchema = schemaOfCatalog(context, catalogName);
        if (xmlSchema == null) {
            LOGGER.warn(
                "ServerPermissions: no readable schema for catalog '"
                + catalogName + "'; deciding " + capability.describe()
                + " from the server-level configuration alone.");
            return isGrantedAtServerLevel(identity, capability);
        }
        return isGranted(xmlSchema, identity, capability);
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
     * Parses the schema file that {@code /WEB-INF/datasources.xml} names for
     * {@code catalogName}. Only the role declarations are of interest, so the
     * file is parsed as XML rather than loaded as a catalog: no SQL connection
     * is opened and no validation is performed, which keeps this usable from a
     * servlet that has no OLAP connection of its own.
     */
    private static MondrianDef.Schema schemaOfCatalog(
        ServletContext context,
        String catalogName)
    {
        if (context == null || catalogName == null) {
            return null;
        }
        final DataSourcesConfig.DataSources dataSources =
            dataSourcesOf(context);
        if (dataSources == null || dataSources.dataSources == null) {
            return null;
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
                if (catalogName.equals(catalog.name)) {
                    return schemaOf(context, catalog.definition);
                }
            }
        }
        return null;
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
     * in the form it is declared in, so that whoever sees it knows what to
     * grant -- and, for the catalog-scoped one, both places it can be granted.
     */
    public static String denialMessage(Identity identity, Capability capability) {
        final String who = identity == null || identity.getUser() == null
            ? "The anonymous user"
            : "User '" + identity.getUser() + "'";
        final StringBuilder buf = new StringBuilder();
        buf.append(who)
            .append(" is not granted ")
            .append(capability.describe())
            .append(" by any role. ");
        if (capability.isCatalogScoped()) {
            buf.append("Add schemaAccess=\"")
                .append(capability.getRequiredValue())
                .append("\" to a <Role> the user is a member of, or to the")
                .append(" schema's defaultRole; or grant it for every catalog")
                .append(" with ")
                .append(ROLE_PROPERTY_PREFIX)
                .append("<role>.")
                .append(capability.getAttribute())
                .append("=")
                .append(capability.getRequiredValue())
                .append(" in mondrian.properties.");
        } else {
            buf.append("This is a server-wide capability: set ")
                .append(ROLE_PROPERTY_PREFIX)
                .append("<role>.")
                .append(capability.getAttribute())
                .append("=")
                .append(capability.getRequiredValue())
                .append(" in mondrian.properties, and list the user or one of")
                .append(" their groups in ")
                .append(ROLE_PROPERTY_PREFIX)
                .append("<role>.")
                .append(MEMBERS_SUFFIX)
                .append(" (or name that role in ")
                .append(DEFAULT_ROLE_PROPERTY)
                .append("). It cannot be granted in a schema file, which is")
                .append(" itself editable through the server.");
        }
        return buf.toString();
    }
}

// End ServerPermissions.java
