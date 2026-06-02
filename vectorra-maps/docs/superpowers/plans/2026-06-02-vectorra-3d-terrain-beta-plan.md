# Vectorra 3D Terrain Beta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a stable public 3D terrain API wrapper around the existing DEM/elevation renderer path.

**Architecture:** Keep native terrain behavior unchanged. Add focused Kotlin terrain models, route public terrain API methods through existing `addElevationLayer`, `setTerrainExaggeration`, `setLayerVisible`, and `removeLayer`, then update sample and docs.

**Tech Stack:** Kotlin, Android Gradle Plugin 8.6.0, existing Vectorra native DEM terrain bridge, JUnit.

---

### Task 1: Terrain Models

**Files:**
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/terrain/VectorraTerrainModels.kt`
- Test: `vectorra-maps/vectorra-maps/src/test/java/com/vectorra/maps/terrain/VectorraTerrainModelsTest.kt`

- [ ] Add `VectorraDemEncoding`.
- [ ] Add `VectorraTerrainSource` validation for id, template URL, and zoom range.
- [ ] Add `VectorraTerrainOptions` validation and exaggeration clamp helper.

### Task 2: Map API Integration

**Files:**
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/VectorraMap.kt`
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/VectorraMapEngine.kt`

- [ ] Add `addTerrain`.
- [ ] Add `removeTerrain`.
- [ ] Add `setTerrainVisible`.
- [ ] Route terrain source through the existing elevation native call.

### Task 3: Sample And Docs

**Files:**
- Modify: `vectorra-maps/vectorra-sample/src/main/java/com/vectorra/sample/MainActivity.kt`
- Modify: `vectorra-maps/README.md`
- Modify: `vectorra-maps/docs/beta/android-aar-integration.md`
- Modify: `vectorra-maps/docs/beta/api-stability.md`
- Modify: `vectorra-maps/docs/beta/release-versioning.md`
- Create: `vectorra-maps/docs/beta/3d-terrain.md`

- [ ] Bump version to `0.4.0-beta.1`.
- [ ] Update sample to use `VectorraTerrainSource`.
- [ ] Document terrain scope and limitations.

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
git commit -m "feat: add 3D terrain beta API"
```
