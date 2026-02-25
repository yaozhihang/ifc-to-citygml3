package org.citydb.ifc2citygml.module;

import org.citygml4j.core.model.core.AbstractFeature;

/**
 * Processes a category of IFC elements and adds them to a CityGML city object.
 *
 * @param <T> the type of CityGML feature being built (e.g. Building)
 */
public interface ElementProcessor<T extends AbstractFeature> {
    void process(T cityObject, ConverterContext ctx);
}
