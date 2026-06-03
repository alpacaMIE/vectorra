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

### 3D Tiles REPLACE LOD Continuity Fix

Fixed the 3D Tiles disappearing behavior seen when zooming closer to a `REPLACE` refinement tileset.

Root cause:

- The traversal selected replacement child tiles as soon as screen-space error exceeded the threshold.
- Loaded parent tiles were removed from `selectedTiles` immediately and therefore added to `unloadTileIds`.
- If the child tile was still `LOADING` or otherwise not yet native-loaded, the renderer removed the visible parent before the replacement was ready, producing a visible disappearance while zooming in.

Completed:

- Passed tile load state into recursive traversal selection.
- For `REPLACE` refinement, keep a loaded renderable parent selected until at least one renderable replacement child selected for that branch is also `LOADED`.
- Once replacement children are loaded, traversal unloads the parent normally.
- Added a regression test proving a loaded parent is retained while a replacement child is still loading and unloaded only after the child reaches `LOADED`.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake build steps completed for `arm64-v8a` and `x86_64`.

### P2.T1 Vector Tile API

Continued Phase 2 by adding the public vector tile source/layer API surface without introducing Mapbox Style JSON compatibility or starting the runtime tile store early.

Completed:

- Added `VectorraVectorTileSource` with XYZ/TMS helpers, template URL, tile size, zoom range, scheme, and headers.
- Added simplified `VectorraVectorTileLayer` variants for `Line`, `Fill`, `Circle`, and `Symbol`.
- Added style options for line color/opacity/width, fill color/opacity, circle color/opacity/radius, and symbol text field/color/opacity/size.
- Added validation for source identity, URL, zoom ranges, unsupported WMTS vector sources, source-layer identity, opacity, widths, radii, and symbol text fields.
- Added `VectorraResourceKind.VECTOR`.
- Added `VectorraMap.addVectorTileLayer(source, layer)` and `removeVectorTileLayer(id)`.
- Registered vector tile source/layer pairs inside `VectorraMapEngine` as the P2.T2 runtime store entry point.
- Emitted unified resource statuses for vector layer add/remove through the existing resource status contract.
- Kept tile loading, decode ownership, native render handles, and query index ownership out of P2.T1; those remain P2.T2/P2.T3.
- Added unit tests for vector source/layer defaults and validation, plus resource status support for `VECTOR`.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake build steps completed for `arm64-v8a` and `x86_64`.

Known remaining Phase 2 work:

- P2.T2: add the Kotlin MVT runtime tile store as the single owner of decoded tiles, native render handles, and query state.
- P2.T3: load vector tiles through the proxy/fetcher/cache path.
- P2.T5/P2.T6: implement visible MVT rendering and query behavior.

### P2.T2 Kotlin MVT Runtime Tile Store

Continued Phase 2 by adding the internal Kotlin runtime tile store that owns decoded MVT tiles, native render handles, and query features together.

Completed:

- Added `VectorraMvtRuntimeTileStore` as the single owner for a vector layer's decoded tile state.
- Added `VectorraMvtRuntimeTile` entries containing `tileId`, decoded tile, native tile handle, and query features.
- Converted decoded MVT features for the configured `sourceLayer` into `VectorraMvtRenderTileInput` for the P2.T0 native renderer contract.
- Converted the same decoded tile into query features from the same store entry so unload removes render and query state together.
- Implemented tile replacement so an old native handle is removed when the same z/x/y tile is replaced.
- Implemented tile unload and layer clear so store state and native renderer state are removed from one owner.
- Wired `VectorraMapEngine` vector layer registration to create and clear the store instead of keeping only source/layer metadata.
- Kept network loading, tile selection, cache hits, and sample-visible MVT rendering out of P2.T2; those remain P2.T3/P2.T5.
- Added focused unit tests for put/replace/remove/clear ownership behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake build steps completed for `arm64-v8a` and `x86_64`.

Known remaining Phase 2 work:

- P2.T3: load vector tiles through the proxy/fetcher/cache path and write decoded results into `VectorraMvtRuntimeTileStore`.
- P2.T4: complete renderer geometry conversion fixtures around extent, Y-down coordinates, tile boundaries, and polygon rings.
- P2.T5/P2.T6: implement visible MVT rendering and query behavior over loaded store entries.

### P2.T3 Vector Tile Load Path

Continued Phase 2 by adding the internal MVT tile request/decode path and wiring it to the runtime tile store.

Completed:

- Added `VectorraMvtTileLoader` for per-tile vector requests.
- Built `TileRequest` values with vector source/layer identity, headers, tile id, `TileResourceType.VECTOR`, and MVT metadata.
- Supported XYZ and TMS URL templates; TMS flips the request URL Y while preserving the store/query tile id.
- Reused `TileResourceFetcher` through `asMvtTileLoader()`, so requests go through interceptors and `TileCacheStore`.
- Decoded successful responses with `VectorraMvtDecoder`.
- Returned explicit loaded/failed results with `NETWORK` errors for HTTP/request failures and `RESOURCE` errors for decode failures.
- Added `VectorraMapEngine.loadVectorTileIntoStore(...)` as the internal bridge that writes loaded decoded tiles into the `VectorraMvtRuntimeTileStore` and emits vector failure status on load failure.
- Kept camera-driven tile selection and automatic viewport scheduling out of this task; that remains for P2.T5/P2.T6 integration.
- Added unit tests for XYZ requests, TMS Y flipping, headers/resource type propagation, HTTP failures, decode failures, and zoom range validation.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake build steps completed for `arm64-v8a` and `x86_64`.

Known remaining Phase 2 work:

- P2.T4: complete geometry conversion fixtures around extent, Y-down coordinates, tile boundaries, and polygon rings.
- P2.T5: schedule visible vector tiles from the camera and render line/fill/circle output.
- P2.T6: query loaded store entries through `queryRenderedFeatures`.

### P2.T4 MVT Geometry Conversion Fixtures

Continued Phase 2 by solidifying MVT geometry conversion behavior with focused fixtures.

Completed:

- Added Web Mercator tile-corner fixtures proving Y-down MVT coordinates map correctly to longitude/latitude.
- Added extent-sensitive point conversion coverage using a non-default extent.
- Added polygon ring fixtures that preserve outer and inner rings.
- Added tile boundary assertions for `-180/180` longitude and Web Mercator max latitude.
- Added validation coverage for non-positive MVT extent.
- Confirmed the existing conversion implementation already satisfied these fixtures; no production conversion changes were required.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.mvt.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- Focused MVT unit tests passed.
- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake build steps completed for `arm64-v8a` and `x86_64`.

Known remaining Phase 2 work:

- P2.T5: schedule visible vector tiles from the camera and render line/fill/circle output.
- P2.T6: query loaded store entries through `queryRenderedFeatures`.

### 3D Tiles LOD Camera Range Consistency

Investigated the sample 3D Tiles disappearing when zooming in after reconnecting the Android device.

Findings:

- The sample 3D Tiles source is Cesium `TilesetWithDiscreteLOD`; its renderable content is a dragon model with `dragon_low.b3dm`, `dragon_medium.b3dm`, and `dragon_high.b3dm`, not a building tileset.
- The root bounding box is small, roughly 14.19m x 6.28m x 10.08m before transform.
- Kotlin traversal estimated camera height as `WGS84_RADIUS / 2^zoom` with a 10m floor, while native rendering clamps camera range to a 100m minimum using the native range formula.
- This mismatch could make traversal switch to finer LOD and unload parent content based on a closer camera distance than native actually uses.

Completed:

- Added `vectorraNativeCameraRangeMetersForZoom(...)` to mirror the native camera range formula, including latitude scale, 512 tile size, 45 degree vertical FOV, and 100m/30,000,000m clamp.
- Updated `CameraState.to3DTilesCamera()` to use the native-equivalent range for 3D Tiles traversal.
- Added unit coverage for formula parity and the high-zoom 100m floor.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Device smoke:

```powershell
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action 3dtiles
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Device `4tqoz9bmfu8t8pr8` installed and launched the sample.
- Logcat showed `dragon_low.b3dm` registered, parsed, applied, and loaded with native radius `16.03`.

Known remaining work:

- Manual pinch zoom verification is still needed on device to confirm the visual disappearance is gone through LOD switching.
- P2.T5 MVT visible rendering remains the next roadmap task.

### P2.T5 MVT Basic Visible Rendering

Continued Phase 2 by connecting decoded MVT tiles to native visible rendering and sample smoke.

Completed:

- Native `renderMvtTile` now queues tile application into the rocky update loop instead of only storing the render contract.
- Added native MVT entity ownership and removal through `mvtEntities`, including tile remove, layer remove, renderer stop, and surface/startup resync.
- Implemented minimum visible rendering for:
  - line: aggregated tile line features into one rocky `Line` entity per tile;
  - fill: polygon rings into rocky `Mesh`;
  - circle: point icon labels;
  - symbol: first-pass point labels using the feature id as text.
- Added camera-driven center tile scheduling for vector layers.
- Unloads loaded MVT tiles that are no longer the current camera target, so pan/zoom does not leave stale native handles or query store entries for the previous center tile.
- Added generation checks so stale vector tile load success/failure cannot write into or fail a newer layer instance.
- Added sample `MVT` button and smoke action using OpenFreeMap `transportation` vector tiles near San Francisco.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake build steps completed for `arm64-v8a` and `x86_64`.

Device smoke:

```powershell
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action mvt
```

Results:

- Device `4tqoz9bmfu8t8pr8` installed and launched the MVT sample action.
- Logcat showed `registered MVT render tile handle=sample-mvt-transportation:12/655/1583 source=sample-mvt style=LINE features=2904 coordinates=6748 visible=1`.
- After line aggregation, logcat showed `applied MVT render tile handle=sample-mvt-transportation:12/655/1583 entities=1`.

Known remaining Phase 2 work:

- P2.T5 still needs manual visual pan/zoom smoke to confirm tile replacement on device beyond the center-tile log path.
- P2.T6: wire `queryRenderedFeatures` to loaded MVT query store entries and cover source-layer/cross-tile behavior.

### P2.T6 MVT Query Store Integration

Continued Phase 2 by wiring loaded MVT store entries into `queryRenderedFeatures`.

Completed:

- Added `sourceId` to `VectorraAnnotationFeature` with a default value so shared hit-testing can preserve source identity for MVT candidates.
- Added `sourceLayerIds` to `VectorraQueryOptions` with a default empty set.
- Extended `VectorraAnnotationHitTester` to query external candidates using the same projection, distance, layer, source-layer, visibility, opacity, zoom, and z-index logic used for annotations.
- Updated `VectorraGeoJsonIndex` to respect `sourceLayerIds`, preventing non-MVT results from leaking into source-layer-filtered queries.
- Added `VectorraMvtRuntimeTileStore.queryHitFeatures()` so the store remains the owner of decoded, native, and queryable hit state for loaded MVT tiles.
- `VectorraMapEngine.queryRenderedFeatures()` now merges annotation, GeoJSON, and current loaded MVT store query results.
- Removed/cleared MVT tiles no longer contribute query candidates because the candidates are derived only from `VectorraMvtRuntimeTileStore.loadedTiles`.
- Added tests for MVT hit-feature conversion, source id/source-layer propagation, remove/clear query cleanup, external candidate hit-testing, layer filtering, and source-layer filtering.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake build steps completed for `arm64-v8a` and `x86_64`.

Device smoke:

```powershell
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action mvt
```

Results:

- Device `4tqoz9bmfu8t8pr8` installed and launched the MVT sample action.
- Logcat showed `registered MVT render tile handle=sample-mvt-transportation:12/655/1583 source=sample-mvt style=LINE features=2904 coordinates=6748 visible=1`.
- Logcat showed `applied MVT render tile handle=sample-mvt-transportation:12/655/1583 entities=1`.

Known remaining Phase 2 work:

- Add a device/UI smoke that performs an actual click/query assertion against the visible MVT layer.
- Broaden P2.T6 fixtures for cross-tile query ordering and cache-hit tile loads.
- P2.T7 device smoke remains open for pan/zoom, query, visibility, and remove/re-add.

### P2.T7 MVT Click Query Device Smoke

Continued Phase 2 device smoke coverage by adding a visible sample click-query path for MVT.

Completed:

- Added a sample map click listener that calls the SDK click/query path and displays the hit count plus first feature layer/source/source-layer/name in the status label.
- Added `VectorraSample` log output for the same click result so adb smoke can capture query evidence.
- Kept the listener generic for all sample features, while the current MVT smoke proves the loaded MVT store is queried through `queryRenderedFeatures`.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- Native CMake build steps completed for `arm64-v8a` and `x86_64`.

Device smoke:

```powershell
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action mvt
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell input tap 540 1120
```

Results:

- Device `4tqoz9bmfu8t8pr8` installed and launched the MVT sample action.
- Logcat showed `registered MVT render tile handle=sample-mvt-transportation:12/655/1583 source=sample-mvt style=LINE features=2904 coordinates=6748 visible=1`.
- Logcat showed `applied MVT render tile handle=sample-mvt-transportation:12/655/1583 entities=1`.
- Logcat showed `VectorraSample: Click: 11 feature(s) layer=sample-mvt-transportation source=sample-mvt source-layer=transportation`.

Known remaining Phase 2 work:

- P2.T7 still needs pan/zoom stale-feature device smoke plus visibility and remove/re-add coverage.
- Broaden P2.T6/P2.T7 fixtures for cross-tile query ordering and cache-hit tile loads.

### 3D Tiles Zoom-In Disappearance Fix

Fixed the current cause of 3D Tiles disappearing when the camera moves closer.

Completed:

- Updated REPLACE refinement traversal so a loaded parent tile remains selected while renderable replacement children are selected.
- This avoids unloading the visible parent immediately when Kotlin has marked child content loaded but the native `rocky::Model` may still be asynchronously preparing geometry.
- Updated traversal coverage so both loading and loaded replacement children keep the loaded parent in the selected set.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.tiles3d.Vectorra3DTilesTraversalTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
```

