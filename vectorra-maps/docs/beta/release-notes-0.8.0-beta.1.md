# Release Notes: 0.8.0-beta.1

Status: unpublished development notes.

These notes describe the current `0.8.0-beta.1` API and sample state before a published Beta artifact is cut. `VECTORRA_VERSION` and Maven coordinates in integration docs still point at the last published Beta until release publication is completed.

## Highlights

- Adds the public `VectorraOfflineManager` entry through `VectorraMap.offline`.
- Adds cache status and cleanup APIs.
- Adds explicit tile prefetch.
- Adds region prefetch for Web Mercator bounds and zoom ranges.
- Adds async prefetch tasks with progress, cancel, retry options, and partial-failure reporting.
- Adds MVT MBTiles source and sample generation path.
- Adds sample smoke actions for offline prefetch and cancel workflows.

## Offline And Cache API

New public Beta types:

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
- `VectorraPrefetchProgress`
- `VectorraPrefetchProgressListener`
- `VectorraPrefetchTask`
- `VectorraPrefetchTaskState`

New public Beta entry points:

- `VectorraMap.offline`
- `VectorraOfflineManager.cacheStatus()`
- `VectorraOfflineManager.clearCache()`
- `VectorraOfflineManager.prefetchTiles(...)`
- `VectorraOfflineManager.prefetchRegion(...)`
- `VectorraOfflineManager.prefetchTilesAsync(...)`
- `VectorraOfflineManager.prefetchRegionAsync(...)`

Behavior:

- Explicit tile prefetch fails early for empty tile lists.
- Region prefetch can return an empty successful result when bounds and source zoom ranges do not intersect.
- Async cancel stops before starting remaining requests and preserves completed results and cache writes.
- Retry is per tile result and controlled by `VectorraPrefetchOptions`.
- `VectorraPrefetchResult.status` reports full success, partial failure, or full failure.

## MBTiles And MVT

Added:

- `VectorraMbTilesVectorSource` for `pbf`, `mvt`, and `application/x-protobuf` MBTiles packages.
- Engine wiring that reuses the existing MVT runtime path through the internal local tile provider.
- Android instrumentation fixture for real SQLite vector MBTiles reads.
- Sample action `mvt-mbtiles`, which generates a real SQLite MBTiles file in app cache and renders it through `addMbTilesVectorLayer(...)`.

## Sample Smoke Actions

Added adb actions:

```powershell
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action mvt-mbtiles
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action offline-prefetch
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action cancel-prefetch
```

Sample UI additions:

- `MVT MBTiles`
- `Offline PF`
- `Cancel PF`

The offline prefetch smoke logs progress, final result status, cache status before and after prefetch, and cache status after cleanup using the `VectorraSample` log tag.

## Fixes And Compatibility Notes

- Fixed `${z}`/`${x}`/`${y}` template replacement order in offline enumeration, MVT loading, and `TileProxyServer`.
- 3D Tiles close-zoom behavior now derives an internal camera target height from the root bounding volume for elevated tilesets.
- `add3DTilesModelLayer(...)` remains deprecated and experimental; use the formal `Vectorra3DTiles*` source/layer API for 3D Tiles runtime work.

## Verification Completed

The following local checks have passed during this development slice:

```powershell
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*" --tests "com.vectorra.maps.mvt.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Targeted tests added or expanded include:

- `VectorraOfflineManagerModelsTest`
- `VectorraOfflineRegionTest`
- `VectorraPrefetchTaskRunnerTest`
- `TileCacheAndSchedulerTest`
- `VectorraMbTilesVectorSourceTest`
- `VectorraMbTilesVectorSourceInstrumentedTest`

## Known Gaps Before Publishing

- Device smoke for `offline-prefetch` and `cancel-prefetch` has not run because adb reported device `4tqoz9bmfu8t8pr8` as `offline`.
- `connectedDebugAndroidTest` for `VectorraMbTilesVectorSourceInstrumentedTest` still needs to be rerun on a device once adb returns to `device`.
- A published-AAR verification pass was run successfully against the current Gradle project version `0.5.0-beta.1`:

```powershell
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

This confirms the current local publication path, Maven metadata, sources jars, native libraries, and sample AAR consumption path. It does not publish or validate a `0.8.0-beta.1` artifact, because the Gradle project version has not been bumped.

Do not treat `0.8.0-beta.1` as published until the Gradle version is bumped and the same release gates are rerun for that version.
