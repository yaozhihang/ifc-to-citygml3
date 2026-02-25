package org.citydb.ifc2citygml.util;

import org.bimserver.emf.IfcModelInterface;
import org.bimserver.models.ifc4.*;
import org.citydb.ifc2citygml.config.ConversionConfig;
import org.citygml4j.core.model.appearance.*;
import org.citygml4j.core.model.appearance.Color;
import org.citygml4j.core.model.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.geometry.DirectPositionList;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurface;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.*;

import java.util.*;

/**
 * Handles geometry extraction, georeferencing, and appearance for CityGML output.
 */
public class GeometryHandler {

    private static final Logger logger = LoggerFactory.getLogger(GeometryHandler.class);

    // IFC Representation Types that imply a Volumetric Solid
    private static final Set<String> SOLID_REPRESENTATION_TYPES = Set.of(
            "SweptSolid",   // Extrusions, Revolutions
            "Brep",         // Boundary Representations (FacetedBrep)
            "AdvancedBrep", // NURBS / Advanced Solids
            "CSG",          // Constructive Solid Geometry
            "Clipping",     // Boolean results (Solid - Solid)
            "BoundingBox"   // Simplified solid box
    );

    private final IfcModelInterface model;
    private final Map<String, List<double[]>> jsonGeometryCache;
    private final Map<String, List<double[]>> jsonMaterialCache;
    private final ConversionConfig config;

    // Georeferencing parameters
    private double eastings = 0.0;
    private double northings = 0.0;
    private double orthogonalHeight = 0.0;
    private double scale = 1.0;
    private double[][] rotationMatrix = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    private String srsName = "EPSG:0";

    public GeometryHandler(IfcModelInterface model,
                           Map<String, List<double[]>> jsonGeometryCache,
                           Map<String, List<double[]>> jsonMaterialCache,
                           ConversionConfig config) {
        this.model = model;
        this.jsonGeometryCache = jsonGeometryCache;
        this.jsonMaterialCache = jsonMaterialCache;
        this.config = config;
    }

    /**
     * Sets up georeferencing from IFC map conversion
     */
    public void setupGeoreferencing() {
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
        if (config.georefOktoberfest()) {
            this.eastings = 689738.0;
            this.northings = 5334100.0;
            this.orthogonalHeight = 521.0;
            this.srsName = "EPSG:25832";
            logger.info("Georeference set to Theresienwiese in Munich (EPSG:25832): E={}, N={}, H={}",
                    eastings, northings, orthogonalHeight);
        }
    }

    public String getSrsName() {
        return srsName;
    }

    public double getEastings() {
        return eastings;
    }

    public double getNorthings() {
        return northings;
    }

    public double getOrthogonalHeight() {
        return orthogonalHeight;
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
        rotated[0] += eastings + config.xOffset();
        rotated[1] += northings + config.yOffset();
        rotated[2] += orthogonalHeight + config.zOffset();

        return rotated;
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
    public List<double[]> getGeometry(IfcProduct element) {
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
     * Adds geometry to a city object (either as solid or multi-surface).
     * Works with any AbstractSpace subclass (building elements, rooms, doors, windows, etc.)
     */
    public void addGeometryToObject(AbstractSpace cityObject, IfcProduct element, List<double[]> polygons) {
        boolean isSolid = isIntendedSolid(element);
        List<String> polygonIds = new ArrayList<>();

        if (isSolid) {
            // Create Solid geometry with Shell
            logger.debug("Creating solid geometry for element {} with {} polygons",
                element.getGlobalId(), polygons.size());

            Shell shell = new Shell();

            for (double[] coords : polygons) {
                Polygon polygon = createPolygon(coords);
                polygonIds.add(polygon.getId());
                shell.getSurfaceMembers().add(new SurfaceProperty(polygon));
            }

            Solid solid = new Solid();
            solid.setId("UUID_" + UUID.randomUUID().toString());
            solid.setSrsName(srsName);
            solid.setSrsDimension(3);
            solid.setExterior(new ShellProperty(shell));

            cityObject.setLod3Solid(new SolidProperty(solid));
        } else {
            // Create MultiSurface geometry
            MultiSurface multiSurface = new MultiSurface();
            multiSurface.setId("UUID_" + UUID.randomUUID().toString());
            multiSurface.setSrsName(srsName);
            multiSurface.setSrsDimension(3);

            for (double[] coords : polygons) {
                Polygon polygon = createPolygon(coords);
                polygonIds.add(polygon.getId());
                multiSurface.getSurfaceMember().add(new SurfaceProperty(polygon));
            }

            cityObject.setLod3MultiSurface(new MultiSurfaceProperty(multiSurface));
        }

        // Add appearance (materials) if available
        addAppearance(cityObject, element, polygonIds);
    }

    /**
     * Adds CityGML Appearance with X3DMaterial entries for per-face materials.
     * Groups faces by material color and creates one X3DMaterial per unique color.
     */
    private void addAppearance(AbstractCityObject cityObject, IfcProduct element,
                               List<String> polygonIds) {
        if (config.noAppearances()) return;
        if (jsonMaterialCache == null) return;

        List<double[]> materials = jsonMaterialCache.get(element.getGlobalId());
        if (materials == null || materials.isEmpty()) return;

        // Group face indices by material key (r,g,b,transparency rounded to 6 decimals)
        Map<String, List<Integer>> materialGroups = new LinkedHashMap<>();
        for (int i = 0; i < materials.size() && i < polygonIds.size(); i++) {
            double[] mat = materials.get(i);
            if (mat == null) continue;
            String key = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", mat[0], mat[1], mat[2], mat[3]);
            materialGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        if (materialGroups.isEmpty()) return;

        Appearance appearance = new Appearance();
        String objId = cityObject.getId();
        appearance.setId("APP_" + objId);
        appearance.setTheme("RGB");

        int matIdx = 0;
        for (Map.Entry<String, List<Integer>> group : materialGroups.entrySet()) {
            String[] parts = group.getKey().split(",");
            double r = Double.parseDouble(parts[0]);
            double g = Double.parseDouble(parts[1]);
            double b = Double.parseDouble(parts[2]);
            double transparency = Double.parseDouble(parts[3]);

            X3DMaterial x3dMaterial = new X3DMaterial();
            x3dMaterial.setId("MAT_" + objId + "_" + matIdx);
            x3dMaterial.setIsFront(true);
            x3dMaterial.setDiffuseColor(new Color(r, g, b));
            if (transparency > 0) {
                x3dMaterial.setTransparency(transparency);
            }

            for (int faceIdx : group.getValue()) {
                x3dMaterial.getTargets().add(new GeometryReference("#" + polygonIds.get(faceIdx)));
            }

            appearance.getSurfaceData().add(new AbstractSurfaceDataProperty(x3dMaterial));
            matIdx++;
        }

        cityObject.getAppearances().add(new AbstractAppearanceProperty(appearance));
    }
}
