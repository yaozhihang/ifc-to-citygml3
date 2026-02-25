package org.citydb.ifc2citygml.module.building;

import org.bimserver.models.ifc4.IfcProduct;
import org.citydb.ifc2citygml.module.ConverterContext;
import org.citydb.ifc2citygml.module.ElementProcessor;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.building.BuildingInstallation;
import org.citygml4j.core.model.building.BuildingInstallationProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.basictypes.Code;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven processor for installation element types (covering, railing, etc.).
 */
class InstallationElementProcessor<T extends IfcProduct> implements ElementProcessor<Building> {

    private static final Logger logger = LoggerFactory.getLogger(InstallationElementProcessor.class);

    private final Class<T> ifcClass;
    private final String className;

    private InstallationElementProcessor(Class<T> ifcClass, String className) {
        this.ifcClass = ifcClass;
        this.className = className;
    }

    static <T extends IfcProduct> InstallationElementProcessor<T> of(Class<T> ifcClass, String className) {
        return new InstallationElementProcessor<>(ifcClass, className);
    }

    @Override
    public void process(Building building, ConverterContext ctx) {
        List<T> elements = new ArrayList<>(ctx.model().getAll(ifcClass));
        elements.removeIf(e -> !ctx.currentElements().contains(e));
        logger.info("{}: processing {} elements", className, elements.size());

        for (T element : elements) {
            BuildingInstallation installation = new BuildingInstallation();
            ctx.initializeCityObject(installation, element);

            List<double[]> polygons = ctx.geometryHandler().getGeometry(element);

            if (!polygons.isEmpty()) {
                ctx.geometryHandler().addGeometryToObject(installation, element, polygons);
                ctx.markExported(element);
                installation.setClassifier(new Code(className));
                building.getBuildingInstallations().add(new BuildingInstallationProperty(installation));
                logger.debug("Added {} with {} polygons", className, polygons.size());
            } else {
                logger.debug("No geometry extracted for {} '{}' ({})", className, element.getName(), element.getGlobalId());
            }
        }
    }
}
