# Vectorra Maps

Vectorra Maps is an Android-first map SDK Beta. It exposes a Mapbox-like independent Kotlin API around `VectorraMapView` and `VectorraMap`, backed by a native Vulkan renderer.

## Beta Runtime Scope

- Android AAR and Kotlin API first.
- `minSdk 26`.
- Vulkan-only renderer; no OpenGL fallback.
- Current focus: map view lifecycle, camera, gestures, raster layers, DEM terrain, 3D Tiles tileset inspection, annotations, query helpers, network interception, cache/offline helpers, and sample integration.
- Not included: POI search, geocoding, routing, navigation, traffic, iOS, desktop runtime, full Mapbox Style JSON compatibility, full MVT pipeline, or production 3D Tiles rendering.

## Current Coordinates

```kotlin
implementation("com.vectorra:vectorra-maps:0.5.0-beta.1")
implementation("com.vectorra:vectorra-maps-turf:0.5.0-beta.1")
```

During local development, publish to Maven local first:

```powershell
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
```

Then verify the sample from the published AAR:

```powershell
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

## Docs

- [Android AAR integration](docs/beta/android-aar-integration.md)
- [API stability](docs/beta/api-stability.md)
- [Diagnostics and troubleshooting](docs/beta/diagnostics-troubleshooting.md)
- [Offline prefetch and cache](docs/beta/offline-prefetch-cache.md)
- [MBTiles offline source](docs/beta/mbtiles-offline-source.md)
- [Basic MVT](docs/beta/basic-mvt.md)
- [3D terrain](docs/beta/3d-terrain.md)
- [3D Tiles](docs/beta/3d-tiles.md)
- [Release and versioning](docs/beta/release-versioning.md)
- [0.8.0-beta.1 development release notes](docs/beta/release-notes-0.8.0-beta.1.md)
