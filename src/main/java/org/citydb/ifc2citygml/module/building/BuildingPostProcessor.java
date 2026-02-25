package org.citydb.ifc2citygml.module.building;

import org.bimserver.models.ifc4.*;
import org.citydb.ifc2citygml.module.ConverterContext;
import org.citygml4j.core.model.building.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.basictypes.Code;

import java.util.*;

/**
 * Handles post-processing after element processors have run:
 * unmapped doors/windows detection, dummy BCE creation, and storey processing.
 */
class BuildingPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(BuildingPostProcessor.class);

    private final boolean listUnmappedDoorsWindows;
    private final boolean unrelatedDoorsWindowsInDummyBce;
    private final boolean noStoreys;

    BuildingPostProcessor(boolean listUnmappedDoorsWindows,
                          boolean unrelatedDoorsWindowsInDummyBce,
                          boolean noStoreys) {
        this.listUnmappedDoorsWindows = listUnmappedDoorsWindows;
        this.unrelatedDoorsWindowsInDummyBce = unrelatedDoorsWindowsInDummyBce;
        this.noStoreys = noStoreys;
    }

    void postProcess(Building building, IfcBuilding ifcBuilding, ConverterContext ctx) {
        // Log unmapped doors/windows count (matching Python behavior)
        if (listUnmappedDoorsWindows || unrelatedDoorsWindowsInDummyBce) {
            listUnmappedDoorsAndWindows(ctx);
        }

        // Create dummy BCEs for unmapped doors/windows if requested
        Map<IfcBuildingStorey, BuildingConstructiveElement> dummyBcePerStorey = null;
        if (unrelatedDoorsWindowsInDummyBce) {
            dummyBcePerStorey = handleUnrelatedDoorsWindowsInDummyBce(building, ctx);
        }

        // Process building storeys if not disabled
        if (!noStoreys) {
            processBuildingStoreys(building, ifcBuilding, dummyBcePerStorey, ctx);
        }
    }

    private void listUnmappedDoorsAndWindows(ConverterContext ctx) {
        logger.info("=== Unmapped Doors and Windows ===");
        int count = 0;

        for (IfcDoor door : ctx.model().getAll(IfcDoor.class)) {
            if (!ctx.exportedElements().contains(door) && ctx.currentElements().contains(door)) {
                count++;
                logger.info("Unmapped Door: {} (GlobalId: {}, Name: {})",
                    door.getClass().getSimpleName(), door.getGlobalId(), door.getName());
                logElementConnections(door);
            }
        }

        for (IfcWindow window : ctx.model().getAll(IfcWindow.class)) {
            if (!ctx.exportedElements().contains(window) && ctx.currentElements().contains(window)) {
                count++;
                logger.info("Unmapped Window: {} (GlobalId: {}, Name: {})",
                    window.getClass().getSimpleName(), window.getGlobalId(), window.getName());
                logElementConnections(window);
            }
        }

        logger.info("Total unmapped doors/windows: {}", count);
    }

    private void logElementConnections(IfcElement element) {
        if (element.isSetFillsVoids()) {
            for (IfcRelFillsElement relFills : element.getFillsVoids()) {
                IfcOpeningElement opening = relFills.getRelatingOpeningElement();
                if (opening != null && opening.isSetVoidsElements()) {
                    IfcRelVoidsElement relVoids = opening.getVoidsElements();
                    if (relVoids != null) {
                        IfcElement host = relVoids.getRelatingBuildingElement();
                        if (host != null) {
                            logger.info("  -> Connected to: {} '{}' ({})",
                                host.getClass().getSimpleName(), host.getName(), host.getGlobalId());
                        }
                    }
                }
            }
        }
    }

    private Map<IfcBuildingStorey, BuildingConstructiveElement> handleUnrelatedDoorsWindowsInDummyBce(
            Building building, ConverterContext ctx) {
        Map<IfcBuildingStorey, BuildingConstructiveElement> dummyBcePerStorey = new HashMap<>();
        Map<IfcBuildingStorey, List<IfcElement>> unmappedPerStorey = new HashMap<>();
        List<IfcElement> unmappedNoStorey = new ArrayList<>();

        // Collect unmapped doors (filtered to current building)
        for (IfcDoor door : ctx.model().getAll(IfcDoor.class)) {
            if (!ctx.exportedElements().contains(door) && ctx.currentElements().contains(door)) {
                IfcBuildingStorey storey = findStoreyForElement(door);
                if (storey != null) {
                    unmappedPerStorey.computeIfAbsent(storey, k -> new ArrayList<>()).add(door);
                } else {
                    unmappedNoStorey.add(door);
                }
            }
        }

        // Collect unmapped windows (filtered to current building)
        for (IfcWindow window : ctx.model().getAll(IfcWindow.class)) {
            if (!ctx.exportedElements().contains(window) && ctx.currentElements().contains(window)) {
                IfcBuildingStorey storey = findStoreyForElement(window);
                if (storey != null) {
                    unmappedPerStorey.computeIfAbsent(storey, k -> new ArrayList<>()).add(window);
                } else {
                    unmappedNoStorey.add(window);
                }
            }
        }

        // Create dummy BCE per storey
        for (Map.Entry<IfcBuildingStorey, List<IfcElement>> entry : unmappedPerStorey.entrySet()) {
            String storeyName = entry.getKey().getName() != null ? entry.getKey().getName() : "Unnamed Storey";
            BuildingConstructiveElement dummyBce = createDummyBce(building, entry.getValue(),
                "Stub Element for unrelated Doors and Windows - Storey: " + storeyName, ctx);
            dummyBcePerStorey.put(entry.getKey(), dummyBce);
        }

        // Create fallback dummy BCE for elements without storey
        if (!unmappedNoStorey.isEmpty()) {
            createDummyBce(building, unmappedNoStorey,
                "Stub Element for unrelated Doors and Windows - No Storey Assignment", ctx);
        }

        return dummyBcePerStorey;
    }

    private BuildingConstructiveElement createDummyBce(Building building, List<IfcElement> elements,
                                                       String name, ConverterContext ctx) {
        BuildingConstructiveElement dummyBce = new BuildingConstructiveElement();
        String gmlId = "UUID_" + UUID.randomUUID().toString();
        dummyBce.setId(gmlId);
        dummyBce.getNames().add(new Code(name));

        for (IfcElement element : elements) {
            if (element instanceof IfcWindow) {
                FillingHandler.addWindowFilling(dummyBce, (IfcWindow) element, ctx);
            } else if (element instanceof IfcDoor) {
                FillingHandler.addDoorFilling(dummyBce, (IfcDoor) element, ctx);
            }
        }

        dummyBce.setClassifier(new Code("DummyBuildingConstructiveElement"));
        building.getBuildingConstructiveElements().add(new BuildingConstructiveElementProperty(dummyBce));
        logger.info("Created dummy BCE '{}' with {} fillings", name, elements.size());

        return dummyBce;
    }

    private IfcBuildingStorey findStoreyForElement(IfcElement element) {
        // Check ContainedInStructure
        if (element.isSetContainedInStructure()) {
            for (IfcRelContainedInSpatialStructure rel : element.getContainedInStructure()) {
                IfcSpatialElement spatial = rel.getRelatingStructure();
                if (spatial instanceof IfcBuildingStorey) {
                    return (IfcBuildingStorey) spatial;
                }
            }
        }

        // Check Decomposes
        if (element.isSetDecomposes()) {
            for (IfcRelAggregates rel : element.getDecomposes()) {
                IfcObjectDefinition parent = rel.getRelatingObject();
                if (parent instanceof IfcBuildingStorey) {
                    return (IfcBuildingStorey) parent;
                }
                // Recurse: check if parent is contained in a storey
                if (parent instanceof IfcElement) {
                    IfcBuildingStorey storey = findStoreyForElement((IfcElement) parent);
                    if (storey != null) return storey;
                }
            }
        }

        // Check via FillsVoids → Opening → VoidsElements → Host element
        if (element.isSetFillsVoids()) {
            for (IfcRelFillsElement relFills : element.getFillsVoids()) {
                IfcOpeningElement opening = relFills.getRelatingOpeningElement();
                if (opening != null && opening.isSetVoidsElements()) {
                    IfcRelVoidsElement relVoids = opening.getVoidsElements();
                    if (relVoids != null) {
                        IfcElement host = relVoids.getRelatingBuildingElement();
                        if (host != null) {
                            return findStoreyForElement(host);
                        }
                    }
                }
            }
        }

        return null;
    }

    private void processBuildingStoreys(Building building, IfcBuilding ifcBuilding,
                                         Map<IfcBuildingStorey, BuildingConstructiveElement> dummyBcePerStorey,
                                         ConverterContext ctx) {
        List<IfcBuildingStorey> storeys = new ArrayList<>(ctx.model().getAll(IfcBuildingStorey.class));
        storeys.removeIf(s -> !ctx.currentElements().contains(s));
        logger.info("IfcBuildingStorey: processing {} storeys", storeys.size());

        for (IfcBuildingStorey storey : storeys) {
            Storey cityStorey = new Storey();
            ctx.initializeCityObject(cityStorey, storey);

            // Collect all IFC elements belonging to this storey
            Set<IfcProduct> storeyElements = getStoreyElements(storey);

            // Add xlinks to exported constructive elements and rooms
            for (IfcProduct element : storeyElements) {
                if (!ctx.exportedElements().contains(element)) continue;
                String gmlId = ctx.elementGmlIds().get(element);
                if (gmlId == null) continue;

                if (element instanceof IfcSpace) {
                    cityStorey.getBuildingRooms().add(new BuildingRoomProperty("#" + gmlId));
                } else {
                    cityStorey.getBuildingConstructiveElements().add(
                        new BuildingConstructiveElementProperty("#" + gmlId));
                }
            }

            // Add xlink to dummy BCE for this storey if exists
            if (dummyBcePerStorey != null) {
                BuildingConstructiveElement dummyBce = dummyBcePerStorey.get(storey);
                if (dummyBce != null) {
                    cityStorey.getBuildingConstructiveElements().add(
                        new BuildingConstructiveElementProperty("#" + dummyBce.getId()));
                }
            }

            building.getBuildingSubdivisions().add(new AbstractBuildingSubdivisionProperty(cityStorey));
            logger.info("Added storey '{}' with {} xlinks",
                storey.getName(), cityStorey.getBuildingConstructiveElements().size()
                    + cityStorey.getBuildingRooms().size());
        }
    }

    private Set<IfcProduct> getStoreyElements(IfcBuildingStorey storey) {
        Set<IfcProduct> elements = new LinkedHashSet<>();
        collectStoreyElements(storey, elements);
        return elements;
    }

    private void collectStoreyElements(IfcObjectDefinition root, Set<IfcProduct> elements) {
        // Via IfcRelContainedInSpatialStructure
        if (root instanceof IfcSpatialStructureElement spatial) {
            if (spatial.isSetContainsElements()) {
                for (IfcRelContainedInSpatialStructure rel : spatial.getContainsElements()) {
                    for (IfcProduct product : rel.getRelatedElements()) {
                        elements.add(product);
                        collectStoreyElements(product, elements);
                    }
                }
            }
        }

        // Via IfcRelAggregates (decomposition) — recursive
        if (root.isSetIsDecomposedBy()) {
            for (IfcRelAggregates rel : root.getIsDecomposedBy()) {
                for (IfcObjectDefinition obj : rel.getRelatedObjects()) {
                    if (obj instanceof IfcProduct) {
                        elements.add((IfcProduct) obj);
                    }
                    collectStoreyElements(obj, elements);
                }
            }
        }
    }
}
