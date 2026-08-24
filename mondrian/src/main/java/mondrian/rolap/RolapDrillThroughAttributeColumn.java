/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Sergei Semenkov
// All Rights Reserved.
*/

package mondrian.rolap;

import mondrian.olap.*;

import java.util.Locale;

/**
 * A drillthrough column addressed directly by DimensionAttribute
 * (Mondrian.xml's {@code DrillThroughAttribute.sourceAttribute}),
 * independent of any Level or Hierarchy -- in particular usable for
 * attributes declared with {@code attributeHierarchyEnabled="false"}, which
 * have no OlapElement of their own to point at.
 *
 * <p>This class plays two roles simultaneously: it is the
 * {@link RolapDrillThroughColumn} stored in
 * {@link RolapDrillThroughAction#getColumns()} (parallel to
 * {@link RolapDrillThroughAttribute} / {@link RolapDrillThroughMeasure}),
 * and it <em>is</em> the {@link OlapElement} its own
 * {@link #getOlapElement()} returns -- a synthetic element wrapping a
 * pre-resolved {@link RolapStar.Column}. It is constructed only by
 * {@link RolapCube#resolveDrillThroughAttributeColumn}, and consumed only by
 * {@link RolapAggregationManager}'s {@code addNonConstrainingColumns}; it is
 * never resolved from MDX, never returned by a {@link SchemaReader}, and
 * never exposed to XMLA.
 */
public class RolapDrillThroughAttributeColumn
    extends RolapDrillThroughColumn implements OlapElement
{
    private final RolapCubeDimension dimension;
    private final RolapCubeHierarchy hierarchy;
    private final RolapStar.Column column;

    RolapDrillThroughAttributeColumn(
            String name,
            RolapCubeDimension dimension,
            RolapCubeHierarchy hierarchy,
            RolapStar.Column column
    ) {
        super(name);
        this.dimension = dimension;
        this.hierarchy = hierarchy;
        this.column = column;
    }

    public RolapStar.Column getColumn() { return this.column; }

    public OlapElement getOlapElement() { return this; }

    // -- OlapElement --

    public String getUniqueName() {
        return dimension.getUniqueName() + ".[" + getName() + "]";
    }

    public String getDescription() { return null; }

    public OlapElement lookupChild(
        SchemaReader schemaReader, Id.Segment s, MatchType matchType)
    {
        return null;
    }

    public String getQualifiedName() {
        return "attribute '" + getName() + "'";
    }

    public String getCaption() { return getName(); }

    public String getLocalized(LocalizedProperty prop, Locale locale) {
        return getName();
    }

    public Hierarchy getHierarchy() { return this.hierarchy; }

    public Dimension getDimension() { return this.dimension; }

    public boolean isVisible() { return false; }
}