Results:

- `Vectorra3DTilesTraversalTest` passed.
- `:vectorra-sample:assembleDebug` passed.
- `:vectorra-maps:testDebugUnitTest` passed.

Device check:

```powershell
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action 3dtiles
```

Results:

- Device `4tqoz9bmfu8t8pr8` was visible and installed the arm64 debug sample.
- Logcat showed the sample 3D Tiles root request, native content application, and model load: `3D Tiles model loaded id=sample-3d-tiles-layer:root ... radius=16.03`.
- The existing sample action did not reliably apply the intended zoom=16 camera during the captured window; a dedicated zoom-in 3D Tiles device smoke action is still needed for repeatable manual verification.

Known remaining work:

- Add a deterministic 3D Tiles zoom-in smoke action that waits for native readiness, applies close camera, and captures traversal/content logs.
- Add native renderer readiness feedback if we want strict REPLACE unloading without parent/child overlap.

### 3D Tiles Geodetic ECEF and Zoom Smoke

Continued the 3D Tiles zoom-in investigation and fixed the coordinate cause behind unreliable close-camera traversal.

Completed:

- Replaced the Kotlin 3D Tiles camera/region WGS84-to-ECEF conversion with a WGS84 ellipsoid geodetic formula instead of the previous spherical radius approximation.
- Reused `Vectorra3DTilesSpatial.wgs84DegreesToEcef()` from `VectorraMapEngine` so traversal camera position and 3D Tiles bounds use the same spatial model.
- Updated traversal camera construction to use the current surface viewport height instead of a fixed `1080`, keeping SSE consistent with the actual device viewport.
- Corrected the sample Cesium `TilesetWithDiscreteLOD` center latitude from geocentric latitude to geodetic latitude.
- Added a `zoom-3dtiles` sample action and a visible `3D Zoom` button that loads root content, waits, then zooms to close levels that trigger replacement LOD requests without putting the elevated dragon behind the camera.
- Added a regression test proving the sample geodetic lon/lat/height converts to the tileset transform translation.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.tiles3d.*" :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
```

Results:

- 3D Tiles unit tests passed.
- `:vectorra-sample:assembleDebug` passed.
- Full `:vectorra-maps:testDebugUnitTest` passed.

Device smoke:

```powershell
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action zoom-3dtiles
```

Results:

- Device `4tqoz9bmfu8t8pr8` installed and launched the zoom smoke action.
- Logcat showed root traversal/load: `3D Tiles traversal layer=sample-3d-tiles-layer requests=1 unloads=0`, `3D Tiles model loaded id=sample-3d-tiles-layer:root ... radius=16.03`.
- Logcat showed close-camera LOD traversal after `VectorraSample: 3D Tiles zoom smoke: camera zoom=18.0`.
- Logcat showed high LOD content registered/applied/loaded: `sample-3d-tiles-layer:root/0/0 ... dragon_high.b3dm.glb`, `3D Tiles model loaded id=sample-3d-tiles-layer:root/0/0 ... radius=15.96`.

Known remaining work:

- The Cesium sample content is a dragon tileset elevated about 503.75m above the ellipsoid; zooming past the safe close range can still place it behind a ground-targeted map camera. A future camera height/target-height API is needed for arbitrary elevated 3D Tiles close inspection.
- Continue P2.T7 MVT device smoke for pan/zoom stale-feature coverage plus visibility and remove/re-add.

### P2.T7 MVT Pan, Visibility, and Re-add Device Smoke

Continued Phase 2 device smoke coverage for MVT runtime tile unload/reload and query ownership.

Completed:

- Added sample buttons and adb smoke actions for MVT pan, remove/re-add, and hidden-layer checks:
  - `pan-mvt`
  - `readd-mvt`
  - `remove-mvt`
  - `hidden-mvt`
- Added center-screen MVT query logging with `layerIds=sample-mvt-transportation` and `sourceLayerIds=transportation` so device smoke can verify the currently loaded Kotlin MVT store is the only query source.
- The pan smoke moves from tile `12/655/1583` to `12/656/1583`, exercising stale tile native removal and replacement tile render/query.
- The hidden smoke loads the same MVT layer with `visible=false`, proving no native tile is registered and no center query features are returned.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
```

Results:

- `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.

Device smoke:

```powershell
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action pan-mvt
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action readd-mvt
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action hidden-mvt
```

Results:

- Device `4tqoz9bmfu8t8pr8` installed and launched the arm64 debug sample.
- `pan-mvt` showed initial native tile `sample-mvt-transportation:12/655/1583` registered/applied, then `removed MVT render tile handle=sample-mvt-transportation:12/655/1583`, then replacement tile `sample-mvt-transportation:12/656/1583` registered/applied.
- `pan-mvt` center query logged `Click: 9 feature(s) layer=sample-mvt-transportation source=sample-mvt source-layer=transportation`.
- `readd-mvt` showed `removed MVT render layer id=sample-mvt-transportation tiles=1`, then re-registered/re-applied tile `12/655/1583`.
- `readd-mvt` center query logged `Click: 424 feature(s) layer=sample-mvt-transportation source=sample-mvt source-layer=transportation`.
- `hidden-mvt` logged `MVT hidden center query: Click: no features`; no native MVT register/apply lines were emitted for the hidden layer during the smoke window.

Known remaining Phase 2 work:

- P2.T6/P2.T7 still need broader fixture coverage for cross-tile query ordering and cache-hit query consistency.
- MVT MBTiles remains Phase 3 work.

### P2.T6/P2.T7 MVT Cross-tile and Cache-hit Fixtures

Continued Phase 2 fixture coverage for MVT query ownership and cache-hit load semantics.

Completed:

- Added a `VectorraMvtRuntimeTileStoreTest` fixture with two concurrently loaded MVT tiles.
- Verified `queryFeatures()` and `queryHitFeatures()` include only features from the currently loaded tile set and that removing one tile removes its query state while preserving the other loaded tile.
- Added a `VectorraMvtTileLoaderTest` fixture proving a `TileCacheStatus.MEMORY` cache-hit response decodes into the same `Loaded` result shape as a network miss.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.mvt.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
```

Results:

- MVT unit tests passed.
- Full `:vectorra-maps:testDebugUnitTest` passed.

Known remaining Phase 2 work:

- Query result ordering across multiple visible tiles is still mostly covered by hit-test sorting rather than an end-to-end engine fixture.
- Phase 3 will need MVT MBTiles/offline cache integration on top of the same loaded-store semantics.

### P3.T7 MVT MBTiles Source Skeleton

Started Phase 3 MVT MBTiles work by adding the source and engine bridge needed to reuse the Phase 2 MVT runtime path for offline vector tiles.

Completed:

- Added `VectorraMbTilesVectorSource` for MBTiles packages with `pbf`/`mvt` vector tile formats.
- Added `VectorraMbTilesVectorMetadataParser` so raster MBTiles still reject vector formats while vector MBTiles accept `pbf`, `mvt`, and `application/x-protobuf`.
- Added `VectorraMap.addMbTilesVectorLayer(source, layer)`.
- Implemented `VectorraMapEngine.addMbTilesVectorLayer` by registering the MBTiles reader as a local tile provider through `TileProxyServer` with `TileResourceType.VECTOR`, then feeding the proxied XYZ template into the existing `addVectorTileLayer` path.
- Kept decoded tile/render/query ownership in the existing `VectorraMvtRuntimeTileStore`; no second MVT state owner was introduced.
- Generalized engine MBTiles source tracking to close both raster and vector MBTiles sources.
- Added unit coverage for vector MBTiles metadata parsing and missing-file early failure.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.mvt.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- Offline and MVT unit tests passed.
- Full `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining Phase 3 work:

- Add an actual MVT MBTiles fixture or generated SQLite fixture that exercises `VectorraMbTilesVectorSource.open()` and tile reads on Android/JVM-compatible test infrastructure.
- Add sample UI/device smoke for loading a real MVT MBTiles file.
- Offline manager, prefetch, cache status, cleanup, cancel, partial failure, and published offline API remain open Phase 3 work.

### P3.T7 MVT MBTiles Tile Read Unit Coverage

Continued MVT MBTiles source work by adding JVM-testable tile-read coverage for the new vector source.

Completed:

- Introduced internal `VectorraMbTilesTileReader` so raster/vector MBTiles sources depend on a reader contract while the existing Android `SQLiteDatabase` reader remains the production implementation.
- Added vector MBTiles source tests for successful tile reads, missing/invalid tile 404 responses, `TileCacheStatus.DISK`, content type, row scheme propagation, and source close behavior.
- Kept the production SQLite reader and source open paths unchanged apart from implementing the new reader contract.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-sample:assembleDebug
```

Results:

- Offline unit tests passed.
- Full `:vectorra-maps:testDebugUnitTest` passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining Phase 3 work:

- The current tile-read fixture uses a fake reader because JVM unit tests do not include Robolectric or sqlite-jdbc. A real SQLite MBTiles fixture still needs androidTest coverage or a dedicated test dependency.
- Sample UI/device smoke for real MVT MBTiles remains open.

### P1 3D Tiles Elevated Camera Target Fix

Fixed the close-zoom disappearance observed with the sample Cesium dragon tileset.

Completed:

- Added ECEF-to-WGS84 height conversion for 3D Tiles spatial calculations.
- Derived an internal 3D Tiles camera target height from the transformed root bounding volume.
- Used that target height for both Kotlin traversal camera construction and the native rocky viewpoint target.
- Re-applied the native camera after a 3D Tiles layer finishes loading so an already-positioned camera updates from ground height to the tileset height without requiring a user gesture.
- Extended native camera logging to include the applied target height.
- Added unit coverage for elevated ECEF round-trip height and for the Cesium dragon-style box + root transform target height.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.tiles3d.*" --tests "com.vectorra.maps.VectorraMapEngineCameraRangeTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- 3D Tiles spatial/traversal and camera range unit tests passed.
- `:vectorra-sample:assembleDebug` passed, including arm64-v8a and x86_64 native builds.

