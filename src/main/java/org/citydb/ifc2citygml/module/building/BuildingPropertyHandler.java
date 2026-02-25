package org.citydb.ifc2citygml.module.building;

import org.bimserver.models.ifc4.*;
import org.citydb.ifc2citygml.util.PropertyHandler;
import org.citygml4j.core.model.core.AbstractCityObject;
import org.citygml4j.core.model.core.AbstractGenericAttributeProperty;
import org.citygml4j.core.model.generics.DoubleAttribute;
import org.citygml4j.core.model.generics.StringAttribute;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles building-specific predefined property sets (window/door lining and panel properties).
 */
class BuildingPropertyHandler {

    private final PropertyHandler propertyHandler;

    BuildingPropertyHandler(PropertyHandler propertyHandler) {
        this.propertyHandler = propertyHandler;
    }

    /**
     * Registers this handler with the PropertyHandler for building-specific predefined pSet types.
     */
    void register() {
        propertyHandler.registerPredefinedPsetHandler((cityObject, propDef) -> {
            if (propDef instanceof IfcWindowLiningProperties) {
                addWindowLiningProperties(cityObject, (IfcWindowLiningProperties) propDef);
                return true;
            } else if (propDef instanceof IfcWindowPanelProperties) {
                addWindowPanelProperties(cityObject, (IfcWindowPanelProperties) propDef);
                return true;
            } else if (propDef instanceof IfcDoorLiningProperties) {
                addDoorLiningProperties(cityObject, (IfcDoorLiningProperties) propDef);
                return true;
            } else if (propDef instanceof IfcDoorPanelProperties) {
                addDoorPanelProperties(cityObject, (IfcDoorPanelProperties) propDef);
                return true;
            }
            return false;
        });
    }

    private void addWindowLiningProperties(AbstractCityObject cityObject, IfcWindowLiningProperties props) {
        String name = props.getName() != null ? props.getName() : "Fenster Linien-Sachmerkmale";
        List<AbstractGenericAttributeProperty> attrs = new ArrayList<>();
        if (props.isSetLiningDepth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningDepth", props.getLiningDepth())));
        if (props.isSetLiningThickness()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningThickness", props.getLiningThickness())));
        if (props.isSetTransomThickness()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("TransomThickness", props.getTransomThickness())));
        if (props.isSetMullionThickness()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("MullionThickness", props.getMullionThickness())));
        if (props.isSetFirstTransomOffset()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("FirstTransomOffset", props.getFirstTransomOffset())));
        if (props.isSetSecondTransomOffset()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("SecondTransomOffset", props.getSecondTransomOffset())));
        if (props.isSetFirstMullionOffset()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("FirstMullionOffset", props.getFirstMullionOffset())));
        if (props.isSetSecondMullionOffset()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("SecondMullionOffset", props.getSecondMullionOffset())));
        if (props.isSetLiningOffset()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningOffset", props.getLiningOffset())));
        if (props.isSetLiningToPanelOffsetX()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningToPanelOffsetX", props.getLiningToPanelOffsetX())));
        if (props.isSetLiningToPanelOffsetY()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningToPanelOffsetY", props.getLiningToPanelOffsetY())));
        propertyHandler.addPredefinedPset(cityObject, name, attrs);
    }

    private void addWindowPanelProperties(AbstractCityObject cityObject, IfcWindowPanelProperties props) {
        String name = props.getName() != null ? props.getName() : "Fenster Flügel-Sachmerkmale";
        List<AbstractGenericAttributeProperty> attrs = new ArrayList<>();
        if (props.getOperationType() != IfcWindowPanelOperationEnum.NULL) attrs.add(new AbstractGenericAttributeProperty(new StringAttribute("OperationType", props.getOperationType().getLiteral())));
        if (props.getPanelPosition() != IfcWindowPanelPositionEnum.NULL) attrs.add(new AbstractGenericAttributeProperty(new StringAttribute("PanelPosition", props.getPanelPosition().getLiteral())));
        if (props.isSetFrameDepth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("FrameDepth", props.getFrameDepth())));
        if (props.isSetFrameThickness()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("FrameThickness", props.getFrameThickness())));
        propertyHandler.addPredefinedPset(cityObject, name, attrs);
    }

    private void addDoorLiningProperties(AbstractCityObject cityObject, IfcDoorLiningProperties props) {
        String name = props.getName() != null ? props.getName() : "Tür Linien-Sachmerkmale";
        List<AbstractGenericAttributeProperty> attrs = new ArrayList<>();
        if (props.isSetLiningDepth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningDepth", props.getLiningDepth())));
        if (props.isSetLiningThickness()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningThickness", props.getLiningThickness())));
        if (props.isSetThresholdDepth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("ThresholdDepth", props.getThresholdDepth())));
        if (props.isSetThresholdThickness()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("ThresholdThickness", props.getThresholdThickness())));
        if (props.isSetTransomThickness()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("TransomThickness", props.getTransomThickness())));
        if (props.isSetTransomOffset()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("TransomOffset", props.getTransomOffset())));
        if (props.isSetLiningOffset()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningOffset", props.getLiningOffset())));
        if (props.isSetThresholdOffset()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("ThresholdOffset", props.getThresholdOffset())));
        if (props.isSetCasingThickness()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("CasingThickness", props.getCasingThickness())));
        if (props.isSetCasingDepth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("CasingDepth", props.getCasingDepth())));
        if (props.isSetLiningToPanelOffsetX()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningToPanelOffsetX", props.getLiningToPanelOffsetX())));
        if (props.isSetLiningToPanelOffsetY()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("LiningToPanelOffsetY", props.getLiningToPanelOffsetY())));
        propertyHandler.addPredefinedPset(cityObject, name, attrs);
    }

    private void addDoorPanelProperties(AbstractCityObject cityObject, IfcDoorPanelProperties props) {
        String name = props.getName() != null ? props.getName() : "Türblatt-Sachmerkmale";
        List<AbstractGenericAttributeProperty> attrs = new ArrayList<>();
        if (props.isSetPanelDepth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("PanelDepth", props.getPanelDepth())));
        if (props.getPanelOperation() != IfcDoorPanelOperationEnum.NULL) attrs.add(new AbstractGenericAttributeProperty(new StringAttribute("PanelOperation", props.getPanelOperation().getLiteral())));
        if (props.isSetPanelWidth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("PanelWidth", props.getPanelWidth())));
        if (props.getPanelPosition() != IfcDoorPanelPositionEnum.NULL) attrs.add(new AbstractGenericAttributeProperty(new StringAttribute("PanelPosition", props.getPanelPosition().getLiteral())));
        propertyHandler.addPredefinedPset(cityObject, name, attrs);
    }
}
