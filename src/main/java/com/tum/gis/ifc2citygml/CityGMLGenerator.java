package com.tum.gis.ifc2citygml;

import org.bimserver.emf.IfcModelInterface;
import org.bimserver.emf.PackageMetaData;
import org.bimserver.emf.Schema;
import org.bimserver.ifc.step.deserializer.Ifc4StepDeserializer;
import org.bimserver.models.ifc4.*;
import org.bimserver.models.ifc4.Ifc4Package;
import org.bimserver.models.ifc4.IfcMapConversion;
import org.bimserver.models.ifc4.IfcProjectedCRS;
import org.citygml4j.core.model.CityGMLVersion;
import org.citygml4j.core.model.building.*;
import org.citygml4j.core.model.construction.*;
import org.citygml4j.core.model.core.*;
import org.citygml4j.core.model.generics.*;
import org.citygml4j.xml.CityGMLContext;
import org.citygml4j.xml.module.citygml.CoreModule;
import org.citygml4j.xml.writer.CityGMLOutputFactory;
import org.citygml4j.xml.writer.CityGMLWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.basictypes.Code;
import org.xmlobjects.gml.model.deprecated.StringOrRef;
import org.xmlobjects.gml.model.geometry.DirectPositionList;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurface;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Generates CityGML 3.0 output from IFC models using BIMserver and citygml4j
 */
public class CityGMLGenerator {

    private static final Logger logger = LoggerFactory.getLogger(CityGMLGenerator.class);

    // IFC Representation Types that imply a Volumetric Solid
    private static final Set<String> SOLID_REPRESENTATION_TYPES = new HashSet<>(Arrays.asList(
            "SweptSolid",   // Extrusions, Revolutions
            "Brep",         // Boundary Representations (FacetedBrep)
            "AdvancedBrep", // NURBS / Advanced Solids
            "CSG",          // Constructive Solid Geometry
            "Clipping",     // Boolean results (Solid - Solid)
            "BoundingBox"   // Simplified solid box
    ));

    // Configuration parameters
    private final String inputPath;
    private final String filename;
    private final String outputPath;
    private final boolean noReferences;
    private final boolean reorientShells;
    private final boolean georefOktoberfest;
    private final boolean noProperties;
    private final boolean listUnmappedDoorsWindows;
    private final boolean unrelatedDoorsWindowsInDummyBce;
    private final boolean noGenericAttributeSets;
    private final boolean psetNamesAsPrefixes;
    private final boolean noStoreys;
    private final double xOffset;
    private final double yOffset;
    private final double zOffset;

    // IFC model
    private IfcModelInterface model;

    // Georeferencing parameters
    private double eastings = 0.0;
    private double northings = 0.0;
    private double orthogonalHeight = 0.0;
    private double scale = 1.0;
    private double[][] rotationMatrix = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    private String srsName = "EPSG:0";

    // Track exported elements
    private Map<IfcRoot, String> elementGmlIds = new HashMap<>();
    private Set<IfcRoot> exportedElements = new HashSet<>();
    // Elements belonging to the current building (via decomposition/containment)
    private Set<IfcProduct> currentBuildingElements;
    // Property set lookup (element -> list of property set definitions)
    // Built from IfcRelDefinesByProperties since BIMserver doesn't populate inverse relationships
    private Map<IfcObject, List<IfcPropertySetDefinitionSelect>> propertySetMap = new HashMap<>();

    // JSON geometry cache: GlobalId -> list of polygon coordinate arrays
    private Map<String, List<double[]>> jsonGeometryCache = null;