Device smoke:

```powershell
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\build\outputs\apk\debug\vectorra-sample-arm64-v8a-debug.apk
C:\Users\myg\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.vectorra.sample/.MainActivity --es vectorra.sample.action zoom-3dtiles
```

Results:

- Device `4tqoz9bmfu8t8pr8` installed and launched the zoom smoke action.
- Logcat showed `cameraTargetHeightMeters=503.75000000279397` for `sample-3d-tiles-layer`.
- Native camera application used the elevated target at zoom 14, 18.0, and 18.5: `height=503.8`.
- Root and high LOD 3D Tiles content were applied and loaded, including `sample-3d-tiles-layer:root/0/0 ... dragon_high.b3dm.glb`.

Known remaining work:

- The current target height is layer-level and derived from the root bounding volume. Future multi-layer or off-center inspection needs explicit camera fit/target APIs instead of choosing the first visible 3D Tiles layer.
- A visual screenshot assertion for close-zoom 3D Tiles visibility would make the smoke less dependent on logcat.

### P3.T7 MVT MBTiles SQLite Instrumentation Fixture

Continued MVT MBTiles validation by adding an Android instrumentation fixture that exercises the production SQLite reader path.

Completed:

- Added AndroidX instrumentation runner dependencies for `:vectorra-maps`.
- Added `VectorraMbTilesVectorSourceInstrumentedTest`, which generates a real SQLite MBTiles file under the target context cache directory.
- The fixture creates `metadata` and `tiles` tables, writes vector metadata with `format=pbf` and `scheme=tms`, stores a generated MVT byte payload, then opens it through `VectorraMbTilesVectorSource.open()`.
- The test verifies metadata, TMS row conversion, `TileCacheStatus.DISK`, protobuf content type, HTTP 200 response, and exact tile bytes.
- Configured only the `debug` variant's `androidTest` packaging to exclude JNI `.so` files, because this SQLite source fixture does not load the native renderer. This shrinks the androidTest APK from about 203MB to about 14MB and avoids ddmlib upload timeouts for this test target.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:compileDebugAndroidTestKotlin
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:assembleDebugAndroidTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- `:vectorra-maps:compileDebugAndroidTestKotlin` passed.
- `:vectorra-maps:assembleDebugAndroidTest` passed.
- The generated `vectorra-maps-debug-androidTest.apk` is about 13.97MB and contains no `lib/*.so` entries.
- Offline JVM unit tests passed.
- `:vectorra-sample:assembleDebug` passed.

Device result:

- `connectedDebugAndroidTest` initially failed before executing tests because Gradle/ddmlib timed out installing the 203MB androidTest APK.
- After shrinking the androidTest APK, the connected run could not be completed because device `4tqoz9bmfu8t8pr8` remained in adb `offline` state after adb reconnect/server restart attempts.

Known remaining work:

- Re-run `connectedDebugAndroidTest` for `com.vectorra.maps.offline.VectorraMbTilesVectorSourceInstrumentedTest` once the device is back online.
- Add sample UI/device smoke for real MVT MBTiles rendering through `addMbTilesVectorLayer`.

### P3.T7/P3.T8 MVT MBTiles Sample Smoke Entry

Added a sample-app smoke path for rendering MVT from a real local MBTiles package.

Completed:

- Added a `MVT MBTiles` sample button.
- Added adb smoke action `mvt-mbtiles`.
- The sample now generates `sample-vector.mbtiles` in app cache with real SQLite `metadata` and `tiles` tables.
- The generated MBTiles stores a z12 TMS vector tile for the existing San Francisco MVT sample camera.
- The sample opens the file through `VectorraMbTilesVectorSource.open()` and renders it through `addMbTilesVectorLayer()` using the existing MVT runtime/render/query path.
- Added a delayed center query log using the same `queryRenderedFeatures` path as the online MVT smoke.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.mvt.*"
```

Results:

- `:vectorra-sample:assembleDebug` passed.
- Offline and MVT unit tests passed.

Device result:

- Device `4tqoz9bmfu8t8pr8` is still adb `offline`, so `mvt-mbtiles` device smoke could not be executed in this pass.

Known remaining work:

- Once adb returns to `device`, run `com.vectorra.sample/.MainActivity --es vectorra.sample.action mvt-mbtiles` and verify native MVT registration plus non-empty center query.
- Re-run the SQLite instrumentation test now that the androidTest APK size issue has been resolved.

### P3.T1 TileCacheStore Status and Cleanup

Continued cache productization by making the internal cache owner report and clear its current state.

Completed:

- Added `TileCacheStoreStatus` with memory/disk entry counts and byte totals.
- Added `TileCacheStore.status()` to aggregate memory and disk cache state from the single cache owner.
- Added `TileCacheStore.clear()` to clear memory and disk entries together.
- Added `TileDiskCache.entryCount()` so disk cache status no longer has to infer count from byte size.
- Added tests for status aggregation and clear behavior across both memory and disk caches.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.network.TileCacheAndSchedulerTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.network.*" --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.mvt.*"
```

Results:

- Tile cache/scheduler tests passed.
- Network, offline, and MVT unit tests passed.

Known remaining work:

- Surface this internal cache status/cleanup through the future `VectorraOfflineManager` public API.
- Unify product-level cache status ownership between `TileProxyServer` and `TileResourceFetcher` around the same store contract.

### P3.T2 Offline Manager Cache Status API

Started the public offline manager surface by exposing cache status and cleanup without leaking proxy/native details.

Completed:

- Added public beta `VectorraOfflineManager`.
- Added public beta `VectorraCacheStatus` and `VectorraCacheBucketStatus` for aggregate cache state.
- Added `VectorraMap.offline`.
- Implemented `VectorraMapEngine.offline.cacheStatus()` by aggregating proxy tile cache and resource cache buckets.
- Implemented `VectorraMapEngine.offline.clearCache()` to clear proxy tile cache and resource cache together.
- Added internal cache status/clear hooks on `TileProxyServer` and `TileResourceFetcher`.
- Added tests for public cache status totals and resource fetcher status/clear behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.network.TileCacheAndSchedulerTest" --tests "com.vectorra.maps.offline.VectorraOfflineManagerModelsTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.network.*" --tests "com.vectorra.maps.offline.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Target cache/offline manager tests passed.
- Network and offline unit tests passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Add prefetch task API and progress/cancel semantics on top of `VectorraOfflineManager`.
- Add sample UI for cache status and cleanup once device smoke is available again.

### P3.T3 Explicit Tile Prefetch API

Continued offline/cache productization by adding the first prefetch path on top of the existing resource cache owner.

Completed:

- Added public beta `VectorraOfflineManager.prefetchTiles(...)` for explicit `TileRequest` lists.
- Added public beta `VectorraPrefetchResult` and `VectorraPrefetchTileResult` with completed, failed, and byte-count totals.
- Added `TileResourceFetcher.prefetch(...)` so prefetch uses the same interceptor, scheduler, timeout, and `TileCacheStore` path as normal resource loading.
- Wired `VectorraMapEngine.offline.prefetchTiles(...)` to the resource fetcher and mapped tile responses to public prefetch results.
- Kept bounds/zoom region enumeration, async progress, retry policy, and cancel semantics out of this step so they can be added as dedicated P3.T3/P3.T5 work without a second cache owner.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.network.TileCacheAndSchedulerTest" --tests "com.vectorra.maps.offline.VectorraOfflineManagerModelsTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.network.*" --tests "com.vectorra.maps.offline.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Target cache/offline manager tests passed.
- Network and offline unit tests passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Add region-based tile enumeration for bounds and zoom ranges.
- Add progress, cancel, partial failure, and retry semantics for long-running prefetch tasks.
- Add sample UI/device smoke for cache status, cleanup, and prefetch once adb is stable.

### P3.T3 Region Tile Enumeration

Continued region prefetch by adding the request-enumeration layer needed before long-running task progress/cancel semantics.

Completed:

- Added public beta `VectorraOfflineBounds`, `VectorraOfflineRegion`, and `VectorraOfflineTileSource`.
- Added offline tile source factories for `VectorraRasterTileSource`, `VectorraRasterLayer`, `VectorraVectorTileSource`, and `VectorraTerrainSource`.
- Added `VectorraOfflineRegion.toTileRequests(...)` to enumerate Web Mercator XYZ tile coverage across bounds and zoom ranges.
- Added `VectorraOfflineManager.prefetchRegion(...)`, wired through `VectorraMapEngine` by enumerating requests and reusing the existing `prefetchTiles(...)` path.
- Supported XYZ, TMS URL Y flipping, and WMTS-style top-left row templates while keeping canonical tile ids in the request metadata.
- Fixed tile template replacement order for `${z}`/`${x}`/`${y}` templates in offline enumeration, MVT loading, and `TileProxyServer`.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.VectorraOfflineRegionTest" --tests "com.vectorra.maps.offline.VectorraOfflineManagerModelsTest" --tests "com.vectorra.maps.mvt.VectorraMvtTileLoaderTest" --tests "com.vectorra.maps.network.TileProxyServerTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*" --tests "com.vectorra.maps.mvt.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Target offline region, MVT loader, and tile proxy tests passed.
- Offline, network, and MVT unit tests passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Add asynchronous `VectorraPrefetchTask` with progress and cancel semantics.
- Define retry and partial failure policy for region prefetch.
- Add sample UI/device smoke for prefetch/cache cleanup once adb is stable.

### P3.T3 Prefetch Task Progress and Cancel

Continued region prefetch by adding a public task contract for asynchronous prefetch execution.

Completed:

- Added public beta `VectorraPrefetchTask`, `VectorraPrefetchTaskState`, `VectorraPrefetchProgress`, and `VectorraPrefetchProgressListener`.
- Added `VectorraOfflineManager.prefetchTilesAsync(...)` and `prefetchRegionAsync(...)`.
- Added internal `VectorraPrefetchTaskRunner` that reports initial, per-tile, and terminal progress snapshots.
- Implemented cancel semantics that stop before starting remaining tile requests while preserving completed tile results and cache writes.
- Wired async tile and region prefetch through `VectorraMapEngine` using the same `TileResourceFetcher` and result mapping as synchronous prefetch.
- Added task tests for completed progress, failure totals, cancellation, and empty task completion.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.VectorraPrefetchTaskRunnerTest" --tests "com.vectorra.maps.offline.VectorraOfflineManagerModelsTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Target prefetch task/offline manager tests passed.
- Offline and network unit tests passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Define retry and partial failure policy for prefetch tasks.
- Add sample UI/device smoke for prefetch progress, cancel, cache status, and cleanup once adb is stable.

### P3.T5 Prefetch Retry and Partial Failure Policy

Continued prefetch productization by making retry and partial-failure reporting explicit in the offline API.

Completed:

- Added public beta `VectorraPrefetchOptions` with `maxAttempts` and retryable HTTP status codes.
- Added public beta `VectorraPrefetchResultStatus` with `SUCCESS`, `PARTIAL_FAILURE`, and `FAILED`.
- Added `attemptCount` to each `VectorraPrefetchTileResult`.
- Updated synchronous and asynchronous tile/region prefetch entry points to accept `VectorraPrefetchOptions`.
- Implemented task-level retry policy in `VectorraPrefetchTaskRunner`, retrying only failed tile results whose status code is in `retryStatusCodes` and only up to `maxAttempts`.
- Kept completed tile cache writes and result aggregation intact when later tiles fail, so partial success is visible through `VectorraPrefetchResult.status`.
- Added tests for result status, option validation, retry success, retry exhaustion, and non-retryable failures.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.VectorraPrefetchTaskRunnerTest" --tests "com.vectorra.maps.offline.VectorraOfflineManagerModelsTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Target prefetch task/offline manager tests passed.
- Offline and network unit tests passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Add sample UI/device smoke for prefetch progress, cancel, cache status, and cleanup once adb is stable.
- Add product-facing documentation for offline region prefetch and cache lifecycle.

