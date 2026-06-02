# Vectorra SDK Beta Capability Scope Design

Date: 2026-06-02

## Purpose

Vectorra Maps Beta defines the first externally integrable SDK release. The SDK should deliver a balanced Android-first map, 3D, GIS, and offline feature set while keeping scope smaller than a full Mapbox, Cesium, or desktop GIS replacement.

The long-term product direction is a cross-platform SDK, but this Beta release is validated only as an Android AAR with a Kotlin API and a Vulkan-backed native renderer.

## Product Positioning

Vectorra Maps is a map rendering and geospatial data SDK. It is not an online map services platform.

The Beta release covers three balanced scenario groups:

- General app map control: base maps, camera, gestures, lifecycle, screenshot, location, annotations, query, cache.
- 3D city, campus, and engineering scenes: 3D terrain plus building/model-oriented 3D Tiles.
- GIS and survey workflows: GeoJSON, drawing, measurement, query, MBTiles, and offline cache management.

## Platform Boundary

The long-term route is cross-platform SDK support, but the Beta delivery target is Android only.

Beta platform requirements:

- Android AAR distribution.
- Kotlin public API.
- `minSdk 26`.
- Mainstream Android Vulkan devices.
- Vulkan-only rendering.
- No OpenGL fallback.
- Android sample app as the primary manual verification target.

Native parsing and selection logic for MVT, terrain, and 3D Tiles should sit behind platform-neutral interfaces unless the code directly depends on Android Surface, Android filesystem access, JNI, or Vulkan surface setup. iOS and desktop SDKs are not Beta acceptance targets.

## Naming Boundary

All SDK-owned APIs and implementation names should use `Vectorra*`.

Required naming direction:

- `VectorraMapView`
- `VectorraMap`
- `VectorraMapEngine`
- `VectorraNative`
- `VectorraRasterLayer`
- `VectorraRasterTileSource`
- `VectorraLocationComponent`
- `VectorraGeoJsonSource`
- `VectorraQueryOptions`
- `VectorraOfflineRegion`
- `VectorraTerrainOptions`
- `Vectorra3DTilesLayer`

JNI and native bridge naming should also move to Vectorra naming, such as `VectorraNative` and `vectorra_jni`.

The vendored dependency at `third_party/rocky/` may keep its upstream name because it is an external dependency, not SDK API.

The Beta does not preserve `Rocky*` public compatibility APIs.

## Module Boundary

The current Gradle module structure remains appropriate:

- `:vectorra-maps`: Android SDK module, public Kotlin API, engine coordination, JNI bridge, network, cache, layers, query, annotations, location, offline, and native build entry.
- `:vectorra-maps-turf`: GeoJSON and Turf-style geometry utilities, including coordinates, distance, area, and GeoJSON parsing/writing.
- `:vectorra-sample`: sample Android app for integration examples and manual validation.

Product code, tests, docs, and build changes should stay inside `vectorra-maps/`. The `vectorra-references/` directory remains read-only reference material.

## Architecture

The SDK should keep a clear layered architecture.

### Kotlin API Layer

The Kotlin API exposes map SDK concepts only:

- `VectorraMapView`
- `VectorraMap`
- camera state and options
- sources
- layers
- annotations
- offline regions
- terrain options
- 3D Tiles layers
- query options and results
- lifecycle and load/error callbacks

It must not expose Vulkan details, rocky types, JNI handles, native pointers, or renderer implementation details.

### Engine Coordination Layer

The engine coordinates:

- camera state
- gesture results
- lifecycle state
- source and layer registration
- network request configuration
- offline and cache routing
- click and feature queries
- location component state
- error and loading events

The engine validates Kotlin inputs and sends renderable work to the native bridge.

### JNI Bridge Layer

The JNI bridge remains internal. It should carry only necessary calls between Android/Kotlin and native code, including:

- surface attach, detach, and resize
- camera updates
- raster layer operations
- DEM and terrain operations
- MVT layer operations
- 3D Tiles layer operations
- annotation and label operations
- native resource release

### Native Rendering and Data Layer

Native code is responsible for:

