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
.\tools\run-device-smoke.ps1
```

The script installs the `arm64-v8a` sample APK, records device properties, performs cold start, runs the sample smoke actions, exercises home/resume and force-stop/recreate lifecycle flows, captures a screenshot and UI dump, and writes logs under `build/device-smoke/`.

By default the script selects the split sample APK from the device ABI list:

- `arm64-v8a` device -> `vectorra-sample-arm64-v8a-debug.apk`
- `x86_64` device -> `vectorra-sample-x86_64-debug.apk`
- otherwise -> `vectorra-sample-universal-debug.apk`

Pass `-Apk <relative-path>` to override this selection.

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
- Snapshot nonblank result
- 3D Tiles load, close zoom, remove/re-add, and bad tileset error
- Pause/resume or rotation
- Destroy/recreate

If adb reports the device as `offline`, do not mark this gate passed. Record it as a release risk and keep the ABI packaging gate separate from runtime smoke.