### P3.T8 Offline Prefetch Sample Smoke Entry

Continued Phase 3 smoke coverage by adding sample-app entry points for offline prefetch progress, cancel, cache status, and cleanup.

Completed:

- Added `Offline PF` and `Cancel PF` buttons to the sample app.
- Added adb smoke actions:
  - `offline-prefetch`
  - `cancel-prefetch`
- The `offline-prefetch` path clears cache, prefetches a San Francisco MVT region through `prefetchRegionAsync(...)`, logs progress/result/cache status, then clears cache again.
- The `cancel-prefetch` path uses the same region and task API, requesting cancel after the first completed tile progress event so completed results and cache writes can be observed.
- Sample log output now includes prefetch progress, final result status/counts/bytes, cache status before/after, and cache status after cleanup.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*"
```

Results:

- `:vectorra-sample:assembleDebug` passed.
- Offline and network unit tests passed.

Device result:

- `adb devices -l` still reported device `4tqoz9bmfu8t8pr8` as `offline`, so the new `offline-prefetch` and `cancel-prefetch` actions could not be executed on device in this pass.

Known remaining work:

- Run `offline-prefetch` and `cancel-prefetch` device smoke once adb returns to `device`.
- Add product-facing documentation for offline region prefetch and cache lifecycle.

### P3.T9 Offline Prefetch and Cache Docs

Continued Phase 3 hardening by documenting the public offline manager and cache lifecycle surface.

Completed:

- Added `docs/beta/offline-prefetch-cache.md`.
- Documented `VectorraMap.offline`, cache status, cache cleanup, explicit tile prefetch, region prefetch, async progress/cancel, retry options, partial failure status, source factories, and sample smoke actions.
- Updated `README.md` to link the offline prefetch/cache beta document.
- Updated `docs/beta/api-stability.md` so the current Beta boundary mentions offline region prefetch, progress/cancel, retry/partial-failure reporting, and cache status/cleanup.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "offline-prefetch-cache|Offline prefetch|VectorraPrefetchOptions|VectorraOfflineRegion|cancel-prefetch" vectorra-maps/README.md vectorra-maps/docs/beta
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Documentation search found the new page and README link.
- Offline and network unit tests passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Run `offline-prefetch` and `cancel-prefetch` device smoke once adb returns to `device`.
- Add release note coverage when the beta version is cut.

### P4.T4 0.8.0-beta.1 Development Release Notes

Continued release hardening by adding unpublished development release notes for the current offline/cache Beta surface.

Completed:

- Added `docs/beta/release-notes-0.8.0-beta.1.md`.
- Documented new offline/cache APIs, MBTiles/MVT additions, sample smoke actions, fixes, local verification, and known release gates.
- Linked the release notes from `README.md`.
- Linked the release notes from `docs/beta/release-versioning.md` while keeping published Maven coordinates at the last released Beta.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "0\.8\.0-beta\.1|development release notes|VectorraPrefetchTask|offline-prefetch|published-AAR" vectorra-maps/README.md vectorra-maps/docs/beta
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.network.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Documentation search found the release notes and links.
- Offline and network unit tests passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Run `offline-prefetch` and `cancel-prefetch` device smoke once adb returns to `device`.
- Run published-AAR verification before cutting an actual `0.8.0-beta.1` artifact.

### P4.T5 Published AAR Verification

Ran the Maven-local publication and sample published-AAR consumption gate for the current Gradle project version.

Completed:

- Published `:vectorra-maps` release publication to Maven local.
- Published `:vectorra-maps-turf` release publication to Maven local.
- Built `:vectorra-sample` with `-Pvectorra.sample.usePublishedAar=true`, so the sample consumed `com.vectorra:vectorra-maps:0.5.0-beta.1` from Maven local instead of the project dependency.
- Confirmed Maven local contains AAR, sources jar, Gradle module metadata, and POM artifacts for both maps and turf.
- Updated `docs/beta/release-notes-0.8.0-beta.1.md` to record that this gate passed for the current `0.5.0-beta.1` Gradle version, while `0.8.0-beta.1` remains unpublished until the project version is bumped and release gates are rerun.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:publishReleasePublicationToMavenLocal :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

Results:

- Maven local publication passed.
- Published-AAR sample build passed.
- Maven local artifacts observed:
  - `vectorra-maps-0.5.0-beta.1.aar`
  - `vectorra-maps-0.5.0-beta.1-sources.jar`
  - `vectorra-maps-0.5.0-beta.1.module`
  - `vectorra-maps-0.5.0-beta.1.pom`
  - `vectorra-maps-turf-0.5.0-beta.1.aar`
  - `vectorra-maps-turf-0.5.0-beta.1-sources.jar`
  - `vectorra-maps-turf-0.5.0-beta.1.module`
  - `vectorra-maps-turf-0.5.0-beta.1.pom`
- The sample published-AAR build emitted a non-fatal strip warning for `librocky.so` and `libvectorra_jni.so`, then packaged the libraries as-is.

Known remaining work:

- Run `offline-prefetch` and `cancel-prefetch` device smoke once adb returns to `device`.
- Bump Gradle/project docs to the intended next Beta version before cutting an actual `0.8.0-beta.1` artifact, then rerun the same published-AAR gate.

### P4.T3 3D Tiles Zoom Smoke Camera Range

Investigated the sample 3D Tiles disappearing when the camera zooms in.

Completed:

- Confirmed the sample `TilesetWithDiscreteLOD` is Cesium's dragon LOD tileset (`dragon_low.b3dm`, `dragon_medium.b3dm`, `dragon_high.b3dm`).
- Confirmed the root transform scales the local box by roughly 100x, producing a large dragon-shaped model with an approximate 850-900 meter bounding-sphere radius.
- Adjusted the sample zoom smoke close steps from `18.0`/`18.5` to `16.25`/`16.5`, so the smoke test approaches the model without putting the camera deep inside the model bounds.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.tiles3d.*" --tests "com.vectorra.maps.VectorraMapEngineCameraRangeTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- 3D Tiles and camera range unit tests passed.
- `:vectorra-sample:assembleDebug` passed.
- The sample build emitted the existing non-fatal strip warning for `librocky.so` and `libvectorra_jni.so`, then packaged the libraries as-is.

Known remaining work:

- Re-run the `zoom-3d-tiles` device smoke once adb returns to `device`; the recently reinserted device still needs to be confirmed from `adb devices -l`.

### P4.T2 Diagnostics and Troubleshooting Docs

Added the public diagnostics and troubleshooting guide for the current Beta hardening pass.

Completed:

- Added `docs/beta/diagnostics-troubleshooting.md`.
- Documented map-load error callbacks, shared resource status, current status queries, redacted tile request logging, offline prefetch progress/result diagnostics, common failure modes, and sample smoke actions.
- Documented the redaction boundary: `RedactingTileLogger` redacts URL query parameters only and does not log headers or bodies.
- Linked diagnostics docs from `README.md` and `docs/beta/android-aar-integration.md`.
- Updated `docs/beta/api-stability.md` to list shared resource status and redacted tile request logging in the Beta boundary.
- Updated `docs/resource-status-contract.md` to include `TILES3D`, `VECTOR`, and current error types.

Verification commands were run from `D:\workspace\code\vectorra` and `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "diagnostics-troubleshooting|Diagnostics and troubleshooting|RedactingTileLogger|VectorraResourceStatus|addMapLoadErrorListener|offline-prefetch|redactQueryKeys" .\vectorra-maps\README.md .\vectorra-maps\docs .\vectorra-maps\vectorra-maps\src\main\java
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.network.*" --tests "com.vectorra.maps.offline.*" --tests "com.vectorra.maps.VectorraNativeResourceStatusMapperTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Documentation search found the new diagnostics page, README link, AAR integration link, resource status docs, logger, and referenced APIs.
- Network, offline, and native resource status mapper unit tests passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Device smoke for user-visible diagnostics still depends on adb returning the physical device as `device` instead of `offline`.

### P4.T4 Published vs Development Version Documentation

Hardened release documentation so integrators can distinguish the published artifact from the current source-tree development target.

Completed:

- Updated `docs/beta/release-versioning.md` to separate the published `0.5.0-beta.1` version from the unpublished `0.8.0-beta.1` development target.
- Added explicit release conditions before `0.8.0-beta.1` can be treated as published: bump `VECTORRA_VERSION`, republish both SDK artifacts, rebuild the sample from the republished AARs, and update release docs.
- Expanded the release checklist with `:vectorra-sample:assembleDebug` and full `assembleDebug`, plus the Android 1.0 device smoke risk note.
- Updated `docs/beta/api-stability.md` to split the published Beta boundary from current source development APIs.
- Updated `docs/beta/offline-prefetch-cache.md` and `docs/beta/release-notes-0.8.0-beta.1.md` to state that 0.8 docs describe source-tree development while `VECTORRA_VERSION` remains `0.5.0-beta.1`.

Verification commands were run from `D:\workspace\code\vectorra` and `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "Published Version|Current Development Target|source-tree development|unpublished|VECTORRA_VERSION|0\.8\.0-beta\.1|0\.5\.0-beta\.1|published-AAR|assembleDebug" .\vectorra-maps\README.md .\vectorra-maps\docs\beta .\vectorra-maps\gradle.properties
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home assembleDebug
```

Results:

- Documentation search found the published/development version split and release gate wording.
- `:vectorra-sample:assembleDebug` passed.
- Full `assembleDebug` passed for `vectorra-maps`, `vectorra-maps-turf`, and `vectorra-sample`.

Known remaining work:

- A future actual `0.8.0-beta.1` release still requires bumping `VECTORRA_VERSION` and rerunning the published-AAR gate for that bumped version.
- Device smoke remains a release-risk item until adb reports a real device as `device`.

### P4.T1 Public API Surface Inventory

Added an explicit public API inventory for the Android Beta hardening pass.

Completed:

- Added `docs/beta/public-api-surface.md`.
- Grouped published baseline SDK entry points by core map/lifecycle, camera, raster/terrain, models, location, annotations/query, MBTiles raster, 3D Tiles parsing, MVT decoding, and SDK metadata.
- Separated unpublished development APIs for 3D Tiles runtime, vector tile runtime, offline prefetch/cache, MVT MBTiles, and redacted diagnostics.
- Documented that `VectorraMap.add3DTilesModelLayer(...)` is deprecated experimental smoke-only API and not the Android 1.0 3D Tiles integration path.
- Documented implementation-public areas that apps must not treat as stable contracts.
- Linked the inventory from `README.md` and `docs/beta/api-stability.md`.
- Adjusted `docs/beta/api-stability.md` so shared resource status APIs are listed in the current source development boundary while `VECTORRA_VERSION` remains `0.5.0-beta.1`.

Verification commands were run from `D:\workspace\code\vectorra` and `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "public-api-surface|Public API surface|Published Baseline SDK Entry Points|Unpublished Development APIs|Shared resource status APIs|RedactingTileLogger|VectorraResourceStatus|add3DTilesModelLayer" .\vectorra-maps\README.md .\vectorra-maps\docs\beta .\vectorra-maps\vectorra-maps\src\main\java
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Documentation search found the public API inventory, README/API stability links, source/development boundary labels, redacted logging, resource status, and deprecated 3D Tiles smoke entry.
- `:vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.*"` passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Before a 1.0 release candidate, every supported public API should either remain in the inventory or be moved behind an internal boundary.
- Device smoke remains blocked by adb reporting the physical device as `offline`.