    public CityGMLGenerator(String inputPath, String outputPath,
                            boolean noReferences, boolean reorientShells, boolean noProperties,
                            boolean georefOktoberfest, boolean listUnmappedDoorsWindows,
                            boolean unrelatedDoorsWindowsInDummyBce,
                            boolean noGenericAttributeSets, boolean psetNamesAsPrefixes,
                            boolean noStoreys, double xOffset, double yOffset, double zOffset,
                            String geometryJsonPath) {
        this.inputPath = inputPath;
        this.filename = Paths.get(inputPath).getFileName().toString();
        this.outputPath = outputPath;
        this.noReferences = noReferences;
        this.reorientShells = reorientShells;
        this.georefOktoberfest = georefOktoberfest;
        this.noProperties = noProperties;
        this.listUnmappedDoorsWindows = listUnmappedDoorsWindows;
        this.unrelatedDoorsWindowsInDummyBce = unrelatedDoorsWindowsInDummyBce;
        this.noGenericAttributeSets = noGenericAttributeSets;
        this.psetNamesAsPrefixes = psetNamesAsPrefixes;
        this.noStoreys = noStoreys;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.zOffset = zOffset;

        // Load JSON geometry: explicit path > auto-detect in output dir > auto-run Python
        try {
            if (geometryJsonPath != null) {
                loadJsonGeometry(geometryJsonPath);
            } else {
                String baseName = Paths.get(inputPath).getFileName().toString().replaceAll("\\.[^.]+$", "");
                String outputDir = new File(outputPath).getParent();
                String autoPath = outputDir != null
                        ? outputDir + File.separator + baseName + ".geom.json"
                        : baseName + ".geom.json";

                if (new File(autoPath).exists()) {
                    logger.info("Auto-detected geometry JSON: {}", autoPath);
                    loadJsonGeometry(autoPath);
                } else {
                    // Try to run extract_geometry.py automatically, output to output dir
                    autoPath = runPythonGeometryExtraction(inputPath, autoPath);
                    if (autoPath != null) {
                        loadJsonGeometry(autoPath);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load JSON geometry: {}", e.getMessage());
        }
    }

    /**
     * Loads pre-extracted geometry from a JSON file produced by extract_geometry.py.
     * JSON format: { "GlobalId": [[x0,y0,z0, x1,y1,z1, ..., x0,y0,z0], ...], ... }
     */
    private void loadJsonGeometry(String jsonPath) throws IOException {
        logger.info("Loading geometry from JSON: {}", jsonPath);
        try (FileReader reader = new FileReader(jsonPath)) {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<
                    Map<String, List<List<Double>>>>(){}.getType();
            Map<String, List<List<Double>>> raw = gson.fromJson(reader, type);

            jsonGeometryCache = new HashMap<>();
            for (Map.Entry<String, List<List<Double>>> entry : raw.entrySet()) {
                List<double[]> polygons = new ArrayList<>();
                for (List<Double> poly : entry.getValue()) {
                    double[] arr = new double[poly.size()];
                    for (int i = 0; i < poly.size(); i++) arr[i] = poly.get(i);
                    polygons.add(arr);
                }
                jsonGeometryCache.put(entry.getKey(), polygons);
            }
            logger.info("Loaded JSON geometry for {} elements", jsonGeometryCache.size());
        }
    }

    /**
     * Runs extract_geometry.py as a subprocess to extract geometry from the IFC file.
     * Searches for the script in: distribution home (APP_HOME/), next to jar/classes, cwd.
     * Returns the output JSON path on success, null on failure.
     */
    private String runPythonGeometryExtraction(String ifcPath, String jsonOutputPath) {
        String scriptName = "extract_geometry.py";
        File scriptFile = null;

        // Try relative to the code location (works for distribution and gradle run)
        try {
            java.net.URL codeUrl = getClass().getProtectionDomain().getCodeSource().getLocation();
            File codeDir = new File(codeUrl.toURI()).getParentFile();
            // Distribution layout: APP_HOME/lib/app.jar -> APP_HOME/python/extract_geometry.py
            File candidate = new File(new File(codeDir.getParentFile(), "python"), scriptName);
            if (candidate.exists()) scriptFile = candidate;
            // Gradle run: build/classes/java/main -> project root/src/main/python/
            if (scriptFile == null) {
                File projectRoot = codeDir.getParentFile().getParentFile().getParentFile();
                candidate = new File(new File(projectRoot, "src/main/python"), scriptName);
                if (candidate.exists()) scriptFile = candidate;
            }
        } catch (Exception ignored) {}

        // Try current working directory
        if (scriptFile == null) {
            File cwd = new File(scriptName);
            if (cwd.exists()) scriptFile = cwd;
        }

        if (scriptFile == null) {
            logger.info("extract_geometry.py not found, skipping Python geometry extraction");
            return null;
        }

        logger.info("Running Python geometry extraction: {} -> {}", ifcPath, jsonOutputPath);

        // Try python3 (Linux/Docker), py (Windows launcher), python
        for (String pythonCmd : new String[]{"python3", "py", "python"}) {
            try {
                List<String> command = new ArrayList<>();
                command.add(pythonCmd);
                command.add(scriptFile.getAbsolutePath());
                command.add(ifcPath);
                command.add(jsonOutputPath);
                if (reorientShells) {
                    command.add("--reorient-shells");
                }
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                pb.inheritIO();
                Process process = pb.start();
                int exitCode = process.waitFor();

                if (exitCode == 0 && new File(jsonOutputPath).exists()) {
                    logger.info("Python geometry extraction completed successfully");
                    return jsonOutputPath;
                } else if (exitCode == 9009) {
                    // Windows Store alias or command not found — try next
                    continue;
                } else {
                    logger.warn("Python geometry extraction failed (exit code {})", exitCode);
                    return null; // python was found but extraction failed
                }
            } catch (IOException e) {
                // command not found, try next
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Python geometry extraction interrupted");
                return null;
            }
        }
        logger.info("Python not available, skipping geometry extraction");
        return null;
    }

    /**
     * Loads the IFC model using BIMserver
     */
    private void loadModel() throws Exception {
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
        try (FileInputStream fis = new FileInputStream(fileToRead)) {
            model = deserializer.read(fis, ifcFile.getName(), fileToRead.length(), null);
        } finally {
            // Delete temp file if one was created for IFC4X3 conversion
            if (fileToRead != ifcFile) {
                fileToRead.delete();
            }
            // Clean up schema cache temp directory
            deleteDirectory(schemaTmpDir);
        }

        logger.info("IFC model loaded successfully. Schema: {}",
                model.getPackageMetaData().getSchema());
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

    /**
     * Builds the property set lookup map from IfcRelDefinesByProperties and IfcRelDefinesByType.
     * BIMserver's STEP deserializer doesn't populate inverse relationships (IsDefinedBy),
     * so we query relationship classes directly.
     */
    private void buildPropertySetMap() {
        // Instance-level properties via IfcRelDefinesByProperties
        List<IfcRelDefinesByProperties> rels = model.getAll(IfcRelDefinesByProperties.class);
        for (IfcRelDefinesByProperties rel : rels) {
            IfcPropertySetDefinitionSelect propDef = rel.getRelatingPropertyDefinition();
            if (propDef == null) continue;

            for (IfcObjectDefinition obj : rel.getRelatedObjects()) {
                if (obj instanceof IfcObject) {
                    propertySetMap.computeIfAbsent((IfcObject) obj, k -> new ArrayList<>()).add(propDef);
                }
            }
        }

        // Type-level properties via IfcRelDefinesByType → IfcTypeObject → HasPropertySets
        List<IfcRelDefinesByType> typeRels = model.getAll(IfcRelDefinesByType.class);
        for (IfcRelDefinesByType rel : typeRels) {
            IfcTypeObject typeObj = rel.getRelatingType();
            if (typeObj == null || !typeObj.isSetHasPropertySets()) continue;

            for (IfcObjectDefinition obj : rel.getRelatedObjects()) {
                if (obj instanceof IfcObject) {
                    for (IfcPropertySetDefinition psetDef : typeObj.getHasPropertySets()) {
                        propertySetMap.computeIfAbsent((IfcObject) obj, k -> new ArrayList<>()).add(psetDef);
                    }
                }
            }
        }

        logger.info("Property set map: {} elements with properties (from {} instance rels, {} type rels)",
            propertySetMap.size(), rels.size(), typeRels.size());
    }

    /**
     * Sets up georeferencing from IFC map conversion
     */
    private void setupGeoreferencing() {
        try {
            List<IfcMapConversion> mapConversions = model.getAll(IfcMapConversion.class);

            if (!mapConversions.isEmpty()) {
                IfcMapConversion mc = mapConversions.get(0);
                this.eastings = mc.getEastings();
                this.northings = mc.getNorthings();
                this.orthogonalHeight = mc.getOrthogonalHeight();
                this.scale = mc.getScale() != 0 ? mc.getScale() : 1.0;

                if (mc.isSetXAxisAbscissa() && mc.isSetXAxisOrdinate()) {
                    double cosR = mc.getXAxisAbscissa();
                    double sinR = mc.getXAxisOrdinate();
                    this.rotationMatrix = new double[][]{
                            {cosR, -sinR, 0},
                            {sinR, cosR, 0},
                            {0, 0, 1}
                    };
                }

                List<IfcProjectedCRS> crsList = model.getAll(IfcProjectedCRS.class);
                if (!crsList.isEmpty() && crsList.get(0).getName() != null) {
                    this.srsName = crsList.get(0).getName();
                }
            } else {
                logger.info("No IfcMapConversion found. Using local coordinates.");
            }
        } catch (Exception e) {
            logger.warn("Error setting up georeferencing: {}", e.getMessage());
        }

        // Override with Oktoberfest coordinates if requested
        if (georefOktoberfest) {
            this.eastings = 689738.0;
            this.northings = 5334100.0;
            this.orthogonalHeight = 521.0;
            this.srsName = "EPSG:25832";
            logger.info("Georeference set to Theresienwiese in Munich (EPSG:25832): E={}, N={}, H={}",
                    eastings, northings, orthogonalHeight);
        }
    }

    /**
     * Transforms a vertex using georeferencing parameters
     */
    private double[] transformVertex(double[] vertex) {
        double[] v = new double[3];

        // Scale
        v[0] = vertex[0] * scale;
        v[1] = vertex[1] * scale;
        v[2] = vertex[2] * scale;

        // Rotate
        double[] rotated = new double[3];
        for (int i = 0; i < 3; i++) {
            rotated[i] = rotationMatrix[i][0] * v[0] +
                    rotationMatrix[i][1] * v[1] +
                    rotationMatrix[i][2] * v[2];
        }

        // Translate
        rotated[0] += eastings + xOffset;
        rotated[1] += northings + yOffset;
        rotated[2] += orthogonalHeight + zOffset;

        return rotated;
    }

    /**
     * Creates an external reference to the IFC element
     */
    private void createExternalReference(AbstractCityObject cityObject, String ifcGuid) {
        if (noReferences) {
            return;
        }

        ExternalReference extRef = new ExternalReference();
        extRef.setTargetResource(ifcGuid);
        extRef.setInformationSystem(filename);

        cityObject.getExternalReferences().add(new ExternalReferenceProperty(extRef));
    }

    /**
     * Adds IFC properties as CityGML generic attributes
     */
    private void addProperties(AbstractCityObject cityObject, IfcRoot ifcElement) {
        if (noProperties) {
            return;
        }

        if (!(ifcElement instanceof IfcObject)) {
            return;
        }

        IfcObject ifcObject = (IfcObject) ifcElement;
        List<IfcPropertySetDefinitionSelect> propDefs = propertySetMap.get(ifcObject);
        if (propDefs == null || propDefs.isEmpty()) {
            return;
        }

        for (IfcPropertySetDefinitionSelect propDef : propDefs) {
            if (propDef instanceof IfcPropertySet) {
                addPropertySet(cityObject, (IfcPropertySet) propDef);
            } else if (propDef instanceof IfcElementQuantity) {
                addElementQuantity(cityObject, (IfcElementQuantity) propDef);
            } else if (propDef instanceof IfcWindowLiningProperties) {
                addWindowLiningProperties(cityObject, (IfcWindowLiningProperties) propDef);
            } else if (propDef instanceof IfcWindowPanelProperties) {
                addWindowPanelProperties(cityObject, (IfcWindowPanelProperties) propDef);
            } else if (propDef instanceof IfcDoorLiningProperties) {
                addDoorLiningProperties(cityObject, (IfcDoorLiningProperties) propDef);
            } else if (propDef instanceof IfcDoorPanelProperties) {
                addDoorPanelProperties(cityObject, (IfcDoorPanelProperties) propDef);
            }
        }
    }

    /**
     * Adds an IFC property set as a CityGML GenericAttributeSet
     */
    private void addPropertySet(AbstractCityObject cityObject, IfcPropertySet pset) {
        String psetName = pset.getName() != null ? pset.getName() : "UnnamedPropertySet";
        List<AbstractGenericAttributeProperty> attributes = new ArrayList<>();

        for (IfcProperty prop : pset.getHasProperties()) {
            String propName = prop.getName();
            if (propName == null || propName.equals("id")) continue;

            AbstractGenericAttribute<?> attr = null;

            if (prop instanceof IfcPropertySingleValue) {
                IfcValue value = ((IfcPropertySingleValue) prop).getNominalValue();
                if (value != null) {
                    attr = convertIfcValueToAttribute(propName, value);
                }
            } else if (prop instanceof IfcPropertyEnumeratedValue) {
                IfcPropertyEnumeratedValue ev = (IfcPropertyEnumeratedValue) prop;
                if (ev.isSetEnumerationValues()) {
                    StringBuilder sb = new StringBuilder();
                    for (IfcValue val : ev.getEnumerationValues()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(ifcValueToString(val));
                    }
                    attr = new StringAttribute(propName, sb.toString());
                }
            } else if (prop instanceof IfcPropertyBoundedValue) {
                IfcPropertyBoundedValue bv = (IfcPropertyBoundedValue) prop;
                IfcValue upper = bv.getUpperBoundValue();
                IfcValue lower = bv.getLowerBoundValue();
                if (upper != null) {
                    attr = convertIfcValueToAttribute(propName, upper);
                } else if (lower != null) {
                    attr = convertIfcValueToAttribute(propName, lower);
                }
            } else if (prop instanceof IfcPropertyListValue) {
                IfcPropertyListValue lv = (IfcPropertyListValue) prop;
                if (lv.isSetListValues()) {
                    StringBuilder sb = new StringBuilder();
                    for (IfcValue val : lv.getListValues()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(ifcValueToString(val));
                    }
                    attr = new StringAttribute(propName, sb.toString());
                }
            } else if (prop instanceof IfcPropertyTableValue) {
                IfcPropertyTableValue tv = (IfcPropertyTableValue) prop;
                StringBuilder sb = new StringBuilder();
                if (tv.isSetDefiningValues()) {
                    for (IfcValue val : tv.getDefiningValues()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(ifcValueToString(val));
                    }
                }
                if (sb.length() > 0) {
                    attr = new StringAttribute(propName, sb.toString());
                }
            } else if (prop instanceof IfcPropertyReferenceValue) {
                IfcPropertyReferenceValue rv = (IfcPropertyReferenceValue) prop;
                if (rv.getPropertyReference() != null) {
                    attr = new StringAttribute(propName, rv.getPropertyReference().toString());
                }
            }

            if (attr != null) {
                if (noGenericAttributeSets) {
                    String name = psetNamesAsPrefixes ? "[" + psetName + "]" + propName : propName;
                    attr.setName(name);
                    cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attr));
                } else {
                    attributes.add(new AbstractGenericAttributeProperty(attr));
                }
            }
        }

        if (!noGenericAttributeSets && !attributes.isEmpty()) {
            GenericAttributeSet attrSet = new GenericAttributeSet();
            attrSet.setName(psetName);
            attrSet.setValue(attributes);
            cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attrSet));
        }
    }

