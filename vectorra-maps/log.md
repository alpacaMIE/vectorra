# Vectorra Development Log

## 2026-06-03

### Context

Continued development from `docs/继续开发计划.md`, starting with Phase 0 resource status contract work. All implementation changes were kept inside `vectorra-maps/`; `vectorra-references/` was not modified.

The worktree already contained uncommitted changes before this session, including native/model/third_party related files. This log records only the work completed in this session.

### Completed

- Added public resource status payload types in `vectorra-maps/src/main/java/com/vectorra/maps/VectorraResourceStatus.kt`.
- Added `VectorraMap` APIs:
  - `addResourceStatusListener`
  - `getLayerResourceStatus`
  - `getSourceResourceStatus`
- Added engine-side resource status storage, generation tracking, and main-thread listener dispatch in `VectorraMapEngine`.
- Wired raster, DEM, MBTiles raster, and GLB/GLTF model add/remove paths into the unified status contract.
- Added failure status emission for synchronous native resource operation failures.
- Marked `add3DTilesModelLayer` as deprecated and experimental smoke API.
- Updated sample app status UI to listen to the unified resource status contract instead of relying on logcat-only feedback.
- Added `docs/resource-status-contract.md` documenting payload fields, state semantics, threading, and ownership.
- Added `VectorraResourceStatusTest` to lock the status payload validation rules.

### Verification

Commands were run from `D:\workspace\code\vectorra\vectorra-maps`.

The default Gradle invocation was blocked by `C:\Users\myg\.gradle\init.d\maven-redirect.gradle`, so verification used an isolated Gradle user home and explicit Android SDK environment:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.

During one full unit test run, `TileCacheAndSchedulerTest.schedulerDeduplicatesSameTileRequest` failed once, then passed when rerun directly and passed again in the final full run. This appears unrelated to the resource status work and should be treated as a possible existing timing-sensitive test.

### Remaining Phase 0 Work

- P0.T2: implement native-to-Kotlin status event bridge so native raster, DEM, and model completion/failure events update the same resource status contract.
- P0.T3: run and document full baseline verification, including turf tests and full assemble.
- P0.T8: add internal `TileCacheStore` skeleton/adapter as the cache owner interface for later 3D Tiles and MVT work.

### Continued Phase 0 Completion

Completed the remaining implementation slice from `docs/继续开发计划.md`:

- Added JNI/native resource status callback registration through `VectorraNative.setResourceStatusCallback`.
- Wired native raster, DEM, and model `LOADED`/`FAILED` events into the existing Kotlin resource status contract.
- Kept source identity and MBTiles kind ownership in `VectorraMapEngine`; native emits only native kind, layer id, state, and optional error fields.
- Added `VectorraNativeResourceStatusMapper` with tests for MBTiles identity preservation, failed callback validation, stale removed callback dropping, and kind mismatch dropping.
- Added internal `TileCacheStore` skeleton and routed `TileProxyServer` plus `CachingTileRequestExecutor` cache read/write decisions through it.
- Added `TileCacheStore` tests for memory hit, disk hit, successful write, and uncacheable request behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps` with isolated Gradle home and explicit Android SDK environment:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-maps-turf:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed and compiled native debug JNI for `arm64-v8a` and `x86_64`.
- `assembleDebug` passed.

Known remaining issue:

- Device smoke was not executed in this session because no Android device/emulator validation was requested or available through the current verification flow. P0.T7 still requires runtime device/API/ABI/GPU/Vulkan recording and screenshot/lifecycle checks.

### P0 Acceptance Check

Follow-up verification checked Android device availability before closing the current P0 development task:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
```

Result:

- ADB reported device `4tqoz9bmfu8t8pr8` as `offline`, so P0.T7 device smoke could not be executed.

P0 implementation status:

- P0.T0, P0.T1, P0.T2, P0.T3, P0.T6, and P0.T8 are covered by the resource status contract, native callback bridge, cache-store skeleton, documentation, unit tests, turf tests, sample assemble, and full debug assemble already recorded above.
- P0.T4 and P0.T5 have compile/build coverage and unified status/error paths, but visual runtime regression remains unverified until a usable Android device or emulator is available.
- P0.T7 remains blocked by unavailable device access. Required unverified items are device model, Android API, ABI, GPU/Vulkan, screenshot non-empty, runtime visibility, remove/re-add, pause/resume or rotation, and destroy/recreate.
- P0.T9 is partially accepted for code and build readiness. Final runtime acceptance is blocked only on P0.T7 device smoke.

### P0 Device Smoke

After the device was unlocked and reconnected, P0.T7 was rerun against a physical Android device.

Device:

