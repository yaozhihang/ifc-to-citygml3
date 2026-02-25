package com.tum.gis.ifc2citygml;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IFC to CityGML 3.0 Converter
 *
 * Written by Thomas H. Kolbe (thomas.kolbe@tum.de), Chair of Geoinformatics,
 * School of Engineering and Design, Technical University of Munich
 *
 * Java Port Version 0.9, Last change: 2026-02-18
 */
public class IFC2CityGMLConverter {

    private static final Logger logger = LoggerFactory.getLogger(IFC2CityGMLConverter.class);

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption(Option.builder("i")
                .longOpt("input")
                .hasArg()
                .required()
                .desc("Path to input IFC file")
                .build());

        options.addOption(Option.builder("o")
                .longOpt("output")
                .hasArg()
                .desc("Output path for CityGML file")
                .build());

        options.addOption(Option.builder()
                .longOpt("no-references")
                .desc("Do not export CityGML external references")
                .build());

        options.addOption(Option.builder()
                .longOpt("no-properties")
                .desc("Do not export property sets/generic attributes")
                .build());

        options.addOption(Option.builder()
                .longOpt("reorient-shells")
                .desc("Ensure that all solid boundary surfaces are oriented outwards (slows down processing!)")
                .build());

        options.addOption(Option.builder()
                .longOpt("georef-oktoberfest")
                .desc("Georeference model to Theresienwiese in Munich (EPSG:25832) to enable viewing in a GIS")
                .build());

        options.addOption(Option.builder()
                .longOpt("list-unmapped-doors-and-windows")
                .desc("List all doors and windows that could not be assigned to a BuildingConstructiveElement")
                .build());

        options.addOption(Option.builder()
                .longOpt("unrelated-doors-and-windows-in-dummy-bce")
                .desc("Put unrelated doors and windows in a dummy BuildingConstructiveElement")
                .build());

        options.addOption(Option.builder()
                .longOpt("no-generic-attribute-sets")
                .desc("Output IFC properties as direct generic attributes instead of wrapped in GenericAttributeSets")
                .build());

        options.addOption(Option.builder()
                .longOpt("pset-names-as-prefixes")
                .desc("Prefix property names with their property set name (e.g., [PSET_NAME]property_name)")
                .build());

        options.addOption(Option.builder()
                .longOpt("no-storeys")
                .desc("Do not export CityGML Storey objects")
                .build());

        options.addOption(Option.builder()
                .longOpt("xoffset")
                .hasArg()
                .type(Number.class)
                .desc("Offset to shift the model in X direction (applied after georeferencing)")
                .build());

        options.addOption(Option.builder()
                .longOpt("yoffset")
                .hasArg()
                .type(Number.class)
                .desc("Offset to shift the model in Y direction (applied after georeferencing)")
                .build());

        options.addOption(Option.builder()
                .longOpt("zoffset")
                .hasArg()
                .type(Number.class)
                .desc("Offset to shift the model in Z direction (applied after georeferencing)")
                .build());

        options.addOption(Option.builder()
                .longOpt("geometry-json")
                .hasArg()
                .desc("Path to pre-extracted geometry JSON file (from extract_geometry.py)")
                .build());

        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Print this help message")
                .build());

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("help")) {
                formatter.printHelp("ifc2citygml", options);
                return;
            }

            String inputPath = cmd.getOptionValue("input");
            String outputPath = cmd.getOptionValue("output");

            if (outputPath == null) {
                String baseName = inputPath.substring(0, inputPath.lastIndexOf('.'));
                outputPath = baseName + ".gml";
            }

            boolean noReferences = cmd.hasOption("no-references");
            boolean noProperties = cmd.hasOption("no-properties");
            boolean reorientShells = cmd.hasOption("reorient-shells");
            boolean georefOktoberfest = cmd.hasOption("georef-oktoberfest");
            boolean listUnmappedDoorsWindows = cmd.hasOption("list-unmapped-doors-and-windows");
            boolean unrelatedDoorsWindowsInDummyBce = cmd.hasOption("unrelated-doors-and-windows-in-dummy-bce");
            boolean noGenericAttributeSets = cmd.hasOption("no-generic-attribute-sets");
            boolean psetNamesAsPrefixes = cmd.hasOption("pset-names-as-prefixes");
            boolean noStoreys = cmd.hasOption("no-storeys");

            double xOffset = cmd.hasOption("xoffset") ?
                    ((Number) cmd.getParsedOptionValue("xoffset")).doubleValue() : 0.0;
            double yOffset = cmd.hasOption("yoffset") ?
                    ((Number) cmd.getParsedOptionValue("yoffset")).doubleValue() : 0.0;
            double zOffset = cmd.hasOption("zoffset") ?
                    ((Number) cmd.getParsedOptionValue("zoffset")).doubleValue() : 0.0;

            String geometryJsonPath = cmd.getOptionValue("geometry-json");

            CityGMLGenerator generator = new CityGMLGenerator(
                    inputPath, outputPath, noReferences, reorientShells, noProperties,
                    georefOktoberfest, listUnmappedDoorsWindows, unrelatedDoorsWindowsInDummyBce,
                    noGenericAttributeSets, psetNamesAsPrefixes, noStoreys,
                    xOffset, yOffset, zOffset, geometryJsonPath
            );

            generator.generate();

        } catch (ParseException e) {
            logger.error("Error parsing command line arguments: " + e.getMessage());
            formatter.printHelp("ifc2citygml", options);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Error during conversion: " + e.getMessage(), e);
            System.exit(1);
        }
    }
}
