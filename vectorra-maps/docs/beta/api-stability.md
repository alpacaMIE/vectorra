# API Stability

Vectorra Maps publishes `0.5.0-beta.1` as the current Beta artifact. The current source tree also contains APIs annotated for the unpublished `0.8.0-beta.1` development target.

Use the published coordinates in integration docs for external apps. Treat APIs marked with a later `@VectorraBetaApi` version than `VectorraSdk.VERSION` as source-tree development APIs until that version is published.

For the current API inventory, see [Public API surface](public-api-surface.md).

## Published Beta Boundary

- `VectorraMapView`
- `VectorraMap`
- `CameraState`
- `CameraOptions`
- `CameraAnimationOptions`
- `VectorraMapLifecycleCallback`
- `VectorraMapErrorListener`
- `VectorraMapLoadError`
- `VectorraMapLoadState`
- `VectorraSurfaceLifecycleState`
- Basic raster and DEM layer entry points.
- 3D terrain source and options APIs.
- 3D Tiles tileset parsing and inspection APIs.
- Basic MVT data decoding and GeoJSON conversion.
- Gesture settings, annotations, query helpers, location component, network config, and offline raster helpers.

## Current Source Development Boundary

Present in the current source tree, but not published until the project version is bumped and release gates are rerun:

- Formal 3D Tiles runtime source/layer/options APIs.
- Vector tile source/layer APIs and basic MVT rendering/query support.
- Shared resource status APIs.
- Redacted tile request logging.
- Offline region prefetch, async prefetch progress/cancel, retry/partial-failure reporting, cache status, and cache cleanup helpers.
- MVT MBTiles source support.

These APIs can still change during Beta, but changes should be intentional and documented in release notes.

## Internal Boundary

The following areas are not external API contracts:

- `com.vectorra.maps.internal`
- JNI method names and native handles.
- C++ renderer types.
- `third_party/rocky`
- Gradle/CMake implementation details.

Apps must not depend on internal packages.

## API Markers

`@VectorraBetaApi` marks APIs intended for Beta use. `@VectorraExperimentalApi` marks APIs that can change more freely. These markers are documentation annotations and do not require Kotlin opt-in.

## Current Source Feature Boundary

Included in Beta scope:

- Android AAR/Kotlin API.
- Vulkan renderer startup and lifecycle.
- Camera, gestures, screenshot, map ready/error callbacks.
- Raster tiles and DEM terrain entry points.
- 3D Tiles tileset metadata, bounding volume, content URI, and statistics parsing.
- Basic annotations, query helpers, network interception, offline region prefetch, prefetch progress/cancel, retry/partial-failure reporting, and cache status/cleanup helpers.
- Diagnostics through map load errors, shared resource status, redacted tile request logging, and offline prefetch result/progress payloads.

Excluded from this Beta boundary:

- POI search.
- Geocoding.
- Routing.
- Navigation.
- Traffic.
- OpenGL fallback.
- iOS or desktop runtime.
- Full Mapbox Style JSON compatibility.
- Production 3D Tiles and full MVT implementation.
