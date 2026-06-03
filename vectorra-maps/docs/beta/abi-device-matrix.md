# ABI And Device Matrix

This page records the Android 1.0 ABI packaging and device-smoke gate.

## Local ABI Packaging Gate

Run from `vectorra-maps/`:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat assembleDebug
.\tools\check-native-libs.ps1
```

The sample debug build must produce:

- `vectorra-sample-arm64-v8a-debug.apk`
- `vectorra-sample-x86_64-debug.apk`
- `vectorra-sample-universal-debug.apk`

Expected APK native libraries:

| Artifact | Required native entries |
| --- | --- |
| `vectorra-sample-arm64-v8a-debug.apk` | `lib/arm64-v8a/librocky.so`, `lib/arm64-v8a/libvectorra_jni.so` |
| `vectorra-sample-x86_64-debug.apk` | `lib/x86_64/librocky.so`, `lib/x86_64/libvectorra_jni.so` |
| `vectorra-sample-universal-debug.apk` | all `arm64-v8a` and `x86_64` entries above |

Expected SDK AAR native libraries:

| Artifact | Required native entries |
| --- | --- |
| `vectorra-maps-debug.aar` | `jni/arm64-v8a/librocky.so`, `jni/arm64-v8a/libvectorra_jni.so`, `jni/x86_64/librocky.so`, `jni/x86_64/libvectorra_jni.so` |
| `vectorra-maps-release.aar` | `jni/arm64-v8a/librocky.so`, `jni/arm64-v8a/libvectorra_jni.so`, `jni/x86_64/librocky.so`, `jni/x86_64/libvectorra_jni.so` |

## Device Smoke Matrix

At least one real Vulkan-capable Android device must run the full smoke before release readiness is declared.

Run from `vectorra-maps/` after `adb devices -l` shows exactly one `device` entry:

```powershell
.\tools\test-device-smoke-result-checker.ps1
.\tools\run-device-smoke.ps1
```

The script first requires `adb devices -l` to complete successfully, then installs the `arm64-v8a` sample APK, records device properties, performs cold start, runs the sample smoke actions, exercises home/resume and force-stop/recreate lifecycle flows, runs a `post-recreate-snapshot` action, captures a screenshot and UI dump, writes logs under `build/device-smoke/`, and fails if an adb step fails or if the screenshot, UI dump, or logcat artifact is missing or empty. The logcat artifact includes Vectorra tags plus Android crash/ANR tags. The text report includes start/end records for every sample action and lifecycle step.
The smoke contract self-test verifies that the runner action list, result-checker required action list, fixture action list, sample action constants, and post-recreate snapshot markers stay aligned.
The checker self-test runs against synthetic local fixtures so the result verifier is known to catch crash-log, missing-action, out-of-order action/lifecycle markers, missing post-recreate snapshot markers, missing post-recreate snapshot logs, invalid-screenshot, missing-device-metadata, empty-device-metadata, install/installed APK mismatch, APK/ABI mismatch, missing artifact byte record, mismatched artifact path, blank-snapshot, and missing 3D Tiles zoom-snapshot failures before the device run.

After a run completes, verify the generated artifacts:

```powershell
.\tools\check-device-smoke-result.ps1
```

This validates non-empty device metadata records, including the Vulkan metadata line, install/installed APK consistency, installed APK compatibility with the reported ABI list, ordered action and lifecycle completion records through the `post-recreate-snapshot` action, positive-byte screenshot/UI/log artifact records that point to the checked files, screenshot PNG signature and dimensions, regular `Snapshot ... nonblank=true` log output, `Post-recreate snapshot ... nonblank=true` log output, 3D Tiles close-zoom snapshot `nonblank=true` log output, UI package ownership, and common crash patterns.

By default the script selects the split sample APK from the device ABI list:

- `arm64-v8a` device -> `vectorra-sample-arm64-v8a-debug.apk`
- `x86_64` device -> `vectorra-sample-x86_64-debug.apk`
- otherwise -> `vectorra-sample-universal-debug.apk`

The ABI query must complete successfully before automatic APK selection continues.

Pass `-Apk <relative-path>` to override this selection.

The UI dump must contain the `com.vectorra.sample` package before the runtime smoke is accepted.

Record:

- Device model
- Android API level
- ABI
- GPU renderer
- Vulkan API version
- Installed APK name

Smoke actions:

- Cold start and map-ready status
- Bad model or bad 3D Tiles error UI
- Raster or imagery load
- DEM terrain
- MVT load, pan, hidden layer, remove/re-add, and center query
- MBTiles vector source
- GeoJSON query
- Draw point, line, polygon, and clear
- Location indicator, heading follow, and clear
- Initial and post-recreate snapshot nonblank results
- 3D Tiles close-zoom snapshot nonblank result
- 3D Tiles load, close zoom, remove/re-add, and bad tileset error
- Pause/resume or rotation
- Destroy/recreate

If adb reports the device as `offline`, do not mark this gate passed. Record it as a release risk and keep the ABI packaging gate separate from runtime smoke.
