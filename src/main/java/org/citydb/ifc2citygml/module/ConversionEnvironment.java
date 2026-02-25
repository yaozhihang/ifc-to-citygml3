package org.citydb.ifc2citygml.module;

import org.bimserver.emf.IfcModelInterface;
import org.citydb.ifc2citygml.util.GeometryHandler;
import org.citydb.ifc2citygml.util.PropertyHandler;

/**
 * Immutable bundle of model-wide resources shared across all spatial structure converters.
 */
public record ConversionEnvironment(
        IfcModelInterface model,
        PropertyHandler propertyHandler,
        GeometryHandler geometryHandler,
        String filename,
        boolean noReferences
) {}