- Vulkan and rocky-backed rendering.
- DEM-based visible 3D terrain.
- raster rendering.
- MVT parsing and simplified rendering.
- 3D Tiles parsing, LOD selection, and model loading.
- glTF/GLB and `b3dm` content integration.
- renderer resource lifetime.

Where practical, native parsing and selection logic should stay platform-neutral to support the long-term cross-platform route.

### Network and Offline Layer

Kotlin owns Android-facing network and offline behavior:

- headers
- request interception
- URL rewriting
- caching
- prefetch
- MBTiles file access
- cache status and cleanup

Native code should consume resolved URLs, bytes, or internal resource handles rather than owning Android networking details.

## Public API Shape

The Beta API should be Mapbox-like but independent. It should use familiar map SDK concepts without trying to be source-compatible with Mapbox.

Recommended API families:

- `VectorraMapView`: Android View container for lifecycle, surface, gestures, and snapshot.
- `VectorraMap`: main map operation entry for camera, source, layer, query, location, and offline access.
- `CameraOptions` and `CameraState`: common camera model.
- `VectorraSource`: source family for raster, DEM, vector tile, GeoJSON, 3D Tiles, and MBTiles.
- `VectorraLayer`: layer family for raster, terrain, line, fill, circle, symbol, 3D Tiles, and annotations.
- `VectorraOfflineManager`: region prefetch, MBTiles, cache status, cancel, and cleanup.
- `VectorraQueryOptions` and `VectorraQueriedFeature`: query API.

The Beta should not introduce a full style object tree yet. It should keep source/layer APIs and simplified layer options. A later release can introduce `VectorraStyle` or fuller style JSON support after MVT and style requirements stabilize.

## Data Flow

The intended data flow is:

1. The app creates `VectorraMapView` and obtains `VectorraMap`.
2. The app adds sources, layers, offline sources, or terrain/3D layers.
3. The Kotlin engine validates options and resolves headers, cache, offline, and proxy behavior.
4. Renderable work is passed through `VectorraNative`.
5. Native code renders raster, DEM terrain, MVT, annotations, and 3D Tiles.
6. Query results return through either Kotlin-side hit testing or native-side feature/object picking.
7. Errors and load state changes are surfaced through the public event model.

GeoJSON and annotation queries may remain Kotlin-side for the Beta. MVT and 3D Tiles queries should be native-backed when the rendered feature or object identity comes from native-rendered data.

## Beta Feature Scope

### General Map Control

The Beta includes:

- `VectorraMapView`
- `VectorraMap`
- surface lifecycle management
- map load state
- load and initialization errors
- camera state
- `setCamera`
- `easeTo`
- `flyTo`
- pan, zoom, rotate, and pitch gestures
- gesture settings
- camera changed callbacks
- map click events
- snapshot support
- location component
- location indicator

### Data Sources and Layers

The Beta includes:

- raster tile sources with XYZ, TMS, and WMTS URL templates
- per-source headers
- raster visibility
- raster opacity
- raster saturation
- raster contrast
- DEM elevation tile loading
- DEM visibility
- terrain exaggeration
- basic MVT source support
- simplified MVT line, fill, circle, and symbol styling
- basic MVT query
- GeoJSON point, line, and polygon sources
- GeoJSON layers
- basic GeoJSON clustering
- point, line, polygon, and label annotations
- drawing point, line, and polygon annotations

### 3D Terrain

3D terrain is a core Beta capability.

The Beta includes:

- visible terrain surface generated from DEM tiles
- terrain exaggeration
- pitched perspective browsing
- raster imagery draped on terrain
- shared rendering with 3D Tiles buildings and models
- terrain layer load state
- terrain load errors

The Beta does not include:

- engineering-grade terrain analysis
- geological body rendering
- underground-space analysis
- profile analysis
- cut-and-fill analysis
- slope and aspect analysis
- complex terrain clipping
- full vector draping rules
- terrain physics or navigation constraints

### 3D Tiles Building and Model Support

The Beta prioritizes building and model content.

The Beta includes:

- `tileset.json` loading
- tileset tree parsing
- basic `region`, `box`, and `sphere` bounding volume handling
- screen-space-error or equivalent LOD selection
- `b3dm`
- glTF and GLB content
- geographic placement and transforms
- layer visibility
- layer opacity option with documented limitations for opaque model materials
- layer load state
- load errors
- usable navigation for a representative medium-size 3D Tiles dataset, defined for Beta validation as one building/model tileset with hundreds of tiles and common `b3dm` plus glTF/GLB content

The Beta does not include:

- `pnts` point clouds
- `i3dm`
- `cmpt`
- implicit tiling
- metadata styling
- full 3D Tiles extension coverage

### GIS and Offline

The Beta includes:

- GeoJSON point, line, and polygon overlay
- drawing point, line, and polygon features
- click query
- distance measurement
- area measurement
- online tile disk caching
- region prefetch tasks
- prefetch progress
- prefetch cancellation
- cache capacity management
- cache cleanup
- cache status queries
- MBTiles raster reading
- MBTiles MVT reading

The Beta does not include:

- GeoPackage
- buffer analysis
- overlay analysis
- complex spatial query language
- heavy desktop GIS analysis workflows

### External Integration

The Beta includes:

- Android AAR packaging.
- sample app covering core scenarios.
- basic integration documentation.
- public API stability notes.
- experimental API labels where needed.
- error callbacks.
- diagnostic logging.
- versioning strategy.
- unit tests for pure Kotlin logic.
- Android build verification.

## Explicit Non-Goals

The Beta does not include:

- complete Mapbox Style JSON compatibility.
- Mapbox API compatibility layer.
- POI search.
- geocoding.
- reverse geocoding.
- route planning.
- navigation.
- traffic flow.
- OpenGL fallback.
- iOS SDK release.
- desktop SDK release.
- full 3D Tiles ecosystem support.
- GeoPackage.
- heavy GIS analysis.

## Acceptance Criteria

### Functional Acceptance

- The sample app demonstrates raster base maps, DEM 3D terrain, basic MVT, GeoJSON, drawing or annotations, 3D Tiles, and MBTiles.
- `VectorraMapView` survives Activity lifecycle operations, destruction, recreation, screen rotation, and surface recreation without crashing.
- Camera, gestures, click handling, snapshot, and location indicator are usable.
- Raster, DEM, MVT, and 3D Tiles layers expose load state and load errors.
- MBTiles can read raster and MVT tiles.
- Region prefetch can write to cache.
- Region prefetch exposes progress, cancellation, and cleanup.

### Performance Acceptance

- General map interaction is smooth on mainstream Vulkan Android devices.
- The representative medium-size 3D Tiles validation dataset loads and remains navigable.
- LOD selection avoids obvious UI-thread stalls.
- Cache and prefetch work does not block the UI thread.
- Devices without usable Vulkan report clear initialization errors.

### API Acceptance

- SDK-owned public APIs use `Vectorra*` naming.
- JNI and native handles are not exposed.
- Stable and experimental APIs are documented.
- Versioning and breaking-change policy are documented.

### Test and Build Acceptance

At minimum, the following commands should pass from `vectorra-maps/`:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat assembleDebug
```

Kotlin unit tests should cover:

- camera and coordinate conversion
- URL template expansion
- network interception
- cache behavior
- GeoJSON query behavior
- measurement
- MBTiles basic reading

Native and JNI behavior should be verified through Android build and sample app validation for the Beta.

## Risks and Controls

The Beta has broad scenario coverage. Scope control depends on keeping each area at Beta depth.

Key controls:

- MVT is basic source/layer/query support, not full Mapbox Style.
- 3D Tiles is building/model support, not full 3D Tiles ecosystem support.
- 3D terrain is visualization and alignment, not terrain analysis.
- Offline is MBTiles and cache first, not GeoPackage.
- Online services are excluded.
- Android is the only Beta release target.

## Open Implementation Decisions

These are implementation planning decisions, not scope blockers:

- Whether all `Rocky*` symbols are renamed in one large migration or staged by API surface and native bridge.
- Exact `VectorraSource` and `VectorraLayer` inheritance model.
- Exact MVT simplified style option model.
- Native ownership boundary for MVT feature query.
- Native ownership boundary for 3D Tiles object picking.
- MBTiles reader placement inside `:vectorra-maps` versus a future data submodule.
