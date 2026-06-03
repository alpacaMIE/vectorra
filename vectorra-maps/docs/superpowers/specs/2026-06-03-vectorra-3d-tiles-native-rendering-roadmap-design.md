# Vectorra 3D Tiles Native Rendering Roadmap Design

**Status:** Started by user request on 2026-06-03.

## Goal

Turn Vectorra 3D Tiles from "JSON parsing only" into a practical Vulkan-native rendering beta, with the first release path focused on stable GLB/GLTF model smoke rendering and no dependence on Cesium Native.

## Scope

This roadmap covers Android Kotlin + JNI + native C++ integration for rendering model-backed 3D Tiles content using the existing Rocky + Vulkan stack.

- Keep parser/API ownership in `:vectorra-maps` (`com.vectorra.maps.tiles3d`) from the existing beta.
- Add a `VectorraModel*` native render path that can display a single local/remote `*.glb` or `*.gltf` model layer.
- Add the formal `Vectorra3DTilesSource`/`Vectorra3DTilesLayer`/`Vectorra3DTilesOptions` runtime path that reads `VectorraTileset3D`, parses tile hierarchy, and renders loaded model-content tiles in camera space.
- Add selection and unload policy for visible/non-visible tiles in a follow-up milestone.
- Expose error callbacks for model-load/parse/render failures in the same style as existing layer failures.
- Keep all work inside `vectorra-maps/`, avoid `vectorra-references/` modifications and avoid `third_party/rocky` edits unless absolutely required.

## Architecture

- **Parser remains Kotlin-first.** `vectorra-maps.vectorra-maps.src.main.java.com.vectorra.maps.tiles3d` continues to validate and normalize `tileset.json`.
- **Rendering is JNI-first.** New render entry points live in `VectorraMapEngine` → `VectorraNative` → `vectorra_jni.cpp`.
- **Rocky as model runtime.** Rendering relies on `rocky::Application` and `rocky::ModelSystemNode` for GLB/GLTF ingestion.
- **No OpenGL fallback.** Rendering remains Vulkan-only, matching the existing minSdk/Vulkan-first constraint.

## Phases

### Phase 0 - Native Model Smoke

Deliver `addModelLayer/removeModelLayer/setModelLayerVisible` with `.glb`/`.gltf` from URL/path and WGS84 placement. This API validates the native model renderer only; it is not the 3D Tiles API.

- Confirm `ModelSystemNode` is initialized before `app->realize()` in C++.
- Render one model layer as a `rocky::Model` wrapped into a map layer.
- Reuse existing layer lifecycle (`removeLayer`, `setLayerVisible`) as a compatibility implementation.

### Phase 1 - Static 3D Tiles Content Rendering

Consume parsed `VectorraTileset3D` and render model nodes from tile `content` URIs.

- Tile traversal is first-depth/recursive with fixed max depth/initial depth.
- Content URI resolution uses existing parser base URI rules.
- Non-model tile contents are skipped with explicit errors (not treated as success).
- Tile transform and tileset transform are applied exactly once and validated against a fixed fixture with known longitude, latitude, elevation, orientation, and debug bounds.
- `region`, `box`, and `sphere` bounds expose WGS84/ECEF debug values and deterministic screen-position checks under a fixed camera.

### Phase 2 - Selection and LOD

Add camera/screen/viewport-driven tile selection and background load queue.

- Add bounds/screen-space checks before scheduling load.
- Respect max tile count and basic cancellation.
- Keep tile refinement deterministic for `ADD` and `REPLACE`.

### Phase 3 - b3dm + Legacy Payload Support

Add `.b3dm` support by extracting the inner glTF payload and passing it to the same model renderer.

- Support standard binary header parsing first.
- Keep error reporting explicit for unsupported header combinations.

### Phase 4 - Long-Horizon Hardening

- Add point cloud/i3dm/cmpt support in later beta milestones.
- Evaluate Cesium Native integration only when Rocky-only path no longer covers planned coverage.

## Public API Direction

Android 1.0 has one formal 3D Tiles API path: `Vectorra3DTilesSource` + `Vectorra3DTilesLayer` + `Vectorra3DTilesOptions`.

The native model smoke API remains explicit and narrow:

- `VectorraGlbModelSource` with ID/URI/longitude/latitude/elevation/scale.
- `VectorraModelLayerOptions` with visibility and optional topocentric tuning.
- `VectorraMap.addModelLayer(...)`
- `VectorraMap.removeModelLayer(id)`
- `VectorraMap.setModelLayerVisible(id, visible)`

Existing `VectorraMap.add3DTilesModelLayer(...)` is a transitional smoke entry point only. New 3D Tiles runtime behavior must be implemented behind `Vectorra3DTiles*`; `add3DTilesModelLayer(...)` should be removed from formal integration docs or marked deprecated/experimental before 1.0.

## Non-goals

- No POI search, geocoding, path planning, navigation, or traffic.
- No terrain-drivens clamping in the first phase.
- No full Cesium-style streaming protocol in phase 0.
- No support for vector tile symbols/signing/filters inside 3D Tiles content.

## Implementation Risks

- Rocky model loading requires runtime model reader availability; if `vsgxchange` reader set is incomplete in C++ build variants, GLB/GLTF load failures may be expected and must be surfaced clearly.
- Heavy tile trees can exceed frame budget without selection throttling; phase 0 must remain bounded by hardcoded test limits.
- b3dm byte alignment and extension fields are not yet handled; phase 3 should treat unsupported variants as explicit errors.

## Verification

Use the existing 3D terrain/3D tiles beta verification baseline and add native model smoke gates:

```powershell
.\gradlew.bat :vectorra-maps:testDebugUnitTest
.\gradlew.bat :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat :vectorra-sample:assembleDebug
.\gradlew.bat :vectorra-maps:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-maps-turf:publishReleasePublicationToMavenLocal
.\gradlew.bat :vectorra-sample:assembleDebug "-Pvectorra.sample.usePublishedAar=true"
```

Phase 0 also requires a minimal local manual run-through:

- Load one GLB model layer.
- Confirm visible in sample at default camera.
- Toggle visibility, remove layer, and restore without crash.
- Confirm load errors flow to the sample error path.

P1 and P4 additionally require a native smoke matrix:

- Record device model, Android API, ABI, GPU, and Vulkan capability.
- Load a fixed building/model tileset through `Vectorra3DTiles*`.
- Verify transform/bounds fixture placement under a deterministic camera.
- Toggle visibility, remove/re-add, rotate or pause/resume, snapshot non-empty, and destroy/recreate without crash.
- Confirm bad tileset and bad content URI errors flow to the sample error path.
