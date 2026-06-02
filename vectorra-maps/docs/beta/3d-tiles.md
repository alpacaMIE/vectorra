# 3D Tiles

Vectorra Maps `0.5.0-beta.1` includes a Beta 3D Tiles tileset data API for buildings and models.

## Scope

Supported:

- Parse `tileset.json` strings.
- Read `asset.version`, `asset.tilesetVersion`, and `asset.generator`.
- Read top-level and tile `geometricError`.
- Parse root and child tiles.
- Parse `region`, `box`, and `sphere` bounding volumes.
- Parse tile `transform` matrices.
- Parse `ADD` and `REPLACE` refine modes.
- Parse `content.uri`, legacy `content.url`, and 3D Tiles 1.1-style `contents`.
- Resolve relative content URIs against a supplied tileset base URI.
- Detect `.b3dm`, `.glb`, `.gltf`, and unknown content kinds.
- Collect basic tileset statistics.

Not supported in this Beta:

- Native 3D Tiles rendering.
- Network streaming and cache scheduling for 3D Tiles.
- b3dm, i3dm, pnts, cmpt, glTF, or GLB payload parsing.
- Vulkan mesh upload.
- Screen-space-error LOD selection.
- Terrain clamping.
- Styling and feature metadata evaluation.

## Usage

```kotlin
val tileset = VectorraTileset3DParser.parse(
    json = tilesetJson,
    baseUri = "https://example.com/tiles/city/tileset.json"
)

val rootContent = tileset.root.content
val statistics = tileset.statistics()
```

`baseUri` is optional. When present, relative content URIs are resolved with standard URI resolution. Absolute content URIs are preserved.

## Renderer Boundary

This API is intentionally data-first. It gives integrators a stable way to inspect 3D Tiles packages before the native Android renderer path exposes a production-ready 3D Tiles layer.
