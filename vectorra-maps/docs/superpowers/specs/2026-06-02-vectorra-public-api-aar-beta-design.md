# Vectorra Public API And AAR Beta Design

**Status:** Approved for execution by user request on 2026-06-02.

## Goal

Build the first external-integration Beta loop for Vectorra Maps: a small Mapbox-like Kotlin API boundary, Android AAR publication, sample app verification from the published artifact, basic documentation, error callbacks, and version policy.

## Scope

This design covers Android only. The SDK remains Vulkan-only with `minSdk 26`; there is no OpenGL fallback. It does not add MBTiles, MVT, 3D Tiles, routing, geocoding, POI search, navigation, traffic, iOS, or desktop runtime support.

## Public API Boundary

The Beta public API is centered on:

- `VectorraMapView` as the Android view and lifecycle owner.
- `VectorraMap` as the map controller.
- Camera models and methods: `CameraState`, `CameraOptions`, `CameraAnimationOptions`, `setCamera`, `easeTo`, `flyTo`, camera callbacks.
- Basic layer/source APIs already present for raster tiles, DEM elevation, annotations, GeoJSON query helpers, location, network interception, and offline raster helper APIs.
- View-level lifecycle and error callbacks.

The public package remains `com.vectorra.maps`. JNI and renderer details remain internal under `com.vectorra.maps.internal` and native C++.

## Beta API Marking

Add lightweight API annotations:

- `@VectorraBetaApi` identifies APIs intended to be usable by external Beta integrators.
- `@VectorraExperimentalApi` identifies APIs that can still change more freely.

These annotations do not require Kotlin opt-in. The Beta must remain easy to consume from Java/Kotlin sample apps without compiler flags.

## Error Callback

`VectorraMapLifecycleCallback.onMapLoadError` already exists, but external integrators need a direct error callback that does not require implementing the full lifecycle callback. `VectorraMapView` will add:

- `VectorraMapErrorListener`
- `errorListener`
- `addMapLoadErrorListener(listener): Closeable`

The callback reports renderer initialization, unsupported Vulkan/device, and resource errors through `VectorraMapLoadError`.

## AAR Publication

Gradle publication will use a centralized version and group:

- Group: `com.vectorra`
- Version: `0.1.0-beta.1`
- Core artifact: `com.vectorra:vectorra-maps`
- Turf artifact: `com.vectorra:vectorra-maps-turf`

Both library modules publish release AARs with source jars. POM metadata will describe the artifact and Android Beta status.

## Sample Integration Verification

The sample keeps project dependency mode as default for development. It also supports a Gradle property:

```powershell
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

When the property is true, the sample depends on `com.vectorra:vectorra-maps:<version>` from `mavenLocal()`. This proves the published AAR can be consumed by an external Android app.

## Documentation

Add Beta docs under `docs/beta/`:

- Android getting started and sample usage.
- API stability boundary.
- Release and versioning policy.

The docs must state Vulkan-only, `minSdk 26`, Android-first Beta, current feature scope, and unsupported product areas.

## Verification

The implementation is accepted only when these pass in a clean Gradle user home:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```
