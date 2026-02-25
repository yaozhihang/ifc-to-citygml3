package org.citydb.ifc2citygml.io;

import org.citydb.ifc2citygml.config.ConversionConfig;
import org.citydb.ifc2citygml.module.ConversionEnvironment;
import org.citydb.ifc2citygml.module.building.BuildingConverter;
import org.citydb.ifc2citygml.module.SpatialStructureConverter;
import org.citydb.ifc2citygml.util.*;

import org.bimserver.emf.IfcModelInterface;
import org.bimserver.models.ifc4.*;
import org.citygml4j.core.model.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.basictypes.Code;
import org.xmlobjects.gml.model.deprecated.StringOrRef;

import java.nio.file.Paths;
import java.util.*;

/**
 * Generates CityGML 3.0 output from IFC models using BIMServer and citygml4j
 */
public class CityGMLGenerator {

    private static final Logger logger = LoggerFactory.getLogger(CityGMLGenerator.class);

    private final String inputPath;
    private final String filename;
    private final String outputPath;
    private final ConversionConfig config;

    // Spatial structure converters
    private final List<SpatialStructureConverter> converters;

    // I/O delegates
    private final IfcModelReader modelReader;
    private final GeometryJsonLoader geometryLoader;
    private final CityGMLModelWriter modelWriter;

    public CityGMLGenerator(String inputPath, String outputPath, ConversionConfig config) {
        this.inputPath = inputPath;
        this.filename = Paths.get(inputPath).getFileName().toString();
        this.outputPath = outputPath;
        this.config = config;
        this.converters = List.of(
            new BuildingConverter(config.listUnmappedDoorsWindows(),
                    config.unrelatedDoorsWindowsInDummyBce(), config.noStoreys())
        );

        // I/O delegates
        this.modelReader = new IfcModelReader();
        this.geometryLoader = new GeometryJsonLoader();
        this.modelWriter = new CityGMLModelWriter();
    }

    /**
     * Main generation method
     */
    public void generate() throws Exception {
        // Run Python geometry extraction to a temp file
        geometryLoader.load(inputPath, config.reorientShells());

        // Load IFC model
        IfcModelInterface model = modelReader.loadModel(inputPath);

        // Initialize property converter and geometry handler
        PropertyHandler propertyHandler = new PropertyHandler(model, config.noProperties(),
                config.noGenericAttributeSets(), config.setNamesAsPrefixes());
        GeometryHandler geometryHandler = new GeometryHandler(model,
                geometryLoader.getGeometryCache(), geometryLoader.getMaterialCache(), config);

        // Build property set lookup map
        propertyHandler.buildPropertySetMap();

        // Setup georeferencing
        geometryHandler.setupGeoreferencing();

        // Create CityModel
        CityModel cityModel = new CityModel();

        // Add project metadata
        List<IfcProject> projects = model.getAll(IfcProject.class);
        if (!projects.isEmpty()) {
            IfcProject project = projects.get(0);
            if (project.getName() != null) {
                cityModel.getNames().add(new Code(project.getName()));
            }
            if (project.getDescription() != null) {
                cityModel.setDescription(new StringOrRef(project.getDescription()));
            }
        }

        // Process spatial structures (buildings, future: bridges, tunnels, ...)
        ConversionEnvironment env = new ConversionEnvironment(
                model, propertyHandler, geometryHandler, filename, config.noReferences());
        for (SpatialStructureConverter converter : converters) {
            cityModel.getCityObjectMembers().addAll(converter.convertAll(env));
        }

        // Write CityGML output
        modelWriter.write(cityModel, outputPath);

        if (config.georefOktoberfest()) {
            logger.info("Georeference used (EPSG:25832): Easting={}, Northing={}, Height={}",
                    geometryHandler.getEastings(), geometryHandler.getNorthings(),
                    geometryHandler.getOrthogonalHeight());
        }

        if (config.xOffset() != 0.0 || config.yOffset() != 0.0 || config.zOffset() != 0.0) {
            logger.info("Offset applied: X={}, Y={}, Z={}",
                    config.xOffset(), config.yOffset(), config.zOffset());
        }
    }

}
