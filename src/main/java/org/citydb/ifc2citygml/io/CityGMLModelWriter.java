package org.citydb.ifc2citygml.io;

import org.citygml4j.core.model.CityGMLVersion;
import org.citygml4j.core.model.core.CityModel;
import org.citygml4j.xml.CityGMLContext;
import org.citygml4j.xml.module.citygml.CoreModule;
import org.citygml4j.xml.writer.CityGMLOutputFactory;
import org.citygml4j.xml.writer.CityGMLWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Writes a CityModel to a CityGML 3.0 XML file.
 */
public class CityGMLModelWriter {

    private static final Logger logger = LoggerFactory.getLogger(CityGMLModelWriter.class);

    /**
     * Writes the given CityModel to a CityGML 3.0 XML file at the specified path.
     */
    public void write(CityModel cityModel, String outputPath) throws Exception {
        CityGMLContext context = CityGMLContext.newInstance();
        CityGMLOutputFactory out = context.createCityGMLOutputFactory();

        try (CityGMLWriter writer = out.createCityGMLWriter(new File(outputPath), "UTF-8")) {
            writer.withDefaultNamespace(CoreModule.of(CityGMLVersion.v3_0).getNamespaceURI())
                    .withDefaultSchemaLocations()
                    .withDefaultPrefixes()
                    .withIndent(" ");

            writer.write(cityModel);
        }

        logger.info("Successfully wrote {}", outputPath);
    }
}
