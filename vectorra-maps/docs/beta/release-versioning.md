# Release And Versioning

Vectorra Maps uses semantic-version-shaped Beta versions.

## Current Version

```text
0.3.0-beta.1
```

## Coordinates

```text
com.vectorra:vectorra-maps:0.3.0-beta.1
com.vectorra:vectorra-maps-turf:0.3.0-beta.1
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
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

The published-AAR sample build is required because it catches missing Maven metadata, native libraries, resources, and consumer rules that project dependency builds can hide.