### P4.T1 Deprecated 3D Tiles Smoke API Guard

Added a regression guard for the deprecated experimental 3D Tiles model smoke API.

Completed:

- Added `VectorraPublicApiSurfaceTest`.
- The test checks that `VectorraMap.add3DTilesModelLayer(...)` remains marked with both `@VectorraExperimentalApi("0.7.0-beta.1")` and `@Deprecated`.
- The test checks the `VectorraMapEngine` implementation carries the same annotations.
- The test checks `docs/beta/public-api-surface.md` does not list `add3DTilesModelLayer(...)` under the published baseline API section and keeps it in the deprecated experimental section.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.VectorraPublicApiSurfaceTest"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- The first test run failed because the source-window assertion was too narrow.
- After fixing the assertion window, `VectorraPublicApiSurfaceTest` passed.
- `:vectorra-sample:assembleDebug` passed.

Known remaining work:

- Keep this guard updated if the smoke API is removed before 1.0 instead of retained as deprecated experimental.

### P4.T3 Snapshot Sample Smoke

Expanded sample coverage for the Android 1.0 sample completeness pass.

Completed:

- Added a `Snapshot` sample button.
- Added adb smoke action `snapshot`.
- Implemented `runSnapshotSmoke()` using `VectorraMapView.snapshot(...)`.
- Snapshot smoke reports bitmap dimensions and a sampled nonblank-pixel check, then recycles the bitmap.
- Updated `docs/beta/release-notes-0.8.0-beta.1.md` and `docs/beta/diagnostics-troubleshooting.md` with the snapshot smoke action.

Verification commands were run from `D:\workspace\code\vectorra` and `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "Snapshot|SAMPLE_ACTION_SNAPSHOT|snapshot|hasVisiblePixel|vectorra.sample.action snapshot" .\vectorra-maps\vectorra-sample\src\main\java\com\vectorra\sample\MainActivity.kt .\vectorra-maps\docs\beta
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
```

Results:

- Search found the sample button, snapshot helper, adb action, and docs references.
- `:vectorra-sample:assembleDebug` passed.
- Attempted `:vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.*Snapshot*"`, but Gradle failed because no tests match that filter; this is a missing-test result, not a snapshot implementation failure.

Known remaining work:

- Run the `snapshot` adb smoke on a real device once adb returns to `device`.
- P4.T3 still needs explicit sample controls/actions for location and GeoJSON/drawing.

### P4.T3 GeoJSON And Draw Sample Smoke

Expanded sample coverage for GeoJSON/query and drawing.

Completed:

- Added a `GeoJSON` sample button and adb smoke action `geojson`.
- Added `Draw` and `Clear Draw` sample buttons with adb smoke actions `draw` and `clear-draw`.
- The GeoJSON smoke installs a query-only source/layer, moves the camera to the sample geometry, and logs a center query.
- The Draw smoke renders point, line, and polygon annotations, moves the camera to the sample geometry, and logs a center query.
- Refactored center-query logging so MVT, GeoJSON, and draw sample actions share the same query helper.
- Updated `docs/beta/release-notes-0.8.0-beta.1.md` and `docs/beta/diagnostics-troubleshooting.md` with the new smoke actions.

Verification commands were run from `D:\workspace\code\vectorra` and `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "GeoJSON|SAMPLE_ACTION_GEOJSON|SAMPLE_ACTION_DRAW|SAMPLE_ACTION_CLEAR_DRAW|geojson|clear-draw|Draw annotations|vectorra.sample.action draw|vectorra.sample.action geojson" .\vectorra-maps\vectorra-sample\src\main\java\com\vectorra\sample\MainActivity.kt .\vectorra-maps\docs\beta
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.annotation.*" --tests "com.vectorra.maps.query.*"
```

Results:

- Search found the sample buttons, smoke action constants, action handling, and docs references.
- `:vectorra-sample:assembleDebug` passed.
- Annotation and query unit tests passed.

Known remaining work:

- Run the `geojson`, `draw`, and `clear-draw` adb smokes on a real device once adb returns to `device`.
- P4.T3 still needs explicit sample controls/actions for location.

### 3D Tiles Close-Zoom Visibility Stabilization

Investigated the sample 3D Tiles disappearance when zooming in.

Completed:

- Confirmed the sample `TilesetWithDiscreteLOD` content is a transformed `dragon_low/medium/high.b3dm` model tileset, not a building tileset.
- Aligned Kotlin camera range math with the native camera FOV by changing the shared Kotlin FOV constant to `30.0`.
- Changed 3D Tiles traversal camera creation to use the same native FOV constant instead of an independent `60.0` value.
- Changed renderer content submission to split the final tile transform into an ECEF origin plus a local matrix with translation removed, avoiding large global ECEF translation inside model local matrices.
- Added unit coverage for camera range FOV alignment and renderer transform splitting, including RTC_CENTER-composed b3dm content.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps` and `D:\workspace\code\vectorra`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.VectorraMapEngineCameraRangeTest" --tests "com.vectorra.maps.tiles3d.*"
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
```

Results:

- Camera range and 3D Tiles unit tests passed.
- `:vectorra-sample:assembleDebug` passed, including native CMake build for `arm64-v8a` and `x86_64`.
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`, so real-device 3D Tiles zoom smoke remains unverified.

Known remaining work:

- Re-run the `3dtiles-zoom` adb smoke and inspect screenshots/logcat after the device returns to `device`.
- Replace the sample 3D Tiles asset with a representative building/model tileset for P1/P4 smoke coverage if the sample is meant to validate map SDK building rendering rather than generic b3dm rendering.

### P4.T3 Location Sample Smoke

Expanded sample coverage for the location component.

Completed:

- Added `Location`, `Follow`, and `Clear Loc` sample buttons.
- Added adb smoke actions `location`, `location-follow`, and `clear-location`.
- The location smoke uses a deterministic sample coordinate, enables the SDK location indicator, shows the accuracy ring, and moves the camera to the sample point.
- The follow smoke updates the same deterministic location and switches to heading follow mode.
- The clear smoke disables follow mode, clears the native indicator, and disables the location component.
- Updated `docs/beta/release-notes-0.8.0-beta.1.md` and `docs/beta/diagnostics-troubleshooting.md` with the new smoke actions.

Verification commands were run from `D:\workspace\code\vectorra` and `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "Location|Follow|Clear Loc|SAMPLE_ACTION_LOCATION|location-follow|clear-location|VectorraLocation|VectorraFollowMode" .\vectorra-maps\vectorra-sample\src\main\java\com\vectorra\sample\MainActivity.kt .\vectorra-maps\docs\beta
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.*"
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
```

Results:

- Search found the location UI controls, smoke action constants, action handling, API imports, and docs references.
- `:vectorra-sample:assembleDebug` passed.
- `:vectorra-maps:testDebugUnitTest --tests "com.vectorra.maps.*"` passed.
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`, so real-device location smoke remains unverified.

Known remaining work:

- Run the `location`, `location-follow`, and `clear-location` adb smokes on a real device once adb returns to `device`.

### P4.T5 Maven Local AAR Verification

Ran the Maven-local publication and published-AAR sample consumption gate.

Completed:

- Published `com.vectorra:vectorra-maps:0.5.0-beta.1` to Maven local.
- Published `com.vectorra:vectorra-maps-turf:0.5.0-beta.1` to Maven local.
- Built `:vectorra-sample:assembleDebug` against the published `vectorra-maps` AAR with `-Pvectorra.sample.usePublishedAar=true`.
- Inspected the published `vectorra-maps` AAR and confirmed `classes.jar`, `proguard.txt`, rocky assets, and both `arm64-v8a` and `x86_64` native libraries are present.
- Inspected both sources jars and confirmed SDK and Turf source entries are present.
- Updated `docs/beta/android-aar-integration.md` with the published artifact content checklist and the PowerShell quoted-property requirement.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps` and `D:\workspace\code\vectorra`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:publishReleasePublicationToMavenLocal :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat -g .\.gradle-agent-home "-Pvectorra.sample.usePublishedAar=true" :vectorra-sample:assembleDebug

$version=(Select-String -Path vectorra-maps\gradle.properties -Pattern '^VECTORRA_VERSION=').Line.Split('=')[1].Trim()
$group=(Select-String -Path vectorra-maps\gradle.properties -Pattern '^VECTORRA_GROUP=').Line.Split('=')[1].Trim()
$groupPath=$group.Replace('.', '\')
$base="$env:USERPROFILE\.m2\repository\$groupPath"
```

Results:

- Maven-local publication passed.
- Published-AAR sample build passed.
- The first sample build attempt with unquoted `-Pvectorra.sample.usePublishedAar=true` failed because Gradle interpreted `.sample.usePublishedAar=true` as a task name; the documented quoted form passed.
- Published `vectorra-maps` contains `jni/arm64-v8a/librocky.so`, `jni/arm64-v8a/libvectorra_jni.so`, `jni/x86_64/librocky.so`, `jni/x86_64/libvectorra_jni.so`, `assets/rocky/...`, `classes.jar`, `proguard.txt`, and a sources jar.
- Published `vectorra-maps-turf` contains an AAR, sources jar, POM, and module metadata.

Known remaining work:

- A published-AAR device startup smoke still depends on adb returning to `device`.

### P4.T6 ABI Packaging Matrix

Verified local ABI packaging artifacts and documented the remaining device matrix gate.

Completed:

- Added `docs/beta/abi-device-matrix.md`.
- Linked the ABI/device matrix from `README.md`.
- Updated `docs/beta/release-versioning.md` to use the new ABI/device matrix document for the Android 1.0 hardening gate.
- Ran the sample debug build and full debug assemble.
- Inspected split and universal sample APKs for expected native library entries.
- Inspected debug and release SDK AARs for expected native library entries.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps` and `D:\workspace\code\vectorra`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug assembleDebug

Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($apk in Get-ChildItem -Path vectorra-maps\vectorra-sample\build\outputs\apk\debug -Filter *.apk) {
    $zip=[System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
    $zip.Entries | ForEach-Object { $_.FullName } | Where-Object { $_ -match '^lib/.*/lib(vectorra_jni|rocky)\.so$' } | Sort-Object
    $zip.Dispose()
}
foreach ($aar in Get-ChildItem -Path vectorra-maps\vectorra-maps\build\outputs\aar -Filter *.aar) {
    $zip=[System.IO.Compression.ZipFile]::OpenRead($aar.FullName)
    $zip.Entries | ForEach-Object { $_.FullName } | Where-Object { $_ -match '^jni/.*/lib(vectorra_jni|rocky)\.so$' } | Sort-Object
    $zip.Dispose()
}

& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
```

Results:

