# Vectorra MBTiles Offline Source Beta Design

**Status:** Started by user request on 2026-06-02.

## Goal

Add the first MBTiles offline source path for Vectorra Maps Beta so an Android app can load raster tiles from a local `.mbtiles` file and render them through the existing raster layer pipeline.

## Scope

This Beta supports raster MBTiles only. Supported tile formats are `png`, `jpg`, `jpeg`, and `webp`. The implementation does not add vector MBTiles/MVT rendering, GeoPackage, encrypted packages, offline region download, or native rocky MBTiles integration.

## Public API

Add a public source class:

```kotlin
VectorraMbTilesRasterSource.open(file, id = "mbtiles-source")
```

Add a map entry point:

```kotlin
map.addMbTilesRasterLayer(source, layerId = source.id)
```

The public API reports parsed MBTiles metadata through `VectorraMbTilesMetadata`, including `name`, `format`, `minZoom`, `maxZoom`, `bounds`, and `center`.

## Architecture

The native renderer already consumes URL templates for raster layers. MBTiles does not map to static files, so the SDK will extend `TileProxyServer` with a local tile provider registration. The renderer receives a localhost template; the proxy decodes `{z}/{x}/{y}`, reads the tile bytes from SQLite, and returns the correct image content type.

This keeps native rocky unchanged and lets future offline sources reuse the same local-provider path.

## Tile Coordinate Handling

MBTiles stores `tile_column` and `tile_row` in the `tiles` table. By default, Vectorra treats renderer requests as XYZ and converts Y to MBTiles TMS storage row:

```text
storageRow = (2^z - 1) - y
```

If metadata contains `scheme=xyz`, Vectorra reads the row directly. Invalid zoom/x/y values return 404 from the local tile proxy.

## Error Handling

Opening a source validates that:

- The file exists and is readable.
- The `metadata` and `tiles` tables can be queried.
- The tile format is raster and supported.
- `minzoom` and `maxzoom` resolve to a valid range.

Invalid packages throw `IllegalArgumentException` with a concrete message.

## Testing

Unit tests cover:

- MBTiles metadata parsing defaults and validation.
- XYZ-to-TMS row conversion.
- Local tile proxy provider success and 404 behavior.
- Public source creation failure for missing files.

The final verification command set is:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
```
