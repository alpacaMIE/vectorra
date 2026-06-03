# 3D Tiles Runtime Beta

Vectorra Maps now exposes a formal 3D Tiles runtime path through:

- `Vectorra3DTilesSource`
- `Vectorra3DTilesLayer`
- `Vectorra3DTilesOptions`
- `VectorraMap.add3DTilesLayer(...)`
- `VectorraMap.remove3DTilesLayer(...)`

`VectorraMap.add3DTilesModelLayer(...)` is not the 1.0 3D Tiles API. It remains only as a deprecated, experimental model-rendering smoke entry and should not be used for new 3D Tiles integrations.

## Supported Scope

Runtime support:

- Load remote `tileset.json` through the SDK resource fetch path.
- Load local `tileset.json` from `file:` URIs.
- Resolve relative content URIs against the tileset URI.
- Expose loading, loaded, failed, and removed status through the shared `VectorraResourceStatus` contract with `VectorraResourceKind.TILES3D`.
- Parse tile hierarchy, `ADD` and `REPLACE` refine modes, geometric error, tile transforms, and region/box/sphere bounding volumes.
- Run camera-driven traversal with screen-space-error selection, distance/frustum culling, request priorities, and loaded-tile unload decisions.
- Load `.glb` and `.gltf` tile content through the SDK resource fetch/cache path.
- Parse b3dm v1 headers, feature table JSON, batch table offsets, inner GLB payload, `BATCH_LENGTH`, and `RTC_CENTER`.
- Extract b3dm inner GLB payloads to renderer cache files and render them through the same native renderer URI contract.
- Compose tileset/tile transforms and b3dm `RTC_CENTER` into a full renderer transform matrix.
- Re-submit loaded renderer content after Android surface pause/resume so 3D Tiles remain visible after native renderer recreation.

Native renderer contract:

- `Vectorra3DTilesRendererContentInput` is the internal boundary between Kotlin runtime and native renderer.
- Content is submitted by renderer-ready URI, not by native byte arrays.
- Transform input supports full matrix or ECEF placement.
- Native content ids are stable as `<layerId>:<tileId>` and are used for remove/re-add and surface re-submit.

## Not Supported Yet

The Phase 1 beta intentionally does not include:

- `pnts`, `i3dm`, `cmpt`, implicit tiling, or 3D Tiles metadata styling.
- Productized offline 3D Tiles prefetch/cache management.
- Feature picking or per-feature styling for b3dm batch tables.
- Full Mapbox Style JSON integration.
- A public renderer-content API; renderer contracts remain internal.

These are not P0/P1 blockers for the Phase 1 runtime beta.

## Usage

```kotlin
val source = Vectorra3DTilesSource(
    id = "buildings",
    tilesetUri = "https://example.com/tiles/buildings/tileset.json",
    headers = mapOf("Authorization" to "Bearer token")
)

val layer = Vectorra3DTilesLayer(
    id = "buildings-layer",
    sourceId = source.id
)

map.add3DTilesLayer(
    source = source,
    layer = layer,
    options = Vectorra3DTilesOptions(
        maximumScreenSpaceError = 16.0,
        maximumLoadedTiles = 128,
        visible = true
    )
)

map.remove3DTilesLayer("buildings-layer")
```

Status can be observed through the shared resource status listener:

```kotlin
val subscription = map.addResourceStatusListener { status ->
    if (status.kind == VectorraResourceKind.TILES3D) {
        when (status.state) {
            VectorraResourceLoadState.LOADING -> Unit
            VectorraResourceLoadState.LOADED -> Unit
            VectorraResourceLoadState.FAILED -> {
                val message = status.error?.message
            }
            VectorraResourceLoadState.REMOVED -> Unit
        }
    }
}
```

## Phase 1 Acceptance Summary

P1.T0 - Renderer contract:

- Complete for Phase 1.
- Evidence: internal renderer contract validates renderer URI, payload source, visibility, full matrix transform, ECEF placement, and stable native content id.
- JNI/native boundary accepts add/remove content calls and stores content by id.

P1.T1 - Formal API:

- Complete for Phase 1.
- Evidence: `Vectorra3DTilesSource`, `Vectorra3DTilesLayer`, `Vectorra3DTilesOptions`, `add3DTilesLayer`, and `remove3DTilesLayer` are present and used by `vectorra-sample`.
- `add3DTilesModelLayer(...)` remains deprecated and experimental.

P1.T2 - Tileset loading:

- Complete for Phase 1.
- Evidence: remote and local tileset loading tests cover base URI resolution and failure status.
- Device bad-tileset smoke showed visible `tiles3d ... failed` UI through the shared status path.

P1.T3 - Runtime traversal:

- Complete for Phase 1.
- Evidence: unit fixtures cover root/child traversal, `ADD`, `REPLACE`, screen-space error, distance/frustum culling, request priority, budget unload, and tile state transitions.
- Device smoke showed live traversal producing a content request for the sample tileset.

P1.T4 - GLB/GLTF content lifecycle:

- Complete for Phase 1.
- Evidence: unit tests cover remote/local GLB and GLTF content, duplicate suppression while loading/loaded, stale completion after unload, renderer cache URI writing, and unload removal.
- Surface resume tests cover re-submit of already loaded renderer content without another network load task.

P1.T5 - Transform and bounds:

- Complete for Phase 1.
- Evidence: unit fixtures cover transform composition, ECEF/WGS84 bounds, region/box/sphere bounding volumes, tile transform chains, and `RTC_CENTER` composition.

P1.T6 - b3dm support:

- Complete for Phase 1.
- Evidence: unit fixtures cover b3dm magic/version/byte length validation, feature and batch table lengths, padding, GLB offset, `BATCH_LENGTH`, `RTC_CENTER`, remote b3dm extraction, local b3dm extraction, inner GLB cache bytes, payload source, and transform propagation.

P1.T7 - Device smoke:

- Complete for Phase 1 on one physical Vulkan Android device.
- Device: `2312DRAABC`, Android API 35, `arm64-v8a`, Mali-G57 MC2, Vulkan API 1.3.278.
- Formal load smoke showed `tiles3d sample-3d-tiles-layer loaded`, traversal `requests=1`, b3dm extraction to `cache/vectorra-3dtiles-content-cache/...dragon_low.b3dm.glb`, and native registration of `sample-3d-tiles-layer:root`.
- Add/remove/re-add smoke showed native register, native remove, second traversal request, and second native register.
- Bad tileset smoke showed visible failed UI and no renderer content registration.
- Pause/resume smoke showed `surfaceDestroyed`, `surfaceCreated`, `resubmitted 3D Tiles renderer content count=1`, native re-registration, final screenshot, and UI dump with `tiles3d sample-3d-tiles-layer loaded`.
- Exact crash searches found no `FATAL EXCEPTION`, `AndroidRuntime`, `ANR`, `Application Not Responding`, or `failed to register` in the filtered smoke logs.

P1.T8 - Acceptance:

- Complete for Phase 1 beta.
- The formal 3D Tiles entry is `Vectorra3DTiles*`.
- b3dm rendering and `RTC_CENTER` are covered by unit fixtures and physical-device smoke.
- No known P0/P1 blocker remains for moving to Phase 2.

## Verification Commands

Phase 1 verification was run from `vectorra-maps/` with:

```powershell
$env:ANDROID_HOME='C:\Users\myg\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-maps-turf:testDebugUnitTest
.\gradlew.bat -g .\.gradle-agent-home :vectorra-sample:assembleDebug
.\gradlew.bat -g .\.gradle-agent-home assembleDebug
```