- ADB id: `4tqoz9bmfu8t8pr8`
- Model: `2312DRAABC`
- Android API: `35`
- ABI: `arm64-v8a`
- GPU/Vulkan: `Mali-G57 MC2`; Vulkan probe reported `VK_SUCCESS`, one physical device, API `1.3.278`, swapchain support, and present queue support.

Commands/actions:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r .\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell monkey -p com.vectorra.sample 1
```

Results:

- `:vectorra-sample:assembleDebug` passed.
- The rebuilt `arm64-v8a` sample APK installed successfully; package `lastUpdateTime` was `2026-06-03 13:53:11`.
- Cold start reached `com.vectorra.sample/.MainActivity`; process stayed alive.
- Native logs reported `rocky frame 1 ok`, subsequent frames, raster layer status `ok=1`, terrain status, and Vulkan probe success.
- UI status used the unified status contract and showed `raster sample-base-imagery loaded`.
- Startup screenshot was captured at `build/p0-smoke/p0-smoke-startup.png`; size was `108643` bytes, resolution `1080x2400`, sampled colors `8`.
- After forced destroy/recreate, a stable screenshot was captured at `build/p0-smoke/p0-smoke-recreated-stable.png`; size was `1267007` bytes, resolution `1080x2400`, sampled colors `132`.
- Home/resume kept the sample focused and process alive.
- Rotation to landscape and back to portrait kept the sample focused and process alive.
- Force-stop/relaunch created a new process and returned to `MainActivity`.
- DEM button path showed `dem sample-terrarium-dem loaded`.
- Model button path was requestable, Toggle showed `Model hidden`, Remove showed `Model removed`, and Bad GLB showed `Broken model requested`.

Known issues / unverified details:

- MIUI Scout reported `APP_SCOUT_WARNING` and `APP_SCOUT_HANG` samples while the main thread was inside native `VectorraNative.setSurface`. The app recovered, rendered frames, stayed focused, and no `FATAL EXCEPTION`, `SIGSEGV`, or fatal signal was observed, but this is a P1 performance/lifecycle risk to revisit.
- Bad GLB was triggered, but a unified `failed` status was not observed in the short automated wait window. The request path is covered; delayed network/model failure callback behavior still needs a focused follow-up.

P0.T9 status:

- P0 is accepted for code, build, and first device smoke coverage.
- Remaining P0 follow-up risk is performance around native `setSurface` on this MIUI device and delayed bad-GLB failure observation.

### Phase 1 Start - P1.T0 3D Tiles Renderer Contract

Continued development into Phase 1 with the renderer input contract required before adding the formal `Vectorra3DTiles*` runtime API.

Completed:

- Added internal `Vectorra3DTilesRendererContract` types for:
  - full 4x4 matrix transforms;
  - ECEF origin plus local transform placement;
  - renderable payload source identity for `.glb`, `.gltf`, and `.b3dm` inner-GLB cache URI.
- Added validation that `.b3dm` content cannot be sent to native as raw bytes or as the original `.b3dm` URI; it must resolve to a cache-managed `.glb`/`.gltf` render URI first.
- Added JNI declarations for `VectorraNative.add3DTilesRendererContent` and `remove3DTilesRendererContent`.
- Added native C++ contract storage and validation for 3D Tiles renderer content ids, render URIs, transform kind, matrix values, ECEF origin, and visibility.
- Kept this as a 3D Tiles-specific internal renderer contract; the deprecated `add3DTilesModelLayer` smoke API was not extended.
- Added `Vectorra3DTilesRendererContractTest` for matrix preservation, ECEF placement, b3dm cache-URI enforcement, unknown content rejection, and invalid matrix rejection.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed and rebuilt native debug JNI for `arm64-v8a` and `x86_64`.

Known remaining Phase 1 work:

- P1.T0 currently defines and compiles the contract but does not yet create rendered rocky model entities for 3D Tiles content. P1.T4/P1.T5/P1.T6 should consume this contract when GLB/GLTF content lifecycle, transform composition, and b3dm payload extraction are implemented.
- P1.T1 is next: add the formal `Vectorra3DTilesSource`, `Vectorra3DTilesLayer`, and `Vectorra3DTilesOptions` API path and wire its load/error state into the unified resource status contract.

### P1.T1 Formal 3D Tiles API

Continued Phase 1 by adding the formal 3D Tiles source/layer/options path.

Completed:

- Added public `Vectorra3DTilesSource`, `Vectorra3DTilesLayer`, and `Vectorra3DTilesOptions` API models.
- Added `VectorraMap.add3DTilesLayer(...)` and `remove3DTilesLayer(...)`.
- Added `VectorraResourceKind.TILES3D` so 3D Tiles source/layer state uses the same public resource status contract as raster, DEM, model, and MBTiles.
- Implemented engine-side 3D Tiles tileset loading for local paths, `file://`, `http`, and `https` tileset URIs.
- Parses loaded `tileset.json` through the existing `VectorraTileset3DParser` and emits unified `LOADING`, `LOADED`, `FAILED`, and `REMOVED` status for the source/layer.
- Added generation checks so stale background loads cannot overwrite a later remove/re-add state.
- Added formal API model tests.
- Updated the sample app with a `3D Tiles` button that calls `Vectorra3DTilesSource` + `Vectorra3DTilesLayer` + `Vectorra3DTilesOptions`; the deprecated `add3DTilesModelLayer` smoke path was not extended.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed and rebuilt native debug JNI for `arm64-v8a` and `x86_64`.

