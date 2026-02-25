package org.citydb.ifc2citygml.module;

import org.bimserver.emf.IfcModelInterface;
import org.bimserver.models.ifc4.IfcProduct;
import org.bimserver.models.ifc4.IfcRoot;
import org.citydb.ifc2citygml.util.GeometryHandler;
import org.citydb.ifc2citygml.util.PropertyHandler;
import org.citygml4j.core.model.core.*;
import org.xmlobjects.gml.model.basictypes.Code;
import org.xmlobjects.gml.model.deprecated.StringOrRef;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bundles the shared state and utility methods that all element processors need.
 */
public record ConverterContext(ConversionEnvironment env,
                               Set<IfcProduct> currentElements,
                               Set<IfcRoot> exportedElements,
                               Map<IfcRoot, String> elementGmlIds) {

    // --- Delegate accessors for convenience ---

    public IfcModelInterface model() { return env.model(); }
    public PropertyHandler propertyHandler() { return env.propertyHandler(); }
    public GeometryHandler geometryHandler() { return env.geometryHandler(); }

    /**
     * Assigns a GML ID, sets name/description, creates an external reference, adds
     * IFC properties, and registers the element for storey xlinks.
     *
     * @return the generated GML ID
     */
    public String initializeCityObject(AbstractCityObject obj, IfcProduct element) {
        return initializeCityObject(obj, element, true);
    }

    /**
     * Like {@link #initializeCityObject(AbstractCityObject, IfcProduct)} but allows
     * skipping element registration (e.g. for fillings embedded inside a BCE).
     */
    public String initializeCityObject(AbstractCityObject obj, IfcProduct element, boolean register) {
        String gmlId = "UUID_" + UUID.randomUUID().toString();
        obj.setId(gmlId);
        if (register) {
            registerElement(element, gmlId);
        }

        if (element.getName() != null) {
            obj.getNames().add(new Code(element.getName()));
        }
        if (element.getDescription() != null) {
            obj.setDescription(new StringOrRef(element.getDescription()));
        }

        createExternalReference(obj, element.getGlobalId());
        env.propertyHandler().addProperties(obj, element);
        return gmlId;
    }

    public void createExternalReference(AbstractCityObject cityObject, String ifcGuid) {
        if (env.noReferences()) {
            return;
        }

        ExternalReference extRef = new ExternalReference();
        extRef.setTargetResource(ifcGuid);
        extRef.setInformationSystem(env.filename());

        cityObject.getExternalReferences().add(new ExternalReferenceProperty(extRef));
    }

    public void registerElement(IfcRoot element, String gmlId) {
        elementGmlIds.put(element, gmlId);
    }

    public void markExported(IfcRoot element) {
        exportedElements.add(element);
    }

}
