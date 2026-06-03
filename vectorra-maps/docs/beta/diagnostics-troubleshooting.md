# Diagnostics and Troubleshooting

Vectorra Maps exposes diagnostics through public callbacks and status payloads. Apps should surface these signals in their own UI instead of relying on logcat-only workflows.

## Map Startup Errors

Use map-load error callbacks for renderer startup, unsupported device, and top-level resource failures:

```kotlin
val subscription = mapView.addMapLoadErrorListener { _, error ->
    when (error) {
        is VectorraMapLoadError.UnsupportedDevice -> showUnsupportedDevice()
        is VectorraMapLoadError.NativeRenderer -> showRendererError(error.message)
        is VectorraMapLoadError.Resource -> showResourceError(error.message)
    }
}
```

Equivalent delivery paths are:

- `VectorraMapView.errorListener`
- `VectorraMapView.addMapLoadErrorListener(...)`
- `VectorraMapLifecycleCallback.onMapLoadError(...)`

Close listener subscriptions when the surrounding component outlives the `VectorraMapView`.

## Layer and Source Status

Use `VectorraResourceStatus` for layer/source diagnostics:

```kotlin
val subscription = map.addResourceStatusListener { status ->
    val text = when (status.state) {
        VectorraResourceLoadState.FAILED ->
            "${status.kind} ${status.layerId} failed: ${status.error?.message}"
        else ->
            "${status.kind} ${status.layerId} ${status.state}"
    }
    showStatus(text)
}
```

Current status can be queried without waiting for another callback:

```kotlin
val layerStatus = map.getLayerResourceStatus("buildings-layer")
val sourceStatus = map.getSourceResourceStatus("buildings")
```

Status fields:

- `kind`: `RASTER`, `DEM`, `MODEL`, `MBTILES`, `TILES3D`, or `VECTOR`.
- `state`: `LOADING`, `LOADED`, `FAILED`, or `REMOVED`.
- `eventSource`: `ENGINE`, `NATIVE`, `TILE_PROXY`, or `LOCAL_PROVIDER`.
- `error.type`: `NETWORK`, `NATIVE_RENDERER`, `RESOURCE`, `UNSUPPORTED`, `CACHE`, or `UNKNOWN`.

For sample validation, the Android sample writes the latest important status into the on-screen status chip. Bad model, bad 3D Tiles, MBTiles, MVT, and offline prefetch paths should show user-visible status text.

## Network Request Logging

Use `RedactingTileLogger` as a `TileNetworkConfig` interceptor when request/response visibility is needed:

```kotlin
map.setTileNetworkConfig(
    TileNetworkConfig(
        interceptors = listOf(
            RedactingTileLogger { event ->
                Log.i(
                    "VectorraNetwork",
                    "${event.phase} ${event.method} ${event.redactedUrl} status=${event.statusCode}"
                )
            }
        )
    )
)
```

`RedactingTileLogger` logs:

- request phase;
- response phase and HTTP status;
- error phase when the chain throws;
- source id, layer id, method, and redacted URL.

The default redacted query keys are:

```text
tk, ak, apikey, api_key, key, token, access_token, mk, url, signature, sign, auth, authorization
```

Pass a custom key set to the `RedactingTileLogger` constructor when a deployment uses additional query credential names.

Security boundary:

- The logger redacts URL query parameters only.
- It does not log headers or request/response bodies.
- Do not add interceptors that print authorization headers, cookies, signed URLs before redaction, or binary tile payloads.
- Put credentials in headers when possible; if a provider requires query credentials, ensure the query key is in `redactQueryKeys`.

## Offline Diagnostics

Use async prefetch progress for UI:

```kotlin
val task = map.offline.prefetchRegionAsync(region, sources) { progress ->
    showStatus("${progress.state}: ${progress.finishedCount}/${progress.totalCount}")
}

task.cancel()
val result = task.await()
```

Result status:

- `SUCCESS`: every requested tile completed successfully.
- `PARTIAL_FAILURE`: at least one tile succeeded and at least one failed.
- `FAILED`: no requested tile completed successfully.

`VectorraPrefetchTileResult.attemptCount` reports retry count per tile. `VectorraOfflineManager.cacheStatus()` and `clearCache()` report and clear the SDK cache buckets. Completed cache writes remain valid after later tile failures or cancellation.

Sample smoke actions:

```powershell
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action offline-prefetch
adb shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action cancel-prefetch
```

## Common Failures

| Symptom | Primary signal | Likely cause | Action |
| --- | --- | --- | --- |
| Map never becomes ready | `VectorraMapLoadError.UnsupportedDevice` or `NativeRenderer` | Device lacks usable Vulkan support or native renderer startup failed | Show unsupported-device UI and capture device model, Android API, ABI, GPU, and Vulkan details |
| Raster, DEM, model, MVT, MBTiles, or 3D Tiles fails | `VectorraResourceStatus.state == FAILED` | Bad URL, unsupported URI, missing local file, invalid content, native renderer failure, or cache/provider failure | Show `status.error.message`; inspect `kind`, `eventSource`, and `error.type` |
| Tile requests return 401/403 | `RedactingTileLogger` response status or resource `NETWORK` error | Missing or rejected credentials | Check source headers and provider token configuration without logging secrets |
| Tile requests return 404 | Network log status or resource `NETWORK` error | URL template, XYZ/TMS row, WMTS matrix, or base URI mismatch | Validate final redacted URL and source scheme |
| MBTiles source fails early | Resource `RESOURCE` or `UNSUPPORTED` error | Missing file, invalid metadata, or unsupported format | Validate metadata and keep app-owned MBTiles files outside `clearCache()` expectations |
| 3D Tiles disappears when zoomed very close | Sample status may remain loaded while geometry is not visible | Camera can move inside a very large model bounds or near-plane clipping can hide content | Use a camera range appropriate to the tileset bounds and verify with `VectorraResourceKind.TILES3D` status |
| Offline prefetch finishes with partial results | `VectorraPrefetchResultStatus.PARTIAL_FAILURE` | Some tiles failed after retry while others were cached | Show completed/failed counts and keep successful cached results |
| Offline prefetch cancel still leaves cached tiles | Task state/result plus cache status | Cancellation stops future work; completed writes are retained | Treat retained cache entries as expected behavior |

## Sample Log Tags

The sample uses `VectorraSample` for smoke progress logs. Product flows should not require these logs for normal error reporting; they are only additional verification evidence during development.