Known remaining Phase 1 work:

- P1.T2 still needs product-level URI loading behavior: cache/interceptor reuse, stronger base URI fixtures, and explicit bad tileset sample status validation.
- P1.T3-P1.T6 still need runtime tile traversal, GLB/GLTF content lifecycle, transform composition, and b3dm extraction/rendering through the P1.T0 renderer contract.

### P1.T2 Tileset Loading

Continued Phase 1 by tightening the `tileset.json` loading path for the formal `Vectorra3DTiles*` API.

Completed:

- Added internal `TileResourceFetcher` so non-proxy resources can reuse the same request scheduler, interceptor chain, resilient HTTP executor, and `TileCacheStore` cache owner used by tile requests.
- Added `TileResourceType.TILES3D` so cache keys and request metadata can distinguish 3D Tiles resources from raster/vector/DEM requests.
- Added internal `Vectorra3DTilesTilesetLoader` for local path, `file://`, `http`, and `https` tileset loading.
- Moved 3D Tiles tileset loading out of `VectorraMapEngine` direct `HttpURLConnection` code and into the loader/fetcher boundary.
- Remote tileset requests now use `TileNetworkConfig` default/source headers, source headers, interceptors, scheduler de-duplication, and `TileCacheStore`.
- Local path and remote URI fixtures now verify that relative tile content URIs resolve against the normalized tileset file/URL base URI.
- HTTP error responses fail early and flow back to the unified `TILES3D` resource status failure path in engine.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed and rebuilt native debug JNI for `arm64-v8a` and `x86_64`.

Known remaining Phase 1 work:

- Bad tileset UI/device validation is still not run in sample; it needs a focused P1 device smoke once traversal/content rendering is further along.
- P1.T3 is next: runtime tile state and traversal with SSE/LOD, request priority, unload budget, and `ADD`/`REPLACE`.

### P1.T3 Runtime Tile State And Traversal

Continued Phase 1 by adding the platform-neutral 3D Tiles traversal core.

Completed:

- Added internal runtime tile models with deterministic tile ids, depth, parent id, renderable content detection, and load states.
- Added `Vectorra3DTilesTraversal` to select tiles from a parsed `VectorraTileset3D` using camera position/direction, viewport height, FOV, maximum distance, maximum screen-space error, and loaded-tile budget.
- Implemented SSE-based LOD selection.
- Implemented `ADD` refinement by keeping parent content while traversing children.
- Implemented `REPLACE` refinement by selecting children when refinement succeeds and falling back to the parent when all children are culled.
- Added frustum-style angle culling and maximum-distance culling from bounding volume center/radius.
- Added request queue output with tile id, content, and priority.
- Added unload output for stale loaded tiles and tiles dropped by the loaded-tile budget.
- Added fixture tests covering `ADD`, `REPLACE`, low-SSE parent selection, frustum/distance filtering, request suppression for already loaded tiles, stale unload, and budget overflow.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed and rebuilt native debug JNI for `arm64-v8a` and `x86_64`.

Known remaining Phase 1 work:

- P1.T3 traversal is implemented as a tested core but is not yet connected to live map camera updates or asynchronous content loading.
- P1.T4 is next: GLB/GLTF tile content lifecycle should consume traversal requests, reuse `TileResourceFetcher`/`TileCacheStore`, deduplicate loads, cancel stale requests, and unload renderer content through the P1.T0 renderer contract.

### Device Smoke Retry - P0/P1 Baseline

Retried the physical Android device smoke after the device was unlocked and reinserted.

Device:

- Model: `2312DRAABC`
- Android API: `35`
- ABI: `arm64-v8a`
- EGL/Vulkan hardware: `meow` / `mali`
- GPU: `Mali-G57 MC2`

Verification commands were run from `D:\workspace\code\vectorra`:

```powershell
& 'C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
& 'C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
& 'C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell am start -W -n com.vectorra.sample/.MainActivity
& 'C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe' exec-out screencap -p
& 'C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell cmd gpu vkjson
```

Results:

- `adb devices -l` reported the device as `device`, so the prior authorization/connection blocker is cleared.
- `vectorra-sample-arm64-v8a-debug.apk` installed successfully.
- `com.vectorra.sample/.MainActivity` became the top resumed activity with process id `29034`.
- Logcat showed continuous `rocky_jni` render updates and SurfaceView buffer queue frames around 34-37 fps.
- No `FATAL EXCEPTION`, `AndroidRuntime`, or ANR entries were found in the filtered launch log.
- Screenshot `vectorra-maps/build/device-smoke-2026-06-03.png` was non-empty and showed the globe with raster imagery plus `raster sample-base-imagery loaded`.

Known remaining Phase 1 work:

- This retry validates device connectivity, native startup, visible raster rendering, and screenshot capture, but it is not a full P1 3D Tiles runtime smoke.
- Continue with P1.T4 GLB/GLTF content lifecycle implementation.

### P1.T4 GLB/GLTF Content Lifecycle

Continued Phase 1 by adding the platform-neutral 3D Tiles GLB/GLTF content lifecycle core.

Completed:

- Added internal `Vectorra3DTilesContentLifecycle` to consume traversal requests and unload ids.
- Added lifecycle state tracking for `LOADING`, `LOADED`, and `FAILED`, with traversal-facing tile load states so repeated traversals do not duplicate in-flight, loaded, or failed content requests.
- Added remote GLB/GLTF load tasks that produce `TileRequest` values using `TileResourceType.TILES3D`, source/layer ids, source headers, priority, and content metadata, so callers can execute them through the existing `TileResourceFetcher`/scheduler/cache path.
- Added completion handling that rejects stale generations after unload/cancel, handles HTTP failures explicitly, writes successful remote GLB/GLTF payloads to renderer content files, and submits renderer inputs through the P1.T0 renderer contract.
- Added local `file://` GLB/GLTF handling that bypasses remote fetch and directly submits renderer URI content.
- Added unload handling that removes loaded native renderer content ids and clears traversal-facing state.
- Added explicit P1.T4 failure for b3dm content so b3dm is not silently rendered through the GLB/GLTF path before P1.T6.
- Added fixture tests covering remote request metadata/header reuse, in-flight deduplication, loaded request suppression, renderer cache URI creation, local GLTF rendering, unload cleanup, stale completion cancellation, and b3dm deferral.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed and rebuilt native debug JNI for `arm64-v8a` and `x86_64`.

Known remaining Phase 1 work:

- P1.T4 is implemented as a tested lifecycle core but is not yet connected to live map camera traversal scheduling in `VectorraMapEngine`.
- P1.T5 is next for tileset/tile transform composition, bounds, `RTC_CENTER`, and renderer visibility semantics.

### P1.T5 Transform And Bounds Core

Continued Phase 1 by adding the platform-neutral 3D Tiles transform and bounds core used by traversal and renderer content input.

Completed:

- Added internal `Vectorra3DTilesSpatial` utilities for column-major 4x4 matrix multiplication, translation matrices, transform composition, point transforms, and optional `RTC_CENTER` composition.
- Added bounding sphere derivation for 3D Tiles `sphere`, `box`, and `region` bounding volumes, including WGS84-radians region corners converted to ECEF.
- Updated traversal runtime tiles to carry composed world transforms from parent tile transform and child tile transform.
- Updated traversal culling, screen-space error distance, and request priority to use transformed bounding spheres instead of untransformed local volumes.
- Updated traversal requests to carry the composed renderer transform for each selected tile content.
- Updated GLB/GLTF content lifecycle to forward traversal transforms into `Vectorra3DTilesRendererContentInput` instead of submitting identity transforms.
- Added finite-value validation for parsed tile transform matrices so invalid transforms fail early.
- Added fixture tests for matrix composition, `RTC_CENTER` composition, sphere/box/region bounds, transformed visibility culling, transform propagation into traversal requests, renderer input transform propagation, and non-finite transform rejection.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed and rebuilt native debug JNI for `arm64-v8a` and `x86_64`.

Known remaining Phase 1 work:

- P1.T5 transform/bounds is implemented as a tested core but is not yet connected to live map camera traversal scheduling in `VectorraMapEngine`.
- `RTC_CENTER` is supported by the transform composer; b3dm feature table extraction and automatic `RTC_CENTER` application remain part of P1.T6.
- P1.T6 is next: parse b3dm header/feature table/batch table, extract inner GLB to cache URI, apply `RTC_CENTER`, and render through the existing renderer contract.

### P1.T6 b3dm Inner GLB Support

Continued Phase 1 by adding b3dm parsing and renderer content preparation through the existing 3D Tiles renderer contract.

Completed:

