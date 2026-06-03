# Offline Prefetch and Cache

Vectorra Maps `0.8.0-beta.1` development builds include a Beta offline manager for region prefetch, cache status, cancellation, retry policy, and cleanup.

`0.8.0-beta.1` is not published while `VECTORRA_VERSION` remains `0.5.0-beta.1`. Treat this page as source-tree development documentation until the version is bumped and the published-AAR release gates are rerun.

## Scope

Supported:

- `VectorraMap.offline` as the public offline entry point.
- Cache status through `VectorraOfflineManager.cacheStatus()`.
- Cache cleanup through `VectorraOfflineManager.clearCache()`.
- Explicit tile prefetch with `prefetchTiles(...)`.
- Region prefetch with `VectorraOfflineRegion` and `VectorraOfflineTileSource`.
- Async prefetch tasks with progress callbacks, cancellation, and `await()`.
- Retry policy through `VectorraPrefetchOptions`.
- Result status through `SUCCESS`, `PARTIAL_FAILURE`, and `FAILED`.
- Raster, vector MVT, and terrain template sources as offline tile source inputs.

Not supported in this Beta:

- GeoPackage.
- Encrypted offline packages.
- Antimeridian region wrapping in a single `VectorraOfflineBounds`; split the area into two regions.
- Background OS-managed downloads.
- Automatic source discovery from the current map style.
- Hard cache capacity controls from the public API.

## Cache Status and Cleanup

```kotlin
val status = map.offline.cacheStatus()
println("entries=${status.totalEntryCount} bytes=${status.totalBytes}")

map.offline.clearCache()
```

`VectorraCacheStatus` reports two buckets:

- `proxy`: tile requests served through the internal localhost tile proxy.
- `resources`: resource requests made through the SDK resource fetcher, including region prefetch, MVT, and 3D Tiles resources.

`clearCache()` clears both buckets. It does not remove app-owned MBTiles files or source files.

## Explicit Tile Prefetch

Use explicit tile prefetch when the app already owns tile ids or URLs:

```kotlin
val result = map.offline.prefetchTiles(
    listOf(
        TileRequest(
            sourceId = "transport",
            layerId = "roads",
            url = "https://tiles.example.com/12/655/1583.pbf",
            tileId = TileId(z = 12, x = 655, y = 1583),
            resourceType = TileResourceType.VECTOR,
            metadata = mapOf("kind" to "mvt")
        )
    )
)

when (result.status) {
    VectorraPrefetchResultStatus.SUCCESS -> Unit
    VectorraPrefetchResultStatus.PARTIAL_FAILURE -> Unit
    VectorraPrefetchResultStatus.FAILED -> Unit
}
```

Empty explicit tile lists fail early. Use region prefetch when an empty tile set is a valid result of bounds/source zoom intersection.

## Region Prefetch

Region prefetch enumerates Web Mercator tiles from bounds and zoom range:

```kotlin
val region = VectorraOfflineRegion(
    bounds = VectorraOfflineBounds(
        west = -122.45,
        south = 37.75,
        east = -122.25,
        north = 37.80
    ),
    minZoom = 12,
    maxZoom = 12
)

val source = VectorraOfflineTileSource.vector(
    VectorraVectorTileSource.xyz(
        id = "transport",
        templateUrl = "https://tiles.example.com/{z}/{x}/{y}.pbf",
        minZoom = 0,
        maxZoom = 14
    ),
    layerId = "roads"
)

val result = map.offline.prefetchRegion(region, listOf(source))
```

Source factories are available for:

- `VectorraOfflineTileSource.raster(VectorraRasterTileSource)`
- `VectorraOfflineTileSource.raster(VectorraRasterLayer)`
- `VectorraOfflineTileSource.vector(VectorraVectorTileSource)`
- `VectorraOfflineTileSource.terrain(VectorraTerrainSource)`

The enumerated `TileRequest.tileId` keeps canonical XYZ tile coordinates. For TMS sources, the URL `{y}` value is flipped while the request tile id preserves the canonical tile row.

## Progress and Cancel

Use async prefetch for UI workflows:

```kotlin
val task = map.offline.prefetchRegionAsync(
    region = region,
    sources = listOf(source),
    options = VectorraPrefetchOptions(maxAttempts = 2)
) { progress ->
    println("${progress.state}: ${progress.finishedCount}/${progress.totalCount}")
}

// Later:
task.cancel()

val result = task.await()
```

Cancel stops before starting remaining tile requests. It does not discard completed results or remove cache entries already written by completed requests.

## Retry and Partial Failure

`VectorraPrefetchOptions` controls task-level retry:

```kotlin
val options = VectorraPrefetchOptions(
    maxAttempts = 3,
    retryStatusCodes = setOf(408, 429, 500, 502, 503, 504)
)
```

Retry applies per tile result. A tile is retried only when:

- the result is not successful;
- the status code is in `retryStatusCodes`;
- `attemptCount` is still below `maxAttempts`.

Completed tile cache writes remain valid if later tiles fail. Use `VectorraPrefetchResult.status` to distinguish full success, partial failure, and full failure.

## Sample App

The sample app includes:

- `Offline PF`: runs a San Francisco MVT region prefetch, logs progress/result/cache status, then clears cache.
- `Cancel PF`: starts the same prefetch and cancels after the first completed tile progress event.

ADB smoke actions:

```powershell
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action offline-prefetch
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action cancel-prefetch
```

The sample logs use the `VectorraSample` tag.
