# Vectorra 3D Tiles Buildings And Models Beta Design

**Status:** Started by user request on 2026-06-02.

## Goal

Add the first public 3D Tiles Beta surface for buildings and models without over-promising native rendering that is not yet wired through the Android JNI renderer path.

## Scope

This Beta supports parsing and inspecting 3D Tiles `tileset.json` documents in the Android SDK. It covers tileset asset metadata, root and child tiles, geometric error, `ADD` and `REPLACE` refine modes, transforms, content URI resolution, content kind detection, and bounding volumes.

## Non-goals

This step does not render 3D Tiles, stream tile content, parse b3dm/glTF payloads, upload model meshes to Vulkan, perform screen-space-error LOD selection, or integrate terrain clamping. Those are follow-up native renderer milestones.

## Public API

Add `com.vectorra.maps.tiles3d`:

- `VectorraTileset3D`
- `VectorraTileset3DAsset`
- `VectorraTileset3DTile`
- `VectorraTileset3DContent`
- `VectorraTileset3DContentKind`
- `VectorraTileset3DRefine`
- `VectorraTileset3DBoundingVolume`
- `VectorraTileset3DStatistics`
- `VectorraTileset3DParser`

## Data Format

The parser accepts 3D Tiles JSON with `asset.version`, top-level `geometricError`, and `root`. It supports `content.uri`, legacy `content.url`, and 3D Tiles 1.1-style `contents`.

## Validation

The parser rejects missing required tileset fields, non-object roots, invalid numeric arrays, unknown bounding volume shapes, unsupported refine values, non-finite geometric error, and blank content URIs.

## Versioning

This feature bumps Vectorra to `0.5.0-beta.1`.

## Verification

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```