- `:vectorra-sample:assembleDebug assembleDebug` passed.
- `vectorra-sample-arm64-v8a-debug.apk` contains `lib/arm64-v8a/librocky.so` and `lib/arm64-v8a/libvectorra_jni.so`.
- `vectorra-sample-x86_64-debug.apk` contains `lib/x86_64/librocky.so` and `lib/x86_64/libvectorra_jni.so`.
- `vectorra-sample-universal-debug.apk` contains both `arm64-v8a` and `x86_64` native libraries.
- `vectorra-maps-debug.aar` and `vectorra-maps-release.aar` both contain `jni/arm64-v8a/librocky.so`, `jni/arm64-v8a/libvectorra_jni.so`, `jni/x86_64/librocky.so`, and `jni/x86_64/libvectorra_jni.so`.
- The sample build emitted the existing non-fatal strip warning for `librocky.so` and `libvectorra_jni.so`, then packaged the libraries as-is.
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`.

Known remaining work:

- P4.T6 is not fully complete until at least one real Vulkan Android device runs the full smoke matrix and records model/API/ABI/GPU/Vulkan details.

### P4.T7 Android 1.0 Local Acceptance Gate

Ran the Android 1.0 local acceptance gate and recorded the remaining runtime release risk.

Completed:

- Added `docs/beta/android-1.0-acceptance.md`.
- Linked the acceptance record from `README.md`.
- Updated `docs/beta/release-versioning.md` to point at the acceptance record.
- Updated `docs/beta/release-notes-0.8.0-beta.1.md` with the local acceptance result and device-gate risk.
- Ran SDK unit tests, Turf unit tests, sample debug assemble, full debug assemble, Maven-local publication for both artifacts, and sample build from the published AAR.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps` and `D:\workspace\code\vectorra`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest :vectorra-maps-turf:testDebugUnitTest :vectorra-sample:assembleDebug assembleDebug :vectorra-maps:publishReleasePublicationToMavenLocal :vectorra-maps-turf:publishReleasePublicationToMavenLocal "-Pvectorra.sample.usePublishedAar=true" :vectorra-sample:assembleDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
```

Results:

- Local acceptance command passed.
- Outputs exist for `vectorra-maps-debug.aar`, `vectorra-maps-release.aar`, `vectorra-maps-turf-debug.aar`, `vectorra-maps-turf-release.aar`, `vectorra-sample-arm64-v8a-debug.apk`, `vectorra-sample-x86_64-debug.apk`, and `vectorra-sample-universal-debug.apk`.
- The sample build emitted the existing non-fatal strip warning for `librocky.so` and `libvectorra_jni.so`, then packaged the libraries as-is.
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`.

Known remaining work:

- Android 1.0 release readiness cannot be declared until the real-device Vulkan smoke matrix passes.

### Android Acceptance Gate Script

Made the local Android acceptance gate repeatable.

Completed:

- Fixed `tools/check-native-libs.ps1` to check current Vectorra native library names:
  - `librocky.so`
  - `libvectorra_jni.so`
- Updated `tools/check-native-libs.ps1` default artifacts to match current split APK outputs:
  - `vectorra-sample-arm64-v8a-debug.apk`
  - `vectorra-sample-x86_64-debug.apk`
  - `vectorra-sample-universal-debug.apk`
  - `vectorra-maps-debug.aar`
  - `vectorra-maps-release.aar`
- Added `tools/check-android-acceptance.ps1`, which runs the local unit/build/publication/published-AAR gate and then calls `check-native-libs.ps1`.
- Updated `docs/beta/android-1.0-acceptance.md`, `docs/beta/abi-device-matrix.md`, and `docs/beta/release-versioning.md` to reference the scripts.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
.\tools\check-native-libs.ps1
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- Native library check passed.
- Android local acceptance gate passed.
- Real-device Vulkan smoke remains outside this script and still requires adb to return a usable `device` state.

Known remaining work:

- Run the ABI/device runtime smoke matrix after adb returns to `device`.

### Device Runtime Smoke Script

Made the real-device runtime smoke gate repeatable for when adb returns to `device`.

Completed:

- Added `tools/run-device-smoke.ps1`.
- The script installs the sample APK, records device properties, runs sample smoke actions, captures a screenshot, and writes logs under `build/device-smoke/`.
- The script fails early if the selected adb device is missing or not in `device` state, so an `offline` device cannot be accidentally marked as passed.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` with the runtime smoke command.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
.\tools\run-device-smoke.ps1 -DeviceSerial 4tqoz9bmfu8t8pr8
$errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path .\tools\run-device-smoke.ps1), [ref]$null, [ref]$errors)
$errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path .\tools\check-native-libs.ps1), [ref]$null, [ref]$errors)
$errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path .\tools\check-android-acceptance.ps1), [ref]$null, [ref]$errors)
```

Results:

- `run-device-smoke.ps1` failed early as expected because adb reported `4tqoz9bmfu8t8pr8 offline`.
- PowerShell parser checks passed for all three scripts.

Known remaining work:

- Run `.\tools\run-device-smoke.ps1` successfully after adb reports a real Vulkan Android device in `device` state.

### Device Runtime Lifecycle Smoke Script

Expanded the real-device runtime smoke script to cover lifecycle gates.

Completed:

- Added cold-start launch before action smokes.
- Added HOME pause and resume.
- Added force-stop destroy and recreate.
- Added UI dump collection next to the screenshot and logcat artifacts.
- Added per-action delays for longer-running MVT, 3D Tiles, and offline smoke actions.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` to describe the lifecycle and UI dump coverage.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path .\tools\run-device-smoke.ps1), [ref]$null, [ref]$errors)
.\tools\run-device-smoke.ps1 -DeviceSerial 4tqoz9bmfu8t8pr8
```

Results:

- PowerShell parser check passed for `run-device-smoke.ps1`.
- `run-device-smoke.ps1` still failed early as expected because adb reported `4tqoz9bmfu8t8pr8 offline`.

Known remaining work:

- Run the lifecycle-capable runtime smoke successfully after adb reports a real Vulkan Android device in `device` state.

### Device Smoke APK Selection

Made the runtime smoke script usable for both physical ARM devices and x86_64 emulators.

Completed:

- Changed `tools/run-device-smoke.ps1` so `-Apk` is optional.
- When `-Apk` is omitted, the script reads `ro.product.cpu.abilist` from the selected online device and chooses:
  - `vectorra-sample-arm64-v8a-debug.apk` for `arm64-v8a`
  - `vectorra-sample-x86_64-debug.apk` for `x86_64`
  - `vectorra-sample-universal-debug.apk` otherwise
- Added the selected APK path to the smoke report.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` with the automatic APK selection behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path .\tools\run-device-smoke.ps1), [ref]$null, [ref]$errors)
.\tools\run-device-smoke.ps1 -DeviceSerial 4tqoz9bmfu8t8pr8
```

Results:

- PowerShell parser check passed for `run-device-smoke.ps1`.
- The script still failed early as expected because adb reported `4tqoz9bmfu8t8pr8 offline`, before any APK install attempt.

Known remaining work:

- Run `.\tools\run-device-smoke.ps1` on an online `arm64-v8a` device and, if available, an online `x86_64` emulator to exercise automatic APK selection.

### Device Smoke Artifact Validation

Tightened the runtime smoke pass criteria.

Completed:

- Added non-empty file assertions for the pulled screenshot, UI dump, and logcat artifacts in `tools/run-device-smoke.ps1`.
- Added a UI dump check that requires `com.vectorra.sample` to be present before the runtime smoke is accepted.
- Appended artifact paths and byte counts to the smoke report.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` with the artifact validation behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path .\tools\run-device-smoke.ps1), [ref]$null, [ref]$errors)
.\tools\run-device-smoke.ps1 -DeviceSerial 4tqoz9bmfu8t8pr8
```

Results:

- PowerShell parser check passed for `run-device-smoke.ps1`.
- The script still failed early as expected because adb reported `4tqoz9bmfu8t8pr8 offline`.

Known remaining work:

- Validate the new artifact checks on an online real device.

### Device Smoke Step Reporting

Improved runtime smoke reports so interrupted device runs are diagnosable.

Completed:

- Added `Write-Report` helper in `tools/run-device-smoke.ps1`.
- The smoke report now records:
  - APK install path
  - logcat clear
  - pre-cold-start force stop
  - start/end for cold start, every sample action, and every lifecycle step
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` with the action/lifecycle reporting behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path .\tools\run-device-smoke.ps1), [ref]$null, [ref]$errors)
.\tools\run-device-smoke.ps1 -DeviceSerial 4tqoz9bmfu8t8pr8
```

Results:

- PowerShell parser check passed for `run-device-smoke.ps1`.
- The script still failed early as expected because adb reported `4tqoz9bmfu8t8pr8 offline`.

Known remaining work:

- Confirm action/lifecycle report output on an online runtime smoke.

### Device Smoke Result Checker

Added a standalone checker for completed runtime smoke artifacts.

Completed:

- Added `tools/check-device-smoke-result.ps1`.
- The checker verifies the latest `build/device-smoke/device-smoke-*.txt` report by default.
- It requires install/logcat/cold-start/lifecycle markers, start/end records for every sample smoke action, non-empty screenshot/UI/logcat artifacts, `com.vectorra.sample` in the UI dump, and no common crash patterns in logcat.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` to run the checker after `tools/run-device-smoke.ps1`.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\run-device-smoke.ps1','.\tools\check-device-smoke-result.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\check-device-smoke-result.ps1
```

Results:

- PowerShell parser checks passed for `run-device-smoke.ps1` and `check-device-smoke-result.ps1`.
- Running the checker without a completed runtime smoke failed early as expected because `build/device-smoke` does not exist.

Known remaining work:

- Run `.\tools\run-device-smoke.ps1` and `.\tools\check-device-smoke-result.ps1` after adb reports the physical device as `device`.

### Device Smoke Result Checker Self-Test

Added a local self-test for the runtime smoke result verifier.

Completed:

- Added `tools/test-device-smoke-result-checker.ps1`.
- The self-test creates synthetic smoke artifacts under `build/device-smoke-checker-test`.
- It verifies that a complete report passes, a crash-log report fails, and a missing-action report fails.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` to run the self-test before trusting physical device smoke results.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\run-device-smoke.ps1','.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for `run-device-smoke.ps1`, `check-device-smoke-result.ps1`, and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed: the valid fixture was accepted, the crash-log fixture failed, and the missing-action fixture failed.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke Artifact Report Validation

Tightened the runtime smoke result checker so device artifact records cannot be silently omitted from the text report.

Completed:

- Added `Assert-ArtifactReportLine` to `tools/check-device-smoke-result.ps1`.
- The checker now requires positive-byte report records for `screenshot=... bytes=N`, `uiDump=... bytes=N`, and `logcat=... bytes=N` in addition to verifying the files exist and are non-empty.
- Updated `tools/test-device-smoke-result-checker.ps1` with a missing-artifact-record fixture that must fail.
- Updated the ABI/device matrix and Android 1.0 acceptance docs with the artifact byte-record requirement.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\check-device-smoke-result.ps1'), [ref]$tokens, [ref]$errors)
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\test-device-smoke-result-checker.ps1'), [ref]$tokens, [ref]$errors)
.\tools\test-device-smoke-result-checker.ps1
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1` and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed, including the expected failure for a report missing a logcat artifact byte record.
- `check-android-acceptance.ps1` passed and reported `Android local acceptance gate passed.`
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`; Android 1.0 runtime device acceptance remains unverified.

### Android Acceptance Instrumentation APK Build

Expanded the local Android 1.0 acceptance gate to cover the SDK instrumentation APK.

Completed:

- Added `:vectorra-maps:assembleDebugAndroidTest` to `tools/check-android-acceptance.ps1`.
- This validates that `VectorraMbTilesVectorSourceInstrumentedTest` and the SDK androidTest package compile and package before release readiness is considered locally green.
- Updated `docs/beta/android-1.0-acceptance.md`, `docs/beta/release-versioning.md`, and `docs/beta/release-notes-0.8.0-beta.1.md` so the documented local gate includes the instrumentation APK build.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\check-android-acceptance.ps1'), [ref]$tokens, [ref]$errors)
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:assembleDebugAndroidTest
rg -n "assembleDebugAndroidTest|instrumentation APK|instrumentation" docs\beta\android-1.0-acceptance.md docs\beta\release-versioning.md docs\beta\release-notes-0.8.0-beta.1.md tools\check-android-acceptance.ps1
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser check passed for `check-android-acceptance.ps1`.
- `:vectorra-maps:assembleDebugAndroidTest` passed and produced the debug androidTest package.
- Documentation search found the instrumentation APK build in the acceptance, release/versioning, release notes, and acceptance script.
- `check-android-acceptance.ps1` passed and reported `Android local acceptance gate passed.`
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`.

Known remaining work:

- Run `connectedDebugAndroidTest` for `VectorraMbTilesVectorSourceInstrumentedTest` and the real device smoke once adb reports the physical device as `device`; Android 1.0 runtime device acceptance remains unverified.

### Android Acceptance Smoke Checker Documentation Sync

Aligned the release and acceptance documentation with the current runtime smoke result checker self-test coverage.

Completed:

- Updated `docs/beta/android-1.0-acceptance.md` so the local gate description no longer says the smoke verifier only checks complete, crash-log, and missing-action fixtures.
- Updated `docs/beta/release-versioning.md` to list the current smoke checker self-test failure categories covered by `tools/check-android-acceptance.ps1`.
- Updated `docs/beta/release-notes-0.8.0-beta.1.md` with the current smoke checker self-test coverage for the unpublished development target.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "complete, crash-log, and missing-action fixtures|crash-log, and missing-action|runtime smoke result verifier|smoke checker self-test|APK/ABI|out-of-order|3D Tiles close-zoom" docs README.md log.md
$tokens=$null; $errors=$null; foreach($path in @('.\tools\check-android-acceptance.ps1','.\tools\test-device-smoke-result-checker.ps1','.\tools\check-device-smoke-result.ps1')) { [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$tokens, [ref]$errors) | Out-Null }
.\tools\test-device-smoke-result-checker.ps1
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- Documentation search no longer found the stale `complete, crash-log, and missing-action fixtures` wording in docs.
- PowerShell parser checks passed for `check-android-acceptance.ps1`, `test-device-smoke-result-checker.ps1`, and `check-device-smoke-result.ps1`.
- `test-device-smoke-result-checker.ps1` passed.
- `check-android-acceptance.ps1` passed and reported `Android local acceptance gate passed.`
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`; Android 1.0 runtime device acceptance remains unverified.

### Device Smoke Ordered Marker Validation

Tightened the runtime smoke result checker so action and lifecycle report markers must follow the script execution order.

Completed:

- Added `Assert-OrderedReportPatterns` to `tools/check-device-smoke-result.ps1`.
- The checker now validates the ordered sequence from logcat clear, force-stop, cold start, all sample actions, pause/home/resume, destroy/recreate, and lifecycle end markers.
- Updated `tools/test-device-smoke-result-checker.ps1` so the valid fixture follows the real `run-device-smoke.ps1` order.
- Added an out-of-order action marker fixture that must fail even though both start/end markers are present.
- Updated the ABI/device matrix and Android 1.0 acceptance docs with the ordered marker requirement.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\check-device-smoke-result.ps1'), [ref]$tokens, [ref]$errors)
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\test-device-smoke-result-checker.ps1'), [ref]$tokens, [ref]$errors)
.\tools\test-device-smoke-result-checker.ps1
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1` and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed, including the expected failure for out-of-order `mvt` action markers.
- `check-android-acceptance.ps1` passed and reported `Android local acceptance gate passed.`
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`; Android 1.0 runtime device acceptance remains unverified.

### Device Smoke APK Metadata Consistency

Tightened the runtime smoke result checker so installed APK metadata must match the actual smoke APK selection.

Completed:

- Added report value extraction and `Assert-InstalledApkConsistency` to `tools/check-device-smoke-result.ps1`.
- The checker now requires `installedApk` to match `installApk`.
- The checker now verifies that split APK names containing `arm64-v8a` or `x86_64` are compatible with the reported device ABI list.
- Updated `tools/test-device-smoke-result-checker.ps1` with mismatched installed APK and incompatible APK/ABI fixtures that must fail.
- Updated the ABI/device matrix and Android 1.0 acceptance docs with the install/ABI consistency requirements.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\check-device-smoke-result.ps1'), [ref]$tokens, [ref]$errors)
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\test-device-smoke-result-checker.ps1'), [ref]$tokens, [ref]$errors)
.\tools\test-device-smoke-result-checker.ps1
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1` and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed, including the expected failures for mismatched installed APK and incompatible APK/ABI metadata.
- `check-android-acceptance.ps1` passed and reported `Android local acceptance gate passed.`
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`; Android 1.0 runtime device acceptance remains unverified.

### Device Smoke Artifact Path Validation

Tightened the runtime smoke result checker so artifact byte records must identify the exact files being validated.

Completed:

- Updated `tools/check-device-smoke-result.ps1` so `Assert-ArtifactReportLine` parses the reported artifact path and positive byte count.
- The checker now compares each reported artifact path against the timestamp-derived screenshot, UI dump, and logcat paths it actually validates.
- Updated `tools/test-device-smoke-result-checker.ps1` with a mismatched artifact path fixture that must fail.
- Updated the ABI/device matrix and Android 1.0 acceptance docs with the artifact path matching requirement.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\check-device-smoke-result.ps1'), [ref]$tokens, [ref]$errors)
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\test-device-smoke-result-checker.ps1'), [ref]$tokens, [ref]$errors)
.\tools\test-device-smoke-result-checker.ps1
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1` and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed, including the expected failure for a mismatched screenshot artifact path.
- `check-android-acceptance.ps1` passed and reported `Android local acceptance gate passed.`
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`; Android 1.0 runtime device acceptance remains unverified.

### Device Smoke ADB Device Enumeration Check

Tightened runtime smoke startup diagnostics.

Completed:

- Updated `tools/run-device-smoke.ps1` to throw immediately if `adb devices -l` exits with a non-zero code.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` to document the adb device enumeration check.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\run-device-smoke.ps1','.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for `run-device-smoke.ps1`, `check-device-smoke-result.ps1`, `test-device-smoke-result-checker.ps1`, and `check-android-acceptance.ps1`.
- `test-device-smoke-result-checker.ps1` passed.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke ABI Query Exit Check

Tightened automatic APK split selection in the runtime smoke script.

Completed:

- Updated `tools/run-device-smoke.ps1` to throw if `adb shell getprop ro.product.cpu.abilist` fails before automatic APK selection.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` to document that ABI query success is required for automatic APK selection.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\run-device-smoke.ps1','.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for `run-device-smoke.ps1`, `check-device-smoke-result.ps1`, `test-device-smoke-result-checker.ps1`, and `check-android-acceptance.ps1`.
- `test-device-smoke-result-checker.ps1` passed.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke ADB Exit Checks

Strengthened runtime smoke script failure handling.

Completed:

- Updated `tools/run-device-smoke.ps1` so `Invoke-Adb` throws when adb exits with a non-zero code.
- adb failure messages now include the device serial and adb arguments that failed.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` to document adb step failure checks.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\run-device-smoke.ps1','.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for `run-device-smoke.ps1`, `check-device-smoke-result.ps1`, `test-device-smoke-result-checker.ps1`, and `check-android-acceptance.ps1`.
- `test-device-smoke-result-checker.ps1` passed.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke Crash Log Capture

Fixed a runtime smoke log capture blind spot.

Completed:

- Updated `tools/run-device-smoke.ps1` so the logcat artifact includes `AndroidRuntime`, `libc`, `DEBUG`, `ActivityManager`, and `ActivityTaskManager` in addition to Vectorra tags.
- This makes the existing `tools/check-device-smoke-result.ps1` crash and ANR patterns actionable against the generated logcat file.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` with the broader logcat coverage.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\run-device-smoke.ps1','.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for `run-device-smoke.ps1`, `check-device-smoke-result.ps1`, `test-device-smoke-result-checker.ps1`, and `check-android-acceptance.ps1`.
- `test-device-smoke-result-checker.ps1` passed and still confirms crash-log patterns fail the checker.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke Snapshot Nonblank Validation

Added validation for the sample snapshot smoke result.

Completed:

- Updated `tools/check-device-smoke-result.ps1` to require a `Snapshot <width>x<height> nonblank=true` logcat entry.
- Updated `tools/test-device-smoke-result-checker.ps1` so the valid fixture contains the snapshot success log and a blank snapshot fixture fails.
- Kept the crash-log fixture targeted by including a snapshot success log before the crash pattern.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` with the snapshot nonblank validation behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1`, `test-device-smoke-result-checker.ps1`, and `check-android-acceptance.ps1`.
- `test-device-smoke-result-checker.ps1` passed: valid fixture passed; crash-log, missing-action, invalid-screenshot, missing-metadata, empty-metadata, and blank-snapshot fixtures failed as expected.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke Vulkan Metadata Validation

Added Vulkan metadata coverage to the runtime smoke report and checker.

Completed:

- Updated `tools/run-device-smoke.ps1` to write a separate `vulkan=` report entry from SurfaceFlinger Vulkan lines or `ro.hardware.vulkan`.
- Updated `tools/check-device-smoke-result.ps1` to require non-empty values for `serial`, `installedApk`, `model`, `sdk`, `abis`, `gpu`, and `vulkan`.
- Updated `tools/test-device-smoke-result-checker.ps1` to include `vulkan` in valid fixtures and to require an empty metadata fixture to fail.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` with the Vulkan and non-empty metadata validation behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\run-device-smoke.ps1','.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for `run-device-smoke.ps1`, `check-device-smoke-result.ps1`, `test-device-smoke-result-checker.ps1`, and `check-android-acceptance.ps1`.
- `test-device-smoke-result-checker.ps1` passed: valid fixture passed; crash-log, missing-action, invalid-screenshot, missing-metadata, and empty-metadata fixtures failed as expected.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke Metadata Validation

Strengthened runtime smoke result metadata validation.

Completed:

- Updated `tools/check-device-smoke-result.ps1` to require `serial`, `installedApk`, `model`, `sdk`, `abis`, and `gpu` report entries.
- Updated `tools/test-device-smoke-result-checker.ps1` to include device metadata in valid fixtures and to require a missing metadata fixture to fail.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` with the metadata validation behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1`, `test-device-smoke-result-checker.ps1`, and `check-android-acceptance.ps1`.
- `test-device-smoke-result-checker.ps1` passed: valid fixture passed; crash-log, missing-action, invalid-screenshot, and missing-metadata fixtures failed as expected.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke Screenshot PNG Validation

Strengthened runtime smoke result artifact validation.

Completed:

