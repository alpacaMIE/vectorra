# Vectorra Basic MVT Beta Design

**Status:** Started by user request on 2026-06-02.

## Goal

Add the first basic Mapbox Vector Tile data capability to Vectorra Maps Beta: decode MVT PBF bytes into typed Kotlin models and convert tile-local geometry into longitude/latitude GeoJSON-style features for query/data workflows.

## Scope

This Beta supports MVT data decoding only. It does not add full Mapbox Style JSON compatibility, GPU vector tile rendering, symbol placement, line joins, text shaping, glyph atlases, collision detection, or automatic network loading of vector tiles.

## Public API

Add `com.vectorra.maps.mvt` models and decoder:

- `VectorraMvtDecoder.decode(bytes)`
- `VectorraMvtTile`
- `VectorraMvtLayer`
- `VectorraMvtFeature`
- `VectorraMvtGeometry`
- `VectorraMvtValue`
- `VectorraMvtTileId`
- `VectorraMvtDecodedFeature`

Add conversion:

```kotlin
tile.toGeoJsonFeatures(tileId)
```

The converted features use existing `VectorraGeoJsonFeature`, `VectorraAnnotationGeometry`, and `VectorraCoordinate` models.

## Decoding Rules

The decoder reads protobuf wire format directly without adding a new dependency. It supports:

- Tile layers at field `3`.
- Layer `name`, `features`, `keys`, `values`, `extent`, and `version`.
- Feature `id`, packed `tags`, `type`, and packed geometry command integers.
- Value string, float, double, int, uint, sint, and bool fields.
- Geometry commands `MoveTo`, `LineTo`, and `ClosePath`.

Unsupported or malformed fields are skipped where possible. Invalid geometry is ignored during GeoJSON conversion.

## Coordinate Conversion

MVT coordinates are tile-local integer coordinates in layer extent units with Y down. Conversion uses Web Mercator:

```text
globalX = tileX * extent + localX
globalY = tileY * extent + localY
lon = globalX / (extent * 2^z) * 360 - 180
lat = atan(sinh(pi * (1 - 2 * globalY / (extent * 2^z))))
```

## Versioning

This feature bumps the SDK version to `0.3.0-beta.1`.

## Testing

Unit tests cover:

- Protobuf varint and signed zig-zag decoding.
- Feature property decoding.
- Point, line, and polygon geometry command decoding.
- Tile-local coordinate conversion to longitude/latitude.
- Conversion to `VectorraGeoJsonFeature`.

Final verification:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```
