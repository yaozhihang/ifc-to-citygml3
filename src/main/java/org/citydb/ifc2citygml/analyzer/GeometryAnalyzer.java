package org.citydb.ifc2citygml.analyzer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.models.ifc4.*;
import org.citydb.ifc2citygml.io.IfcModelReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans IFC files and produces a histogram of geometry-related entity types
 * actually used in the corpus. Output drives empirical scoping of a future
 * pure-Java geometry kernel: implement what real files contain, not what the
 * spec defines.
 *
 * Usage: GeometryAnalyzer &lt;ifc-file-or-dir&gt; [output.json]
 */
public class GeometryAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(GeometryAnalyzer.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: GeometryAnalyzer <ifc-file-or-dir> [output.json]");
            System.exit(1);
        }
        Path input = Paths.get(args[0]);
        Path output = args.length >= 2 ? Paths.get(args[1]) : Paths.get("geometry-analysis.json");

        List<Path> files = collectIfcFiles(input);
        if (files.isEmpty()) {
            System.err.println("No .ifc files found under: " + input);
            System.exit(1);
        }
        logger.info("Found {} IFC file(s)", files.size());

        Report report = new Report();
        for (Path f : files) {
            try {
                FileReport fr = analyzeFile(f);
                report.files.add(fr);
                printSummary(fr);
            } catch (Exception e) {
                logger.error("Failed to analyze {}: {}", f, e.getMessage(), e);
            }
        }
        aggregate(report);
        writeReport(report, output);
        printAggregate(report);
        logger.info("Report written to: {}", output.toAbsolutePath());
    }

    private static List<Path> collectIfcFiles(Path input) throws IOException {
        if (Files.isRegularFile(input)) return List.of(input);
        try (Stream<Path> walk = Files.walk(input)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ifc"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static FileReport analyzeFile(Path file) throws Exception {
        logger.info("Analyzing: {}", file);
        IfcModelInterface model = new IfcModelReader().loadModel(file.toString());

        FileReport r = new FileReport();
        r.path = file.toString();
        r.schema = String.valueOf(model.getPackageMetaData().getSchema());

        List<IfcProduct> products = model.getAllWithSubTypes(IfcProduct.class);
        r.totalProducts = products.size();
        for (IfcProduct p : products) {
            String pClass = p.eClass().getName();
            r.productClasses.merge(pClass, 1, Integer::sum);
            if (p.getRepresentation() == null) continue;
            r.productsWithRepresentation++;
            for (IfcRepresentation rep : p.getRepresentation().getRepresentations()) {
                String repType = "(unset)";
                String repId = "(unset)";
                if (rep instanceof IfcShapeRepresentation sr) {
                    repType = nullSafe(sr.getRepresentationType());
                    repId = nullSafe(sr.getRepresentationIdentifier());
                }
                r.representationTypes.merge(repType, 1, Integer::sum);
                r.representationIdentifiers.merge(repId, 1, Integer::sum);
                for (IfcRepresentationItem item : rep.getItems()) {
                    String itemClass = item.eClass().getName();
                    r.representationItems.merge(itemClass, 1, Integer::sum);
                    r.entityRepBreakdown
                            .computeIfAbsent(pClass, k -> new TreeMap<>())
                            .merge(itemClass, 1, Integer::sum);
                }
            }
        }

        for (IfcProfileDef pd : model.getAllWithSubTypes(IfcProfileDef.class))
            r.profiles.merge(pd.eClass().getName(), 1, Integer::sum);

        for (IfcCurve c : model.getAllWithSubTypes(IfcCurve.class))
            r.curves.merge(c.eClass().getName(), 1, Integer::sum);

        for (IfcSurface s : model.getAllWithSubTypes(IfcSurface.class))
            r.surfaces.merge(s.eClass().getName(), 1, Integer::sum);

        r.styles.put("IfcStyledItem", model.getAllWithSubTypes(IfcStyledItem.class).size());
        r.styles.put("IfcSurfaceStyle", model.getAllWithSubTypes(IfcSurfaceStyle.class).size());
        r.styles.put("IfcSurfaceStyleRendering", model.getAllWithSubTypes(IfcSurfaceStyleRendering.class).size());
        r.styles.put("IfcSurfaceStyleShading", model.getAllWithSubTypes(IfcSurfaceStyleShading.class).size());

        r.voids.put("IfcRelVoidsElement", model.getAllWithSubTypes(IfcRelVoidsElement.class).size());
        r.voids.put("IfcOpeningElement", model.getAllWithSubTypes(IfcOpeningElement.class).size());

        List<IfcBooleanResult> booleans = model.getAllWithSubTypes(IfcBooleanResult.class);
        r.csg.put("IfcBooleanResult", booleans.size());
        r.csg.put("IfcBooleanClippingResult",
                model.getAllWithSubTypes(IfcBooleanClippingResult.class).size());
        int maxDepth = 0;
        for (IfcBooleanResult b : booleans) {
            maxDepth = Math.max(maxDepth, booleanDepth(b));
            tallyOperand(b.getFirstOperand(), r.booleanOperandTypes);
            tallyOperand(b.getSecondOperand(), r.booleanOperandTypes);
            String op = b.getOperator() == null ? "(unset)" : b.getOperator().getName();
            r.booleanOperators.merge(op, 1, Integer::sum);

            // Capture details for non-clipping IfcBooleanResult — these are the
            // potentially "general CSG" cases that need explicit scoping.
            if (!(b instanceof IfcBooleanClippingResult)) {
                BooleanDetail d = new BooleanDetail();
                d.expressId = b.getExpressId();
                d.operator = op;
                d.firstOperand = b.getFirstOperand() == null
                        ? "(null)" : b.getFirstOperand().eClass().getName();
                d.secondOperand = b.getSecondOperand() == null
                        ? "(null)" : b.getSecondOperand().eClass().getName();
                d.host = findHostingProduct(model, b);
                d.coAxialExtrusionDiff = analyzeCoAxialDiff(b);
                r.nonClippingBooleans.add(d);
            }
        }
        r.csgMaxNestingDepth = maxDepth;

        // Dump exotic-rep-item hosts: anything that is neither in the Tier-0
        // supported set nor a known annotation type.
        for (IfcProduct p : products) {
            if (p.getRepresentation() == null) continue;
            for (IfcRepresentation rep : p.getRepresentation().getRepresentations()) {
                for (IfcRepresentationItem item : rep.getItems()) {
                    String name = item.eClass().getName();
                    if (EXOTIC_REP_ITEMS.contains(name)) {
                        ExoticDetail d = new ExoticDetail();
                        d.repItem = name;
                        d.host = p.eClass().getName() + "#" + p.getExpressId()
                                + " (" + nullSafe(p.getGlobalId()) + ")";
                        r.exoticRepItemHosts.add(d);
                    }
                }
            }
        }

        return r;
    }

    /** RepresentationItem types that are neither in Tier-0 nor pure annotation. */
    private static final java.util.Set<String> EXOTIC_REP_ITEMS = java.util.Set.of(
            "IfcFaceBasedSurfaceModel",
            "IfcShellBasedSurfaceModel",
            "IfcAdvancedBrep",
            "IfcAdvancedBrepWithVoids",
            "IfcSweptDiskSolid",
            "IfcRevolvedAreaSolid",
            "IfcSurfaceCurveSweptAreaSolid",
            "IfcCsgSolid",
            "IfcCsgPrimitive3D",
            "IfcBlock",
            "IfcSphere",
            "IfcRightCircularCone",
            "IfcRightCircularCylinder",
            "IfcRectangularPyramid"
    );

    /** Walks back the representation tree to find the IfcProduct that uses this item. */
    private static String findHostingProduct(IfcModelInterface model, IfcRepresentationItem item) {
        for (IfcProduct p : model.getAllWithSubTypes(IfcProduct.class)) {
            if (p.getRepresentation() == null) continue;
            for (IfcRepresentation rep : p.getRepresentation().getRepresentations()) {
                if (containsItem(rep, item, 0)) {
                    return p.eClass().getName() + "#" + p.getExpressId()
                            + " (" + nullSafe(p.getGlobalId()) + ")";
                }
            }
        }
        return "(none)";
    }

    /**
     * Checks whether all leaf operands of this boolean (recursively) are
     * IfcExtrudedAreaSolid with the same extrusion direction. If so, the
     * subtraction reduces to a 2D polygon boolean + extrusion — no mesh CSG.
     */
    private static String analyzeCoAxialDiff(IfcBooleanResult b) {
        List<IfcExtrudedAreaSolid> leaves = new ArrayList<>();
        if (!collectExtrusionLeaves(b, leaves)) return "non-extrusion-leaf";
        if (leaves.isEmpty()) return "no-leaves";
        double[] ref = directionOf(leaves.get(0));
        if (ref == null) return "no-direction";
        for (IfcExtrudedAreaSolid s : leaves) {
            double[] d = directionOf(s);
            if (d == null) return "no-direction";
            if (Math.abs(d[0] - ref[0]) > 1e-6
                    || Math.abs(d[1] - ref[1]) > 1e-6
                    || Math.abs(d[2] - ref[2]) > 1e-6) {
                return "diverging-axes(" + Math.round(d[0]*100)/100.0 + ","
                        + Math.round(d[1]*100)/100.0 + ","
                        + Math.round(d[2]*100)/100.0 + " vs ref)";
            }
        }
        return "co-axial(" + ref[0] + "," + ref[1] + "," + ref[2] + ")";
    }

    private static boolean collectExtrusionLeaves(IfcBooleanOperand op, List<IfcExtrudedAreaSolid> out) {
        if (op instanceof IfcExtrudedAreaSolid e) {
            out.add(e);
            return true;
        }
        if (op instanceof IfcBooleanResult br) {
            return collectExtrusionLeaves(br.getFirstOperand(), out)
                    && collectExtrusionLeaves(br.getSecondOperand(), out);
        }
        return false;
    }

    private static double[] directionOf(IfcExtrudedAreaSolid s) {
        if (s.getExtrudedDirection() == null) return null;
        var ratios = s.getExtrudedDirection().getDirectionRatios();
        if (ratios == null || ratios.size() < 3) return null;
        return new double[] { ratios.get(0), ratios.get(1), ratios.get(2) };
    }

    private static boolean containsItem(IfcRepresentation rep, IfcRepresentationItem target, int depth) {
        if (depth > 10) return false;
        for (IfcRepresentationItem item : rep.getItems()) {
            if (item == target) return true;
            if (item instanceof IfcBooleanResult b
                    && (b.getFirstOperand() == target || b.getSecondOperand() == target)) {
                return true;
            }
        }
        return false;
    }

    private static void tallyOperand(IfcBooleanOperand operand, Map<String, Integer> dst) {
        if (operand == null) return;
        dst.merge(operand.eClass().getName(), 1, Integer::sum);
    }

    private static int booleanDepth(IfcBooleanResult b) {
        int l = 0, r = 0;
        if (b.getFirstOperand() instanceof IfcBooleanResult br) l = booleanDepth(br);
        if (b.getSecondOperand() instanceof IfcBooleanResult br) r = booleanDepth(br);
        return 1 + Math.max(l, r);
    }

    private static String nullSafe(String s) {
        return (s == null || s.isEmpty()) ? "(unset)" : s;
    }

    private static void aggregate(Report r) {
        for (FileReport f : r.files) {
            mergeInto(r.aggregateRepresentationTypes, f.representationTypes);
            mergeInto(r.aggregateRepresentationItems, f.representationItems);
            mergeInto(r.aggregateProfiles, f.profiles);
            mergeInto(r.aggregateCurves, f.curves);
            mergeInto(r.aggregateSurfaces, f.surfaces);
            mergeInto(r.aggregateBooleanOperandTypes, f.booleanOperandTypes);
        }
    }

    private static void mergeInto(Map<String, Integer> dst, Map<String, Integer> src) {
        src.forEach((k, v) -> dst.merge(k, v, Integer::sum));
    }

    private static void printSummary(FileReport f) {
        int totalRepItems = f.representationItems.values().stream().mapToInt(Integer::intValue).sum();
        logger.info("  schema={}, products={}, withRep={}, repItems={}, csgDepth={}",
                f.schema, f.totalProducts, f.productsWithRepresentation,
                totalRepItems, f.csgMaxNestingDepth);
        if (!f.nonClippingBooleans.isEmpty()) {
            logger.info("  Non-Clipping IfcBooleanResult ({} instance(s)):", f.nonClippingBooleans.size());
            for (BooleanDetail d : f.nonClippingBooleans) {
                logger.info("    #{} op={} firstOp={} secondOp={} host={} axes={}",
                        d.expressId, d.operator, d.firstOperand, d.secondOperand,
                        d.host, d.coAxialExtrusionDiff);
            }
        }
        if (!f.exoticRepItemHosts.isEmpty()) {
            logger.info("  Exotic RepresentationItems ({} instance(s)):", f.exoticRepItemHosts.size());
            for (ExoticDetail d : f.exoticRepItemHosts) {
                logger.info("    {} on {}", d.repItem, d.host);
            }
        }
    }

    private static void printAggregate(Report r) {
        logger.info("");
        logger.info("=== Aggregate across {} file(s) ===", r.files.size());
        logTopN("RepresentationItem", r.aggregateRepresentationItems, 20);
        logTopN("Profile", r.aggregateProfiles, 15);
        logTopN("Curve", r.aggregateCurves, 15);
        logTopN("Surface", r.aggregateSurfaces, 10);
        logTopN("BooleanOperand", r.aggregateBooleanOperandTypes, 10);
        logTopN("RepresentationType", r.aggregateRepresentationTypes, 15);
    }

    private static void logTopN(String label, Map<String, Integer> hist, int n) {
        int total = hist.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return;
        logger.info("--- {} (total={}) ---", label, total);
        hist.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .forEach(e -> logger.info(String.format("  %6d (%5.1f%%)  %s",
                        e.getValue(),
                        100.0 * e.getValue() / total,
                        e.getKey())));
    }

    private static void writeReport(Report r, Path out) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer w = Files.newBufferedWriter(out)) {
            gson.toJson(r, w);
        }
    }

    // --- Data classes (public fields for Gson) ---

    static class Report {
        List<FileReport> files = new ArrayList<>();
        Map<String, Integer> aggregateRepresentationTypes = new TreeMap<>();
        Map<String, Integer> aggregateRepresentationItems = new TreeMap<>();
        Map<String, Integer> aggregateProfiles = new TreeMap<>();
        Map<String, Integer> aggregateCurves = new TreeMap<>();
        Map<String, Integer> aggregateSurfaces = new TreeMap<>();
        Map<String, Integer> aggregateBooleanOperandTypes = new TreeMap<>();
    }

    static class FileReport {
        String path;
        String schema;
        int totalProducts;
        int productsWithRepresentation;
        int csgMaxNestingDepth;
        Map<String, Integer> productClasses = new TreeMap<>();
        Map<String, Integer> representationTypes = new TreeMap<>();
        Map<String, Integer> representationIdentifiers = new TreeMap<>();
        Map<String, Integer> representationItems = new TreeMap<>();
        Map<String, Integer> profiles = new TreeMap<>();
        Map<String, Integer> curves = new TreeMap<>();
        Map<String, Integer> surfaces = new TreeMap<>();
        Map<String, Integer> styles = new LinkedHashMap<>();
        Map<String, Integer> voids = new LinkedHashMap<>();
        Map<String, Integer> csg = new LinkedHashMap<>();
        Map<String, Integer> booleanOperators = new TreeMap<>();
        Map<String, Integer> booleanOperandTypes = new TreeMap<>();
        Map<String, Map<String, Integer>> entityRepBreakdown = new TreeMap<>();
        List<BooleanDetail> nonClippingBooleans = new ArrayList<>();
        List<ExoticDetail> exoticRepItemHosts = new ArrayList<>();
    }

    static class BooleanDetail {
        long expressId;
        String operator;
        String firstOperand;
        String secondOperand;
        String host;
        String coAxialExtrusionDiff;
    }

    static class ExoticDetail {
        String repItem;
        String host;
    }
}