- Added internal `Vectorra3DTilesB3dmParser` for b3dm v1 headers.
- Validated b3dm magic, version, declared byte length, table section lengths, and inner GLB magic.
- Parsed feature table JSON for `BATCH_LENGTH` and `RTC_CENTER`.
- Preserved batch table section offsets so the inner GLB start is computed after feature table JSON/binary and batch table JSON/binary.
- Added local and remote b3dm content handling in `Vectorra3DTilesContentLifecycle`.
- Extracted b3dm inner GLB bytes into renderer content cache files ending in `.glb`.
- Submitted b3dm renderer inputs with `VectorraTileset3DContentKind.B3DM` and `B3DM_INNER_GLB_CACHE_URI`.
- Composed b3dm `RTC_CENTER` onto the traversal/tile transform before submitting renderer content.
- Kept failed b3dm parse/preparation in lifecycle `FAILED` state instead of retrying invalid payloads silently.
- Added tests for b3dm header parsing, padding, GLB offset, `BATCH_LENGTH`, `RTC_CENTER`, invalid magic, byte length mismatch, missing GLB magic, remote b3dm rendering, local b3dm rendering, inner GLB cache bytes, payload source, and RTC transform propagation.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed and rebuilt native debug JNI for `arm64-v8a` and `x86_64`.

Known remaining Phase 1 work:

- P1.T6 is implemented as a tested parser/content-preparation core but is not yet connected to live map camera traversal scheduling in `VectorraMapEngine`.
- P1.T7 is next: sample/device smoke for formal `Vectorra3DTiles*` loading with lifecycle validation once live scheduling is connected.

### P1.T7 Prework - Live 3D Tiles Scheduling

Continued Phase 1 by connecting the tested 3D Tiles traversal and content lifecycle cores to the live `VectorraMapEngine` runtime.

Completed:

- Added engine-owned `Vectorra3DTilesTraversal` and `Vectorra3DTilesContentLifecycle` instances for formal `Vectorra3DTiles*` layers.
- Created a native renderer adapter that submits lifecycle renderer inputs to `VectorraNative.add3DTilesRendererContent` and unloads via `remove3DTilesRendererContent`.
- Added per-layer content cache directories for renderer-ready GLB/GLTF and extracted b3dm inner GLB content.
- After tileset load, the engine now schedules a traversal pass instead of stopping at tileset parsing.
- Camera updates now coalesce traversal work through a frame callback, so pan/zoom changes can request and unload 3D Tiles content.
- Traversal output now feeds the content lifecycle, which deduplicates in-flight/loaded/failed content and emits load tasks.
- Remote content load tasks now execute through `TileResourceFetcher`, preserving the P1.T2 scheduler/interceptor/cache path.
- Remote content completion re-enters the lifecycle with layer generation checks so stale results after remove/re-add do not render.
- Lifecycle renderer calls are posted to the main thread before entering JNI.
- Removing a 3D Tiles layer now cancels lifecycle state and unloads renderer content through the lifecycle owner.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed and rebuilt native debug JNI for `arm64-v8a` and `x86_64`.

Device smoke note:

- Installed and launched `vectorra-sample-arm64-v8a-debug.apk` on device `2312DRAABC`.
- The app stayed in foreground and logcat showed continuous `rocky_jni` frame updates without `FATAL EXCEPTION`, `AndroidRuntime`, or ANR in the filtered output.
- The attempted coordinate tap did not provide valid P1.T7 3D Tiles evidence; filtered logs showed the model/C130 path was triggered instead of a formal `Vectorra3DTiles*` load.

Known remaining Phase 1 work:

- Run a focused P1.T7 device smoke with reliable UI automation or a dedicated sample trigger for the `3D Tiles` button.
- Add/remove/re-add, rotate or pause/resume, screenshot, and bad tileset validation still need to be executed against the live scheduling path.

### P1.T7 Reliable 3D Tiles Smoke Trigger

Continued Phase 1 by adding a deterministic sample-app trigger for live 3D Tiles smoke validation.

Completed:

- Added `vectorra.sample.action` intent handling in `vectorra-sample`.
- Added `3dtiles`, `bad-3dtiles`, and `remove-3dtiles` smoke actions so device runs do not depend on coordinate taps.
- Moved the `3dtiles` smoke camera to the Cesium discrete LOD sample root transform location.
- Kept the manual `3D Tiles` button path intact while sharing the same layer/source setup.
- Added a narrow engine traversal log when formal 3D Tiles traversal emits requests, unloads, or failures.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Device smoke was run on `2312DRAABC` with:

```powershell
$adb='C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$apk='D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk'
& $adb install -r -d $apk
& $adb shell am force-stop com.vectorra.sample
& $adb logcat -c
& $adb shell am start -W -n com.vectorra.sample/.MainActivity --es vectorra.sample.action 3dtiles
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Device install succeeded after the user reinserted/unlocked the device.
- Screenshot `D:\workspace\code\vectorra\vectorra-maps\build\device-3dtiles-intent-smoke-2026-06-03-retry.png` showed `tiles3d sample-3d-tiles-layer loaded`.
- Filtered logcat showed `VectorraMapEngine` traversal for `sample-3d-tiles-layer` with `requests=1`, `unloads=0`, and `failures=0`.
- Filtered logcat showed JNI registration of `sample-3d-tiles-layer:root`.
- App cache contained `cache/vectorra-3dtiles-content-cache/sample-3d-tiles-layer/sample-3d-tiles-layer-root-dragon_low.b3dm.glb`.
- No `FATAL EXCEPTION`, `AndroidRuntime`, or ANR appeared in the filtered smoke log.

Known remaining Phase 1 work:

- Run add/remove/re-add smoke validation against renderer unload logs.
- Run bad tileset failure smoke validation.
- Run rotate or pause/resume lifecycle smoke validation.

### P1.T7 Add/Remove/Re-add 3D Tiles Smoke

Continued Phase 1 by adding and running a deterministic add/remove/re-add smoke sequence for formal `Vectorra3DTiles*` layers.

Completed:

- Added a `readd-3dtiles` `vectorra.sample.action` in `vectorra-sample`.
- The action loads the sample 3D Tiles layer, waits for content registration, removes the layer, then re-adds the same formal source/layer path.
- The existing `3dtiles`, `bad-3dtiles`, and manual button paths remain unchanged.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Device smoke was run on `2312DRAABC` with:

```powershell
$adb='C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$apk='D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk'
& $adb shell settings put global verifier_verify_adb_installs 0
& $adb install -r -d $apk
& $adb shell am force-stop com.vectorra.sample
& $adb logcat -c
& $adb shell am start -W -n com.vectorra.sample/.MainActivity --es vectorra.sample.action readd-3dtiles
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Device install succeeded.
- First traversal emitted `requests=1`, `unloads=0`, and `failures=0` for `sample-3d-tiles-layer`.
- JNI registered `sample-3d-tiles-layer:root`.
- The remove step logged JNI removal for `sample-3d-tiles-layer:root`.
- The re-add step emitted another traversal request and registered `sample-3d-tiles-layer:root` again.
- Screenshot `D:\workspace\code\vectorra\vectorra-maps\build\device-3dtiles-readd-smoke-2026-06-03-retry.png` showed `tiles3d sample-3d-tiles-layer loaded` after re-add.
- The exact crash search found no `FATAL EXCEPTION`, `AndroidRuntime`, `ANR`, `Application Not Responding`, or `failed to register`.

Known remaining Phase 1 work:

- Run bad tileset failure smoke validation.
- Run rotate or pause/resume lifecycle smoke validation.

### P1.T7 Bad 3D Tiles Smoke

Continued Phase 1 by running the bad tileset device smoke path through the formal `Vectorra3DTiles*` API.

Completed:

- Reused the `bad-3dtiles` `vectorra.sample.action` in `vectorra-sample`.
- Verified that a missing remote tileset does not attempt renderer content registration.
- Verified that the failed tileset load reaches the sample through the unified `tiles3d` resource status UI.

Device smoke was run on `2312DRAABC` with:

```powershell
$adb='C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell am force-stop com.vectorra.sample
& $adb logcat -c
& $adb shell am start -W -n com.vectorra.sample/.MainActivity --es vectorra.sample.action bad-3dtiles
```

Results:

- Screenshot `D:\workspace\code\vectorra\vectorra-maps\build\device-3dtiles-bad-smoke-2026-06-03.png` showed `tiles3d sample-3d-tiles-layer-bad failed: 3D Tiles tileset request failed with HTTP 504.`
- `uiautomator` dump contained the same visible failure text from the `TextView`.
- Filtered logcat showed no `registered 3D Tiles` renderer content for the bad layer.
- The exact crash search found no `FATAL EXCEPTION`, `AndroidRuntime`, `ANR`, `Application Not Responding`, or `failed to register`.

Known remaining Phase 1 work:

- Run rotate or pause/resume lifecycle smoke validation.

### P1.T7 Pause/Resume 3D Tiles Lifecycle Fix

Continued Phase 1 by fixing and validating formal 3D Tiles renderer content across Android surface pause/resume.

Problem found:

- The first pause/resume smoke brought the Activity back without 3D Tiles renderer registration evidence.
- Root cause: `Vectorra3DTilesContentLifecycle` kept loaded tile state after surface detach, while the native renderer surface/application had been recreated, so no new traversal request was emitted and loaded content was not submitted to the recreated native renderer.
- The sample status UI also allowed ordinary raster `loaded` status to mask the more relevant 3D Tiles smoke status after resume.

Completed:

