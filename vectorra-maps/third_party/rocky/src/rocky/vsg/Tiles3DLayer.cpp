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

    _vsgctx = vsgctx;
    if (!_vsgctx)
        return Failure("Tiles3DLayer: vsgctx is null — set layer->vsgctx before calling open()");

    // Cancellation token: set to true in closeImplementation so the async callback
    // does not touch a destroyed layer.
    _openCanceled = std::make_shared<std::atomic<bool>>(false);

    // Snapshot immutable params for the background lambda
    const auto uri         = tilesetURI;
    const auto ctx         = _vsgctx;
    const auto maxSSE      = maximumScreenSpaceError;
    const auto maxTiles_   = maximumLoadedTiles;
    const auto maxBytes_   = maximumResidentBytes;
    const auto vpHeight    = _viewportHeight.load();
    // Raw pointer is safe here: the cancellation token prevents the callback
    // from running after closeImplementation() signals cancellation.
    Tiles3DLayer* rawSelf  = this;
    auto canceled          = _openCanceled;

    // Dispatch fetch+parse to a background thread so the calling thread
    // (typically the Android UI thread) is not blocked, avoiding ANR.
    auto& jobs = io.services().jobs;
    jobs::context jobCtx;
    jobCtx.name = uri.full();
    jobCtx.can_cancel = false; // run to completion; we manage cancellation ourselves

    _openFuture = jobs.dispatch(
        [rawSelf, uri, io, ctx, maxSSE, maxTiles_, maxBytes_, vpHeight, canceled](Cancelable&) -> int
    {
        // 1. Fetch tileset.json — skip the in-memory content cache (a root
        // tileset.json can be tens of MB and is parsed exactly once; the
        // disk cache still serves it on the next app run)
        auto localIO = io;
        localIO.useContentCache = false;
        auto rr = uri.read(localIO);
        if (!rr || canceled->load())
        {
            if (!rr)
                TILES3D_LOG_E("Tiles3DLayer: fetch failed uri=%s error=%s",
                    uri.full().c_str(), rr.error().string().c_str());
            return 0;
        }
        TILES3D_LOG_I("Tiles3DLayer: fetched tileset.json uri=%s bytes=%zu",
            uri.full().c_str(), rr.value().content.data.size());

        // 2. Parse tileset.json
        auto tileset = Tiles3DTileset::fromJSON(rr.value().content.data, uri.full());
        if (!tileset || canceled->load())
        {
            if (!tileset)
                TILES3D_LOG_E("Tiles3DLayer: parse failed uri=%s error=%s",
                    uri.full().c_str(), tileset.error().string().c_str());
            return 0;
        }

        // 3. Build the scene graph node
        auto tilesNode = Tiles3DNode::create(
            tileset.value(), uri.context(), ctx, maxSSE, maxTiles_);
        tilesNode->maximumResidentBytes = maxBytes_;
        tilesNode->viewportHeight.store(vpHeight);

        TILES3D_LOG_I("Tiles3DLayer: opened uri=%s geometricError=%.1f",
            uri.full().c_str(), tileset.value().geometricError);

        // 4. Wire into the live scene graph on the update pass.
        // Check the token before touching rawSelf — if the layer was closed
        // between step 3 and now, rawSelf might be invalid.
        ctx->onNextUpdate([rawSelf, tilesNode, canceled](VSGContext vsgCtx) {
            if (canceled->load())
                return;
            rawSelf->_tilesNode = tilesNode;
            rawSelf->node = tilesNode;
            vsgCtx->requestFrame();
        });
        return 0;
    }, jobCtx);

    return ResultVoidOK;
}

void Tiles3DLayer::closeImplementation()
{
    if (_openCanceled)
        _openCanceled->store(true);
    _openFuture = {};
    _tilesNode = nullptr;
    node = nullptr;
    NodeLayer::closeImplementation();
}

void Tiles3DLayer::setViewportHeight(float height)
{
    _viewportHeight.store(height);
    if (_tilesNode)
        _tilesNode->viewportHeight.store(height);
}
