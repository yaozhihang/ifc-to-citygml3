package org.citydb.ifc2citygml.module.building;

import org.bimserver.models.ifc4.*;
import org.citydb.ifc2citygml.module.ConversionEnvironment;
import org.citydb.ifc2citygml.module.ConverterContext;
import org.citydb.ifc2citygml.module.ElementProcessor;
import org.citydb.ifc2citygml.module.SpatialStructureConverter;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.core.AbstractCityObjectProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts IfcBuilding instances into CityGML Building objects.
 */
public class BuildingConverter implements SpatialStructureConverter {

    private static final Logger logger = LoggerFactory.getLogger(BuildingConverter.class);

    private final List<ElementProcessor<Building>> processors;
    private final BuildingPostProcessor postProcessor;
    private boolean handlersRegistered;

    public BuildingConverter(boolean listUnmappedDoorsWindows,
                      boolean unrelatedDoorsWindowsInDummyBce,
                      boolean noStoreys) {
        this.postProcessor = new BuildingPostProcessor(
                listUnmappedDoorsWindows, unrelatedDoorsWindowsInDummyBce, noStoreys);
        this.processors = List.of(
            new WallProcessor(),
            ConstructiveElementProcessor.of(IfcSlab.class, "IfcSlab"),
            ConstructiveElementProcessor.of(IfcRoof.class, "IfcRoof"),
            ConstructiveElementProcessor.of(IfcBeam.class, "IfcBeam"),
            ConstructiveElementProcessor.of(IfcColumn.class, "IfcColumn"),
            ConstructiveElementProcessor.of(IfcStair.class, "IfcStair"),
            ConstructiveElementProcessor.of(IfcStairFlight.class, "IfcStairFlight"),
            ConstructiveElementProcessor.of(IfcRamp.class, "IfcRamp"),
            ConstructiveElementProcessor.of(IfcRampFlight.class, "IfcRampFlight"),
            ConstructiveElementProcessor.of(IfcCurtainWall.class, "IfcCurtainWall"),
            ConstructiveElementProcessor.of(IfcPlate.class, "IfcPlate"),
            ConstructiveElementProcessor.of(IfcMember.class, "IfcMember"),
            ConstructiveElementProcessor.of(IfcFooting.class, "IfcFooting"),
            ConstructiveElementProcessor.of(IfcPile.class, "IfcPile"),
            ConstructiveElementProcessor.of(IfcBuildingElementProxy.class, "IfcBuildingElementProxy"),
            InstallationElementProcessor.of(IfcCovering.class, "IfcCovering"),
            InstallationElementProcessor.of(IfcRailing.class, "IfcRailing"),
            new RoomProcessor(),
            new FurnitureProcessor()
        );
    }

    @Override
    public List<AbstractCityObjectProperty> convertAll(ConversionEnvironment env) {
        // Register building-specific predefined property set handlers (once)
        if (!handlersRegistered) {
            new BuildingPropertyHandler(env.propertyHandler()).register();
            handlersRegistered = true;
        }

        List<IfcBuilding> buildings = env.model().getAll(IfcBuilding.class);

        if (buildings.isEmpty()) {
            logger.warn("No IfcBuilding objects found in the model.");
            return List.of();
        }

        // Each building has independent state — process in parallel
        return buildings.parallelStream()
                .map(ifcBuilding -> convertBuilding(ifcBuilding, env))
                .map(AbstractCityObjectProperty::new)
                .collect(Collectors.toList());
    }

    private Building convertBuilding(IfcBuilding ifcBuilding, ConversionEnvironment env) {
        logger.info("Converting building: {}",
                ifcBuilding.getName() != null ? ifcBuilding.getName() : "Unnamed");

        // Build set of elements belonging to this building
        Set<IfcProduct> currentElements = getContainedElements(ifcBuilding);
        logger.info("Building decomposition: {} elements", currentElements.size());

        // Create per-building context
        ConverterContext ctx = new ConverterContext(
                env, currentElements, new HashSet<>(), new HashMap<>());

        // Initialize building object (ID, name, description, ext ref, properties)
        Building building = new Building();
        ctx.initializeCityObject(building, ifcBuilding);

        // Run all element processors
        for (ElementProcessor<Building> processor : processors) {
            processor.process(building, ctx);
        }

        // Run post-processing (unmapped doors/windows, storeys)
        postProcessor.postProcess(building, ifcBuilding, ctx);

        return building;
    }

    /**
     * Recursively collects all IfcProduct elements belonging to a spatial structure
     * via IfcRelAggregates (decomposition) and IfcRelContainedInSpatialStructure.
     */
    static Set<IfcProduct> getContainedElements(IfcObjectDefinition root) {
        Set<IfcProduct> result = new HashSet<>();
        if (root instanceof IfcProduct product) result.add(product);
        if (root.isSetIsDecomposedBy()) {
            for (IfcRelAggregates rel : root.getIsDecomposedBy()) {
                for (IfcObjectDefinition child : rel.getRelatedObjects()) {
                    result.addAll(getContainedElements(child));
                }
            }
        }
        if (root instanceof IfcSpatialStructureElement spatial) {
            if (spatial.isSetContainsElements()) {
                for (IfcRelContainedInSpatialStructure rel : spatial.getContainsElements()) {
                    for (IfcProduct product : rel.getRelatedElements()) {
                        if (product != null) {
                            result.add(product);
                            result.addAll(getContainedElements(product));
                        }
                    }
                }
            }
        }
        return result;
    }
}
