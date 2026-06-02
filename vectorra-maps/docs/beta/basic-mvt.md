# Basic MVT

Vectorra Maps `0.3.0-beta.1` includes a basic Mapbox Vector Tile data decoder.

## Scope

Supported:

- Decode MVT PBF bytes.
- Decode layers, features, ids, tags, keys, values, extents, and versions.
- Decode point, line string, and polygon geometry commands.
- Convert tile-local geometry to longitude/latitude GeoJSON-style features.

Not supported in this Beta:

- Full Mapbox Style JSON compatibility.
- GPU vector tile rendering.
- Symbol placement.
- Text shaping and glyph atlas rendering.
- Line joins, caps, fill styling, collision detection, or automatic vector tile network loading.

## Usage

```kotlin
val tile = VectorraMvtDecoder.decode(bytes)
val features = tile.toGeoJsonFeatures(VectorraMvtTileId(z = 10, x = 823, y = 412))
```

The returned features use existing `VectorraGeoJsonFeature`, `VectorraAnnotationGeometry`, and `VectorraCoordinate` models. This makes the first MVT step useful for data inspection, tests, feature queries, and future rendering integration.

## Coordinate Conversion

MVT coordinates are local to the tile extent and use Y-down Web Mercator tile space. Vectorra converts them to longitude/latitude using the tile id and layer extent.
