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
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action snapshot
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action geojson
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action draw
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action clear-draw
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action location
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action location-follow
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action clear-location
```

Sample UI additions:

- `MVT MBTiles`
- `Offline PF`
- `Cancel PF`
- `Snapshot`
- `GeoJSON`
- `Draw`
- `Clear Draw`
- `Location`
- `Follow`
- `Clear Loc`

The offline prefetch smoke logs progress, final result status, cache status before and after prefetch, and cache status after cleanup using the `VectorraSample` log tag. The snapshot smoke reports bitmap dimensions and a sampled nonblank-pixel check. The GeoJSON smoke installs a query-only source/layer and logs a center query. The Draw smoke renders point, line, and polygon annotations and logs a center query. The Location smoke uses a deterministic sample location, shows the accuracy ring, exercises heading follow mode, and clears the indicator.

## Fixes And Compatibility Notes

- Fixed `${z}`/`${x}`/`${y}` template replacement order in offline enumeration, MVT loading, and `TileProxyServer`.
- 3D Tiles close-zoom behavior now derives an internal camera target height from the root bounding volume for elevated tilesets.
- `add3DTilesModelLayer(...)` remains deprecated and experimental; use the formal `Vectorra3DTiles*` source/layer API for 3D Tiles runtime work.

## Verification Completed

The following local checks have passed during this development slice:

```powershell
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:assembleDebugAndroidTest
.\tools\run-mbtiles-vector-instrumentation.ps1 -DeviceSerial emulator-5554
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*" --tests "com.vectorra.maps.mvt.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

The full local Android acceptance gate includes unit tests, the SDK instrumentation APK build, sample and aggregate debug builds, release publication to Maven local, sample rebuild from the published AARs, native library content checks, instrumentation APK no-native-library checks, the instrumentation APK checker self-test, the device smoke script contract self-test, the runtime smoke result checker self-test, the emulator smoke gate contract self-test, and the MBTiles vector instrumentation runner contract self-test. The instrumentation APK checker self-test rejects missing, empty, and native-library APK fixtures. The smoke contract self-test keeps runner, checker, fixture, sample action names, final screenshot visible-pixel coverage, and close-zoom 3D Tiles screenshot artifact/visible-pixel coverage aligned. The smoke checker self-test covers the complete fixture plus crash logs, missing or out-of-order action markers, missing post-recreate snapshot markers or logs, missing or empty metadata, APK/ABI mismatches, missing or mismatched artifact records, invalid screenshots, blank final screenshots, missing or blank close-zoom 3D Tiles screenshots, blank snapshots, and missing 3D Tiles close-zoom snapshots. The emulator smoke gate and MBTiles instrumentation runner self-tests keep the rerunnable device-facing scripts and docs aligned without requiring a device.

Targeted tests added or expanded include:

- `VectorraOfflineManagerModelsTest`
- `VectorraOfflineRegionTest`
- `VectorraPrefetchTaskRunnerTest`
- `TileCacheAndSchedulerTest`
- `VectorraMbTilesVectorSourceTest`
- `VectorraMbTilesVectorSourceInstrumentedTest`, passed on `emulator-5554`

## Known Gaps Before Publishing

- Emulator device smoke for `offline-prefetch` and `cancel-prefetch` passed on `emulator-5554`.
- `connectedDebugAndroidTest` for `VectorraMbTilesVectorSourceInstrumentedTest` passed on `emulator-5554`.
- Android 1.0 local acceptance gates have passed. Per user decision on 2026-06-04, the passing emulator smoke report `build/device-smoke/device-smoke-20260604-090522.txt` is accepted as the current release runtime gate substitute; the physical device `4tqoz9bmfu8t8pr8` remains a residual risk because adb large-file transfer failed during APK install/push.
- A published-AAR verification pass was run successfully against the current Gradle project version `0.5.0-beta.1`:

```powershell
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

This confirms the current local publication path, Maven metadata, sources jars, native libraries, and sample AAR consumption path. It does not publish or validate a `0.8.0-beta.1` artifact, because the Gradle project version has not been bumped.

Do not treat `0.8.0-beta.1` as published until the Gradle version is bumped and the same release gates are rerun for that version.
