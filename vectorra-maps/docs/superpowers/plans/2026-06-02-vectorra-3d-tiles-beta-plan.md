# Vectorra 3D Tiles Buildings And Models Beta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a stable, testable 3D Tiles tileset data API for the Android SDK Beta.

**Architecture:** Keep native renderer behavior unchanged. Add Kotlin models and parser in `com.vectorra.maps.tiles3d`, mark the API as Beta, document the rendering limitation, and verify through unit tests plus the published-AAR sample loop.

**Tech Stack:** Kotlin, Gson, Android Gradle Plugin 8.6.0, JUnit.

---

### Task 1: Tileset Models

**Files:**
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/tiles3d/VectorraTileset3DModels.kt`
- Test: `vectorra-maps/vectorra-maps/src/test/java/com/vectorra/maps/tiles3d/VectorraTileset3DParserTest.kt`

- [ ] Add tileset, asset, tile, content, content kind, refine, bounding volume, and statistics models.
- [ ] Validate non-finite geometric errors, invalid transforms, and blank content URIs.

### Task 2: Tileset Parser

**Files:**
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/tiles3d/VectorraTileset3DParser.kt`
- Modify: `vectorra-maps/vectorra-maps/build.gradle.kts`

- [ ] Add Gson dependency to `:vectorra-maps`.
- [ ] Parse asset, root, children, bounding volumes, transforms, refine modes, `content`, and `contents`.
- [ ] Resolve relative content URIs against an optional base URI.
- [ ] Detect `.b3dm`, `.glb`, `.gltf`, and unknown content kinds.

### Task 3: Docs And Version

**Files:**
- Modify: `vectorra-maps/gradle.properties`
- Modify: `vectorra-maps/README.md`
- Modify: `vectorra-maps/docs/beta/android-aar-integration.md`
- Modify: `vectorra-maps/docs/beta/api-stability.md`
- Modify: `vectorra-maps/docs/beta/release-versioning.md`
- Create: `vectorra-maps/docs/beta/3d-tiles.md`

- [ ] Bump version to `0.5.0-beta.1`.
- [ ] Document API scope, integration sample, limitations, and follow-up renderer milestones.

### Task 4: Verify And Commit

**Files:**
- Verify all modified files.

- [ ] Run:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

- [ ] Commit:

```powershell
git add vectorra-maps
git commit -m "feat: add 3D Tiles tileset parser"
```
