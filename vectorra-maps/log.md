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
