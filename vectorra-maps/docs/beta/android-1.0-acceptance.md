# Android 1.0 Acceptance Record

This record separates local release gates from runtime device gates.

## Local Gates

Run from `vectorra-maps/`:

```powershell
.\tools\check-android-acceptance.ps1
```

This script runs the full local gate, builds the SDK instrumentation APK, checks native library entries in the generated SDK AAR and sample APK artifacts, verifies the SDK instrumentation APK exists without packaged native `.so` entries, validates the instrumentation APK checker against missing, empty, and native-library failure fixtures, and validates the runtime smoke result verifier against the complete fixture plus crash, missing-action, ordering, post-recreate snapshot, metadata, APK/ABI, artifact, screenshot, and snapshot failure fixtures.

Equivalent expanded command:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :vectorra-maps:testDebugUnitTest :vectorra-maps-turf:testDebugUnitTest :vectorra-maps:assembleDebugAndroidTest :vectorra-sample:assembleDebug assembleDebug :vectorra-maps:publishReleasePublicationToMavenLocal :vectorra-maps-turf:publishReleasePublicationToMavenLocal "-Pvectorra.sample.usePublishedAar=true" :vectorra-sample:assembleDebug
.\tools\check-native-libs.ps1
.\tools\check-android-test-apk.ps1
.\tools\test-android-test-apk-checker.ps1
.\tools\test-device-smoke-result-checker.ps1
```

Current local result: passed with `.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home`.

Latest local evidence:

- Gradle local gate: `BUILD SUCCESSFUL`
- `:vectorra-maps:assembleDebugAndroidTest`: passed
- `check-native-libs.ps1`: passed
- `check-android-test-apk.ps1`: passed; `vectorra-maps-debug-androidTest.apk` exists and contains no native `.so` entries
- `test-android-test-apk-checker.ps1`: passed, including missing APK, empty APK, and native `.so` rejection
- `test-device-smoke-result-checker.ps1`: passed, including invalid screenshot PNG, missing device metadata, empty device metadata, out-of-order action/lifecycle markers, missing post-recreate snapshot markers, missing post-recreate snapshot log rejection, install/installed APK mismatch, APK/ABI mismatch, missing artifact byte record, mismatched artifact path, blank snapshot rejection, and missing 3D Tiles zoom-snapshot rejection

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

Current device result: not passed.

Runtime command:

```powershell
.\tools\run-device-smoke.ps1
.\tools\check-device-smoke-result.ps1
```

The runtime script performs adb device enumeration, cold start, sample smoke actions, home/resume, force-stop/recreate, `post-recreate-snapshot` action, screenshot capture, UI dump, logcat export, adb exit-code checks, non-empty artifact checks, device metadata recording, and action/lifecycle start-end reporting. The logcat export includes Vectorra tags plus Android crash/ANR tags. The result checker also verifies install/installed APK consistency, installed APK compatibility with the reported ABI list, ordered action/lifecycle records including the `post-recreate-snapshot` action, positive-byte screenshot/UI/log artifact records point to the checked files, screenshot PNG signature, dimensions, regular `Snapshot ... nonblank=true` log output, `Post-recreate snapshot ... nonblank=true` log output, 3D Tiles close-zoom snapshot `nonblank=true` log output, and non-empty required device metadata keys, including Vulkan metadata.

The runtime script automatically selects the matching split sample APK from the online device ABI list unless `-Apk` is provided. The ABI query must complete successfully before automatic APK selection continues.

Latest adb state:

```text
4tqoz9bmfu8t8pr8 offline
```

Do not declare Android 1.0 release readiness until a real Vulkan-capable Android device completes the [ABI and device matrix](abi-device-matrix.md).

## Release Risk

The current source tree has passed local unit, build, AAR publication, published-AAR consumption, and ABI packaging checks. It has not passed the required runtime device smoke for cold start, map-ready status, bad-resource UI, raster/DEM/MVT/MBTiles/GeoJSON/draw/location/snapshot/3D Tiles flows, pause/resume or rotation, and destroy/recreate.
