package org.citydb.ifc2citygml.module.building;

import org.bimserver.models.ifc4.*;
import org.citydb.ifc2citygml.module.ConverterContext;
import org.citygml4j.core.model.building.BuildingConstructiveElement;
import org.citygml4j.core.model.construction.AbstractFillingElement;
import org.citygml4j.core.model.construction.AbstractFillingElementProperty;
import org.citygml4j.core.model.core.AbstractSpaceBoundary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handles embedding of doors/windows as con:filling in BuildingConstructiveElements.
 */
class FillingHandler {

    private static final Logger logger = LoggerFactory.getLogger(FillingHandler.class);

    /**
     * Embeds doors/windows found via HasOpenings as con:filling in a BCE.
     */
    static void embedFillings(BuildingConstructiveElement bce, IfcElement element, ConverterContext ctx) {
        if (!element.isSetHasOpenings()) return;

        for (IfcRelVoidsElement relVoids : element.getHasOpenings()) {
            IfcFeatureElementSubtraction sub = relVoids.getRelatedOpeningElement();
            if (sub instanceof IfcOpeningElement opening) {
                if (opening.isSetHasFillings()) {
                    for (IfcRelFillsElement relFills : opening.getHasFillings()) {
                        IfcElement filling = relFills.getRelatedBuildingElement();
                        if (filling instanceof IfcWindow window) {
                            addFilling(bce, window,
                                    new org.citygml4j.core.model.construction.Window(), "Window", ctx);
                        } else if (filling instanceof IfcDoor door) {
                            addFilling(bce, door,
                                    new org.citygml4j.core.model.construction.Door(), "Door", ctx);
                        }
                    }
                }
            }
        }
    }

    static void addWindowFilling(BuildingConstructiveElement wallBce, IfcWindow window, ConverterContext ctx) {
        addFilling(wallBce, window,
                new org.citygml4j.core.model.construction.Window(), "Window", ctx);
    }

    static void addDoorFilling(BuildingConstructiveElement wallBce, IfcDoor door, ConverterContext ctx) {
        addFilling(wallBce, door,
                new org.citygml4j.core.model.construction.Door(), "Door", ctx);
    }

    private static <T extends AbstractFillingElement> void addFilling(
            BuildingConstructiveElement bce, IfcElement element,
            T cityFilling, String label, ConverterContext ctx) {
        ctx.initializeCityObject(cityFilling, element, false);

        List<double[]> polygons = ctx.geometryHandler().getGeometry(element);
        if (!polygons.isEmpty()) {
            ctx.geometryHandler().addGeometryToObject(cityFilling, element, polygons);
        }

        bce.getFillings().add(new AbstractFillingElementProperty(cityFilling));
        ctx.markExported(element);
        logger.info("  Embedded {} '{}' in wall (polygons: {})", label, element.getName(), polygons.size());
    }
}
