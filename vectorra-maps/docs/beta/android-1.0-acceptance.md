# Android 1.0 Acceptance Record

This record separates local release gates from runtime device gates.

## Local Gates

Run from `vectorra-maps/`:

```powershell
.\tools\check-android-acceptance.ps1
```

This script runs the full local gate, checks native library entries in the generated APK/AAR artifacts, and validates the runtime smoke result verifier against complete, crash-log, and missing-action fixtures.

Equivalent expanded command:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :vectorra-maps:testDebugUnitTest :vectorra-maps-turf:testDebugUnitTest :vectorra-sample:assembleDebug assembleDebug :vectorra-maps:publishReleasePublicationToMavenLocal :vectorra-maps-turf:publishReleasePublicationToMavenLocal "-Pvectorra.sample.usePublishedAar=true" :vectorra-sample:assembleDebug
.\tools\check-native-libs.ps1
.\tools\test-device-smoke-result-checker.ps1
```

Current local result: passed with `.\tools\check-android-acceptance.ps1 -GradleUserHome .\.gradle-agent-home`.

Latest local evidence:

- Gradle local gate: `BUILD SUCCESSFUL`
- `check-native-libs.ps1`: passed
- `test-device-smoke-result-checker.ps1`: passed, including invalid screenshot PNG, missing device metadata, empty device metadata, and blank snapshot rejection

Validated outputs:

- `vectorra-maps-debug.aar`
- `vectorra-maps-release.aar`
- `vectorra-maps-turf-debug.aar`
- `vectorra-maps-turf-release.aar`
- `vectorra-sample-arm64-v8a-debug.apk`
- `vectorra-sample-x86_64-debug.apk`
- `vectorra-sample-universal-debug.apk`

## Runtime Device Gate

Current device result: not passed.

Runtime command:

```powershell
.\tools\run-device-smoke.ps1
.\tools\check-device-smoke-result.ps1
```

The runtime script performs cold start, sample smoke actions, home/resume, force-stop/recreate, screenshot capture, UI dump, logcat export, adb exit-code checks, non-empty artifact checks, device metadata recording, and action/lifecycle start-end reporting. The logcat export includes Vectorra tags plus Android crash/ANR tags. The result checker also verifies screenshot PNG signature, dimensions, sample snapshot `nonblank=true` log output, and non-empty required device metadata keys, including Vulkan metadata.

The runtime script automatically selects the matching split sample APK from the online device ABI list unless `-Apk` is provided.

Latest adb state:

```text
4tqoz9bmfu8t8pr8 offline
```

Do not declare Android 1.0 release readiness until a real Vulkan-capable Android device completes the [ABI and device matrix](abi-device-matrix.md).

## Release Risk

The current source tree has passed local unit, build, AAR publication, published-AAR consumption, and ABI packaging checks. It has not passed the required runtime device smoke for cold start, map-ready status, bad-resource UI, raster/DEM/MVT/MBTiles/GeoJSON/draw/location/snapshot/3D Tiles flows, pause/resume or rotation, and destroy/recreate.
