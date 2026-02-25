# IFC to CityGML 3.0 Converter (Java Version)

Converts IFC building models (IFC4 / IFC4X3) to CityGML 3.0 using BIMserver (IFC parsing) and citygml4j (CityGML output). Geometry extraction is handled by Python/ifcopenshell and passed to Java via JSON.

## Requirements

- Java 17 or higher (Java 21 recommended)
- Python 3 with `ifcopenshell` (for geometry extraction)
- Gradle 8.5+ (or use included wrapper)

## Build

```bash
# Build distribution (recommended)
gradlew.bat installDist      # Windows
./gradlew installDist         # Linux/Mac

# Create distributable zip
gradlew.bat distZip
```

Output: `build/install/ifc-to-citygml3/` or `build/distributions/ifc-to-citygml3-0.9.zip`

## Usage

```bash
# Via distribution
bash build/install/ifc-to-citygml3/bin/ifc-to-citygml3 -i input.ifc -o output.gml

# Via Gradle (development)
gradlew.bat run --args="-i input/AC20-FZK-Haus.ifc -o output/test.gml --georef-oktoberfest"
```

### Command-line Options

| Option | Description |
|--------|-------------|
| `-i, --input <file>` | Input IFC file (required) |
| `-o, --output <file>` | Output CityGML file (default: input name with .gml) |
| `-g, --geometry-json <file>` | Explicit path to geometry JSON (default: auto-detect/auto-run) |
| `--georef-oktoberfest` | Georeference to Theresienwiese, Munich (EPSG:25832) |
| `--no-references` | Skip external references |
| `--no-properties` | Skip property sets / generic attributes |
| `--no-storeys` | Skip Storey objects |
| `--no-generic-attribute-sets` | Flatten properties (no GenericAttributeSet grouping) |
| `--pset-names-as-prefixes` | Prefix property names with `[PsetName]` |
| `--reorient-shells` | Ensure outward-oriented solid boundaries (passed to ifcopenshell) |
| `--list-unmapped-doors-and-windows` | Log doors/windows not assigned to a BCE |
| `--unrelated-doors-and-windows-in-dummy-bce` | Wrap orphan doors/windows in dummy BCEs |
| `--xoffset/--yoffset/--zoffset <value>` | Coordinate offset (post-georeferencing) |

### Examples

```bash
# Basic conversion (geometry JSON auto-generated if Python available)
bin/ifc-to-citygml3 -i building.ifc

# With georeferencing
bin/ifc-to-citygml3 -i building.ifc -o building.gml --georef-oktoberfest

# With pre-extracted geometry
python python/extract_geometry.py building.ifc building.geom.json
bin/ifc-to-citygml3 -i building.ifc -g building.geom.json
```

## Geometry Pipeline

Geometry extraction uses Python/ifcopenshell (triangulated meshes with world coordinates) rather than BIMserver's RenderEngine:

1. **Auto-detect**: looks for `<basename>.geom.json` in the output directory
2. **Auto-run**: if not found, runs `extract_geometry.py` via Python subprocess (output to output directory)
3. **Explicit**: pass `-g path/to/geometry.json` to use a specific file

The JSON format is `{ "GlobalId": [[x0,y0,z0, x1,y1,z1, ..., x0,y0,z0], ...], ... }` where each inner array is a closed polygon (triangle).

## Implemented Features

- All standard building element types (walls, slabs, roofs, beams, columns, stairs, ramps, curtain walls, plates, members, footings, piles, proxies)
- Doors/windows embedded as `con:filling` in host walls
- Standalone doors/windows wrapped in BCEs
- BuildingInstallation (coverings, railings)
- BuildingRoom (IfcSpace)
- BuildingFurniture (IfcFurnishingElement)
- Storey export with xlink references
- Full property extraction (IfcPropertySet, IfcElementQuantity, predefined psets for doors/windows)
- GenericAttributeSet grouping or flat output
- Georeferencing (IfcMapConversion or Oktoberfest override)
- Solid vs MultiSurface based on IFC representation type
- External references to IFC GlobalId

### Verified Output (AC20-FZK-Haus test)

- 64 BuildingConstructiveElements, 7 rooms, 2 installations, 16 fillings
- 502 GenericAttributeSets, 3266 StringAttr, 496 IntAttr, 4014 DoubleAttr
- 73 xlink:href references

## Project Structure

```
ifc-to-citygml3/
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat
├── src/main/
│   ├── java/com/tum/gis/ifc2citygml/
│   │   ├── IFC2CityGMLConverter.java   -- CLI entry point
│   │   └── CityGMLGenerator.java       -- Core conversion logic
│   ├── python/
│   │   └── extract_geometry.py         -- Geometry extraction (ifcopenshell)
│   └── resources/
│       └── simplelogger.properties     -- Logging config
├── resources/ifc/                       -- Test IFC files
├── output/                              -- Generated files (in .gitignore)
└── Dockerfile
```

Distribution layout after `installDist` / `distZip`:

```
ifc-to-citygml3-0.9/
├── bin/          -- Start scripts (bash + .bat)
├── lib/          -- All JARs (kept separate, no merging issues)
└── python/       -- Python scripts
    └── extract_geometry.py
```

## Docker

```bash
# Build
docker build -t ifc2citygml .

# Run (geometry extraction + conversion in one step)
docker run -v /path/to/ifc:/data/input -v /path/to/output:/data/output \
  ifc2citygml -i /data/input/building.ifc -o /data/output/building.gml
```

Note: On Git Bash (Windows), prefix with `MSYS_NO_PATHCONV=1` to prevent path conversion.

## IFC Schema Support

- **IFC4**: fully supported (native BIMserver parser)
- **IFC4X3 / IFC4X3_ADD2**: supported (automatic header conversion to IFC4; building entities are backward-compatible)
- **IFC2X3**: not supported

## Dependencies

- **citygml4j 3.2.8**: CityGML 3.0 XML generation
- **BIMserver 1.5.182**: IFC4 STEP parsing (pluginbase, shared, ifcplugins)
- **Gson**: JSON geometry loading
- **Commons CLI**: Command-line parsing
- **SLF4J**: Logging

## Credits

Original Python version by:
- **Thomas H. Kolbe** (thomas.kolbe@tum.de)
- Chair of Geoinformatics, Technical University of Munich

## License

Please refer to the original project's license terms.
