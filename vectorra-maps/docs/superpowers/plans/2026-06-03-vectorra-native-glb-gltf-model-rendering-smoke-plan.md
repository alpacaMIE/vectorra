# Vectorra Native GLB/GLTF Model Rendering Smoke Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a minimal Android-native smoke path that can render one `.glb`/`.gltf` model layer and manage its lifecycle through Vectorra public API + JNI + C++.

**Architecture:** Keep parser behavior unchanged and introduce a dedicated model render entry in `VectorraMap` and `VectorraMapEngine`. Route model layer calls through `VectorraNative` to a new C++ path in `vectorra_jni.cpp` based on Rocky model primitives.

**Tech Stack:** Kotlin, JNI, C++, Rocky ECS/vsg model node, Android Vulkan, JUnit.

---

### Task 1: Kotlin Model API

**Files:**
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/model/VectorraGlbModelSource.kt`
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/model/VectorraGlbModelLayerOptions.kt`
- Create: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/model/VectorraModelRenderError.kt`
- Test: `vectorra-maps/vectorra-maps/src/test/java/com/vectorra/maps/model/VectorraGlbModelModelsTest.kt`
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/VectorraMap.kt`
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/VectorraMapEngine.kt`

- [x] Add a compact `VectorraGlbModelSource` model with `id`, `uri`, `longitude`, `latitude`, `heightMeters`, `scale`, and `yawDegrees` validation.
- [x] Add `VectorraGlbModelLayerOptions` with optional `visible`, `orientationScale`, and `loadPriority` fields.
- [x] Add a minimal `VectorraModelRenderError` sealed type for `UnsupportedFormat` and `LoadFailure`.
- [x] Add interface methods to `VectorraMap`:
  - `fun addModelLayer(source: VectorraGlbModelSource, options: VectorraGlbModelLayerOptions = VectorraGlbModelLayerOptions())`
  - `fun removeModelLayer(id: String)`
  - `fun setModelLayerVisible(id: String, visible: Boolean)`
- [x] Add temporary API entry to `VectorraMap` for smoke only:
  - `fun add3DTilesModelLayer(source: VectorraGlbModelSource, options: VectorraGlbModelLayerOptions = VectorraGlbModelLayerOptions())`
- [x] Implement these methods in `VectorraMapEngine` using a dedicated `modelLayerSet` list to avoid collisions with raster/terrain layers.

### Task 2: JNI API Surface

**Files:**
- Modify: `vectorra-maps/vectorra-maps/src/main/java/com/vectorra/maps/internal/VectorraNative.kt`
- Modify: `vectorra-maps/vectorra-maps/src/main/cpp/vectorra_jni.cpp`

- [x] Add JNI methods:
  - `external fun addModelLayer(handle: Long, id: String, uri: String, longitude: Double, latitude: Double, heightMeters: Double, scale: Double, yawDegrees: Double, visible: Boolean)`
  - `external fun removeModelLayer(handle: Long, id: String)`
  - `external fun setModelLayerVisible(handle: Long, id: String, visible: Boolean)`
- [x] Add corresponding JNI bindings in `vectorra_jni.cpp`.
- [x] Add `ModelLayerConfig` storage inside the native engine and thread-safe load/remove operations.
- [x] Add `rocky::Model`/`rocky::Transform` creation for one-shot model rendering:
  - Load from URI (relative or absolute path supported).
  - Create an entity, emplace `Model`, `Transform`, and optional `Label`/hit helper metadata.
  - Ensure entity is destroyed on `removeModelLayer`.

### Task 3: Native System Activation in Rocky

**Files:**
- Modify: `vectorra-maps/vectorra-maps/src/main/cpp/vectorra_jni.cpp`

- [x] Enable Rocky model rendering system in the app graph:
  - include `rocky/vsg/ecs/Model.h`
  - include `rocky/vsg/ecs/ModelSystem.h`
  - add `ModelSystemNode` creation to `VectorraNativeEngine::setSurface`.
- [x] Confirm the render loop remains safe with `systemsNode->add(...)` and no change to current raster/terrain paths.
- [x] Add a dedicated log tag path for model load/start/complete/errors.

### Task 4: Sample Smoke + Error Path

**Files:**
- Modify: `vectorra-maps/vectorra-sample/src/main/java/com/vectorra/sample/MainActivity.kt`

- [x] Add a simple sample model layer load flow behind a flag for native rendering smoke (single URL/path model).
- [x] Add a visible “load model” and “toggle model” action to verify add/remove/visibility.
- [x] Add one known-safe public model asset URL or bundled `assets` model for offline verification.
- [x] Capture and display model load errors through existing error callback surfaces.
- [ ] Manual device validation currently runs on device, but visual model rendering is not yet confirmed.
  - First run failed because `vsgXchange_DIR` was `NOTFOUND`; fixed bootstrap dependency by adding `vsgxchange[assimp,freetype,draco]`.
  - After rebuilding with `ROCKY_HAS_VSGXCHANGE`, sample installs and starts, `Model` updates sample status, and process has `rocky::ModelSys` worker threads.
  - Screen remains black behind controls and no `model loaded` log appears yet; continue with native model loader/render debugging.

### Task 5: Docs and Changelog

**Files:**
- Modify: `vectorra-maps/docs/beta/3d-tiles.md`
- Modify: `vectorra-maps/README.md`

- [ ] Update 3D Tiles beta docs to state this is the first model-rendering smoke milestone.
- [ ] Add usage snippet for `addModelLayer`/`removeModelLayer` and sample troubleshooting notes.
- [ ] Mark API stability as Beta with explicit compatibility warning.

### Task 6: Verify and Commit

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

- [ ] Do a manual model smoke validation in sample:
  - add model layer
  - confirm render visible
  - hide/show
  - remove model layer
  - trigger at least one load error and check callback text.

- [ ] Commit:

```powershell
git add vectorra-maps
git commit -m "feat: add native GLB/glTF model rendering smoke"
```
