# Vectorra 3D Terrain Beta Design

**Status:** Started by user request on 2026-06-02.

## Goal

Promote the existing DEM/elevation path into a clear public 3D Terrain Beta API. The feature should remain Android-first, Vulkan-only, and backed by the current native renderer path.

## Scope

This Beta supports raster DEM terrain sources through URL templates. The first stable API covers source configuration, terrain options, exaggeration clamping, visibility, removal, sample usage, and documentation.

## Non-goals

This step does not add quantized-mesh terrain, terrain collision, elevation sampling, draped vectors, 3D Tiles ground clamping, multi-DEM blending, or native rocky changes.

## Public API

Add `com.vectorra.maps.terrain`:

- `VectorraTerrainSource`
- `VectorraTerrainOptions`
- `VectorraDemEncoding`

Add `VectorraMap` methods:

```kotlin
fun addTerrain(source: VectorraTerrainSource, options: VectorraTerrainOptions = VectorraTerrainOptions())
fun removeTerrain(id: String)
fun setTerrainVisible(id: String, visible: Boolean)
```

Existing `addElevationLayer` and `setTerrainExaggeration` remain for compatibility.

## Data Format

The first public encoding is `TERRARIUM`, matching the current sample and supported renderer path. URL templates use `{z}`, `{x}`, and `{y}` placeholders.

## Validation

Terrain source validation requires:

- Non-blank source id.
- Non-blank URL template.
- `minZoom >= 0`.
- `maxZoom >= minZoom`.

Terrain options require finite exaggeration and clamp it to `0.0..10.0` before native calls.

## Versioning

This feature bumps Vectorra to `0.4.0-beta.1`.

## Verification

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```
