# Release And Versioning

Vectorra Maps uses semantic-version-shaped Beta versions.

## Published Version

```text
0.5.0-beta.1
```

`VECTORRA_VERSION` in `gradle.properties` is still `0.5.0-beta.1`. Integration coordinates and Maven-local published-AAR verification use this version until the project version is intentionally bumped.

## Current Development Target

The current source tree contains Beta APIs annotated for the next unpublished development target:

- [0.8.0-beta.1 development release notes](release-notes-0.8.0-beta.1.md)

Do not treat `0.8.0-beta.1` as published until:

- `VECTORRA_VERSION` is bumped;
- both SDK artifacts are republished;
- the sample is rebuilt from the republished AARs;
- release notes and integration docs are updated from development notes to published release notes.

## Coordinates

```text
com.vectorra:vectorra-maps:0.5.0-beta.1
com.vectorra:vectorra-maps-turf:0.5.0-beta.1
```

## Beta Version Rules

- Patch Beta increments such as `0.1.0-beta.2` are for fixes, docs, sample improvements, and small API clarifications.
- Minor Beta increments such as `0.2.0-beta.1` are for feature additions such as MBTiles, MVT, terrain improvements, or 3D Tiles milestones.
- Breaking API changes are allowed before `1.0.0`, but they should be called out explicitly.
- Public artifacts should use the same version across `vectorra-maps` and `vectorra-maps-turf`.

## Release Checklist

Before publishing a Beta artifact:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-maps:assembleDebugAndroidTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat assembleDebug
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

The local Android acceptance script runs this gate plus the SDK instrumentation APK build, ABI native-library content check, instrumentation APK no-native-library check, and runtime smoke result checker self-test. The smoke checker self-test validates a complete fixture and rejects crash logs, missing or out-of-order action markers, missing or empty metadata, APK/ABI mismatches, missing or mismatched artifact records, invalid screenshots, blank snapshots, and missing 3D Tiles close-zoom snapshots:

```powershell
.\tools\check-android-acceptance.ps1
```

The published-AAR sample build is required because it catches missing Maven metadata, native libraries, resources, and consumer rules that project dependency builds can hide.

For Android 1.0 hardening, also run the [ABI and device matrix](abi-device-matrix.md) before declaring release readiness. If a device gate cannot run, record it as a release risk instead of silently treating the artifact as fully verified.

The current Android 1.0 gate status is tracked in the [Android 1.0 acceptance record](android-1.0-acceptance.md).
