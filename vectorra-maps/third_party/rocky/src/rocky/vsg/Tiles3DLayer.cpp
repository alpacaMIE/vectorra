/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#include "Tiles3DLayer.h"
#include "Tiles3DNode.h"
#include <rocky/Log.h>

#ifdef __ANDROID__
#include <android/log.h>
#define TILES3D_LOG_I(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  "rocky_tiles3d", fmt, ##__VA_ARGS__)
#define TILES3D_LOG_E(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, "rocky_tiles3d", fmt, ##__VA_ARGS__)
#else
#define TILES3D_LOG_I(fmt, ...) Log()->info(fmt, ##__VA_ARGS__)
#define TILES3D_LOG_E(fmt, ...) Log()->error(fmt, ##__VA_ARGS__)
#endif

using namespace ROCKY_NAMESPACE;

Result<> Tiles3DLayer::openImplementation(const IOOptions& io)
{
    if (tilesetURI.empty())
        return Failure("Tiles3DLayer: tilesetURI is empty");

    // 1. Fetch tileset.json via rocky's CURL/cache infrastructure
    auto rr = tilesetURI.read(io);
    if (!rr)
    {
        TILES3D_LOG_E("Tiles3DLayer: fetch failed uri=%s error=%s",
            tilesetURI.full().c_str(), rr.error().string().c_str());
        return rr.error();
    }

    TILES3D_LOG_I("Tiles3DLayer: fetched tileset.json uri=%s bytes=%zu",
        tilesetURI.full().c_str(), rr.value().content.data.size());

    // 2. Parse tileset.json
    auto tileset = Tiles3DTileset::fromJSON(
        rr.value().content.data,
        tilesetURI.full());

    if (!tileset)
    {
        TILES3D_LOG_E("Tiles3DLayer: parse failed uri=%s error=%s",
            tilesetURI.full().c_str(), tileset.error().string().c_str());
        return tileset.error();
    }

    // 3. Require VSGContext (must be set before open() is called)
    _vsgctx = vsgctx;
    if (!_vsgctx)
        return Failure("Tiles3DLayer: vsgctx is null — set layer->vsgctx before calling open()");

    // 4. Build the scene graph node
    _tilesNode = Tiles3DNode::create(
        tileset.value(),
        tilesetURI.context(),
        _vsgctx,
        maximumScreenSpaceError,
        maximumLoadedTiles);

    node = _tilesNode;

    TILES3D_LOG_I("Tiles3DLayer: opened uri=%s geometricError=%.1f",
        tilesetURI.full().c_str(), tileset.value().geometricError);

    return ResultVoidOK;
}

void Tiles3DLayer::closeImplementation()
{
    _tilesNode = nullptr;
    node = nullptr;
    NodeLayer::closeImplementation();
}

void Tiles3DLayer::setViewportHeight(float height)
{
    if (_tilesNode)
        _tilesNode->viewportHeight.store(height);
}
