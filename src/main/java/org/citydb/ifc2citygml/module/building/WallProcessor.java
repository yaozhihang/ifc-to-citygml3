package org.citydb.ifc2citygml.module.building;

import org.bimserver.models.ifc4.IfcWall;
import org.bimserver.models.ifc4.IfcWallStandardCase;
import org.citydb.ifc2citygml.module.ConverterContext;
import org.citydb.ifc2citygml.module.ElementProcessor;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.building.BuildingConstructiveElement;
import org.citygml4j.core.model.building.BuildingConstructiveElementProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.basictypes.Code;

import java.util.ArrayList;
import java.util.List;

/**
 * Processes walls with special handling for IfcWallStandardCase vs IfcWall.
 */
class WallProcessor implements ElementProcessor<Building> {

    private static final Logger logger = LoggerFactory.getLogger(WallProcessor.class);

    @Override
    public void process(Building building, ConverterContext ctx) {
        // Collect both IfcWallStandardCase and IfcWall (non-standard-case)
        List<IfcWall> walls = new ArrayList<>(ctx.model().getAll(IfcWallStandardCase.class));
        for (IfcWall w : ctx.model().getAll(IfcWall.class)) {
            if (!(w instanceof IfcWallStandardCase)) {
                walls.add(w);
            }
        }
        walls.removeIf(e -> !ctx.currentElements().contains(e));

        logger.info("IfcWall: processing {} walls", walls.size());

        for (IfcWall wall : walls) {
            BuildingConstructiveElement bce = new BuildingConstructiveElement();
            ctx.initializeCityObject(bce, wall);

            // Get geometry
            List<double[]> polygons = ctx.geometryHandler().getGeometry(wall);

            if (!polygons.isEmpty()) {
                ctx.geometryHandler().addGeometryToObject(bce, wall, polygons);
                ctx.markExported(wall);
            }

            // Embed doors/windows as con:filling inside the wall BCE
            FillingHandler.embedFillings(bce, wall, ctx);

            // Set class (must come after con:filling for schema order)
            String wallClass = (wall instanceof IfcWallStandardCase) ? "IfcWallStandardCase" : "IfcWall";
            bce.setClassifier(new Code(wallClass));
            building.getBuildingConstructiveElements().add(new BuildingConstructiveElementProperty(bce));

            if (!polygons.isEmpty()) {
                logger.info("Added wall '{}' (IFC: {}) with {} polygons",
                    wall.getName(), wall.getGlobalId(), polygons.size());
            } else {
                logger.debug("Added wall '{}' (IFC: {}) without geometry",
                    wall.getName(), wall.getGlobalId());
            }
        }
    }
}
