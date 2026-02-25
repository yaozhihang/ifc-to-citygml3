"""
Extract triangulated geometry from an IFC file using ifcopenshell.

Outputs a JSON file keyed by GlobalId, where each value is a list of polygons.
Each polygon is a flat array of coordinates: [x0,y0,z0, x1,y1,z1, ..., x0,y0,z0] (closed).

Usage:
    python extract_geometry.py <input.ifc> [output.geom.json]

If no output path is given, defaults to <input>.geom.json.
"""

import argparse
import ifcopenshell
import ifcopenshell.geom
import json
import sys

parser = argparse.ArgumentParser(description="Extract triangulated geometry from an IFC file")
parser.add_argument("input_ifc", help="Path to input IFC file")
parser.add_argument("output_json", nargs="?", default=None, help="Output geometry JSON path")
parser.add_argument("--reorient-shells", action="store_true",
                    help="Ensure outward-oriented solid boundaries")
args = parser.parse_args()

settings = ifcopenshell.geom.settings()
settings.set(settings.USE_WORLD_COORDS, True)
settings.set("triangulation-type", ifcopenshell.ifcopenshell_wrapper.TRIANGLE_MESH)
if args.reorient_shells:
    try:
        settings.set("reorient-shells", True)
    except Exception:
        pass

ifc_file = ifcopenshell.open(args.input_ifc)
result = {}
errors = 0

products = [p for p in ifc_file.by_type("IfcProduct") if p.Representation is not None]
total = len(products)

for idx, product in enumerate(products, 1):
    print(f"\r[{idx}/{total}] {product.is_a()} {product.Name or '':<40s}", end="", flush=True)
    try:
        shape = ifcopenshell.geom.create_shape(settings, product)
        verts = shape.geometry.verts
        faces = shape.geometry.faces

        polygons = []
        for i in range(0, len(faces), 3):
            tri = []
            for vi in [faces[i], faces[i + 1], faces[i + 2], faces[i]]:
                tri.extend([verts[vi * 3], verts[vi * 3 + 1], verts[vi * 3 + 2]])
            polygons.append(tri)

        if polygons:
            result[product.GlobalId] = polygons
    except Exception as e:
        errors += 1
        print(f"\nWarning: {product.GlobalId} ({product.is_a()}): {e}", file=sys.stderr)

output_path = args.output_json if args.output_json else args.input_ifc.rsplit(".", 1)[0] + ".geom.json"
print()
with open(output_path, "w") as f:
    json.dump(result, f)
print(f"Extracted geometry for {len(result)} elements ({errors} errors) -> {output_path}")
