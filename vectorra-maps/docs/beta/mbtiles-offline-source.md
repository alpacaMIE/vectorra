# MBTiles Offline Source

Vectorra Maps `0.2.0-beta.1` includes a Beta raster MBTiles source path for local offline maps.

## Scope

Supported:

- Raster MBTiles.
- `png`, `jpg`, `jpeg`, and `webp` tile formats.
- Metadata fields: `name`, `format`, `minzoom`, `maxzoom`, `bounds`, `center`, and `scheme`.
- Default MBTiles TMS tile row storage.
- `scheme=xyz` metadata for packages that store rows in XYZ order.

Not supported in this Beta:

- Vector MBTiles and MVT rendering.
- GeoPackage.
- Encrypted packages.
- Offline region download.
- Native rocky MBTiles integration.

## Usage

```kotlin
val file = File(context.filesDir, "basemap.mbtiles")
val source = VectorraMbTilesRasterSource.open(file, id = "basemap")
map.addMbTilesRasterLayer(source, layerId = "basemap")
```

The source validates that the file exists, can be opened read-only, exposes `metadata` and `tiles`, and uses a supported raster format.

## Sample App

The sample app includes an `MBTiles` button. To use it, place a raster package at:

```text
context.filesDir/sample.mbtiles
```

Then tap `MBTiles`. If the package metadata contains `center`, the sample moves the camera there.

## Coordinate Scheme

The renderer requests XYZ `{z}/{x}/{y}` tiles from an internal localhost URL. For standard MBTiles packages, Vectorra converts the requested XYZ row to TMS storage:

```text
storageRow = (2^z - 1) - y
```

If the package metadata contains `scheme=xyz`, Vectorra reads `tile_row` directly.
