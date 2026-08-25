/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2001-2005 Julian Hyde
// Copyright (C) 2005-2017 Hitachi Vantara and others
// All Rights Reserved.
//
// jhyde, 10 August, 2001
*/
package mondrian.rolap;

import mondrian.olap.*;
import mondrian.resource.MondrianResource;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.util.Collections;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * <code>RolapDimension</code> implements {@link Dimension}for a ROLAP
 * database.
 *
 * <h2><a name="topic_ordinals">Topic: Dimension ordinals </a></h2>
 *
 * {@link RolapEvaluator} needs each dimension to have an ordinal, so that it
 * can store the evaluation context as an array of members.
 *
 * <p>
 * A dimension may be either shared or private to a particular cube. The
 * dimension object doesn't actually know which; {@link Schema} has a list of
 * shared hierarchies ({@link Schema#getSharedHierarchies}), and {@link Cube}
 * has a list of dimensions ({@link Cube#getDimensions}).
 *
 * <p>
 * If a dimension is shared between several cubes, the {@link Dimension}objects
 * which represent them may (or may not be) the same. (That's why there's no
 * <code>getCube()</code> method.)
 *
 * <p>
 * Furthermore, since members are created by a {@link MemberReader}which
 * belongs to the {@link RolapHierarchy}, you will the members will be the same
 * too. For example, if you query <code>[Product].[Beer]</code> from the
 * <code>Sales</code> and <code>Warehouse</code> cubes, you will get the
 * same {@link RolapMember}object.
 * ({@link RolapSchema#mapSharedHierarchyToReader} holds the mapping. I don't
 * know whether it's still necessary.)
 *
 * @author jhyde
 * @since 10 August, 2001
 */
class RolapDimension extends DimensionBase {

    private static final Logger LOGGER = LogManager.getLogger(RolapDimension.class);

    /**
     * HIERARCHY_ORIGIN bit values (see MDSCHEMA_HIERARCHIES / MDSCHEMA_LEVELS):
     * MD_ORIGIN_USER_DEFINED 0x1, MD_ORIGIN_ATTRIBUTE 0x2,
     * MD_ORIGIN_KEY_ATTRIBUTE 0x4, MD_ORIGIN_INTERNAL 0x8.
     *
     * <p>A key attribute is reported as ATTRIBUTE|KEY_ATTRIBUTE rather than
     * KEY_ATTRIBUTE alone: clients apply a default restriction of
     * USER_DEFINED|ATTRIBUTE to these rowsets, so a bare 0x4 would hide the
     * dimension's key attribute from them entirely.
     */
    private static final String ATTRIBUTE_ORIGIN = "2";
    private static final String KEY_ATTRIBUTE_ORIGIN = "6";

    private final Schema schema;
    private final Map<String, Annotation> annotationMap;
    private MondrianDef.DimensionAttribute[] xmlAttributes; // Add this field
    public MondrianDef.CubeDimension xmlCubeDimension;


    RolapDimension(
        Schema schema,
        String name,
        String caption,
        boolean visible,
        String description,
        DimensionType dimensionType,
        final boolean highCardinality,
        Map<String, Annotation> annotationMap)
    {
        // todo: recognition of a time dimension should be improved
        // allow multiple time dimensions
        super(
            name,
            caption,
            visible,
            description,
            dimensionType,
            highCardinality);
        assert annotationMap != null;
        this.schema = schema;
        this.annotationMap = annotationMap;
        this.hierarchies = new RolapHierarchy[0];
    }

    /**
     * Creates a dimension from an XML definition.
     *
     * @pre schema != null
     */
    RolapDimension(
        RolapSchema schema,
        RolapCube cube,
        MondrianDef.Dimension xmlDimension,
        MondrianDef.CubeDimension xmlCubeDimension)
    {
        this(
            schema,
            xmlDimension.name,
            xmlDimension.caption,
            xmlDimension.visible,
            xmlDimension.description,
            xmlDimension.getDimensionType(),
            xmlDimension.highCardinality,
            RolapHierarchy.createAnnotationMap(xmlDimension.annotations));

        Util.assertPrecondition(schema != null);

        if (cube != null) {
            Util.assertTrue(cube.getSchema() == schema);
        }

        if (!Util.isEmpty(xmlDimension.caption)) {
            setCaption(xmlDimension.caption);
        }

        this.xmlCubeDimension = xmlCubeDimension;

        // A shared dimension states its table once, on the <Dimension>; every
        // usage of it (DimensionUsage, VirtualCubeDimension) then inherits it
        // unless it overrides the table itself. Without this, usages that
        // cannot carry a table of their own -- VirtualCubeDimension in
        // particular -- would fall back to the cube's fact table.
        if (xmlCubeDimension != null
            && xmlCubeDimension != xmlDimension
            && Util.isEmpty(xmlCubeDimension.table)
            && !Util.isEmpty(xmlDimension.table))
        {
            xmlCubeDimension.table = xmlDimension.table;
        }

        // A shared dimension's own explicit primaryKey= needs the identical
        // inheritance as table= above, for the identical reason: a usage
        // (DimensionUsage/VirtualCubeDimension) normally states only
        // foreignKey=, relying on the shared <Dimension>'s primaryKey=.
        // Without this, RolapHierarchy's own primaryKey propagation (below,
        // and see RolapHierarchy's "CubeDimension has a primaryKey" check)
        // never fires per usage, and HierarchyUsage.init falls back to
        // joining on "the key of the last level" -- i.e. whatever column the
        // attribute happens to key that particular hierarchy on, silently
        // wrong for every hierarchy but the one whose own level column
        // happens to coincide with the real join column.
        if (xmlCubeDimension != null
            && xmlCubeDimension != xmlDimension
            && Util.isEmpty(xmlCubeDimension.primaryKey)
            && !Util.isEmpty(xmlDimension.primaryKey))
        {
            xmlCubeDimension.primaryKey = xmlDimension.primaryKey;
        }

        // Store the XML attributes
        this.xmlAttributes = xmlDimension.Attributes;

        // A dimension joins the fact table through the key attribute's column,
        // so derive primaryKey from it when the dimension doesn't state one
        // explicitly. RolapHierarchy then propagates it to every hierarchy.
        MondrianDef.DimensionAttribute keyAttribute =
            findKeyAttribute(xmlDimension);
        if (keyAttribute != null
            && xmlCubeDimension != null
            && Util.isEmpty(xmlCubeDimension.primaryKey))
        {
            xmlCubeDimension.primaryKey = keyAttribute.keyColumn.columnName;
        }

        // Create hierarchies from XML hierarchy definitions
        List<RolapHierarchy> hierarchyList = new ArrayList<>();
        for (MondrianDef.Hierarchy xmlHierarchy : xmlDimension.hierarchies) {
            RolapHierarchy hierarchy = new RolapHierarchy(
                cube, this, xmlHierarchy, xmlCubeDimension);
            hierarchyList.add(hierarchy);
        }

        // Create additional hierarchies from attributes
        if (xmlDimension.Attributes != null) {
            for (MondrianDef.DimensionAttribute xmlDimensionAttribute : xmlDimension.Attributes) {
                if (xmlDimensionAttribute.attributeHierarchyEnabled == null || xmlDimensionAttribute.attributeHierarchyEnabled) {
                    RolapHierarchy hierarchy = createHierarchyFromAttribute(cube, xmlCubeDimension, xmlDimensionAttribute);
                    hierarchyList.add(hierarchy);
                }
            }
        }

        this.hierarchies = hierarchyList.toArray(new RolapHierarchy[0]);

        // if there was no dimension type assigned, determine now.
        if (dimensionType == null) {
            for (int i = 0; i < hierarchies.length; i++) {
                Level[] levels = hierarchies[i].getLevels();
                LevLoop:
                for (int j = 0; j < levels.length; j++) {
                    Level lev = levels[j];
                    if (lev.isAll()) {
                        continue LevLoop;
                    }
                    if (dimensionType == null) {
                        // not set yet - set it according to current level
                        dimensionType = (lev.getLevelType().isTime())
                            ? DimensionType.TimeDimension
                            : isMeasures()
                            ? DimensionType.MeasuresDimension
                            : DimensionType.StandardDimension;

                    } else {
                        // Dimension type was set according to first level.
                        // Make sure that other levels fit to definition.
                        if (dimensionType == DimensionType.TimeDimension
                            && !lev.getLevelType().isTime()
                            && !lev.isAll())
                        {
                            throw MondrianResource.instance()
                                .NonTimeLevelInTimeHierarchy.ex(
                                    getUniqueName());
                        }
                        if (dimensionType != DimensionType.TimeDimension
                            && lev.getLevelType().isTime())
                        {
                            throw MondrianResource.instance()
                                .TimeLevelInNonTimeHierarchy.ex(
                                    getUniqueName());
                        }
                    }
                }
            }
        }
    }

    protected Logger getLogger() {
        return LOGGER;
    }

    /**
     * Initializes a dimension within the context of a cube.
     */
    void init(MondrianDef.CubeDimension xmlDimension) {
        for (int i = 0; i < hierarchies.length; i++) {
            if (hierarchies[i] != null) {
                ((RolapHierarchy) hierarchies[i]).init(xmlDimension);
            }
        }
    }

    /**
     * Creates a hierarchy.
     *
     * @param subName Name of this hierarchy.
     * @param hasAll Whether hierarchy has an 'all' member
     * @param closureFor Hierarchy for which the new hierarchy is a closure;
     *     null for regular hierarchies
     * @return Hierarchy
     */
    RolapHierarchy newHierarchy(
        String subName,
        boolean hasAll,
        RolapHierarchy closureFor)
    {
        RolapHierarchy hierarchy =
            new RolapHierarchy(
                this, subName,
                caption, visible, description, null, hasAll, closureFor,
                Collections.<String, Annotation>emptyMap());
        this.hierarchies = Util.append(this.hierarchies, hierarchy);
        return hierarchy;
    }

    /**
     * Returns the hierarchy of an expression.
     *
     * <p>In this case, the expression is a dimension, so the hierarchy is the
     * dimension's default hierarchy (its first).
     */
    public Hierarchy getHierarchy() {
        return hierarchies[0];
    }

    public Schema getSchema() {
        return schema;
    }

    public Map<String, Annotation> getAnnotationMap() {
        return annotationMap;
    }

    @Override
    protected int computeHashCode() {
      if (isMeasuresDimension()) {
        return System.identityHashCode(this);
      }
      return super.computeHashCode();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
    if (!(o instanceof RolapDimension)) {
        return false;
    }
      if (isMeasuresDimension()) {
        RolapDimension that = (RolapDimension) o;
        return this == that;
      }
      return super.equals(o);
    }

    private boolean isMeasuresDimension() {
      return this.getDimensionType() == DimensionType.MeasuresDimension;
    }

    /**
     * Creates a hierarchy from a dimension attribute.
     */
    private RolapHierarchy createHierarchyFromAttribute(
            RolapCube cube,
            MondrianDef.CubeDimension xmlCubeDimension,
            MondrianDef.DimensionAttribute xmlDimensionAttribute
    ) {
        MondrianDef.Hierarchy xmlHierarchy = new MondrianDef.Hierarchy();
        xmlHierarchy.name = xmlDimensionAttribute.name;
        // SSAS IsAggregatable=false means the attribute hierarchy has no (All) level.
        xmlHierarchy.hasAll = xmlDimensionAttribute.isAggregatable == null
                || xmlDimensionAttribute.isAggregatable;
        xmlHierarchy.visible = xmlDimensionAttribute.attributeHierarchyVisible != null ?
                xmlDimensionAttribute.attributeHierarchyVisible : true;
        xmlHierarchy.description = xmlDimensionAttribute.description;
        xmlHierarchy.displayFolder =
                xmlDimensionAttribute.attributeHierarchyDisplayFolder;
        xmlHierarchy.defaultMember = xmlDimensionAttribute.defaultMember;

        // Create single level for the attribute
        MondrianDef.Level levelDef = new MondrianDef.Level();
        levelDef.name = xmlDimensionAttribute.name;
        levelDef.column = xmlDimensionAttribute.keyColumn.columnName;
        levelDef.visible = true;
        levelDef.uniqueMembers = true;
        levelDef.type = xmlDimensionAttribute.keyColumn.dataType;
        levelDef.hideMemberIf = "Never";
        levelDef.properties = new MondrianDef.Property[0];
        levelDef.description = xmlDimensionAttribute.description;
        levelDef.levelType = xmlDimensionAttribute.levelType != null
                ? xmlDimensionAttribute.levelType
                : "Regular";
        // Null (rather than a made-up count) makes RolapLevel.loadApproxRowCount
        // record "unknown" instead of asserting a cardinality we don't have.
        levelDef.approxRowCount = xmlDimensionAttribute.estimatedCount != null
                ? xmlDimensionAttribute.estimatedCount.toString()
                : null;

        // SSAS NameColumn is a display name only -- member identity stays on the key.
        // Mondrian's Level.nameColumn would change the member's name and therefore its
        // unique name (how MDX/DAX addresses it), so captionColumn is the closer match.
        if (xmlDimensionAttribute.nameColumn != null) {
            levelDef.captionColumn = xmlDimensionAttribute.nameColumn.columnName;
        }

        levelDef.ordinalColumn = resolveOrdinalColumn(xmlDimensionAttribute);

        xmlHierarchy.levels = new MondrianDef.Level[] { levelDef };
        xmlHierarchy.origin = "Key".equals(xmlDimensionAttribute.usage)
                ? KEY_ATTRIBUTE_ORIGIN
                : ATTRIBUTE_ORIGIN;

        RolapHierarchy hierarchy = new RolapHierarchy(
                cube, this, xmlHierarchy, xmlCubeDimension);
        return hierarchy;
    }

    /** Returns the dimension's usage='Key' attribute, or null if it has none. */
    private static MondrianDef.DimensionAttribute findKeyAttribute(
        MondrianDef.Dimension xmlDimension)
    {
        if (xmlDimension.Attributes == null) {
            return null;
        }
        for (MondrianDef.DimensionAttribute attribute : xmlDimension.Attributes) {
            if ("Key".equals(attribute.usage) && attribute.keyColumn != null) {
                return attribute;
            }
        }
        return null;
    }

    /**
     * Picks the column an attribute's members are ordered by. An explicit
     * orderByColumn wins; otherwise SSAS's orderBy enum selects one of the
     * attribute's own columns.
     */
    private static String resolveOrdinalColumn(
            MondrianDef.DimensionAttribute xmlDimensionAttribute)
    {
        if (xmlDimensionAttribute.orderByColumn != null) {
            return xmlDimensionAttribute.orderByColumn.columnName;
        }
        if (xmlDimensionAttribute.orderBy == null) {
            return null;
        }
        switch (xmlDimensionAttribute.orderBy) {
        case "Key":
            return xmlDimensionAttribute.keyColumn.columnName;
        case "Name":
            return xmlDimensionAttribute.nameColumn != null
                    ? xmlDimensionAttribute.nameColumn.columnName
                    : xmlDimensionAttribute.keyColumn.columnName;
        default:
            // SchemaValidator rejects the other orderBy values at load time, so
            // this is unreachable for any schema the engine accepted.
            return null;
        }
    }

    /**
     * Finds the source dimension attribute with the given name in the dimension.
     */
    public MondrianDef.DimensionAttribute findSourceAttribute(String attributeName) {
        if (this.xmlAttributes != null) {
            for (MondrianDef.DimensionAttribute attr : xmlAttributes) {
                if (attr.name.equals(attributeName)) {
                    return attr;
                }
            }
        }
        return null;
    }

}

// End RolapDimension.java
