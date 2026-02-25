package org.citydb.ifc2citygml.module.building;

import org.bimserver.models.ifc4.IfcFurnishingElement;
import org.citydb.ifc2citygml.module.ConverterContext;
import org.citydb.ifc2citygml.module.ElementProcessor;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.building.BuildingFurniture;
import org.citygml4j.core.model.building.BuildingFurnitureProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.basictypes.Code;

import java.util.ArrayList;
import java.util.List;

/**
 * Processes IfcFurnishingElement as BuildingFurniture.
 */
class FurnitureProcessor implements ElementProcessor<Building> {

    private static final Logger logger = LoggerFactory.getLogger(FurnitureProcessor.class);

    @Override
    public void process(Building building, ConverterContext ctx) {
        List<IfcFurnishingElement> allFurniture = new ArrayList<>(ctx.model().getAll(IfcFurnishingElement.class));
        allFurniture.removeIf(e -> !ctx.currentElements().contains(e));
        logger.info("IfcFurnishingElement: processing {} furniture items", allFurniture.size());

        for (IfcFurnishingElement element : allFurniture) {
            BuildingFurniture furniture = new BuildingFurniture();
            ctx.initializeCityObject(furniture, element);

            List<double[]> polygons = ctx.geometryHandler().getGeometry(element);

            if (!polygons.isEmpty()) {
                ctx.geometryHandler().addGeometryToObject(furniture, element, polygons);
                ctx.markExported(element);
                furniture.setClassifier(new Code(element.eClass().getName()));
                building.getBuildingFurniture().add(new BuildingFurnitureProperty(furniture));
                logger.debug("Added {} with {} polygons", element.eClass().getName(), polygons.size());
            }
        }
    }
}