    /**
     * Adds an IFC element quantity set as a CityGML GenericAttributeSet
     */
    private void addElementQuantity(AbstractCityObject cityObject, IfcElementQuantity eq) {
        String qtoName = eq.getName() != null ? eq.getName() : "UnnamedQuantitySet";
        List<AbstractGenericAttributeProperty> attributes = new ArrayList<>();

        for (IfcPhysicalQuantity qty : eq.getQuantities()) {
            String qtyName = qty.getName();
            if (qtyName == null) continue;

            AbstractGenericAttribute<?> attr = null;
            if (qty instanceof IfcQuantityLength) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityLength) qty).getLengthValue());
            } else if (qty instanceof IfcQuantityArea) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityArea) qty).getAreaValue());
            } else if (qty instanceof IfcQuantityVolume) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityVolume) qty).getVolumeValue());
            } else if (qty instanceof IfcQuantityCount) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityCount) qty).getCountValue());
            } else if (qty instanceof IfcQuantityWeight) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityWeight) qty).getWeightValue());
            } else if (qty instanceof IfcQuantityTime) {
                attr = new DoubleAttribute(qtyName, ((IfcQuantityTime) qty).getTimeValue());
            }

            if (attr != null) {
                if (noGenericAttributeSets) {
                    String name = psetNamesAsPrefixes ? "[" + qtoName + "]" + qtyName : qtyName;
                    attr.setName(name);
                    cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attr));
                } else {
                    attributes.add(new AbstractGenericAttributeProperty(attr));
                }
            }
        }

        if (!noGenericAttributeSets && !attributes.isEmpty()) {
            GenericAttributeSet attrSet = new GenericAttributeSet();
            attrSet.setName(qtoName);
            attrSet.setValue(attributes);
            cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attrSet));
        }
    }

    /**
     * Helper to add a predefined property set as a GenericAttributeSet
     */
    private void addPredefinedPset(AbstractCityObject cityObject, String psetName,
                                    List<AbstractGenericAttributeProperty> attributes) {
        if (attributes.isEmpty()) return;

        if (noGenericAttributeSets) {
            for (AbstractGenericAttributeProperty attr : attributes) {
                if (psetNamesAsPrefixes) {
                    AbstractGenericAttribute<?> ga = attr.getObject();
                    if (ga != null) ga.setName("[" + psetName + "]" + ga.getName());
                }
                cityObject.getGenericAttributes().add(attr);
            }
        } else {
            GenericAttributeSet attrSet = new GenericAttributeSet();
            attrSet.setName(psetName);
            attrSet.setValue(attributes);
            cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(attrSet));
        }
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
        addPredefinedPset(cityObject, name, attrs);
    }

    private void addWindowPanelProperties(AbstractCityObject cityObject, IfcWindowPanelProperties props) {
        String name = props.getName() != null ? props.getName() : "Fenster Flügel-Sachmerkmale";
        List<AbstractGenericAttributeProperty> attrs = new ArrayList<>();
        if (props.getOperationType() != IfcWindowPanelOperationEnum.NULL) attrs.add(new AbstractGenericAttributeProperty(new StringAttribute("OperationType", props.getOperationType().getLiteral())));
        if (props.getPanelPosition() != IfcWindowPanelPositionEnum.NULL) attrs.add(new AbstractGenericAttributeProperty(new StringAttribute("PanelPosition", props.getPanelPosition().getLiteral())));
        if (props.isSetFrameDepth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("FrameDepth", props.getFrameDepth())));
        if (props.isSetFrameThickness()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("FrameThickness", props.getFrameThickness())));
        addPredefinedPset(cityObject, name, attrs);
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
        addPredefinedPset(cityObject, name, attrs);
    }

    private void addDoorPanelProperties(AbstractCityObject cityObject, IfcDoorPanelProperties props) {
        String name = props.getName() != null ? props.getName() : "Türblatt-Sachmerkmale";
        List<AbstractGenericAttributeProperty> attrs = new ArrayList<>();
        if (props.isSetPanelDepth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("PanelDepth", props.getPanelDepth())));
        if (props.getPanelOperation() != IfcDoorPanelOperationEnum.NULL) attrs.add(new AbstractGenericAttributeProperty(new StringAttribute("PanelOperation", props.getPanelOperation().getLiteral())));
        if (props.isSetPanelWidth()) attrs.add(new AbstractGenericAttributeProperty(new DoubleAttribute("PanelWidth", props.getPanelWidth())));
        if (props.getPanelPosition() != IfcDoorPanelPositionEnum.NULL) attrs.add(new AbstractGenericAttributeProperty(new StringAttribute("PanelPosition", props.getPanelPosition().getLiteral())));
        addPredefinedPset(cityObject, name, attrs);
    }

    /**
     * Converts an IFC value to a CityGML generic attribute
     */
    private AbstractGenericAttribute<?> convertIfcValueToAttribute(String name, IfcValue value) {
        if (value instanceof IfcBoolean) {
            Tristate ts = ((IfcBoolean) value).getWrappedValue();
            return new IntAttribute(name, ts == Tristate.TRUE ? 1 : 0);
        } else if (value instanceof IfcLogical) {
            Tristate ts = ((IfcLogical) value).getWrappedValue();
            return new IntAttribute(name, ts == Tristate.TRUE ? 1 : 0);
        } else if (value instanceof IfcInteger) {
            return new IntAttribute(name, (int) ((IfcInteger) value).getWrappedValue());
        } else if (value instanceof IfcReal) {
            return new DoubleAttribute(name, ((IfcReal) value).getWrappedValue());
        } else if (value instanceof IfcLabel) {
            String s = ((IfcLabel) value).getWrappedValue();
            return s != null ? new StringAttribute(name, s) : null;
        } else if (value instanceof IfcText) {
            String s = ((IfcText) value).getWrappedValue();
            return s != null ? new StringAttribute(name, s) : null;
        } else if (value instanceof IfcIdentifier) {
            String s = ((IfcIdentifier) value).getWrappedValue();
            return s != null ? new StringAttribute(name, s) : null;
        } else if (value instanceof IfcLengthMeasure) {
            return new DoubleAttribute(name, ((IfcLengthMeasure) value).getWrappedValue());
        } else if (value instanceof IfcAreaMeasure) {
            return new DoubleAttribute(name, ((IfcAreaMeasure) value).getWrappedValue());
        } else if (value instanceof IfcVolumeMeasure) {
            return new DoubleAttribute(name, ((IfcVolumeMeasure) value).getWrappedValue());
        } else if (value instanceof IfcCountMeasure) {
            return new DoubleAttribute(name, ((IfcCountMeasure) value).getWrappedValue());
        } else if (value instanceof IfcPlaneAngleMeasure) {
            return new DoubleAttribute(name, ((IfcPlaneAngleMeasure) value).getWrappedValue());
        } else if (value instanceof IfcMassMeasure) {
            return new DoubleAttribute(name, ((IfcMassMeasure) value).getWrappedValue());
        } else if (value instanceof IfcPowerMeasure) {
            return new DoubleAttribute(name, ((IfcPowerMeasure) value).getWrappedValue());
        } else if (value instanceof IfcThermalTransmittanceMeasure) {
            return new DoubleAttribute(name, ((IfcThermalTransmittanceMeasure) value).getWrappedValue());
        } else if (value instanceof IfcThermodynamicTemperatureMeasure) {
            return new DoubleAttribute(name, ((IfcThermodynamicTemperatureMeasure) value).getWrappedValue());
        } else if (value instanceof IfcPositiveLengthMeasure) {
            return new DoubleAttribute(name, ((IfcPositiveLengthMeasure) value).getWrappedValue());
        } else if (value instanceof IfcMeasureValue) {
            // Generic fallback for all remaining measure types
            try {
                java.lang.reflect.Method m = value.getClass().getMethod("getWrappedValue");
                Object result = m.invoke(value);
                if (result instanceof Number) {
                    return new DoubleAttribute(name, ((Number) result).doubleValue());
                }
            } catch (Exception e) {
                // Fall through to string
            }
            return new StringAttribute(name, value.toString());
        } else {
            // Fallback: try to get string representation
            return new StringAttribute(name, value.toString());
        }
    }

    /**
     * Converts an IFC value to its string representation
     */
    private String ifcValueToString(IfcValue value) {
        if (value instanceof IfcLabel) return ((IfcLabel) value).getWrappedValue();
        if (value instanceof IfcText) return ((IfcText) value).getWrappedValue();
        if (value instanceof IfcIdentifier) return ((IfcIdentifier) value).getWrappedValue();
        if (value instanceof IfcBoolean) return ((IfcBoolean) value).getWrappedValue() == Tristate.TRUE ? "True" : "False";
        if (value instanceof IfcInteger) return String.valueOf(((IfcInteger) value).getWrappedValue());
        if (value instanceof IfcReal) return String.valueOf(((IfcReal) value).getWrappedValue());
        return value.toString();
    }

    /**
     * Checks if the element is intended to be a solid based on IFC representation type
     */
    private boolean isIntendedSolid(IfcProduct element) {
        if (element.getRepresentation() == null) {
            return false;
        }

        for (IfcRepresentation rep : element.getRepresentation().getRepresentations()) {
            String repId = rep.getRepresentationIdentifier();
            if (repId != null && !repId.toLowerCase().matches("body|mesh|facetedbrep")) {
                continue;
            }

            String repType = rep.getRepresentationType();
            if (repType != null && SOLID_REPRESENTATION_TYPES.contains(repType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets geometry for an IFC element from the JSON geometry cache.
     * Returns list of polygons, where each polygon is a flat array of georeferenced coordinates.
     */
    private List<double[]> getGeometry(IfcProduct element) {
        if (jsonGeometryCache == null) return new ArrayList<>();
        List<double[]> cached = jsonGeometryCache.get(element.getGlobalId());
        if (cached == null || cached.isEmpty()) return new ArrayList<>();

        List<double[]> transformed = new ArrayList<>();
        for (double[] poly : cached) {
            double[] tPoly = new double[poly.length];
            for (int i = 0; i < poly.length; i += 3) {
                double[] v = transformVertex(new double[]{poly[i], poly[i + 1], poly[i + 2]});
                tPoly[i] = v[0];
                tPoly[i + 1] = v[1];
                tPoly[i + 2] = v[2];
            }
            transformed.add(tPoly);
        }
        return transformed;
    }

    /**
     * Creates a GML Polygon from coordinate array
     */
    private Polygon createPolygon(double[] coords) {
        List<Double> posList = new ArrayList<>();
        for (double coord : coords) {
            posList.add(Math.round(coord * 1000.0) / 1000.0);
        }


        LinearRing linearRing = new LinearRing();

        DirectPositionList directPosition = new DirectPositionList(posList);
        directPosition.setSrsDimension(3);
        linearRing.setControlPoints(new org.xmlobjects.gml.model.geometry.GeometricPositionList(directPosition));

        Polygon polygon = new Polygon();
        polygon.setExterior(new AbstractRingProperty(linearRing));
        polygon.setSrsName(srsName);
        polygon.setSrsDimension(3);
        polygon.setId("UUID_" + UUID.randomUUID().toString());

        return polygon;
    }

    /**
     * Main generation method
     */
    public void generate() throws Exception {
        // Load IFC model
        loadModel();

        // Build property set lookup map
        buildPropertySetMap();

        // Setup georeferencing
        setupGeoreferencing();

        // Create CityGML context
        CityGMLContext context = CityGMLContext.newInstance();

        CityGMLOutputFactory out = context.createCityGMLOutputFactory();

        // Create output file
        try (CityGMLWriter writer = out.createCityGMLWriter(new File(outputPath), "UTF-8")) {

            writer.withDefaultNamespace(CoreModule.of(CityGMLVersion.v3_0).getNamespaceURI())
                    .withDefaultSchemaLocations()
                    .withDefaultPrefixes()
                    .withIndent(" ");

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

            // Process each building
            List<IfcBuilding> buildings = model.getAll(IfcBuilding.class);

            if (buildings.isEmpty()) {
                logger.warn("No IfcBuilding objects found in the model.");
            } else {
                for (IfcBuilding ifcBuilding : buildings) {
                    Building building = processBuilding(ifcBuilding);
                    if (building != null) {
                        cityModel.getCityObjectMembers().add(new AbstractCityObjectProperty(building));
                    }
                }
            }

            // Write CityModel
            writer.write(cityModel);

            logger.info("Successfully wrote {}", outputPath);

            if (georefOktoberfest) {
                logger.info("Georeference used (EPSG:25832): Easting={}, Northing={}, Height={}",
                        eastings, northings, orthogonalHeight);
            }

            if (xOffset != 0.0 || yOffset != 0.0 || zOffset != 0.0) {
                logger.info("Offset applied: X={}, Y={}, Z={}", xOffset, yOffset, zOffset);
            }
        }
    }

    /**
     * Recursively collects all IfcProduct elements belonging to a building
     * via IfcRelAggregates (decomposition) and IfcRelContainedInSpatialStructure.
     */
    private Set<IfcProduct> getBuildingElements(IfcObjectDefinition root) {
        Set<IfcProduct> result = new HashSet<>();
        if (root instanceof IfcProduct) result.add((IfcProduct) root);
        if (root.isSetIsDecomposedBy()) {
            for (IfcRelAggregates rel : root.getIsDecomposedBy()) {
                for (IfcObjectDefinition child : rel.getRelatedObjects()) {
                    result.addAll(getBuildingElements(child));
                }
            }
        }
        if (root instanceof IfcSpatialStructureElement) {
            IfcSpatialStructureElement spatial = (IfcSpatialStructureElement) root;
            if (spatial.isSetContainsElements()) {
                for (IfcRelContainedInSpatialStructure rel : spatial.getContainsElements()) {
                    for (IfcProduct product : rel.getRelatedElements()) {
                        result.add(product);
                        if (product instanceof IfcObjectDefinition) {
                            result.addAll(getBuildingElements(product));
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Processes a single IFC building
     */
    private Building processBuilding(IfcBuilding ifcBuilding) {
        logger.info("Converting building: {}",
                ifcBuilding.getName() != null ? ifcBuilding.getName() : "Unnamed");

        Building building = new Building();
        building.setId("UUID_" + UUID.randomUUID().toString());

        // Reset per-building tracking
        exportedElements = new HashSet<>();
        elementGmlIds = new HashMap<>();

        // Build set of elements belonging to this building
        currentBuildingElements = getBuildingElements(ifcBuilding);
        logger.info("Building decomposition: {} elements", currentBuildingElements.size());

        // Add metadata
        if (ifcBuilding.getName() != null) {
            building.getNames().add(new Code(ifcBuilding.getName()));
        }
        if (ifcBuilding.getDescription() != null) {
            building.setDescription(new StringOrRef(ifcBuilding.getDescription()));
        }

        // Add external reference
        createExternalReference(building, ifcBuilding.getGlobalId());

        // Add properties
        addProperties(building, ifcBuilding);

        // Process all building element types
        processWalls(building, ifcBuilding);
        processSlabs(building, ifcBuilding);
        processRoofs(building, ifcBuilding);
        processBeams(building, ifcBuilding);
        processColumns(building, ifcBuilding);
        processStairs(building, ifcBuilding);
        processStairFlights(building, ifcBuilding);
        processRamps(building, ifcBuilding);
        processRampFlights(building, ifcBuilding);
        processCurtainWalls(building, ifcBuilding);
        processPlates(building, ifcBuilding);
        processMembers(building, ifcBuilding);
        processFootings(building, ifcBuilding);
        processPiles(building, ifcBuilding);
        processBuildingElementProxies(building, ifcBuilding);
        // BuildingInstallation types
        processCoverings(building, ifcBuilding);
        processRailings(building, ifcBuilding);
        // Rooms
        processRooms(building, ifcBuilding);
        // Furniture
        processFurniture(building, ifcBuilding);

        // Log unmapped doors/windows count (matching Python behavior)
        if (listUnmappedDoorsWindows || unrelatedDoorsWindowsInDummyBce) {
            listUnmappedDoorsAndWindows();
        }

        // Create dummy BCEs for unmapped doors/windows if requested
        Map<IfcBuildingStorey, BuildingConstructiveElement> dummyBcePerStorey = null;
        if (unrelatedDoorsWindowsInDummyBce) {
            dummyBcePerStorey = handleUnrelatedDoorsWindowsInDummyBce(building);
        }

        // Process building storeys if not disabled
        if (!noStoreys) {
            processBuildingStoreys(building, ifcBuilding, dummyBcePerStorey);
        }
        return building;
    }

    /**
     * Processes walls in a building
     */
    private void processWalls(Building building, IfcBuilding ifcBuilding) {
        // Collect both IfcWallStandardCase and IfcWall (non-standard-case)
        List<IfcWall> walls = new ArrayList<>();
        walls.addAll(model.getAll(IfcWallStandardCase.class));
        for (IfcWall w : model.getAll(IfcWall.class)) {
            if (!(w instanceof IfcWallStandardCase)) {
                walls.add(w);
            }
        }
        walls.removeIf(e -> !currentBuildingElements.contains(e));

        logger.info("IfcWall: processing {} walls", walls.size());

        for (IfcWall wall : walls) {
            BuildingConstructiveElement bce = new BuildingConstructiveElement();
            String gmlId = "UUID_" + UUID.randomUUID().toString();
            bce.setId(gmlId);
            elementGmlIds.put(wall, gmlId);

            // Add metadata
            if (wall.getName() != null) {
                bce.getNames().add(new Code(wall.getName()));
            }
            if (wall.getDescription() != null) {
                bce.setDescription(new StringOrRef(wall.getDescription()));
            }

            createExternalReference(bce, wall.getGlobalId());
            addProperties(bce, wall);

            // Get geometry
            List<double[]> polygons = getGeometry(wall);

            if (!polygons.isEmpty()) {
                addGeometryToObject(bce, wall, polygons);
                exportedElements.add(wall);
            }

            // Embed doors/windows as con:filling inside the wall BCE
            embedFillings(bce, wall);

            // Set class (must come after con:filling for schema order)
            String wallClass = (wall instanceof IfcWallStandardCase) ? "IfcWallStandardCase" : "IfcWall";
            bce.setClassifier(new Code(wallClass));
            building.getBuildingConstructiveElements().add(new BuildingConstructiveElementProperty(bce));

            if (!polygons.isEmpty()) {
                logger.info("Added wall '{}' (IFC: {}) with {} polygons",
                    wall.getName(), wall.getGlobalId(), polygons.size());
            } else {
                logger.debug("Added wall '{}' (IFC: {}) without geometry",
                    wall.getName(), wall.getGlobalId());
            }
        }
    }

    /**
     * Embeds doors/windows found via HasOpenings as con:filling in a BCE.
     */
    private void embedFillings(BuildingConstructiveElement bce, IfcElement element) {
        if (!element.isSetHasOpenings()) return;

        for (IfcRelVoidsElement relVoids : element.getHasOpenings()) {
            IfcFeatureElementSubtraction sub = relVoids.getRelatedOpeningElement();
            if (sub instanceof IfcOpeningElement) {
                IfcOpeningElement opening = (IfcOpeningElement) sub;
                if (opening.isSetHasFillings()) {
                    for (IfcRelFillsElement relFills : opening.getHasFillings()) {
                        IfcElement filling = relFills.getRelatedBuildingElement();
                        if (filling instanceof IfcWindow) {
                            addWindowFilling(bce, (IfcWindow) filling);
                        } else if (filling instanceof IfcDoor) {
                            addDoorFilling(bce, (IfcDoor) filling);
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds an IfcWindow as con:filling to a wall BCE.
     */
    private void addWindowFilling(BuildingConstructiveElement wallBce, IfcWindow window) {
        Window cityWindow = new Window();
        cityWindow.setId("UUID_" + UUID.randomUUID().toString());
        if (window.getName() != null) {
            cityWindow.getNames().add(new Code(window.getName()));
        }
        if (window.getDescription() != null) {
            cityWindow.setDescription(new StringOrRef(window.getDescription()));
        }
        createExternalReference(cityWindow, window.getGlobalId());
        addProperties(cityWindow, window);

        List<double[]> polygons = getGeometry(window);
        if (!polygons.isEmpty()) {
            addGeometryToObject(cityWindow, window, polygons);
        }

        wallBce.getFillings().add(new AbstractFillingElementProperty(cityWindow));
        exportedElements.add(window);
        logger.info("  Embedded Window '{}' in wall (polygons: {})", window.getName(), polygons.size());
    }

    /**
     * Adds an IfcDoor as con:filling to a wall BCE.
     */
    private void addDoorFilling(BuildingConstructiveElement wallBce, IfcDoor door) {
        Door cityDoor = new Door();
        cityDoor.setId("UUID_" + UUID.randomUUID().toString());
        if (door.getName() != null) {
            cityDoor.getNames().add(new Code(door.getName()));
        }
        if (door.getDescription() != null) {
            cityDoor.setDescription(new StringOrRef(door.getDescription()));
        }
        createExternalReference(cityDoor, door.getGlobalId());
        addProperties(cityDoor, door);

        List<double[]> polygons = getGeometry(door);
        if (!polygons.isEmpty()) {
            addGeometryToObject(cityDoor, door, polygons);
        }

        wallBce.getFillings().add(new AbstractFillingElementProperty(cityDoor));
        exportedElements.add(door);
        logger.info("  Embedded Door '{}' in wall (polygons: {})", door.getName(), polygons.size());
    }

    /**
     * Processes slabs in a building
     */
    private void processSlabs(Building building, IfcBuilding ifcBuilding) {
        List<IfcSlab> slabs = new ArrayList<>(model.getAll(IfcSlab.class));
        slabs.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcSlab: processing {} slabs", slabs.size());

        for (IfcSlab slab : slabs) {
            processConstructiveElement(building, slab, "IfcSlab");
        }
    }

    /**
     * Processes roofs in a building
     */
    private void processRoofs(Building building, IfcBuilding ifcBuilding) {
        List<IfcRoof> roofs = new ArrayList<>(model.getAll(IfcRoof.class));
        roofs.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcRoof: processing {} roofs", roofs.size());

        for (IfcRoof roof : roofs) {
            processConstructiveElement(building, roof, "IfcRoof");
        }
    }

    /**
     * Processes beams in a building
     */
    private void processBeams(Building building, IfcBuilding ifcBuilding) {
        List<IfcBeam> beams = new ArrayList<>(model.getAll(IfcBeam.class));
        beams.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcBeam: processing {} beams", beams.size());

        for (IfcBeam beam : beams) {
            processConstructiveElement(building, beam, "IfcBeam");
        }
    }

    /**
     * Processes columns in a building
     */
    private void processColumns(Building building, IfcBuilding ifcBuilding) {
        List<IfcColumn> columns = new ArrayList<>(model.getAll(IfcColumn.class));
        columns.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcColumn: processing {} columns", columns.size());

        for (IfcColumn column : columns) {
            processConstructiveElement(building, column, "IfcColumn");
        }
    }

    /**
     * Processes stairs in a building
     */
    private void processStairs(Building building, IfcBuilding ifcBuilding) {
        List<IfcStair> stairs = new ArrayList<>(model.getAll(IfcStair.class));
        stairs.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcStair: processing {} stairs", stairs.size());

        for (IfcStair stair : stairs) {
            processConstructiveElement(building, stair, "IfcStair");
        }
    }

    /**
     * Processes railings in a building as BuildingInstallation
     */
    private void processRailings(Building building, IfcBuilding ifcBuilding) {
        List<IfcRailing> railings = new ArrayList<>(model.getAll(IfcRailing.class));
        railings.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcRailing: processing {} railings", railings.size());

        for (IfcRailing railing : railings) {
            processInstallationElement(building, railing, "IfcRailing");
        }
    }

    /**
     * Processes curtain walls in a building
     */
    private void processCurtainWalls(Building building, IfcBuilding ifcBuilding) {
        List<IfcCurtainWall> curtainWalls = new ArrayList<>(model.getAll(IfcCurtainWall.class));
        curtainWalls.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcCurtainWall: processing {} curtain walls", curtainWalls.size());

        for (IfcCurtainWall curtainWall : curtainWalls) {
            processConstructiveElement(building, curtainWall, "IfcCurtainWall");
        }
    }

    /**
     * Processes plates in a building
     */
    private void processPlates(Building building, IfcBuilding ifcBuilding) {
        List<IfcPlate> plates = new ArrayList<>(model.getAll(IfcPlate.class));
        plates.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcPlate: processing {} plates", plates.size());

        for (IfcPlate plate : plates) {
            processConstructiveElement(building, plate, "IfcPlate");
        }
    }

    /**
     * Processes members in a building
     */
    private void processMembers(Building building, IfcBuilding ifcBuilding) {
        List<IfcMember> members = new ArrayList<>(model.getAll(IfcMember.class));
        members.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcMember: processing {} members", members.size());

        for (IfcMember member : members) {
            processConstructiveElement(building, member, "IfcMember");
        }
    }

    /**
     * Processes footings in a building
     */
    private void processFootings(Building building, IfcBuilding ifcBuilding) {
        List<IfcFooting> footings = new ArrayList<>(model.getAll(IfcFooting.class));
        footings.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcFooting: processing {} footings", footings.size());

        for (IfcFooting footing : footings) {
            processConstructiveElement(building, footing, "IfcFooting");
        }
    }

    /**
     * Processes piles in a building
     */
    private void processPiles(Building building, IfcBuilding ifcBuilding) {
        List<IfcPile> piles = new ArrayList<>(model.getAll(IfcPile.class));
        piles.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcPile: processing {} piles", piles.size());

        for (IfcPile pile : piles) {
            processConstructiveElement(building, pile, "IfcPile");
        }
    }

    /**
     * Processes stair flights in a building
     */
    private void processStairFlights(Building building, IfcBuilding ifcBuilding) {
        List<IfcStairFlight> flights = new ArrayList<>(model.getAll(IfcStairFlight.class));
        flights.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcStairFlight: processing {} stair flights", flights.size());

        for (IfcStairFlight flight : flights) {
            processConstructiveElement(building, flight, "IfcStairFlight");
        }
    }

    /**
     * Processes ramps in a building
     */
    private void processRamps(Building building, IfcBuilding ifcBuilding) {
        List<IfcRamp> ramps = new ArrayList<>(model.getAll(IfcRamp.class));
        ramps.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcRamp: processing {} ramps", ramps.size());

        for (IfcRamp ramp : ramps) {
            processConstructiveElement(building, ramp, "IfcRamp");
        }
    }

    /**
     * Processes ramp flights in a building
     */
    private void processRampFlights(Building building, IfcBuilding ifcBuilding) {
        List<IfcRampFlight> flights = new ArrayList<>(model.getAll(IfcRampFlight.class));
        flights.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcRampFlight: processing {} ramp flights", flights.size());

        for (IfcRampFlight flight : flights) {
            processConstructiveElement(building, flight, "IfcRampFlight");
        }
    }

    /**
     * Processes building element proxies in a building
     */
    private void processBuildingElementProxies(Building building, IfcBuilding ifcBuilding) {
        List<IfcBuildingElementProxy> proxies = new ArrayList<>(model.getAll(IfcBuildingElementProxy.class));
        proxies.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcBuildingElementProxy: processing {} proxies", proxies.size());

        for (IfcBuildingElementProxy proxy : proxies) {
            processConstructiveElement(building, proxy, "IfcBuildingElementProxy");
        }
    }

    /**
     * Processes coverings in a building as BuildingInstallation
     */
    private void processCoverings(Building building, IfcBuilding ifcBuilding) {
        List<IfcCovering> coverings = new ArrayList<>(model.getAll(IfcCovering.class));
        coverings.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcCovering: processing {} coverings", coverings.size());

        for (IfcCovering covering : coverings) {
            processInstallationElement(building, covering, "IfcCovering");
        }
    }

    /**
     * Processes rooms (IfcSpace) in a building
     */
    private void processRooms(Building building, IfcBuilding ifcBuilding) {
        List<IfcSpace> spaces = new ArrayList<>(model.getAll(IfcSpace.class));
        spaces.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcSpace: processing {} rooms", spaces.size());

        for (IfcSpace space : spaces) {
            org.citygml4j.core.model.building.BuildingRoom room =
                    new org.citygml4j.core.model.building.BuildingRoom();
            String gmlId = "UUID_" + UUID.randomUUID().toString();
            room.setId(gmlId);
            elementGmlIds.put(space, gmlId);

            if (space.getName() != null) {
                room.getNames().add(new Code(space.getName()));
            }
            if (space.getDescription() != null) {
                room.setDescription(new StringOrRef(space.getDescription()));
            }

            createExternalReference(room, space.getGlobalId());
            addProperties(room, space);

            List<double[]> polygons = getGeometry(space);

            if (!polygons.isEmpty()) {
                addGeometryToObject(room, space, polygons);
                exportedElements.add(space);
                room.setClassifier(new Code("IfcSpace"));
                building.getBuildingRooms().add(
                        new org.citygml4j.core.model.building.BuildingRoomProperty(room));
                logger.debug("Added IfcSpace (room) with {} polygons", polygons.size());
            } else {
                logger.debug("No geometry for IfcSpace '{}' ({})", space.getName(), space.getGlobalId());
            }
        }
    }

    /**
     * Processes furniture in a building
     */
    private void processFurniture(Building building, IfcBuilding ifcBuilding) {
        // Collect IfcFurnishingElement (parent type) which includes IfcFurniture and IfcSystemFurnitureElement
        List<IfcFurnishingElement> allFurniture = new ArrayList<>(model.getAll(IfcFurnishingElement.class));
        allFurniture.removeIf(e -> !currentBuildingElements.contains(e));
        logger.info("IfcFurnishingElement: processing {} furniture items", allFurniture.size());

        for (IfcFurnishingElement element : allFurniture) {
            BuildingFurniture furniture = new BuildingFurniture();
            String gmlId = "UUID_" + UUID.randomUUID().toString();
            furniture.setId(gmlId);
            elementGmlIds.put(element, gmlId);

            if (element.getName() != null) {
                furniture.getNames().add(new Code(element.getName()));
            }
            if (element.getDescription() != null) {
                furniture.setDescription(new StringOrRef(element.getDescription()));
            }

            createExternalReference(furniture, element.getGlobalId());
            addProperties(furniture, element);

            List<double[]> polygons = getGeometry(element);

            if (!polygons.isEmpty()) {
                addGeometryToObject(furniture, element, polygons);
                exportedElements.add(element);
                building.getBuildingFurniture().add(new BuildingFurnitureProperty(furniture));
                logger.debug("Added {} with {} polygons", element.eClass().getName(), polygons.size());
            }

            furniture.setClassifier(new Code(element.eClass().getName()));
        }
    }

    /**
     * Lists all unmapped doors and windows (not assigned to any constructive element)
     */
    private void listUnmappedDoorsAndWindows() {
        logger.info("=== Unmapped Doors and Windows ===");
        int count = 0;

        for (IfcDoor door : model.getAll(IfcDoor.class)) {
            if (!exportedElements.contains(door) && currentBuildingElements.contains(door)) {
                count++;
                logger.info("Unmapped Door: {} (GlobalId: {}, Name: {})",
                    door.getClass().getSimpleName(), door.getGlobalId(), door.getName());
                logElementConnections(door);
            }
        }

        for (IfcWindow window : model.getAll(IfcWindow.class)) {
            if (!exportedElements.contains(window) && currentBuildingElements.contains(window)) {
                count++;
                logger.info("Unmapped Window: {} (GlobalId: {}, Name: {})",
                    window.getClass().getSimpleName(), window.getGlobalId(), window.getName());
                logElementConnections(window);
            }
        }

        logger.info("Total unmapped doors/windows: {}", count);
    }

    /**
     * Logs the connections of an element (via IfcRelFillsElement chain)
     */
    private void logElementConnections(IfcElement element) {
        if (element.isSetFillsVoids()) {
            for (IfcRelFillsElement relFills : element.getFillsVoids()) {
                IfcOpeningElement opening = relFills.getRelatingOpeningElement();
                if (opening != null && opening.isSetVoidsElements()) {
                    IfcRelVoidsElement relVoids = opening.getVoidsElements();
                    if (relVoids != null) {
                        IfcElement host = relVoids.getRelatingBuildingElement();
                        if (host != null) {
                            logger.info("  -> Connected to: {} '{}' ({})",
                                host.getClass().getSimpleName(), host.getName(), host.getGlobalId());
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates dummy BCEs for unmapped doors/windows, grouped by storey.
     * Returns a map of storey -> dummy BCE for xlink creation.
     */
    private Map<IfcBuildingStorey, BuildingConstructiveElement> handleUnrelatedDoorsWindowsInDummyBce(Building building) {
        Map<IfcBuildingStorey, BuildingConstructiveElement> dummyBcePerStorey = new HashMap<>();
        Map<IfcBuildingStorey, List<IfcElement>> unmappedPerStorey = new HashMap<>();
        List<IfcElement> unmappedNoStorey = new ArrayList<>();

        // Collect unmapped doors (filtered to current building)
        for (IfcDoor door : model.getAll(IfcDoor.class)) {
            if (!exportedElements.contains(door) && currentBuildingElements.contains(door)) {
                IfcBuildingStorey storey = findStoreyForElement(door);
                if (storey != null) {
                    unmappedPerStorey.computeIfAbsent(storey, k -> new ArrayList<>()).add(door);
                } else {
                    unmappedNoStorey.add(door);
                }
            }
        }

        // Collect unmapped windows (filtered to current building)
        for (IfcWindow window : model.getAll(IfcWindow.class)) {
            if (!exportedElements.contains(window) && currentBuildingElements.contains(window)) {
                IfcBuildingStorey storey = findStoreyForElement(window);
                if (storey != null) {
                    unmappedPerStorey.computeIfAbsent(storey, k -> new ArrayList<>()).add(window);
                } else {
                    unmappedNoStorey.add(window);
                }
            }
        }

        // Create dummy BCE per storey
        for (Map.Entry<IfcBuildingStorey, List<IfcElement>> entry : unmappedPerStorey.entrySet()) {
            String storeyName = entry.getKey().getName() != null ? entry.getKey().getName() : "Unnamed Storey";
            BuildingConstructiveElement dummyBce = createDummyBce(building, entry.getValue(),
                "Stub Element for unrelated Doors and Windows - Storey: " + storeyName);
            dummyBcePerStorey.put(entry.getKey(), dummyBce);
        }

        // Create fallback dummy BCE for elements without storey
        if (!unmappedNoStorey.isEmpty()) {
            createDummyBce(building, unmappedNoStorey, "Stub Element for unrelated Doors and Windows - No Storey Assignment");
        }

        return dummyBcePerStorey;
    }

    /**
     * Creates a dummy BCE containing unmapped doors/windows as fillings.
     */
    private BuildingConstructiveElement createDummyBce(Building building, List<IfcElement> elements, String name) {
        BuildingConstructiveElement dummyBce = new BuildingConstructiveElement();
        String gmlId = "UUID_" + UUID.randomUUID().toString();
        dummyBce.setId(gmlId);
        dummyBce.getNames().add(new Code(name));

        for (IfcElement element : elements) {
            if (element instanceof IfcWindow) {
                addWindowFilling(dummyBce, (IfcWindow) element);
            } else if (element instanceof IfcDoor) {
                addDoorFilling(dummyBce, (IfcDoor) element);
            }
        }

        dummyBce.setClassifier(new Code("DummyBuildingConstructiveElement"));
        building.getBuildingConstructiveElements().add(new BuildingConstructiveElementProperty(dummyBce));
        logger.info("Created dummy BCE '{}' with {} fillings", name, elements.size());

        return dummyBce;
    }

    /**
     * Finds the IfcBuildingStorey containing an element.
     */
    private IfcBuildingStorey findStoreyForElement(IfcElement element) {
        // Check ContainedInStructure
        if (element.isSetContainedInStructure()) {
            for (IfcRelContainedInSpatialStructure rel : element.getContainedInStructure()) {
                IfcSpatialElement spatial = rel.getRelatingStructure();
                if (spatial instanceof IfcBuildingStorey) {
                    return (IfcBuildingStorey) spatial;
                }
            }
        }

        // Check Decomposes
        if (element.isSetDecomposes()) {
            for (IfcRelAggregates rel : element.getDecomposes()) {
                IfcObjectDefinition parent = rel.getRelatingObject();
                if (parent instanceof IfcBuildingStorey) {
                    return (IfcBuildingStorey) parent;
                }
                // Recurse: check if parent is contained in a storey
                if (parent instanceof IfcElement) {
                    IfcBuildingStorey storey = findStoreyForElement((IfcElement) parent);
                    if (storey != null) return storey;
                }
            }
        }

        // Check via FillsVoids → Opening → VoidsElements → Host element
        if (element.isSetFillsVoids()) {
            for (IfcRelFillsElement relFills : element.getFillsVoids()) {
                IfcOpeningElement opening = relFills.getRelatingOpeningElement();
                if (opening != null && opening.isSetVoidsElements()) {
                    IfcRelVoidsElement relVoids = opening.getVoidsElements();
                    if (relVoids != null) {
                        IfcElement host = relVoids.getRelatingBuildingElement();
                        if (host != null) {
                            return findStoreyForElement(host);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Processes building storeys
     */
    private void processBuildingStoreys(Building building, IfcBuilding ifcBuilding,
                                         Map<IfcBuildingStorey, BuildingConstructiveElement> dummyBcePerStorey) {
        List<IfcBuildingStorey> storeys = new ArrayList<>(model.getAll(IfcBuildingStorey.class));
        storeys.removeIf(s -> !currentBuildingElements.contains(s));
        logger.info("IfcBuildingStorey: processing {} storeys", storeys.size());

        for (IfcBuildingStorey storey : storeys) {
            Storey cityStorey = new Storey();
            cityStorey.setId("UUID_" + UUID.randomUUID().toString());

            if (storey.getName() != null) {
                cityStorey.getNames().add(new Code(storey.getName()));
            }
            if (storey.getDescription() != null) {
                cityStorey.setDescription(new StringOrRef(storey.getDescription()));
            }

            createExternalReference(cityStorey, storey.getGlobalId());
            addProperties(cityStorey, storey);

            // Collect all IFC elements belonging to this storey
            Set<IfcProduct> storeyElements = getStoreyElements(storey);

            // Add xlinks to exported constructive elements and rooms
            for (IfcProduct element : storeyElements) {
                if (!exportedElements.contains(element)) continue;
                String gmlId = elementGmlIds.get(element);
                if (gmlId == null) continue;

                if (element instanceof IfcSpace) {
                    cityStorey.getBuildingRooms().add(new BuildingRoomProperty("#" + gmlId));
                } else {
                    cityStorey.getBuildingConstructiveElements().add(
                        new BuildingConstructiveElementProperty("#" + gmlId));
                }
            }

            // Add xlink to dummy BCE for this storey if exists
            if (dummyBcePerStorey != null) {
                BuildingConstructiveElement dummyBce = dummyBcePerStorey.get(storey);
                if (dummyBce != null) {
                    cityStorey.getBuildingConstructiveElements().add(
                        new BuildingConstructiveElementProperty("#" + dummyBce.getId()));
                }
            }

            building.getBuildingSubdivisions().add(new AbstractBuildingSubdivisionProperty(cityStorey));
            logger.info("Added storey '{}' with {} xlinks",
                storey.getName(), cityStorey.getBuildingConstructiveElements().size()
                    + cityStorey.getBuildingRooms().size());
        }
    }

    /**
     * Gets all IFC elements belonging to a storey via ContainsElements and IsDecomposedBy (recursive).
     */
    private Set<IfcProduct> getStoreyElements(IfcBuildingStorey storey) {
        Set<IfcProduct> elements = new LinkedHashSet<>();
        collectStoreyElements(storey, elements);
        return elements;
    }

    private void collectStoreyElements(IfcObjectDefinition root, Set<IfcProduct> elements) {
        // Via IfcRelContainedInSpatialStructure
        if (root instanceof IfcSpatialStructureElement) {
            IfcSpatialStructureElement spatial = (IfcSpatialStructureElement) root;
            if (spatial.isSetContainsElements()) {
                for (IfcRelContainedInSpatialStructure rel : spatial.getContainsElements()) {
                    for (IfcProduct product : rel.getRelatedElements()) {
                        elements.add(product);
                        collectStoreyElements(product, elements);
                    }
                }
            }
        }

        // Via IfcRelAggregates (decomposition) — recursive
        if (root.isSetIsDecomposedBy()) {
            for (IfcRelAggregates rel : root.getIsDecomposedBy()) {
                for (IfcObjectDefinition obj : rel.getRelatedObjects()) {
                    if (obj instanceof IfcProduct) {
                        elements.add((IfcProduct) obj);
                    }
                    collectStoreyElements(obj, elements);
                }
            }
        }
    }

    /**
     * Generic method to process constructive elements
     */
    private void processConstructiveElement(Building building, IfcProduct element, String className) {
        BuildingConstructiveElement bce = new BuildingConstructiveElement();
        String gmlId = "UUID_" + UUID.randomUUID().toString();
        bce.setId(gmlId);
        elementGmlIds.put(element, gmlId);

        if (element.getName() != null) {
            bce.getNames().add(new Code(element.getName()));
        }
        if (element.getDescription() != null) {
            bce.setDescription(new StringOrRef(element.getDescription()));
        }

        createExternalReference(bce, element.getGlobalId());
        addProperties(bce, element);

        List<double[]> polygons = getGeometry(element);

        // Embed doors/windows as con:filling (regardless of geometry, to track them as exported)
        if (element instanceof IfcElement) {
            embedFillings(bce, (IfcElement) element);
        }

        if (!polygons.isEmpty()) {
            addGeometryToObject(bce, element, polygons);
            exportedElements.add(element);

            bce.setClassifier(new Code(className));
            building.getBuildingConstructiveElements().add(new BuildingConstructiveElementProperty(bce));
            logger.debug("Added {} with {} polygons", className, polygons.size());
        } else {
            logger.debug("No geometry extracted for {} '{}' ({})", className, element.getName(), element.getGlobalId());
        }
    }

    /**
     * Generic method to process installation elements (Covering, Railing, etc.)
     */
    private void processInstallationElement(Building building, IfcProduct element, String className) {
        BuildingInstallation installation = new BuildingInstallation();
        String gmlId = "UUID_" + UUID.randomUUID().toString();
        installation.setId(gmlId);
        elementGmlIds.put(element, gmlId);

        if (element.getName() != null) {
            installation.getNames().add(new Code(element.getName()));
        }
        if (element.getDescription() != null) {
            installation.setDescription(new StringOrRef(element.getDescription()));
        }

        createExternalReference(installation, element.getGlobalId());
        addProperties(installation, element);

        List<double[]> polygons = getGeometry(element);

        if (!polygons.isEmpty()) {
            addGeometryToObject(installation, element, polygons);
            exportedElements.add(element);
            installation.setClassifier(new Code(className));
            building.getBuildingInstallations().add(new BuildingInstallationProperty(installation));
            logger.debug("Added {} with {} polygons", className, polygons.size());
        } else {
            logger.debug("No geometry extracted for {} '{}' ({})", className, element.getName(), element.getGlobalId());
        }
    }

    /**
     * Adds geometry to a city object (either as solid or multi-surface)
     */
    private void addGeometryToObject(Object cityObject, IfcProduct element, List<double[]> polygons) {
        boolean isSolid = isIntendedSolid(element);

        if (isSolid) {
            // Create Solid geometry with Shell
            logger.debug("Creating solid geometry for element {} with {} polygons",
                element.getGlobalId(), polygons.size());

            Shell shell = new Shell();

            for (double[] coords : polygons) {
                Polygon polygon = createPolygon(coords);
                shell.getSurfaceMembers().add(new SurfaceProperty(polygon));
            }

            Solid solid = new Solid();
            solid.setId("UUID_" + UUID.randomUUID().toString());
            solid.setSrsName(srsName);
            solid.setSrsDimension(3);
            solid.setExterior(new ShellProperty(shell));

            SolidProperty solidProperty = new SolidProperty(solid);

            if (cityObject instanceof BuildingConstructiveElement) {
                ((BuildingConstructiveElement) cityObject).setLod3Solid(solidProperty);
            } else if (cityObject instanceof BuildingInstallation) {
                ((BuildingInstallation) cityObject).setLod3Solid(solidProperty);
            } else if (cityObject instanceof BuildingFurniture) {
                ((BuildingFurniture) cityObject).setLod3Solid(solidProperty);
            } else if (cityObject instanceof BuildingRoom) {
                ((BuildingRoom) cityObject).setLod3Solid(solidProperty);
            } else if (cityObject instanceof Door) {
                ((Door) cityObject).setLod3Solid(solidProperty);
            } else if (cityObject instanceof Window) {
                ((Window) cityObject).setLod3Solid(solidProperty);
            }
        } else {
            // Create MultiSurface geometry
            MultiSurface multiSurface = new MultiSurface();
            multiSurface.setId("UUID_" + UUID.randomUUID().toString());
            multiSurface.setSrsName(srsName);
            multiSurface.setSrsDimension(3);

            for (double[] coords : polygons) {
                Polygon polygon = createPolygon(coords);
                multiSurface.getSurfaceMember().add(new SurfaceProperty(polygon));
            }

            if (cityObject instanceof BuildingConstructiveElement) {
                ((BuildingConstructiveElement) cityObject).setLod3MultiSurface(new MultiSurfaceProperty(multiSurface));
            } else if (cityObject instanceof BuildingInstallation) {
                ((BuildingInstallation) cityObject).setLod3MultiSurface(new MultiSurfaceProperty(multiSurface));
            } else if (cityObject instanceof BuildingFurniture) {
                ((BuildingFurniture) cityObject).setLod3MultiSurface(new MultiSurfaceProperty(multiSurface));
            } else if (cityObject instanceof BuildingRoom) {
                ((BuildingRoom) cityObject).setLod3MultiSurface(new MultiSurfaceProperty(multiSurface));
            } else if (cityObject instanceof Door) {
                ((Door) cityObject).setLod3MultiSurface(new MultiSurfaceProperty(multiSurface));
            } else if (cityObject instanceof Window) {
                ((Window) cityObject).setLod3MultiSurface(new MultiSurfaceProperty(multiSurface));
            }
        }
    }
}
