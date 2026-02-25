package org.citydb.ifc2citygml.module;

import org.citygml4j.core.model.core.AbstractCityObjectProperty;

import java.util.List;

/**
 * Converts a category of IFC spatial structures (e.g. IfcBuilding, IfcBridge)
 * into corresponding CityGML top-level city objects.
 *
 * Implementations should be thread-safe: the shared ConversionEnvironment is read-only,
 * and each spatial structure instance should be converted with independent state.
 */
public interface SpatialStructureConverter {
    List<AbstractCityObjectProperty> convertAll(ConversionEnvironment env);
}
