# 3D Terrain

Vectorra Maps `0.4.0-beta.1` includes a Beta 3D terrain API around raster DEM tiles.

## Scope

Supported:

- Raster DEM URL templates.
- Terrarium DEM encoding.
- Terrain exaggeration.
- Terrain visibility.
- Terrain removal.
- Network headers through `VectorraTerrainSource.headers`.

Not supported in this Beta:

- Quantized-mesh terrain.
- Terrain collision.
- Elevation sampling.
- Draped vector rendering.
- 3D Tiles ground clamping.
- Multi-DEM blending.

## Usage

```kotlin
val source = VectorraTerrainSource(
    id = "terrain",
    templateUrl = "https://example.com/terrarium/{z}/{x}/{y}.png",
    minZoom = 0,
    maxZoom = 14
)

map.addTerrain(
    source = source,
    options = VectorraTerrainOptions(exaggeration = 1.5)
)
```

To hide or remove terrain:

```kotlin
map.setTerrainVisible("terrain", false)
map.removeTerrain("terrain")
```

## Exaggeration

`VectorraTerrainOptions.exaggeration` must be finite. Values are clamped to `0.0..10.0` before reaching the native renderer.