- Updated `tools/check-device-smoke-result.ps1` to verify the captured screenshot has a PNG signature, an `IHDR` chunk, and positive width/height.
- Updated `tools/test-device-smoke-result-checker.ps1` to generate a valid synthetic PNG for the passing fixture and to require an invalid screenshot fixture to fail.
- Updated `docs/beta/abi-device-matrix.md` and `docs/beta/android-1.0-acceptance.md` with the screenshot PNG validation behavior.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1`, `test-device-smoke-result-checker.ps1`, and `check-android-acceptance.ps1`.
- `test-device-smoke-result-checker.ps1` passed: valid fixture passed; crash-log, missing-action, and invalid-screenshot fixtures failed as expected.
- `tools/check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home` passed after the PNG validation change.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Release Documentation Local Gate Alignment

Aligned release documentation with the current local Android acceptance script behavior.

Completed:

- Updated `docs/beta/release-versioning.md` to state that `tools/check-android-acceptance.ps1` includes the Gradle release gate, native library content check, and runtime smoke result checker self-test.
- Updated `docs/beta/release-notes-0.8.0-beta.1.md` to include the latest full local acceptance command and describe its coverage.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "check-android-acceptance|native-library|runtime smoke result checker|Current local result|full local Android acceptance gate" .\docs\beta\release-versioning.md .\docs\beta\release-notes-0.8.0-beta.1.md .\docs\beta\android-1.0-acceptance.md
$files=@('.\tools\check-android-acceptance.ps1','.\tools\check-native-libs.ps1','.\tools\test-device-smoke-result-checker.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- Release/versioning, release notes, and Android acceptance docs now describe the same local acceptance entry point and smoke checker self-test coverage.
- PowerShell parser checks passed for `check-android-acceptance.ps1`, `check-native-libs.ps1`, and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed.

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Android Acceptance Script Smoke Checker Self-Test

Aligned the local acceptance script with the documented Android 1.0 gate.

Completed:

- Updated `tools/check-android-acceptance.ps1` to run `tools/test-device-smoke-result-checker.ps1` after `tools/check-native-libs.ps1`.
- The documented one-command local gate now includes Gradle/unit/build/published-AAR checks, native library checks, and the runtime smoke result checker self-test.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\check-android-acceptance.ps1','.\tools\check-native-libs.ps1','.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- PowerShell parser checks passed for the local acceptance, native-lib, smoke-result, and smoke-result self-test scripts.
- `test-device-smoke-result-checker.ps1` passed.

Known remaining work:

- Run the full `.\tools\check-android-acceptance.ps1` gate after the next broad local verification cycle.
- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke Snapshot Count Validation

Tightened snapshot log validation so the runtime gate requires both the initial snapshot smoke and the post-recreate snapshot smoke to report nonblank output.

Completed:

- Updated `tools/check-device-smoke-result.ps1` to require at least two `Snapshot <width>x<height> nonblank=true` logcat entries.
- Kept the 3D Tiles close-zoom snapshot as a separate required logcat pattern.
- Updated `tools/test-device-smoke-result-checker.ps1` valid fixtures to include two sample snapshot logs.
- Added a negative fixture where only one sample snapshot log is present and must fail.
- Updated the Android 1.0 acceptance record, ABI/device matrix, release versioning checklist, and 0.8.0 beta development notes to document the stricter snapshot-count gate.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1` and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed; the blank snapshot fixture failed with zero nonblank snapshot logs and the new single-snapshot fixture failed with one nonblank snapshot log.
- Search found the snapshot-count requirement in the checker, self-test, Android 1.0 acceptance record, ABI/device matrix, release versioning checklist, release notes, and this log.
- `check-android-acceptance.ps1` passed with `BUILD SUCCESSFUL`, `Native library check passed.`, `Android instrumentation APK check passed.`, `Android instrumentation APK checker self-test passed.`, `Device smoke result checker self-test passed.`, and `Android local acceptance gate passed.`

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke Post-Recreate Snapshot Marker

Made the post-recreate snapshot smoke report marker distinct from the earlier regular snapshot action.

Completed:

- Updated `tools/run-device-smoke.ps1` so `Smoke-Action` can record a report action name separately from the sample action sent to `MainActivity`.
- The post-recreate step now records `actionStart=post-recreate-snapshot` and `actionEnd=post-recreate-snapshot`, while still sending `vectorra.sample.action=snapshot` to the sample.
- Updated `tools/check-device-smoke-result.ps1` to require the distinct `post-recreate-snapshot` marker after `startSampleEnd=recreate-after-force-stop`.
- Updated `tools/test-device-smoke-result-checker.ps1` valid and negative fixtures for the distinct marker.
- Updated the Android 1.0 acceptance record and ABI/device matrix to document the distinct runtime marker.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\run-device-smoke.ps1','.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `run-device-smoke.ps1`, `check-device-smoke-result.ps1`, and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed; the missing post-recreate snapshot fixture failed as expected with `Ordered smoke report marker missing after line 56: actionStart=post-recreate-snapshot(\s|$)`.
- Search confirmed `post-recreate-snapshot` is present in the runtime script, result checker, fixture self-test, Android 1.0 acceptance record, ABI/device matrix, and this log.
- `check-android-acceptance.ps1` passed with `BUILD SUCCESSFUL`, `Native library check passed.`, `Android instrumentation APK check passed.`, `Android instrumentation APK checker self-test passed.`, `Device smoke result checker self-test passed.`, and `Android local acceptance gate passed.`

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Device Smoke Post-Recreate Snapshot Validation

Tightened the device smoke result checker so the runtime gate proves that the sample can still run a snapshot action after force-stop/recreate.

Completed:

- Updated `tools/check-device-smoke-result.ps1` so the ordered smoke report markers require `actionStart=snapshot` and `actionEnd=snapshot` after `startSampleEnd=recreate-after-force-stop`.
- Updated `tools/test-device-smoke-result-checker.ps1` so the valid fixture includes the post-recreate snapshot markers.
- Added a negative fixture that omits the post-recreate snapshot and must fail.
- Updated the Android 1.0 acceptance record, ABI/device matrix, release versioning checklist, and 0.8.0 beta development notes to document the stricter smoke checker coverage.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\check-device-smoke-result.ps1','.\tools\test-device-smoke-result-checker.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1` and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed; the new missing post-recreate snapshot fixture failed as expected with `Ordered smoke report marker missing after line 56: actionStart=snapshot(\s|$)`.
- `check-android-acceptance.ps1` passed with `BUILD SUCCESSFUL`, `Native library check passed.`, `Android instrumentation APK check passed.`, `Android instrumentation APK checker self-test passed.`, `Device smoke result checker self-test passed.`, and `Android local acceptance gate passed.`

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Android Instrumentation APK Checker Self-Test

Added a self-test for the Android instrumentation APK packaging checker.

Completed:

- Added `tools/test-android-test-apk-checker.ps1`.
- The self-test creates valid, missing, empty, and native-library APK fixtures under `build/android-test-apk-checker-test`.
- Wired the self-test into `tools/check-android-acceptance.ps1` after the real instrumentation APK packaging check.
- Updated the Android 1.0 acceptance record, release versioning checklist, and 0.8.0 beta development notes to document the instrumentation APK checker self-test.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\check-android-test-apk.ps1','.\tools\test-android-test-apk-checker.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-android-test-apk-checker.ps1
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `check-android-test-apk.ps1`, `test-android-test-apk-checker.ps1`, and `check-android-acceptance.ps1`.
- `test-android-test-apk-checker.ps1` passed and rejected the missing APK, empty APK, and native `.so` APK fixtures.
- An initial full acceptance run exposed that the new self-test left `$LASTEXITCODE=1` after expected subprocess failures, which caused the next smoke checker self-test to misread its valid fixture as failed.
- Fixed `test-android-test-apk-checker.ps1` to preserve and restore the caller's `$LASTEXITCODE`.
- Reran `test-android-test-apk-checker.ps1` followed by `test-device-smoke-result-checker.ps1`; both passed in sequence.
- Reran `check-android-acceptance.ps1`; it passed with `BUILD SUCCESSFUL`, `Native library check passed.`, `Android instrumentation APK check passed.`, `Android instrumentation APK checker self-test passed.`, `Device smoke result checker self-test passed.`, and `Android local acceptance gate passed.`

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Android Instrumentation APK Packaging Check

Added a local acceptance check for the SDK instrumentation APK packaging policy.

Completed:

- Added `tools/check-android-test-apk.ps1`.
- The script fails if `vectorra-maps-debug-androidTest.apk` is missing, empty, or contains any native `.so` entry.
- Wired the script into `tools/check-android-acceptance.ps1` after the SDK/sample native library content check.
- Updated the Android 1.0 acceptance record, release versioning checklist, and 0.8.0 beta development notes to document the new instrumentation APK no-native-library gate.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$files=@('.\tools\check-android-test-apk.ps1','.\tools\check-android-acceptance.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\check-android-test-apk.ps1
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `check-android-test-apk.ps1` and `check-android-acceptance.ps1`.
- `check-android-test-apk.ps1` passed and reported `Android instrumentation APK check passed.`
- `check-android-acceptance.ps1` passed with `BUILD SUCCESSFUL`, `Native library check passed.`, `Android instrumentation APK check passed.`, `Device smoke result checker self-test passed.`, and `Android local acceptance gate passed.`

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### 3D Tiles Close-Zoom Snapshot Smoke Evidence

Tightened the device smoke gate around the 3D Tiles close-zoom disappearance investigation.

Completed:

- Refactored the sample snapshot smoke into a reusable `logSnapshotSmoke(...)` helper.
- Added a delayed `3D Tiles zoom snapshot <width>x<height> nonblank=<value>` log after the `zoom-3dtiles` action reaches its closest camera step.
- Updated `tools/check-device-smoke-result.ps1` to require `3D Tiles zoom snapshot ... nonblank=true` in logcat.
- Updated `tools/test-device-smoke-result-checker.ps1` so the valid fixture includes the 3D Tiles zoom snapshot and a missing zoom-snapshot fixture fails.
- Updated the ABI/device matrix and Android 1.0 acceptance docs with the new close-zoom snapshot requirement.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\check-device-smoke-result.ps1'), [ref]$tokens, [ref]$errors)
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\test-device-smoke-result-checker.ps1'), [ref]$tokens, [ref]$errors)
$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path '.\tools\run-device-smoke.ps1'), [ref]$tokens, [ref]$errors)
.\tools\test-device-smoke-result-checker.ps1
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- PowerShell parser checks passed for `check-device-smoke-result.ps1`, `test-device-smoke-result-checker.ps1`, and `run-device-smoke.ps1`.
- `test-device-smoke-result-checker.ps1` passed, including the expected failure for a report missing the 3D Tiles zoom snapshot.
- `:vectorra-sample:assembleDebug` passed. The existing non-fatal strip warning for `librocky.so` and `libvectorra_jni.so` remained.
- `check-android-acceptance.ps1` passed and reported `Android local acceptance gate passed.`
- adb still reported device `4tqoz9bmfu8t8pr8` as `offline`.

Known remaining work:

- Re-run `tools/run-device-smoke.ps1` and `tools/check-device-smoke-result.ps1` once adb reports the device as `device`; the real close-zoom visual snapshot remains unverified on hardware.

### Android Local Acceptance Rerun

Reran the full documented local Android 1.0 acceptance gate after adding the smoke result checker self-test to the gate.

Completed:

- Ran `tools/check-android-acceptance.ps1` with the project-local Gradle user home.
- Updated `docs/beta/android-1.0-acceptance.md` with the latest local gate evidence.

Verification command was run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home
```

Results:

- Gradle local gate passed with `BUILD SUCCESSFUL`.
- `check-native-libs.ps1` passed.
- `test-device-smoke-result-checker.ps1` passed.
- `check-android-acceptance.ps1` reported `Android local acceptance gate passed.`

Known remaining work:

- Run the real device smoke and result checker after adb reports the physical device as `device`.

### Android Acceptance Documentation Gate Entry

Removed duplicated local gate commands from the Android 1.0 acceptance record.

Completed:

- Updated `docs/beta/android-1.0-acceptance.md` so `tools/check-android-acceptance.ps1` is the single local acceptance entry point.
- Kept the expanded command list showing that the script includes native library checks and `tools/test-device-smoke-result-checker.ps1`.

Verification commands were run from `D:\workspace\code\vectorra\vectorra-maps`:

```powershell
rg -n "check-android-acceptance|test-device-smoke-result-checker|Equivalent expanded command|Local Gates" .\docs\beta\android-1.0-acceptance.md .\docs\beta\release-versioning.md .\docs\beta\abi-device-matrix.md
$files=@('.\tools\check-android-acceptance.ps1','.\tools\test-device-smoke-result-checker.ps1'); foreach($path in $files){ $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $path), [ref]$null, [ref]$errors) | Out-Null; if($errors.Count -gt 0){ foreach($err in $errors){ Write-Error ($path + ': ' + $err.Message) }; exit 1 }; Write-Output ($path + ' syntax ok') }
.\tools\test-device-smoke-result-checker.ps1
```

Results:

- Acceptance docs now show one local gate entry command: `.\tools\check-android-acceptance.ps1`.
- PowerShell parser checks passed for `check-android-acceptance.ps1` and `test-device-smoke-result-checker.ps1`.
- `test-device-smoke-result-checker.ps1` passed.

Known remaining work:

- Run the full `.\tools\check-android-acceptance.ps1` gate after the next broad local verification cycle.
- Run the real device smoke and result checker after adb reports the physical device as `device`.