- Stored the original `Vectorra3DTilesRendererContentInput` with each loaded lifecycle entry.
- Added `Vectorra3DTilesContentLifecycle.resubmitLoadedContent()` to re-submit loaded renderer content without creating another load state or network request.
- Called `resubmitLoaded3DTilesContent()` from `VectorraMapEngine.attachSurface()` after native surface setup and camera apply.
- Added a lifecycle unit test proving loaded remote content can be re-submitted after renderer recreation while suppressing duplicate traversal load tasks.
- Updated the sample status UI to preserve higher-priority `tiles3d` status instead of letting routine raster `loaded` events overwrite smoke evidence.
- Confirmed native registration is keyed by content id, so repeated attach callbacks replace the same `tiles3DRendererContent[id]` entry instead of accumulating duplicate native entries.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Device smoke was run on `2312DRAABC` with:

```powershell
$adb='C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$apk='D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk'
& $adb shell settings put global verifier_verify_adb_installs 0
& $adb install -r -d $apk
& $adb shell am force-stop com.vectorra.sample
& $adb logcat -c
& $adb shell am start -W -n com.vectorra.sample/.MainActivity --es vectorra.sample.action 3dtiles
& $adb shell input keyevent HOME
& $adb shell am start -W -n com.vectorra.sample/.MainActivity --activity-reorder-to-front
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Device install succeeded.
- Logcat showed `surfaceDestroyed` followed by `surfaceCreated`.
- Logcat showed `resubmitted 3D Tiles renderer content count=1` after resume.
- Logcat showed native registration of `sample-3d-tiles-layer:root` after resume.
- Screenshot `D:\workspace\code\vectorra\vectorra-maps\build\device-3dtiles-pause-resume-smoke-2026-06-03-final2.png` showed `tiles3d sample-3d-tiles-layer loaded`.
- `uiautomator` dump contained `tiles3d sample-3d-tiles-layer loaded`.
- The exact crash search found no `FATAL EXCEPTION`, `AndroidRuntime`, `ANR`, `Application Not Responding`, or `failed to register`.

Known remaining Phase 1 work:

- P1.T7 evidence now covers formal load, b3dm content registration, add/remove/re-add, bad tileset UI failure, and pause/resume renderer re-submit.
- P1.T8 is next: summarize Phase 1 API, fixture, and device validation status and identify any remaining P0/P1 blockers.

### P1.T8 Phase 1 3D Tiles Runtime Acceptance

Completed Phase 1 acceptance documentation for the formal 3D Tiles runtime beta.

Completed:

- Updated `docs/beta/3d-tiles.md` from the old parser-only beta description to the current runtime beta scope.
- Documented the formal 1.0 3D Tiles entry points: `Vectorra3DTilesSource`, `Vectorra3DTilesLayer`, `Vectorra3DTilesOptions`, `add3DTilesLayer`, and `remove3DTilesLayer`.
- Documented that `add3DTilesModelLayer(...)` remains deprecated and experimental and is not the 1.0 3D Tiles API.
- Summarized P1.T0 through P1.T8 acceptance evidence.
- Recorded supported runtime scope, internal renderer contract boundaries, unsupported Phase 1 content types, device smoke coverage, and remaining out-of-scope limitations.
- Recorded the physical device evidence for formal load, b3dm content extraction and registration, add/remove/re-add, bad tileset UI failure, and pause/resume renderer re-submit.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-maps-turf:testDebugUnitTest :vectorra-sample:assembleDebug assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-maps-turf:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- `assembleDebug` passed and produced debug AAR/APK outputs for the current multi-module build.

Acceptance:

- P1.T8 is accepted for the Phase 1 runtime beta.
- The formal 3D Tiles entry is `Vectorra3DTiles*`.
- b3dm rendering and `RTC_CENTER` are covered by unit fixtures and physical-device smoke.
- No known P0/P1 blocker remains for moving to Phase 2.

Known remaining Android 1.0 work:

- Phase 2 is next: MVT native render contract, vector tile API, runtime tile store, rendering, query, device smoke, and P2 acceptance.

### P2.T0 MVT Native Render Contract

Started Phase 2 by defining the internal MVT render contract boundary used by the upcoming Kotlin runtime tile store.

Completed:

- Added internal MVT render contract types for style, feature batches, tile input, and stable native render handles.
- Fixed the native tile handle shape as `layerId:z/x/y`.
- Added deterministic feature batch flattening for feature ids, source layers, geometry types, coordinate offsets, coordinates, ring offsets, and ring ends.
- Added JNI methods for `renderMvtTile`, `removeMvtTile`, and `removeMvtLayer`.
- Added native-side MVT tile registration storage keyed by native tile handle, with remove-by-tile and remove-by-layer cleanup.
- Added focused unit tests covering native flattening and invalid contract inputs.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake built both `arm64-v8a` and `x86_64`.
- Existing rocky warnings and the sample native library strip warning remained non-blocking; the build completed successfully.

Known remaining Phase 2 work:

- P2.T1: add public vector tile source/layer API.
- P2.T2: add the Kotlin MVT runtime tile store as the single owner of decoded tiles, native handles, and query state.
- P2.T5: replace the native registration stub with actual visible MVT rendering.

### Device Reinsert Smoke Retry

Retried physical-device validation after the device was unlocked and reinserted.

Device:

- Model: `2312DRAABC`
- ADB serial: `4tqoz9bmfu8t8pr8`
- State: `device`

Commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$adb='C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$apk='D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk'
& $adb devices -l
& $adb shell settings put global verifier_verify_adb_installs 0
& $adb install -r -d $apk
& $adb shell am force-stop com.vectorra.sample
& $adb logcat -c
& $adb shell am start -W -n com.vectorra.sample/.MainActivity --es vectorra.sample.action 3dtiles
```

