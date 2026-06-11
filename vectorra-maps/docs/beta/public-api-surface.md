# Public API Surface

This page is the current Android Beta public API inventory. It separates the source-tree SDK entry points from unpublished development APIs and implementation-public classes that should not be treated as stable app contracts.

Published coordinates still point at `0.5.0-beta.1`. APIs marked with a later `@VectorraBetaApi` value are source-tree development APIs until `VECTORRA_VERSION` is bumped and the published-AAR gates are rerun.

## Published Baseline SDK Entry Points

Core map view and lifecycle:

- `VectorraMapView`
- `VectorraMap`
- `VectorraMapLifecycleCallback`
- `VectorraMapErrorListener`
- `VectorraMapLoadError`
- `VectorraMapLoadState`
- `VectorraSurfaceLifecycleState`
- `VectorraSnapshotCallback`
- `VectorraSnapshotError`
- `VectorraSnapshotter`

Camera:

- `CameraState`
- `CameraOptions`
- `CameraAnimationOptions`

Network and cache:

- `TileNetworkConfig`
- `TileCachePolicy`

Raster and terrain:

- `VectorraRasterLayer`
- `VectorraRasterTileSource`
- `VectorraTerrainSource`
- `VectorraTerrainOptions`
- `VectorraDemEncoding`

Models:

- `VectorraGlbModelSource`
- `VectorraGlbModelLayerOptions`
- `VectorraModelRenderError`

Location:

- `VectorraLocation`
- `VectorraFollowMode`
- `VectorraLocationComponent`

Annotations and query:

- `VectorraCoordinate`
- `VectorraScreenPoint`
- `VectorraAnnotationGeometry`
- `VectorraAnnotationFeature`
- `VectorraGeoJsonFeature`
- `VectorraGeoJsonSource`
- `VectorraGeoJsonLayer`
- `VectorraGeoJsonLayerFilter`
- `VectorraQueryOptions`
- `VectorraQueriedFeature`
- `VectorraMapClickEvent`
- `VectorraMapClickListener`
- `VectorraDrawPointAnnotation`
- `VectorraDrawLineAnnotation`
- `VectorraDrawPolygonAnnotation`
- `VectorraLabelAnnotation`

MBTiles raster:

- `VectorraMbTilesRasterSource`
- `VectorraMbTilesMetadata`
- `VectorraMbTilesBounds`
- `VectorraMbTilesCenter`
- `VectorraMbTilesTileRowScheme`

3D Tiles parsing and inspection:

- `VectorraTileset3D`
- `VectorraTileset3DAsset`
- `VectorraTileset3DTile`
- `VectorraTileset3DContent`
- `VectorraTileset3DContentKind`
- `VectorraTileset3DRefine`
- `VectorraTileset3DBoundingVolume`
- `VectorraTileset3DStatistics`
- `VectorraTileset3DParser`

MVT decoding helpers:

- `VectorraMvtDecoder`
- `VectorraMvtTile`
- `VectorraMvtLayer`
- `VectorraMvtFeature`
- `VectorraMvtGeometry`
- `VectorraMvtPoint`
- `VectorraMvtTileId`
- `VectorraMvtValue`
- `VectorraMvtDecodedFeature`

SDK metadata and markers:

- `VectorraSdk`
- `VectorraBetaApi`
- `VectorraExperimentalApi`

## Unpublished Development APIs

These APIs are present in the current source tree and documented for the unpublished `0.8.0-beta.1` development target. They are not published while `VectorraSdk.VERSION` remains `0.5.0-beta.1`.

3D Tiles runtime:

- `Vectorra3DTilesSource`
- `Vectorra3DTilesLayer`
- `Vectorra3DTilesOptions`
- `VectorraMap.add3DTilesLayer(...)`
- `VectorraMap.remove3DTilesLayer(...)`

Vector tile runtime:

- `VectorraVectorTileSource`
- `VectorraVectorTileLayer`
- `VectorraMap.addVectorTileLayer(...)`
- `VectorraMap.removeVectorTileLayer(...)`

Offline prefetch and cache:

- `VectorraMap.offline`
- `VectorraOfflineManager`
- `VectorraCacheBucketStatus`
- `VectorraCacheStatus`
- `VectorraOfflineBounds`
- `VectorraOfflineRegion`
- `VectorraOfflineTileSource`
- `VectorraPrefetchTileResult`
- `VectorraPrefetchResult`
- `VectorraPrefetchResultStatus`
- `VectorraPrefetchOptions`
- `VectorraPrefetchTaskState`
- `VectorraPrefetchProgress`
- `VectorraPrefetchProgressListener`
- `VectorraPrefetchTask`
- `VectorraMbTilesVectorSource`

Diagnostics:

- `VectorraResourceKind`
- `VectorraResourceLoadState`
- `VectorraResourceEventSource`
- `VectorraResourceErrorType`
- `VectorraResourceLoadError`
- `VectorraResourceStatus`
- `TileLogEvent`
- `TileRequestLogger`
- `RedactingTileLogger`

## Deprecated Experimental Entry Point

`VectorraMap.add3DTilesModelLayer(...)` remains available only as a deprecated, experimental model-rendering smoke path. It is not the Android 1.0 3D Tiles API and must not be documented as an integration path.

Use `addModelLayer(...)` for standalone GLB/GLTF models and `Vectorra3DTilesSource` plus `Vectorra3DTilesLayer` for 3D Tiles runtime work.

## Not Stable App Contracts

The following areas can be Kotlin-public for module or test wiring, but apps should not treat them as stable SDK contracts unless they are listed above:

- `com.vectorra.maps.internal`
- Native JNI names, handles, and callback payload details.
- Renderer-content contracts under `tiles3d` that are marked internal or are not listed above.
- Tile proxy, cache store, scheduler, executor, and local provider internals.
- C++ renderer classes and `third_party/rocky`.
- Gradle, CMake, vcpkg, and packaging implementation details.

## Marker Policy

- `@VectorraBetaApi` marks APIs intended for Beta use.
- `@VectorraExperimentalApi` marks APIs that can change more freely.
- `@Deprecated` plus `@VectorraExperimentalApi` means the API is retained only for migration or smoke validation.
- APIs marked with a `since` version newer than `VectorraSdk.VERSION` are source-tree development APIs until that version is published.

Before a 1.0 release candidate, every supported public API should either appear in this inventory or be moved behind an internal boundary.
