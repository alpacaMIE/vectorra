# API Stability

Vectorra Maps `0.5.0-beta.1` exposes a small Beta API boundary for external Android integration.

## Stable Enough For Beta Integrators

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
- Gesture settings, annotations, query helpers, location component, network config, offline raster helpers, and offline prefetch/cache helpers.

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

## Current Feature Boundary

Included in Beta scope:

- Android AAR/Kotlin API.
- Vulkan renderer startup and lifecycle.
- Camera, gestures, screenshot, map ready/error callbacks.
- Raster tiles and DEM terrain entry points.
- 3D Tiles tileset metadata, bounding volume, content URI, and statistics parsing.
- Basic annotations, query helpers, network interception, offline region prefetch, prefetch progress/cancel, retry/partial-failure reporting, and cache status/cleanup helpers.

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
