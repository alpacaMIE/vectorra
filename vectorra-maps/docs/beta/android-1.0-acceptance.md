# Android 1.0 Acceptance Record

This record separates local release gates from runtime device gates.

## Local Gates

Run from `vectorra-maps/`:

```powershell
.\tools\check-android-acceptance.ps1
```

This script runs the full local gate, builds the SDK instrumentation APK, checks native library entries in the generated SDK AAR and sample APK artifacts, verifies the SDK instrumentation APK exists without packaged native `.so` entries, validates the instrumentation APK checker against missing, empty, and native-library failure fixtures, validates the device smoke script contract across runner, result checker, fixtures, and sample actions, validates the runtime smoke result verifier against the complete fixture plus crash, native renderer startup failure, missing-action, ordering, post-recreate snapshot, metadata, APK/ABI, artifact, screenshot, close-zoom 3D Tiles screenshot, snapshot, base raster loaded, 3D Tiles high-LOD runtime/re-add, MVT pan/hidden/remove, MVT MBTiles native render, offline prefetch cleanup, and GeoJSON/Draw/Location interaction failure fixtures, validates the emulator smoke gate runner contract, and validates the targeted MBTiles vector instrumentation runner contract.

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
.\tools\test-emulator-smoke-gate.ps1
.\tools\test-mbtiles-vector-instrumentation-runner.ps1
```

Current local result: passed with `.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home`.

Latest local evidence:

- Gradle local gate: `BUILD SUCCESSFUL`
- `:vectorra-maps:assembleDebugAndroidTest`: passed
- `check-native-libs.ps1`: passed
- `check-android-test-apk.ps1`: passed; `vectorra-maps-debug-androidTest.apk` exists and contains no native `.so` entries
- `test-android-test-apk-checker.ps1`: passed, including missing APK, empty APK, and native `.so` rejection
- `test-device-smoke-contract.ps1`: passed; runner actions, checker required actions, fixture actions, sample action constants, post-recreate snapshot markers, final screenshot visible-pixel coverage, close-zoom 3D Tiles screenshot artifact coverage, and close-zoom visible-pixel coverage are aligned
- `test-device-smoke-result-checker.ps1`: passed, including invalid screenshot PNG, missing device metadata, empty device metadata, native renderer startup failure rejection, out-of-order action/lifecycle markers, missing post-recreate snapshot markers, missing post-recreate snapshot log rejection, install/installed APK mismatch, APK/ABI mismatch, missing artifact byte record, mismatched artifact path, blank final screenshot, missing close-zoom 3D Tiles screenshot, mismatched close-zoom 3D Tiles screenshot path, blank close-zoom 3D Tiles screenshot, blank snapshot rejection, missing 3D Tiles zoom-snapshot rejection, missing 3D Tiles high-LOD native evidence rejection, missing 3D Tiles re-add loaded rejection, missing MVT pan query, hidden no-features, removed no-features, MVT MBTiles native render rejection, missing offline prefetch cleanup rejection, and missing GeoJSON/Draw/Location interaction rejection
- `test-emulator-smoke-gate.ps1`: passed; emulator wrapper parameters, adb online-device guard, underlying smoke runner invocation, latest-report checker invocation, and docs commands are aligned
- `test-mbtiles-vector-instrumentation-runner.ps1`: passed; runner parameters, adb online-device guards, targeted test class, and docs commands are aligned
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

Current runtime gate result: accepted with user-approved emulator substitution. On 2026-06-04, `emulator-5554` (`sdk_gphone64_x86_64`, API 36, ABIs `x86_64,arm64-v8a`) completed the full sample smoke action sequence through `tools/run-emulator-smoke-gate.ps1` and passed `tools/check-device-smoke-result.ps1` against `build/device-smoke/device-smoke-20260604-090522.txt`.

The emulator also passed the targeted Android instrumentation MBTiles vector source test:

```powershell
.\tools\run-mbtiles-vector-instrumentation.ps1 -DeviceSerial emulator-5554
```

Latest accepted runtime evidence includes:

- final screenshot `build/device-smoke/vectorra-smoke-20260604-090522.png`, `1080x2424`, visible-pixel check: `8588` visible samples
- close-zoom 3D Tiles screenshot artifact `build/device-smoke/vectorra-smoke-zoom-3dtiles-20260604-090522.png`
- close-zoom 3D Tiles screenshot visible-pixel check: `2573` visible samples
- native application of high-LOD `dragon_high.b3dm.glb`
- `MVT pan center query: Click: 9 feature(s)`
- `MVT removed center query: Click: no features`
- `MVT readd center query: Click: 424 feature(s)`
- MVT MBTiles native render evidence
- offline prefetch success, cancel, and cleanup evidence
- GeoJSON, Draw, and Location interaction evidence
- no native renderer startup failure during home/resume

Physical-device result: not passed because `4tqoz9bmfu8t8pr8` has unstable adb large-file transfer and repeatedly falls back to `offline` during APK install/push. Per user decision on 2026-06-04, the passing emulator smoke above is accepted as the current release runtime gate substitute.

Runtime command:

```powershell
.\tools\run-emulator-smoke-gate.ps1 -DeviceSerial emulator-5554
```

The emulator smoke gate script requires an `emulator-*` adb serial, runs `run-device-smoke.ps1`, selects the latest generated `device-smoke-*.txt` report, and immediately validates it with `check-device-smoke-result.ps1`. The underlying runtime script performs adb device enumeration, cold start, sample smoke actions, home/resume, force-stop/recreate, `post-recreate-snapshot` action, close-zoom 3D Tiles screenshot capture, final screenshot capture, UI dump, logcat export, adb exit-code checks, non-empty artifact checks, device metadata recording, and action/lifecycle start-end reporting. The logcat export includes Vectorra tags plus Android crash/ANR tags. The result checker also verifies install/installed APK consistency, installed APK compatibility with the reported ABI list, ordered action/lifecycle records including the `post-recreate-snapshot` action, positive-byte screenshot/UI/log artifact records point to the checked files, screenshot PNG signature, dimensions, final screenshot visible-pixel content, regular `Snapshot ... nonblank=true` log output, `Post-recreate snapshot ... nonblank=true` log output, base raster loaded log output, 3D Tiles close-zoom screenshot artifact and visible-pixel content, 3D Tiles close-zoom snapshot `nonblank=true` log output, 3D Tiles status/native high-LOD render/bad-tileset/re-add log output, MVT native render, pan action, hidden no-features, remove/re-add, MVT MBTiles request/native render log output, offline prefetch success/cancel/cache cleanup log output, GeoJSON query, Draw query/clear, Location indicator/follow/clear log output, non-empty required device metadata keys including Vulkan metadata, and absence of native renderer startup failures.

The runtime script automatically selects the matching split sample APK from the online device ABI list unless `-Apk` is provided. The ABI query must complete successfully before automatic APK selection continues.

Latest physical-device adb state:

```text
4tqoz9bmfu8t8pr8 offline / unstable during large-file transfer
```

Do not describe the emulator as a physical device. The release decision is based on an explicit emulator substitution waiver plus the passing runtime evidence above.

## Release Risk

The current source tree has passed local unit, build, AAR publication, published-AAR consumption, ABI packaging checks, and the accepted emulator runtime smoke. Residual release risk remains: the physical device `4tqoz9bmfu8t8pr8` could not complete the gate because adb large-file transfer failed during APK install/push.
