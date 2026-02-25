package org.citydb.ifc2citygml.module.building;

import org.bimserver.models.ifc4.IfcElement;
import org.bimserver.models.ifc4.IfcProduct;
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
 * Data-driven processor for standard constructive element types (slab, roof, beam, etc.).
 */
class ConstructiveElementProcessor<T extends IfcProduct> implements ElementProcessor<Building> {

    private static final Logger logger = LoggerFactory.getLogger(ConstructiveElementProcessor.class);

    private final Class<T> ifcClass;
    private final String className;

    private ConstructiveElementProcessor(Class<T> ifcClass, String className) {
        this.ifcClass = ifcClass;
        this.className = className;
    }

    static <T extends IfcProduct> ConstructiveElementProcessor<T> of(Class<T> ifcClass, String className) {
        return new ConstructiveElementProcessor<>(ifcClass, className);
    }

    @Override
    public void process(Building building, ConverterContext ctx) {
        List<T> elements = new ArrayList<>(ctx.model().getAll(ifcClass));
        elements.removeIf(e -> !ctx.currentElements().contains(e));
        logger.info("{}: processing {} elements", className, elements.size());

        for (T element : elements) {
            BuildingConstructiveElement bce = new BuildingConstructiveElement();
            ctx.initializeCityObject(bce, element);

            List<double[]> polygons = ctx.geometryHandler().getGeometry(element);

            // Embed doors/windows as con:filling
            if (element instanceof IfcElement ifcElement) {
                FillingHandler.embedFillings(bce, ifcElement, ctx);
            }

            if (!polygons.isEmpty()) {
                ctx.geometryHandler().addGeometryToObject(bce, element, polygons);
                ctx.markExported(element);

                bce.setClassifier(new Code(className));
                building.getBuildingConstructiveElements().add(new BuildingConstructiveElementProperty(bce));
                logger.debug("Added {} with {} polygons", className, polygons.size());
            } else {
                logger.debug("No geometry extracted for {} '{}' ({})", className, element.getName(), element.getGlobalId());
            }
        }
    }
}
