# IFC to CityGML 3.0 Converter (Java)

This is a Java version of the [Python IFC-to-CityGML3 converter](https://github.com/tum-gis/ifc-to-citygml3) by Thomas H. Kolbe (TUM).

It converts IFC building models (IFC4 / IFC4X3) to CityGML 3.0 using [BIMserver](https://github.com/opensourceBIM/BIMserver) for IFC parsing, [citygml4j](https://github.com/citygml4j/citygml4j) for CityGML output, and [ifcopenshell](https://ifcopenshell.org/) (Python) for geometry extraction.


## License
[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)


## Requirements

- Java 17+ (21 recommended)
- Python 3 with `ifcopenshell` (for geometry extraction)
- Gradle 8.5+ (or use the included wrapper)

## Quick Start

```bash
# Build
gradlew.bat installDist          # Windows
./gradlew installDist             # Linux/Mac

# Run
build/install/ifc-to-citygml3/bin/ifc-to-citygml3 -i building.ifc -o building.gml
```

## Usage

```bash
# Via distribution
bin/ifc-to-citygml3 -i input.ifc -o output.gml

# Via Gradle (development)
gradlew.bat run --args="-i resources/ifc/fzk-haus/AC20-FZK-Haus.ifc -o output/test.gml"
```

### Command-line Options

| Option | Description |
|--------|-------------|
| `-i, --input <file>` | Input IFC file (required) |
| `-o, --output <file>` | Output CityGML file (default: `<input>.gml`) |
| `--georef-oktoberfest` | Georeference to Theresienwiese, Munich (EPSG:25832) |
| `--no-references` | Skip external references |
| `--no-properties` | Skip property sets / generic attributes |
| `--no-appearances` | Skip appearance (material/color) data |
| `--no-storeys` | Skip Storey objects |
| `--no-generic-attribute-sets` | Flatten properties (no GenericAttributeSet grouping) |
| `--pset-names-as-prefixes` | Prefix property names with `[PsetName]` |
| `--reorient-shells` | Ensure outward-oriented solid boundaries (slower) |
| `--list-unmapped-doors-and-windows` | Log doors/windows not assigned to a BCE |
| `--unrelated-doors-and-windows-in-dummy-bce` | Wrap orphan doors/windows in dummy BCEs |
| `--xoffset / --yoffset / --zoffset <value>` | Coordinate offset (applied after georeferencing) |

### Examples

```bash
# Basic conversion
bin/ifc-to-citygml3 -i building.ifc

# With georeferencing
bin/ifc-to-citygml3 -i building.ifc -o building.gml --georef-oktoberfest

# Without appearances or properties
bin/ifc-to-citygml3 -i building.ifc --no-appearances --no-properties
```

## Architecture

### Conversion Pipeline

```
IFC file
  │
  ├──► Python/ifcopenshell ──► geometry JSON (temp file, multi-threaded)
  │
  └──► BIMserver (IFC4 STEP parser) ──► IfcModelInterface
                                              │
                              ┌────────────────┤
                              ▼                ▼
                     PropertyHandler    GeometryHandler
                              │                │
                              └───────┬────────┘
                                      ▼
                         SpatialStructureConverters
                          (buildings processed in parallel)
                                      │
                                      ▼
                              CityGML 3.0 XML
```

### Geometry Extraction

Geometry extraction uses `extract_geometry.py` with ifcopenshell's multi-threaded geometry iterator, utilizing all available CPU cores. On each run, the script writes to a unique temporary file that is deleted after loading — ensuring geometry is always fresh and consistent with the input IFC file.

The JSON format supports per-face materials:
```json
{
  "GlobalId": {
    "polygons": [[x0,y0,z0, x1,y1,z1, x2,y2,z2, x0,y0,z0], ...],
    "materials": [[r, g, b, transparency], null, ...]
  }
}
```

### Parallelism

| Level | Strategy | Benefit |
|-------|----------|---------|
| Geometry extraction | ifcopenshell multi-threaded iterator (C++ level) | High — main bottleneck |
| Multiple buildings | `parallelStream()` over IfcBuilding instances | Medium — for multi-building files |
| Feature types | Extensible via `SpatialStructureConverter` interface | Future: bridges, tunnels |

## Implemented Features

- All standard building element types (walls, slabs, roofs, beams, columns, stairs, ramps, curtain walls, plates, members, footings, piles, proxies)
- Doors/windows embedded as `con:filling` in host walls
- Standalone doors/windows wrapped in dummy BCEs
- BuildingInstallation (coverings, railings)
- BuildingRoom (IfcSpace)
- BuildingFurniture (IfcFurnishingElement)
- Storey export with xlink references
- Full property extraction (IfcPropertySet, IfcElementQuantity, predefined psets for doors/windows)
- GenericAttributeSet grouping or flat output
- Appearance with per-face X3DMaterial (diffuse color + transparency)
- Georeferencing (IfcMapConversion or Oktoberfest override)
- Solid vs MultiSurface geometry based on IFC representation type
- External references to IFC GlobalId

### Verified Output (AC20-FZK-Haus)

| Metric | Count |
|--------|-------|
| BuildingConstructiveElement | 64 |
| BuildingRoom | 7 |
| BuildingInstallation | 2 |
| Fillings (doors + windows) | 16 |
| GenericAttributeSet | 502 |
| StringAttribute | 3,266 |
| IntAttribute | 496 |
| DoubleAttribute | 4,014 |
| xlink:href | 73 |

## IFC Schema Support

| Schema | Status |
|--------|--------|
| **IFC4** | Fully supported (native BIMserver parser) |
| **IFC4X3 / IFC4X3_ADD2** | Supported (automatic header rewrite to IFC4) |
| **IFC2X3** | Not supported |

## Build

```bash
# Build distribution (recommended)
gradlew.bat installDist      # Windows
./gradlew installDist         # Linux/Mac

# Create distributable zip
gradlew.bat distZip
```

Distribution layout:

```
ifc-to-citygml3-0.9/
├── bin/          Start scripts (bash + .bat)
├── lib/          All JARs
└── python/       extract_geometry.py
```

## Docker

```bash
# Build
docker build -t ifc2citygml .

# Run
docker run --rm \
  -v /path/to/ifc:/data/input \
  -v /path/to/output:/data/output \
  ifc2citygml -i /data/input/building.ifc -o /data/output/building.gml
```

### Windows examples

PowerShell:
```powershell
docker run --rm `
  -v "${PWD}\resources\ifc\fzk-haus:/data/input" `
  -v "${PWD}\output:/data/output" `
  ifc2citygml -i /data/input/AC20-FZK-Haus.ifc -o /data/output/building.gml
```

CMD:
```cmd
docker run --rm ^
  -v "%cd%\resources\ifc\fzk-haus:/data/input" ^
  -v "%cd%\output:/data/output" ^
  ifc2citygml -i /data/input/AC20-FZK-Haus.ifc -o /data/output/building.gml
```




