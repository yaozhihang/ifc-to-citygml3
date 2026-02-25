package org.citydb.ifc2citygml.io;

import org.bimserver.emf.IfcModelInterface;
import org.bimserver.emf.PackageMetaData;
import org.bimserver.emf.Schema;
import org.bimserver.ifc.step.deserializer.Ifc4StepDeserializer;
import org.bimserver.models.ifc4.Ifc4Package;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Loads IFC models using BIMserver's IFC4 deserializer.
 * Handles IFC4X3 files by transparently converting the header to IFC4.
 */
public class IfcModelReader {

    private static final Logger logger = LoggerFactory.getLogger(IfcModelReader.class);

    /**
     * Loads an IFC model from the given file path.
     */
    // Streaming deserializer requires BIMserver's DatabaseInterface; not usable standalone
    @SuppressWarnings("deprecation")
    public IfcModelInterface loadModel(String inputPath) throws Exception {
        logger.info("Loading IFC file: {}", inputPath);

        // Construct PackageMetaData for IFC4 (use system temp dir for schema cache)
        Path schemaTmpDir = Files.createTempDirectory("bimserver-schema-");
        PackageMetaData packageMetaData = new PackageMetaData(
                Ifc4Package.eINSTANCE,
                Schema.IFC4,
                schemaTmpDir
        );

        // Create IFC4 deserializer
        Ifc4StepDeserializer deserializer = new Ifc4StepDeserializer(Schema.IFC4);
        deserializer.init(packageMetaData);

        // Read IFC file, detect IFC4X3 and convert header if needed
        File ifcFile = new File(inputPath);
        File fileToRead = preprocessIfcFile(ifcFile);
        IfcModelInterface model;
        try (FileInputStream fis = new FileInputStream(fileToRead)) {
            model = deserializer.read(fis, ifcFile.getName(), fileToRead.length(), null);
        } finally {
            // Delete temp file if one was created for IFC4X3 conversion
            if (fileToRead != ifcFile) {
                Files.delete(fileToRead.toPath());
            }
            // Clean up schema cache temp directory
            deleteDirectory(schemaTmpDir);
        }

        logger.info("IFC model loaded successfully. Schema: {}",
                model.getPackageMetaData().getSchema());
        return model;
    }

    /**
     * Detects IFC4X3 files and creates a temporary copy with the schema changed to IFC4.
     * BIMserver does not support IFC4X3, but IFC4X3 building entities are backward-compatible
     * with IFC4 for typical building models.
     */
    private File preprocessIfcFile(File ifcFile) throws IOException {
        // Read the first ~20 lines to check the FILE_SCHEMA
        boolean needsConversion = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(ifcFile), StandardCharsets.UTF_8))) {
            for (int i = 0; i < 20; i++) {
                String line = reader.readLine();
                if (line == null) break;
                if (line.contains("FILE_SCHEMA") && line.contains("IFC4X3")) {
                    needsConversion = true;
                    break;
                }
            }
        }

        if (!needsConversion) {
            return ifcFile;
        }

        logger.info("Detected IFC4X3 schema — converting header to IFC4 for BIMserver compatibility");

        // Create a temporary file with the schema rewritten
        Path tempFile = Files.createTempFile("ifc4x3_to_ifc4_", ".ifc");
        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new FileInputStream(ifcFile), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(tempFile.toFile()), StandardCharsets.UTF_8))) {
            String line;
            boolean inHeader = false;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("HEADER")) {
                    inHeader = true;
                }
                if (inHeader && line.contains("FILE_SCHEMA")) {
                    // Replace any IFC4X3 variant with IFC4
                    line = line.replaceAll("IFC4X3[A-Z0-9_]*", "IFC4");
                }
                if (line.startsWith("ENDSEC") && inHeader) {
                    inHeader = false;
                }
                writer.write(line);
                writer.newLine();
            }
        }

        return tempFile.toFile();
    }

    /** Recursively deletes a directory and its contents. */
    private static void deleteDirectory(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}
