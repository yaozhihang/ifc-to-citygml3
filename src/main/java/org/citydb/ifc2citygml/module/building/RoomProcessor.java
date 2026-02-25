package org.citydb.ifc2citygml.module.building;

import org.bimserver.models.ifc4.IfcSpace;
import org.citydb.ifc2citygml.module.ConverterContext;
import org.citydb.ifc2citygml.module.ElementProcessor;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.building.BuildingRoom;
import org.citygml4j.core.model.building.BuildingRoomProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.basictypes.Code;

import java.util.ArrayList;
import java.util.List;

/**
 * Processes IfcSpace elements as BuildingRoom.
 */
class RoomProcessor implements ElementProcessor<Building> {

    private static final Logger logger = LoggerFactory.getLogger(RoomProcessor.class);

    @Override
    public void process(Building building, ConverterContext ctx) {
        List<IfcSpace> spaces = new ArrayList<>(ctx.model().getAll(IfcSpace.class));
        spaces.removeIf(e -> !ctx.currentElements().contains(e));
        logger.info("IfcSpace: processing {} rooms", spaces.size());

        for (IfcSpace space : spaces) {
            BuildingRoom room = new BuildingRoom();
            ctx.initializeCityObject(room, space);

            List<double[]> polygons = ctx.geometryHandler().getGeometry(space);

            if (!polygons.isEmpty()) {
                ctx.geometryHandler().addGeometryToObject(room, space, polygons);
                ctx.markExported(space);
                room.setClassifier(new Code("IfcSpace"));
                building.getBuildingRooms().add(new BuildingRoomProperty(room));
                logger.debug("Added IfcSpace (room) with {} polygons", polygons.size());
            } else {
                logger.debug("No geometry for IfcSpace '{}' ({})", space.getName(), space.getGlobalId());
            }
        }
    }
}
