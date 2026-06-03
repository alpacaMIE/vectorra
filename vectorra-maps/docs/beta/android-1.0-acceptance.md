# Android 1.0 Acceptance Record

This record separates local release gates from runtime device gates.

## Local Gates

Run from `vectorra-maps/`:

```powershell
.\tools\check-android-acceptance.ps1
```

This script runs the full local gate, builds the SDK instrumentation APK, checks native library entries in the generated SDK AAR and sample APK artifacts, verifies the SDK instrumentation APK exists without packaged native `.so` entries, validates the instrumentation APK checker against missing, empty, and native-library failure fixtures, validates the device smoke script contract across runner, result checker, fixtures, and sample actions, and validates the runtime smoke result verifier against the complete fixture plus crash, native renderer startup failure, missing-action, ordering, post-recreate snapshot, metadata, APK/ABI, artifact, screenshot, snapshot, base raster loaded, 3D Tiles high-LOD runtime/re-add, MVT pan/hidden/remove, MVT MBTiles native render, offline prefetch cleanup, and GeoJSON/Draw/Location interaction failure fixtures.

Equivalent expanded command:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :vectorra-maps:testDebugUnitTest :vectorra-maps-turf:testDebugUnitTest :vectorra-maps:assembleDebugAndroidTest :vectorra-sample:assembleDebug assembleDebug :vectorra-maps:publishReleasePublicationToMavenLocal :vectorra-maps-turf:publishReleasePublicationToMavenLocal "-Pvectorra.sample.usePublishedAar=true" :vectorra-sample:assembleDebug
.\tools\check-native-libs.ps1
.\tools\check-android-test-apk.ps1
.\tools\test-android-test-apk-checker.ps1
.\tools\test-device-smoke-contract.ps1
.\tools\test-device-smoke-result-checker.ps1
```

Current local result: passed with `.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home`.

Latest local evidence:

- Gradle local gate: `BUILD SUCCESSFUL`
- `:vectorra-maps:assembleDebugAndroidTest`: passed
- `check-native-libs.ps1`: passed
- `check-android-test-apk.ps1`: passed; `vectorra-maps-debug-androidTest.apk` exists and contains no native `.so` entries
- `test-android-test-apk-checker.ps1`: passed, including missing APK, empty APK, and native `.so` rejection
- `test-device-smoke-contract.ps1`: passed; runner actions, checker required actions, fixture actions, sample action constants, and post-recreate snapshot markers are aligned
- `test-device-smoke-result-checker.ps1`: passed, including invalid screenshot PNG, missing device metadata, empty device metadata, native renderer startup failure rejection, out-of-order action/lifecycle markers, missing post-recreate snapshot markers, missing post-recreate snapshot log rejection, install/installed APK mismatch, APK/ABI mismatch, missing artifact byte record, mismatched artifact path, blank snapshot rejection, missing 3D Tiles zoom-snapshot rejection, missing 3D Tiles high-LOD native evidence rejection, missing 3D Tiles re-add loaded rejection, missing MVT pan query, hidden no-features, removed no-features, MVT MBTiles native render rejection, missing offline prefetch cleanup rejection, and missing GeoJSON/Draw/Location interaction rejection
- `VectorraMbTilesVectorSourceInstrumentedTest`: passed on `emulator-5554` through `:vectorra-maps:connectedDebugAndroidTest`, proving the Android SQLite MBTiles vector source can open and read a real MBTiles file

Validated outputs:

- `vectorra-maps-debug.aar`
- `vectorra-maps-release.aar`
- `vectorra-maps-turf-debug.aar`
- `vectorra-maps-turf-release.aar`
- `vectorra-sample-arm64-v8a-debug.apk`
- `vectorra-sample-x86_64-debug.apk`
- `vectorra-sample-universal-debug.apk`
- `vectorra-maps-debug-androidTest.apk`

## Runtime Device Gate

Current emulator result: passed. On 2026-06-04, `emulator-5554` (`sdk_gphone64_x86_64`, API 36, ABIs `x86_64,arm64-v8a`) completed the full sample smoke action sequence and passed `tools/check-device-smoke-result.ps1` against `build/device-smoke/device-smoke-20260604-060442.txt`.

The emulator also passed the targeted Android instrumentation MBTiles vector source test:

```powershell
.\tools\run-mbtiles-vector-instrumentation.ps1 -DeviceSerial emulator-5554
```

Latest emulator evidence includes:

- `3D Tiles zoom snapshot 1080x2219 nonblank=true`
- native application of high-LOD `dragon_high.b3dm.glb`
- `MVT pan center query: Click: 9 feature(s)`
- `MVT removed center query: Click: no features`
- `MVT readd center query: Click: 424 feature(s)`
- MVT MBTiles native render evidence
- offline prefetch success, cancel, and cleanup evidence
- GeoJSON, Draw, and Location interaction evidence
- no native renderer startup failure during home/resume

Current real-device result: not passed because the physical device is offline.

Runtime command:

```powershell
.\tools\run-device-smoke.ps1
.\tools\check-device-smoke-result.ps1
```

The runtime script performs adb device enumeration, cold start, sample smoke actions, home/resume, force-stop/recreate, `post-recreate-snapshot` action, screenshot capture, UI dump, logcat export, adb exit-code checks, non-empty artifact checks, device metadata recording, and action/lifecycle start-end reporting. The logcat export includes Vectorra tags plus Android crash/ANR tags. The result checker also verifies install/installed APK consistency, installed APK compatibility with the reported ABI list, ordered action/lifecycle records including the `post-recreate-snapshot` action, positive-byte screenshot/UI/log artifact records point to the checked files, screenshot PNG signature, dimensions, regular `Snapshot ... nonblank=true` log output, `Post-recreate snapshot ... nonblank=true` log output, base raster loaded log output, 3D Tiles close-zoom snapshot `nonblank=true` log output, 3D Tiles status/native high-LOD render/bad-tileset/re-add log output, MVT native render, pan action, hidden no-features, remove/re-add, MVT MBTiles request/native render log output, offline prefetch success/cancel/cache cleanup log output, GeoJSON query, Draw query/clear, Location indicator/follow/clear log output, non-empty required device metadata keys including Vulkan metadata, and absence of native renderer startup failures.

The runtime script automatically selects the matching split sample APK from the online device ABI list unless `-Apk` is provided. The ABI query must complete successfully before automatic APK selection continues.

Latest adb state:

```text
4tqoz9bmfu8t8pr8 offline
```

Do not declare Android 1.0 release readiness until a real Vulkan-capable Android device completes the [ABI and device matrix](abi-device-matrix.md).

## Release Risk

The current source tree has passed local unit, build, AAR publication, published-AAR consumption, ABI packaging checks, and emulator runtime smoke. Release readiness remains blocked on the real-device runtime gate because `4tqoz9bmfu8t8pr8` is still offline.