Results:

- ADB detected the device in `device` state.
- `adb install -r -d` succeeded.
- `com.vectorra.sample/.MainActivity` became the top resumed Activity.
- Screenshot `D:\workspace\code\vectorra\vectorra-maps\build\device-reinsert-smoke-2026-06-03.png` showed the sample UI status `tiles3d sample-3d-tiles-layer loaded`, but no visible 3D Tiles content in the render surface.
- UI dump `D:\workspace\code\vectorra\vectorra-maps\build\device-reinsert-smoke-2026-06-03.xml` contained `tiles3d sample-3d-tiles-layer loaded`.
- Strict sample-process log filtering found no `FATAL EXCEPTION`, sample ANR, `Application Not Responding`, `failed to register`, `SIGABRT`, or `SIGSEGV`.

Observation:

- This retry exposed a correctness gap: the Kotlin 3D Tiles lifecycle could report `loaded` while native had only registered renderer content metadata and had not actually attached a model entity to the rocky scene.
- `am start -W` returned `Status: timeout`, and MIUI emitted an `APP_SCOUT_HANG` warning while native `setSurface` was realizing the renderer. The app recovered, initialized Vulkan, produced `rocky frame 1 ok`, and remained foreground.

### 3D Tiles Native Visibility Fix

Fixed the 3D Tiles runtime path after device smoke showed `loaded` status without visible 3D Tiles content.

Root cause:

- `add3DTilesRendererContent` only stored `Tiles3DRendererContentConfig` and logged `registered 3D Tiles renderer content`.
- No rocky `Model` entity was created for the renderer content, so successful Kotlin content loading did not imply anything was attached to the native scene.
- After adding the entity path, the first device retry exposed a second issue: Android `File.toURI().toString()` produced `file:/data/...`, while rocky only normalizes `file://`; the model loader treated the URI as unavailable.

Completed:

- Added native 3D Tiles entity ownership separate from smoke `modelLayers`.
- On content registration, native now queues and applies a rocky `Model` entity with a `Transform` from the renderer contract.
- On content removal and renderer teardown, native now removes 3D Tiles entities and clears their load diagnostics.
- Surface recreation now syncs registered 3D Tiles renderer content into actual native entities.
- Added native diagnostics for `applied native 3D Tiles content`, `3D Tiles model loaded`, and `3D Tiles model load error`.
- Changed 3D Tiles prepared local renderer content from Java `file:/...` URI strings to local absolute file paths, which rocky can read directly.
- Updated lifecycle tests to assert local absolute render paths for cached GLB/GLTF/B3DM inner GLB content.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake built both `arm64-v8a` and `x86_64`.
- Existing rocky warnings remained non-blocking.

Device smoke was rerun on `2312DRAABC` with:

```powershell
$adb='C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$apk='D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk'
& $adb install -r -d $apk
& $adb shell am force-stop com.vectorra.sample
& $adb logcat -c
& $adb shell am start -W -n com.vectorra.sample/.MainActivity --es vectorra.sample.action 3dtiles
```

Device results:

- Install succeeded.
- UI dump `D:\workspace\code\vectorra\vectorra-maps\build\device-3dtiles-visible-fix2-2026-06-03.xml` contained `tiles3d sample-3d-tiles-layer loaded`.
- Screenshot `D:\workspace\code\vectorra\vectorra-maps\build\device-3dtiles-visible-fix2-2026-06-03.png` showed non-empty rendered scene content instead of the earlier empty green surface.
- Logcat showed `registered 3D Tiles renderer content`.
- Logcat showed `applied native 3D Tiles content`.
- Logcat showed `3D Tiles model loaded id=sample-3d-tiles-layer:root ... radius=16.03`.
- Strict sample-process log filtering found no `FATAL EXCEPTION`, sample ANR, `Application Not Responding`, `failed to register`, `SIGABRT`, or `SIGSEGV`.

Known remaining issue:

- `am start -W` still returns `Status: timeout` on this MIUI device while native renderer startup blocks long enough to trigger the platform wait timeout. The app recovers and renders, but startup latency remains a separate hardening item.
