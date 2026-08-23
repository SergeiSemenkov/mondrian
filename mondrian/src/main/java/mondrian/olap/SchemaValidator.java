/*
* This software is subject to the terms of the Eclipse Public License v1.0
* Agreement, available at the following URL:
* http://www.eclipse.org/legal/epl-v10.html.
* You must accept the terms of that agreement to use this software.
*
* Copyright (C) 2026 Sergei Semenkov
* All Rights Reserved.
*/

package mondrian.olap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Structural checks over schema XML, run before the schema is turned into
 * Rolap objects.
 *
 * <p>This is the single place schema rules live. The engine runs it at load
 * time (see {@code RolapSchema.load}) and fails on {@link Severity#ERROR};
 * tooling calls {@link #validate(String)} directly to report the same findings
 * against candidate XML before it is deployed. Add new rules here rather than
 * in the Rolap classes, so both paths stay in agreement.
 *
 * <p>These rules are documented for schema authors in CUBE_AUTHORING.md, which
 * ships in the emondrian-mcp module
 * ({@code mcp/src/main/resources/emondrian/mcp/}) and is served by its
 * {@code get_cube_authoring_guide} tool. Changing a rule here means updating
 * that guide too.
 *
 * <p>Checks run on the raw XML rather than on {@link MondrianDef} objects
 * deliberately: several rules are about what the author <em>wrote</em>, which
 * the parsed objects cannot answer -- an unknown attribute never reaches them
 * at all, and {@code Level.type} carries a default that hides whether it was
 * set explicitly.
 */
public class SchemaValidator {

    public enum Severity {
        /** The schema is wrong; the engine refuses to load it. */
        ERROR,
        /** Legal, but very likely not what the author intended. */
        WARN
    }

    /** One problem found in a schema. */
    public static final class Finding {
        private final Severity severity;
        private final String message;

        public Finding(Severity severity, String message) {
            this.severity = severity;
            this.message = message;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }

        public boolean isError() {
            return severity == Severity.ERROR;
        }

        @Override
        public String toString() {
            return severity + ": " + message;
        }
    }

    // Attribute names each element understands. The XOM parser ignores anything
    // else without complaint, so a typo has no effect and no error -- these sets
    // exist to turn that silence into a warning.
    private static final Set<String> DIMENSION_ATTRS = Set.of(
        "name", "type", "caption", "description", "usagePrefix", "visible",
        "foreignKey", "highCardinality", "table", "primaryKey", "source", "level");
    private static final Set<String> ATTRIBUTE_ATTRS = Set.of(
        "name", "id", "description", "defaultMember", "usage", "estimatedCount",
        "orderBy", "isAggregatable", "attributeHierarchyEnabled",
        "attributeHierarchyVisible", "attributeHierarchyDisplayFolder", "levelType");
    private static final Set<String> COLUMN_ATTRS = Set.of(
        "dataType", "dataSize", "columnName", "mimeType", "nullProcessing",
        "trimming", "invalidXmlCharacters", "collation", "format");
    private static final Set<String> LEVEL_ATTRS = Set.of(
        "sourceAttribute", "approxRowCount", "name", "visible", "table", "column",
        "nameColumn", "ordinalColumn", "parentColumn", "nullParentValue", "type",
        "internalType", "uniqueMembers", "levelType", "hideMemberIf", "formatter",
        "caption", "description", "captionColumn");

    private static final Set<String> PROPERTY_ATTRS = Set.of(
        "name", "column", "sourceAttribute", "type", "formatter", "caption",
        "description", "dependsOnLevelValue");

    private static final Set<String> VIRTUAL_CUBE_DIMENSION_ATTRS = Set.of(
        "name", "cubeName", "caption", "description", "visible", "usagePrefix",
        "foreignKey", "highCardinality", "table", "primaryKey", "source", "level");

    private static final Set<String> COLUMN_TAGS = Set.of(
        "KeyColumn", "NameColumn", "OrderByColumn", "ValueColumn");

    /** Elements that give a hierarchy a relation of its own. */
    private static final Set<String> RELATION_TAGS = Set.of(
        "Table", "View", "Join", "InlineTable");

    private SchemaValidator() {
    }

    /** Validates schema XML. */
    public static List<Finding> validate(String schemaXml) {
        final Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Schema XML is operator-supplied, but it is still parsed input.
            factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(
                    schemaXml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw Util.newError(e, "while parsing schema for validation");
        }
        List<Finding> findings = new ArrayList<>();
        Element root = document.getDocumentElement();
        collectDimensions(root, findings);
        collectVirtualCubes(root, findings);
        collectRoles(root, findings);
        return findings;
    }

    /**
     * Declared child order of a {@code <Role>}, per Mondrian.xml. The XML
     * parser reads children with a single forward cursor, so a child that
     * appears out of this order is not merely cosmetic: it is skipped, along
     * with everything the cursor has already advanced past. A {@code <Union>}
     * placed before {@code <SchemaGrant>}, for instance, drops the
     * {@code <SchemaGrant>} silently -- and every {@code <RoleMember>} after
     * it, which would quietly turn an access-control rule into no rule at all.
     * Checking the order here turns that into a load failure instead.
     */
    private static final List<String> ROLE_CHILD_ORDER = List.of(
        "Annotations", "SchemaGrant", "Union", "RoleMember");

    private static void collectRoles(Element element, List<Finding> findings) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element child = (Element) node;
            if ("Role".equals(child.getTagName())) {
                validateRoleChildOrder(child, findings);
                validateNoServerGrant(child, findings);
            } else {
                collectRoles(child, findings);
            }
        }
    }

    /**
     * Rejects the {@code <ServerGrant>} element that earlier versions accepted
     * inside a {@code <Role>}.
     *
     * <p>Its {@code schema} attribute is now {@code schemaAccess} on the
     * {@code <Role>} itself; its {@code database}, {@code logs} and
     * {@code license} attributes have moved out of the schema altogether, into
     * {@code mondrian.properties}. That move is the point: a schema file is
     * editable through the server, so anyone able to write one could otherwise
     * grant themselves -- or everyone, via the schema's {@code defaultRole} --
     * the right to read logs, rewrite {@code datasources.xml} and install
     * license files.</p>
     *
     * <p>This is an error rather than a warning because the alternative is
     * silence: the element would simply be ignored, and a deployment that
     * believed itself locked down would be wide open.</p>
     */
    private static void validateNoServerGrant(
        Element role, List<Finding> findings)
    {
        final String roleName = role.getAttribute("name");
        final String where = roleName == null || roleName.isEmpty()
            ? "Role" : "Role '" + roleName + "'";
        NodeList children = role.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            if (!"ServerGrant".equals(((Element) node).getTagName())) {
                continue;
            }
            findings.add(
                new Finding(
                    Severity.ERROR,
                    where + ": <ServerGrant> is no longer part of a schema."
                    + " Write schemaAccess=\"read|write\" on the <Role>"
                    + " element itself for access to this catalog's schema"
                    + " XML. The server-wide capabilities it used to carry"
                    + " (database, logs, license) are now set in"
                    + " mondrian.properties as"
                    + " mondrian.security.role.<role>.<database|logs|license>,"
                    + " outside any file the server itself can write."));
            return;
        }
    }

    private static void validateRoleChildOrder(
        Element role, List<Finding> findings)
    {
        final String roleName = role.getAttribute("name");
        final String where = roleName == null || roleName.isEmpty()
            ? "Role" : "Role '" + roleName + "'";
        int highestRankSeen = -1;
        String highestTagSeen = null;
        NodeList children = role.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            final String tag = ((Element) node).getTagName();
            final int rank = ROLE_CHILD_ORDER.indexOf(tag);
            if (rank < 0) {
                // Not a child this check knows about; leave it to the parser.
                continue;
            }
            if (rank < highestRankSeen) {
                findings.add(
                    new Finding(
                        Severity.ERROR,
                        where + ": <" + tag + "> must come before <"
                        + highestTagSeen + ">. Inside a <Role> the children"
                        + " must appear in the order "
                        + String.join(", ", ROLE_CHILD_ORDER)
                        + "; one that is out of order is ignored when the"
                        + " schema is read, silently dropping the access it"
                        + " was meant to grant or deny."));
                return;
            }
            if (rank > highestRankSeen) {
                highestRankSeen = rank;
                highestTagSeen = tag;
            }
        }
    }

    /** Returns only the errors from {@code findings}. */
    public static List<Finding> errorsIn(List<Finding> findings) {
        List<Finding> errors = new ArrayList<>();
        for (Finding finding : findings) {
            if (finding.isError()) {
                errors.add(finding);
            }
        }
        return errors;
    }

    private static void collectDimensions(Element element, List<Finding> findings) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element child = (Element) node;
            if ("Dimension".equals(child.getTagName())) {
                validateDimension(child, findings);
            } else {
                // Dimensions appear both at schema level (shared) and inside cubes.
                collectDimensions(child, findings);
            }
        }
    }

    private static void validateDimension(Element dimension, List<Finding> findings) {
        String dimensionName = attr(dimension, "name");
        String where = "Dimension '" + dimensionName + "'";
        checkUnknownAttributes(dimension, DIMENSION_ATTRS, where, findings);

        List<Element> attributes = childrenNamed(dimension, "DimensionAttribute");
        if (attributes.isEmpty()) {
            // A classic hierarchy-only dimension; none of the attribute rules apply.
            return;
        }

        List<Element> keyAttributes = new ArrayList<>();
        Map<String, Element> attributesByName = new LinkedHashMap<>();
        for (Element attribute : attributes) {
            String attributeName = attr(attribute, "name");
            String attributeWhere = where + ", attribute '" + attributeName + "'";
            attributesByName.put(attributeName, attribute);
            validateAttribute(attribute, attributeWhere, findings);
            if ("Key".equals(attr(attribute, "usage"))) {
                keyAttributes.add(attribute);
            }
        }

        validateKeyAttribute(dimension, where, keyAttributes, findings);
        validateLevels(dimension, where, attributesByName, findings);
    }

    private static void validateAttribute(
        Element attribute, String where, List<Finding> findings)
    {
        checkUnknownAttributes(attribute, ATTRIBUTE_ATTRS, where, findings);

        List<Element> keyColumns = childrenNamed(attribute, "KeyColumn");
        if (keyColumns.isEmpty()) {
            findings.add(error(where + " has no KeyColumn."));
        } else if (attr(keyColumns.get(0), "dataType").isEmpty()) {
            findings.add(error(where + " KeyColumn has no dataType."));
        }
        for (Element child : elementChildren(attribute)) {
            if (COLUMN_TAGS.contains(child.getTagName())) {
                checkUnknownAttributes(
                    child, COLUMN_ATTRS, where + " " + child.getTagName(), findings);
            }
        }

        if ("Parent".equals(attr(attribute, "usage"))) {
            findings.add(error(
                where + " uses usage='Parent', which is not supported. Define a"
                + " parent-child hierarchy with Level.parentColumn and"
                + " Level.nullParentValue instead."));
        }

        String orderBy = attr(attribute, "orderBy");
        if ("AttributeKey".equals(orderBy) || "AttributeName".equals(orderBy)) {
            findings.add(error(
                where + " uses orderBy='" + orderBy + "', which is not supported;"
                + " use orderBy='Key', orderBy='Name', or an explicit OrderByColumn."));
        }

        if ("false".equals(attr(attribute, "isAggregatable"))
            && attr(attribute, "defaultMember").isEmpty())
        {
            findings.add(warn(
                where + " sets isAggregatable='false' without a defaultMember, so its"
                + " first member silently becomes the default filter for every query"
                + " that does not mention it."));
        }
    }

    private static void validateKeyAttribute(
        Element dimension,
        String where,
        List<Element> keyAttributes,
        List<Finding> findings)
    {
        String primaryKey = attr(dimension, "primaryKey");

        if (keyAttributes.isEmpty()) {
            if (attr(dimension, "table").isEmpty()
                && attr(dimension, "foreignKey").isEmpty())
            {
                // Degenerate dimension: its columns live in the fact table, so
                // there is nothing to join and no key column to require.
                return;
            }
            if (primaryKey.isEmpty()) {
                findings.add(error(
                    where + " has neither a usage='Key' attribute nor a primaryKey,"
                    + " so it has no column to join to the fact table."));
            } else {
                findings.add(warn(
                    where + " has no usage='Key' attribute; the join relies on"
                    + " primaryKey='" + primaryKey + "' alone."));
            }
            return;
        }
        if (keyAttributes.size() > 1) {
            findings.add(error(
                where + " has " + keyAttributes.size() + " attributes with"
                + " usage='Key'; a dimension joins the fact table through exactly one."));
            return;
        }

        Element keyAttribute = keyAttributes.get(0);
        String keyName = attr(keyAttribute, "name");
        List<Element> keyColumns = childrenNamed(keyAttribute, "KeyColumn");
        String keyColumn =
            keyColumns.isEmpty() ? "" : attr(keyColumns.get(0), "columnName");

        // Nothing downstream cross-checks these: when they disagree the explicit
        // primaryKey wins, so the dimension joins on a different column than the
        // schema appears to say.
        if (!primaryKey.isEmpty()
            && !keyColumn.isEmpty()
            && !primaryKey.equals(keyColumn))
        {
            findings.add(warn(
                where + " declares primaryKey='" + primaryKey + "' but its key"
                + " attribute '" + keyName + "' has KeyColumn '" + keyColumn + "'."
                + " The primaryKey wins, so the join uses '" + primaryKey + "'."
                + " Remove primaryKey, or point the key attribute at the same column."));
        }
        // An attribute with attributeHierarchyEnabled='false' generates no
        // hierarchy at all, so there is nothing for clients to see.
        if (!"false".equals(attr(keyAttribute, "attributeHierarchyVisible"))
            && !"false".equals(attr(keyAttribute, "attributeHierarchyEnabled")))
        {
            findings.add(warn(
                where + " key attribute '" + keyName + "' is visible to clients;"
                + " key attributes are usually ID columns and should set"
                + " attributeHierarchyVisible='false'."));
        }
    }

    private static void validateLevels(
        Element dimension,
        String where,
        Map<String, Element> attributesByName,
        List<Finding> findings)
    {
        for (Element hierarchy : childrenNamed(dimension, "Hierarchy")) {
            for (Element level : childrenNamed(hierarchy, "Level")) {
                String levelWhere = where + ", level '" + attr(level, "name") + "'";
                checkUnknownAttributes(level, LEVEL_ATTRS, levelWhere, findings);
                validateProperties(level, levelWhere, attributesByName, findings);

                String sourceAttribute = attr(level, "sourceAttribute");
                if (sourceAttribute.isEmpty()) {
                    continue;
                }
                if (!attributesByName.containsKey(sourceAttribute)) {
                    findings.add(error(
                        levelWhere + " references sourceAttribute '" + sourceAttribute
                        + "', which this dimension does not define."));
                }
                if (hasAttribute(level, "type")) {
                    findings.add(warn(
                        levelWhere + " sets type='" + attr(level, "type") + "' alongside"
                        + " sourceAttribute; the attribute's KeyColumn dataType overrides"
                        + " it, so this value has no effect."));
                }
                if (!hasAttribute(level, "uniqueMembers")) {
                    findings.add(warn(
                        levelWhere + " does not set uniqueMembers; it defaults to false."
                        + " Set it explicitly -- a wrong value yields wrong results,"
                        + " not an error."));
                }
            }
        }
    }

    /**
     * Property rules. These apply to classic levels as well as attribute-based
     * ones, so they run before the sourceAttribute checks.
     */
    private static void validateProperties(
        Element level,
        String levelWhere,
        Map<String, Element> attributesByName,
        List<Finding> findings)
    {
        for (Element property : childrenNamed(level, "Property")) {
            String propertyWhere =
                levelWhere + ", property '" + attr(property, "name") + "'";
            checkUnknownAttributes(property, PROPERTY_ATTRS, propertyWhere, findings);

            String sourceAttribute = attr(property, "sourceAttribute");
            if (sourceAttribute.isEmpty()) {
                if (attr(property, "column").isEmpty()) {
                    findings.add(error(
                        propertyWhere + " has neither sourceAttribute nor column."));
                }
                continue;
            }
            Element attribute = attributesByName.get(sourceAttribute);
            if (attribute == null) {
                findings.add(error(
                    propertyWhere + " references sourceAttribute '" + sourceAttribute
                    + "', which this dimension does not define."));
                continue;
            }
            if (!attr(property, "column").isEmpty()) {
                findings.add(warn(
                    propertyWhere + " sets both sourceAttribute and column;"
                    + " the attribute wins and column is ignored."));
            }
            if (!"false".equals(attr(attribute, "attributeHierarchyEnabled"))) {
                findings.add(warn(
                    propertyWhere + " uses attribute '" + sourceAttribute + "', which"
                    + " does not set attributeHierarchyEnabled='false', so that"
                    + " attribute also becomes a browsable hierarchy."));
            }
        }
    }

    private static void checkUnknownAttributes(
        Element element, Set<String> known, String where, List<Finding> findings)
    {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            String name = attributes.item(i).getNodeName();
            if (!known.contains(name)) {
                findings.add(warn(
                    where + " has unknown attribute '" + name + "', which the parser"
                    + " ignores silently (check spelling)."));
            }
        }
    }

    private static void collectVirtualCubes(Element root, List<Finding> findings) {
        // Index by name once: a VirtualCubeDimension is a reference, so every
        // rule about it is a question about something declared elsewhere.
        Map<String, Element> sharedDimensions = new LinkedHashMap<>();
        for (Element dimension : childrenNamed(root, "Dimension")) {
            sharedDimensions.put(attr(dimension, "name"), dimension);
        }
        Map<String, Element> cubes = new LinkedHashMap<>();
        for (Element cube : childrenNamed(root, "Cube")) {
            cubes.put(attr(cube, "name"), cube);
        }

        for (Element virtualCube : childrenNamed(root, "VirtualCube")) {
            String virtualCubeName = attr(virtualCube, "name");
            for (Element reference
                : childrenNamed(virtualCube, "VirtualCubeDimension"))
            {
                validateVirtualCubeDimension(
                    reference, virtualCubeName, cubes, sharedDimensions, findings);
            }
        }
    }

    private static void validateVirtualCubeDimension(
        Element reference,
        String virtualCubeName,
        Map<String, Element> cubes,
        Map<String, Element> sharedDimensions,
        List<Finding> findings)
    {
        String dimensionName = attr(reference, "name");
        String cubeName = attr(reference, "cubeName");
        String where = "VirtualCube '" + virtualCubeName
            + "', dimension '" + dimensionName + "'";
        checkUnknownAttributes(
            reference, VIRTUAL_CUBE_DIMENSION_ATTRS, where, findings);

        if (dimensionName.isEmpty()) {
            findings.add(error(where + " has no name."));
            return;
        }

        // Resolve the dimension the same way MondrianDef.VirtualCubeDimension
        // does: from the named cube, or from the shared dimensions.
        Element dimension;
        if (cubeName.isEmpty()) {
            dimension = sharedDimensions.get(dimensionName);
            if (dimension == null) {
                findings.add(error(
                    where + " sets no cubeName, so it refers to a shared"
                    + " dimension, but the schema declares no shared Dimension"
                    + " named '" + dimensionName + "'."));
                return;
            }
        } else {
            Element cube = cubes.get(cubeName);
            if (cube == null) {
                findings.add(error(
                    where + " refers to cube '" + cubeName + "', which the"
                    + " schema does not declare."));
                return;
            }
            dimension = dimensionOfCube(cube, dimensionName, sharedDimensions);
            if (dimension == null) {
                findings.add(error(
                    where + " refers to cube '" + cubeName + "', which has no"
                    + " dimension named '" + dimensionName + "'."));
                return;
            }
        }

        // A hierarchy with no relation of its own falls back to the cube's fact
        // table. A virtual cube has no fact table, so it borrows the one behind
        // cubeName -- and without cubeName there is nothing to borrow, leaving
        // the hierarchy with no relation at all.
        if (cubeName.isEmpty() && needsFactTable(dimension)) {
            findings.add(error(
                where + " sets no cubeName, but dimension '" + dimensionName
                + "' has a hierarchy with no relation of its own, so it can only"
                + " bind to a fact table. A virtual cube has none: set cubeName"
                + " to the cube whose fact table it should use, or give the"
                + " dimension a table."));
        }
    }

    /**
     * Finds the dimension a cube exposes under {@code dimensionName}, whether
     * declared inline or brought in by a DimensionUsage.
     */
    private static Element dimensionOfCube(
        Element cube, String dimensionName, Map<String, Element> sharedDimensions)
    {
        for (Element child : elementChildren(cube)) {
            if (!dimensionName.equals(attr(child, "name"))) {
                continue;
            }
            if ("Dimension".equals(child.getTagName())) {
                return child;
            }
            if ("DimensionUsage".equals(child.getTagName())) {
                // The usage names the dimension; the shared declaration holds
                // the hierarchies the rules below ask about.
                String source = attr(child, "source");
                Element shared = sharedDimensions.get(
                    source.isEmpty() ? dimensionName : source);
                return shared != null ? shared : child;
            }
        }
        return null;
    }

    /**
     * Returns whether any hierarchy of {@code dimension} would have to take its
     * relation from the cube's fact table.
     */
    private static boolean needsFactTable(Element dimension) {
        if (!attr(dimension, "table").isEmpty()) {
            // The dimension names its own table; every hierarchy uses it.
            return false;
        }
        // Hierarchies generated from attributes carry no relation of their own.
        if (!childrenNamed(dimension, "DimensionAttribute").isEmpty()) {
            return true;
        }
        for (Element hierarchy : childrenNamed(dimension, "Hierarchy")) {
            boolean hasRelation = false;
            for (Element child : elementChildren(hierarchy)) {
                if (RELATION_TAGS.contains(child.getTagName())) {
                    hasRelation = true;
                    break;
                }
            }
            if (!hasRelation) {
                return true;
            }
        }
        return false;
    }

    private static List<Element> childrenNamed(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        for (Element child : elementChildren(parent)) {
            if (tagName.equals(child.getTagName())) {
                result.add(child);
            }
        }
        return result;
    }

    private static List<Element> elementChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element) {
                result.add((Element) node);
            }
        }
        return result;
    }

    /** {@code getAttribute} returns "" when absent; callers want "". */
    private static String attr(Element element, String name) {
        return element.getAttribute(name);
    }

    private static boolean hasAttribute(Element element, String name) {
        return element.hasAttribute(name);
    }

    private static Finding error(String message) {
        return new Finding(Severity.ERROR, message);
    }

    private static Finding warn(String message) {
        return new Finding(Severity.WARN, message);
    }
}

// End SchemaValidator.java
