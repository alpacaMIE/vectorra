# Vectorra SDK Beta Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the approved Vectorra SDK Beta capability scope into ordered, independently testable implementation subplans.

**Architecture:** The Beta is delivered as an Android-first SDK in `:vectorra-maps`, with `:vectorra-maps-turf` for geometry utilities and `:vectorra-sample` for integration validation. Implementation proceeds from naming/API foundation to data/rendering features, then offline, docs, and release hardening.

**Tech Stack:** Android Gradle Plugin 8.6.0, Kotlin 2.1.0, Android minSdk 26, NDK 28.2.13676358, CMake 3.22.1, Vulkan, rocky native dependency, JUnit.

---

## Current Workspace Constraint

This checkout has no `.git` metadata at `D:\workspace\code\vectorra` or `D:\workspace\code\vectorra\vectorra-maps`. Worktree creation and commits are unavailable until repository metadata is restored. When git is available, each subplan should be executed on a dedicated branch or worktree and committed at its task boundaries.

## Source Scope

Primary product work stays inside:

- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps-turf`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-sample`
- `D:\workspace\code\vectorra\vectorra-maps\docs`
- `D:\workspace\code\vectorra\vectorra-maps\tools`

Do not modify:

- `D:\workspace\code\vectorra\vectorra-references`
- `D:\workspace\code\vectorra\vectorra-maps\third_party\rocky` unless a later task explicitly targets vendored rocky integration.

## Subplan Sequence

### Task 1: Vectorra Naming Foundation

**Plan file:** `D:\workspace\code\vectorra\vectorra-maps\docs\superpowers\plans\2026-06-02-vectorra-rename-implementation-plan.md`

**Purpose:** Rename SDK-owned `Rocky*` API and implementation names to `Vectorra*`, including Kotlin sources, tests, sample imports, JNI bridge names, native library target, and consumer-rule comments.

**Entry checks:**

- `.\gradlew.bat :vectorra-maps:testDebugUnitTest` should pass or its pre-existing failures should be recorded.
- `.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest` should pass or its pre-existing failures should be recorded.

**Exit checks:**

- No SDK-owned Kotlin source or test file outside `third_party`, `build`, or `src/main/assets/rocky` contains `Rocky`.
- `VectorraNative` loads `vectorra_jni`.
- CMake builds the JNI bridge target as `vectorra_jni`.
- `.\gradlew.bat :vectorra-maps:testDebugUnitTest` passes.
- `.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest` passes.

### Task 2: Public API Shape

**Plan file to create:** `D:\workspace\code\vectorra\vectorra-maps\docs\superpowers\plans\2026-06-02-vectorra-public-api-implementation-plan.md`

**Purpose:** Organize the renamed API around `VectorraMap`, source/layer concepts, query models, terrain options, and offline manager entry points without adding full Mapbox Style compatibility.

**Primary files:**

- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\VectorraMap.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\VectorraMapEngine.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\source`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\layer`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\query`

**Exit checks:**

- Source/layer public APIs exist for raster, DEM, GeoJSON, and future MVT/3D Tiles extension.
- Public API docs describe stable and experimental surface.
- `.\gradlew.bat :vectorra-maps:testDebugUnitTest` passes.

### Task 3: Basic MVT Support

**Plan file to create:** `D:\workspace\code\vectorra\vectorra-maps\docs\superpowers\plans\2026-06-02-vectorra-mvt-implementation-plan.md`

**Purpose:** Add basic vector tile source/layer support with simplified line, fill, circle, and symbol styling plus feature query.

**Primary files:**

- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\source`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\layer`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\internal\VectorraNative.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\cpp\vectorra_jni.cpp`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\test\java\com\vectorra\maps`

**Exit checks:**

- URL-template MVT source can be registered.
- Simplified style layers can be added and removed.
- Already loaded MVT features can be queried at a screen point.
- `.\gradlew.bat :vectorra-maps:testDebugUnitTest` passes.
- `.\gradlew.bat :vectorra-sample:assembleDebug` passes when native dependencies are available.

### Task 4: 3D Terrain

**Plan file to create:** `D:\workspace\code\vectorra\vectorra-maps\docs\superpowers\plans\2026-06-02-vectorra-terrain-implementation-plan.md`

**Purpose:** Make DEM-backed visible 3D terrain a first-class Beta feature with terrain state, terrain errors, exaggeration, pitched browsing, and raster draping.

**Primary files:**

- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\VectorraMap.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\VectorraMapEngine.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\layer`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\internal\VectorraNative.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\cpp\vectorra_jni.cpp`

**Exit checks:**

- DEM source can produce visible terrain.
- Exaggeration updates at runtime.
- Raster imagery displays on terrain.
- Terrain load errors surface to Kotlin callbacks.
- `.\gradlew.bat :vectorra-sample:assembleDebug` passes when native dependencies are available.

### Task 5: 3D Tiles Building and Model Support

**Plan file to create:** `D:\workspace\code\vectorra\vectorra-maps\docs\superpowers\plans\2026-06-02-vectorra-3d-tiles-implementation-plan.md`

**Purpose:** Add building/model-oriented 3D Tiles support for `tileset.json`, bounding volumes, LOD, `b3dm`, and glTF/GLB.

**Primary files:**

- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\source`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\layer`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\internal\VectorraNative.kt`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\cpp\vectorra_jni.cpp`
- native helper files to be created under `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\cpp`

**Exit checks:**

- A representative medium-size building/model tileset loads.
- `b3dm` content reaches glTF/GLB rendering.
- Layer visibility and load errors work.
- LOD selection avoids UI-thread stalls in sample validation.

### Task 6: Offline Cache and MBTiles

**Plan file to create:** `D:\workspace\code\vectorra\vectorra-maps\docs\superpowers\plans\2026-06-02-vectorra-offline-mbtiles-implementation-plan.md`

**Purpose:** Add Beta offline support: cache status, cleanup, region prefetch, MBTiles raster reading, and MBTiles MVT reading.

**Primary files:**

- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\network`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\main\java\com\vectorra\maps\offline`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\src\test\java\com\vectorra\maps\offline`

**Exit checks:**

- Online tiles can be prefetched into disk cache.
- Prefetch exposes progress and cancellation.
- Cache status and cleanup APIs work.
- MBTiles raster and MVT fixtures can be read in tests.
- `.\gradlew.bat :vectorra-maps:testDebugUnitTest` passes.

### Task 7: Beta Sample, Docs, and Release Hardening

**Plan file to create:** `D:\workspace\code\vectorra\vectorra-maps\docs\superpowers\plans\2026-06-02-vectorra-beta-release-implementation-plan.md`

**Purpose:** Prepare external Beta integration materials and release checks.

**Primary files:**

- `D:\workspace\code\vectorra\vectorra-maps\vectorra-sample\src\main\java\com\vectorra\sample\MainActivity.kt`
- `D:\workspace\code\vectorra\vectorra-maps\docs`
- `D:\workspace\code\vectorra\vectorra-maps\tools`
- `D:\workspace\code\vectorra\vectorra-maps\vectorra-maps\build.gradle.kts`

**Exit checks:**

- Sample demonstrates raster, DEM terrain, basic MVT, GeoJSON, drawing or annotations, 3D Tiles, and MBTiles.
- AAR publishing metadata is correct.
- Basic integration guide exists.
- Error callback and diagnostic logging docs exist.
- Versioning policy exists.
- `.\gradlew.bat :vectorra-maps:testDebugUnitTest` passes.
- `.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest` passes.
- `.\gradlew.bat :vectorra-sample:assembleDebug` passes when native dependencies are available.
- `.\gradlew.bat assembleDebug` passes when native dependencies are available.

## Global Verification Commands

Run these from `D:\workspace\code\vectorra\vectorra-maps` whenever the relevant dependencies are available:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat assembleDebug
```

If native build verification fails because `build/vcpkg/scripts/buildsystems/vcpkg.cmake` is missing, run the project bootstrap workflow before native-dependent tasks:

```powershell
.\tools\bootstrap-vcpkg-android.ps1
```

Then rerun the native-dependent Gradle command.
