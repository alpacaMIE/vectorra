/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#include "Tiles3DNode.h"
#include <rocky/vsg/FutureNode.h>
#include <rocky/Log.h>

#include <vsg/all.h>

#ifdef __ANDROID__
#include <android/log.h>
#define TILES3D_LOG_I(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  "rocky_tiles3d", fmt, ##__VA_ARGS__)
#define TILES3D_LOG_E(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, "rocky_tiles3d", fmt, ##__VA_ARGS__)
#else
#define TILES3D_LOG_I(fmt, ...) Log()->info(fmt, ##__VA_ARGS__)
#define TILES3D_LOG_E(fmt, ...) Log()->error(fmt, ##__VA_ARGS__)
#endif

using namespace ROCKY_NAMESPACE;

// ---------------------------------------------------------------------------
// Tiles3DNode
// ---------------------------------------------------------------------------

Tiles3DNode::Tiles3DNode(
    const Tiles3DTileset& tileset,
    const URI::Context& uriContext,
    VSGContext vsgctx,
    float maxSSE,
    unsigned maxTiles)
    : _uriContext(uriContext)
    , _vsgctx(vsgctx)
    , maximumScreenSpaceError(maxSSE)
    , maximumLoadedTiles(maxTiles)
{
    _root = vsg::Group::create();
    addChild(_root);

    if (tileset.root)
    {
        auto rootTile = Tile3DNode::create(this, *tileset.root, /*immediateLoad=*/true);
        _root->addChild(rootTile);
    }

    // Sentinel at end of tracker for LRU expiry boundary
    _tracker.push_back(nullptr);
}

void Tiles3DNode::touchTile(Tile3DNode* tile)
{
    std::lock_guard<std::mutex> lock(_trackerMutex);
    if (tile->_trackerItrValid)
        _tracker.erase(tile->_trackerItr);

    // Insert before the sentinel (second-to-last = most recently used)
    auto sentinelItr = std::prev(_tracker.end());
    _tracker.insert(sentinelItr, tile);
    tile->_trackerItr = std::prev(sentinelItr);
    tile->_trackerItrValid = true;
}

void Tiles3DNode::expireTiles(uint64_t frameNumber, double referenceTime) const
{
    if (frameNumber <= _lastExpireFrame)
        return;
    _lastExpireFrame = frameNumber;

    std::lock_guard<std::mutex> lock(_trackerMutex);

    auto sentinelItr = std::prev(_tracker.end());
    auto itr = _tracker.begin();

    // Pass 1: evict tiles older than max age (front of LRU = oldest)
    while (_tracker.size() > static_cast<size_t>(maximumLoadedTiles) + 1u && itr != sentinelItr)
    {
        Tile3DNode* tile = *itr;
        if (tile)
        {
            const double age = referenceTime - tile->_lastCulledTime;
            if (tile->autoUnload && age >= _maxTileAge && tile->unloadContent())
            {
                tile->_trackerItrValid = false;
                itr = _tracker.erase(itr);
                continue;
            }
        }
        ++itr;
    }

    // Pass 2: if still over limit, force-evict the oldest tiles regardless of age
    itr = _tracker.begin();
    while (_tracker.size() > static_cast<size_t>(maximumLoadedTiles) + 1u && itr != sentinelItr)
    {
        Tile3DNode* tile = *itr;
        if (tile && tile->autoUnload && tile->unloadContent())
        {
            tile->_trackerItrValid = false;
            itr = _tracker.erase(itr);
        }
        else
        {
            ++itr;
        }
    }
}

void Tiles3DNode::traverse(vsg::RecordTraversal& rv) const
{
    // Capture the ECEF-space view frustum BEFORE any tile-local transforms are applied.
    // At this point modelviewMatrixStack.top() = view-only matrix (no model transforms),
    // giving us the correct world-space (ECEF) frustum for bounding-sphere tests.
    if (auto* state = rv.getState())
    {
        vsg::Frustum projFrustum(vsg::Frustum{}, state->projectionMatrixStack.top());
        _ecefFrustum.set(projFrustum, state->modelviewMatrixStack.top());
    }

    // Expire old tiles once per frame (analogous to OSGEarth's UPDATE_VISITOR pass)
    if (rv.getFrameStamp())
    {
        const auto frame = rv.getFrameStamp()->frameCount;
        expireTiles(frame, rv.getFrameStamp()->simulationTime);
    }

    Inherit::traverse(rv);
}

void Tiles3DNode::traverse(vsg::Visitor& v) { Inherit::traverse(v); }
void Tiles3DNode::traverse(vsg::ConstVisitor& v) const { Inherit::traverse(v); }


// ---------------------------------------------------------------------------
// Tile3DNode
// ---------------------------------------------------------------------------

Tile3DNode::Tile3DNode(
    Tiles3DNode* tilesetNode,
    const Tiles3DTile& tile,
    bool immediateLoad)
    : _tilesetNode(tilesetNode)
    , _tile(tile)
{
    // Apply tile's local transform (column-major matrix from tileset.json)
    if (_tile.transform.has_value())
        this->matrix = *_tile.transform;

    _boundingSphere = _tile.boundingVolume.asBoundingSphere();

    if (immediateLoad && _tile.content.has_value())
    {
        requestContent();
        resolveContent();
    }
}

bool Tile3DNode::hasContent() const
{
    return _tile.content.has_value();
}

bool Tile3DNode::isContentReady() const
{
    return _content != nullptr;
}

void Tile3DNode::requestContent() const
{
    if (_contentRequested || !_tile.content.has_value())
        return;

    _contentRequested = true;

    auto& io = _tilesetNode->vsgContext()->io;
    auto opts = _tilesetNode->vsgContext()->readerWriterOptions;
    const auto uri = _tile.content->resolved;
    const auto uriContext = _tilesetNode->uriContext();

    auto load = [uri, uriContext, io, opts](Cancelable& c) -> NodeResult
    {
        if (c.canceled())
            return Failure_OperationCanceled;

        URI u(uri, uriContext);
        auto rr = u.read(io);
        if (!rr)
            return rr.error();

        std::filesystem::path path(uri);
        auto localOpts = opts ? vsg::clone(opts) : vsg::Options::create();
        localOpts->extensionHint = path.extension();
        if (localOpts->extensionHint.empty())
            localOpts->extensionHint = rr.value().content.type;

        std::istringstream buf(rr.value().content.data);
        auto node = vsg::read_cast<vsg::Node>(buf, localOpts);
        if (!node)
            return Failure("Tiles3D: vsg::read_cast failed for " + uri);

        return node;
    };

    auto& j = io.services().jobs;
    // 16 threads: GLB loading is network-I/O bound, more threads drain the queue faster
    jobs::context ctx{ uri, j.get_pool("rocky::Tiles3DNode", 16) };
    _loadFuture = j.dispatch(load, ctx);
    TILES3D_LOG_I("Tile3DNode: requestContent dispatched uri=%s", uri.c_str());
}

void Tile3DNode::resolveContent() const
{
    if (!_loadFuture.available())
        return;

    if (_loadFuture->failed())
    {
        TILES3D_LOG_E("Tile3DNode load failed: %s", _loadFuture->error().string().c_str());
        _loadFuture = {};
        return;
    }

    auto node = _loadFuture->value();
    _loadFuture = {};

    if (!node)
        return;

    auto* vsgCtx = _tilesetNode->vsgContext();
    if (vsgCtx)
    {
        // Compile during the update pass (between frames) — not during record traversal.
        // Calling compile() inline in traverse() races with in-progress command recording,
        // causing texture descriptors to be incomplete when the first draw executes.
        // Use ref_ptr so the tile stays alive until the lambda fires.
        _compilePending.store(true);
        vsg::ref_ptr<Tile3DNode> self(const_cast<Tile3DNode*>(this));
        vsgCtx->onNextUpdate([self, node](VSGContext ctx) {
            if (!self->_compilePending.load())
                return; // was evicted before compile ran
            ctx->compile(node);
            self->_content = node;
            self->_compilePending.store(false);
            TILES3D_LOG_I("Tile3DNode: content compiled node=%p", (void*)node.get());
        });
    }
    else
    {
        _content = node;
    }
}

bool Tile3DNode::unloadContent()
{
    // Nothing to unload if content isn't loaded and no load is in flight
    if (!_content && !_loadFuture.available() && !_compilePending.load())
        return false;

    _content = nullptr;
    _loadFuture = {};
    _contentRequested = false;
    _compilePending.store(false); // cancel pending onNextUpdate compile
    return true;
}

void Tile3DNode::createChildren() const
{
    if (_childrenCreated)
        return;
    _childrenCreated = true;

    if (_tile.children.empty())
        return;

    _childGroup = vsg::Group::create();
    for (const auto& childData : _tile.children)
        _childGroup->addChild(Tile3DNode::create(_tilesetNode, *childData));
}

bool Tile3DNode::allChildrenReady() const
{
    if (!_childGroup)
        return false;

    for (const auto& child : _childGroup->children)
    {
        auto* ct = child->cast<Tile3DNode>();
        if (ct && ct->hasContent() && !ct->isContentReady())
            return false;
    }
    return true;
}

bool Tile3DNode::intersectsFrustum(const vsg::RecordTraversal& rv) const
{
    (void)rv;
    if (_boundingSphere.radius <= 0.0)
        return true;

    // Test against the ECEF frustum captured in Tiles3DNode::traverse() before any
    // tile transforms were pushed. All bounding volumes in the NLSC dataset are
    // region-type (geographic→ECEF), so this is always the correct space to test.
    return _tilesetNode->_ecefFrustum.intersect(_boundingSphere);
}

double Tile3DNode::computeScreenSpaceError(const vsg::RecordTraversal& rv) const
{
    if (_tile.geometricError <= 0.0)
        return 0.0;

    auto* state = const_cast<vsg::RecordTraversal&>(rv).getState();
    if (!state)
        return 0.0;

    // --- Distance in eye space ---
    // MV matrix = view * any-parent-transforms (MatrixTransform already applied by VSG)
    const vsg::dmat4& mv = state->modelviewMatrixStack.top();

    vsg::dvec4 eyeCenter4 = mv * vsg::dvec4(_boundingSphere.center, 1.0);
    vsg::dvec3 eyeCenter(eyeCenter4.x, eyeCenter4.y, eyeCenter4.z);
    double dist = vsg::length(eyeCenter) - _boundingSphere.radius;
    dist = std::max(dist, 0.0000001);

    // --- SSE denominator from projection matrix ---
    // For perspective: proj[1][1] = cot(fovY/2) → sseDenom = 2/proj[1][1]
    double sseDenom = 1.0;
    {
        const vsg::dmat4& proj = state->projectionMatrixStack.top();
        if (std::abs(proj[1][1]) > 1e-10)
            sseDenom = 2.0 / std::abs(proj[1][1]);  // Vulkan: proj[1][1] < 0
    }

    const double vh = static_cast<double>(_tilesetNode->viewportHeight.load());
    return (_tile.geometricError * vh) / (dist * sseDenom);
}

void Tile3DNode::traverse(vsg::RecordTraversal& rv) const
{
    if (!intersectsFrustum(rv))
        return;

    // Resolve any completed async load
    resolveContent();

    // Lazily create child nodes
    if (!_childrenCreated && !_tile.children.empty())
        createChildren();

    // Check children readiness
    const bool childrenReady = allChildrenReady();
    const double sse = computeScreenSpaceError(rv);
    // Per 3D Tiles spec: tiles with no content are spatial hierarchy nodes and
    // must always recurse to children to show anything at all.
    const bool needsRefinement = !hasContent() || sse > _tilesetNode->maximumScreenSpaceError;

    if (needsRefinement && childrenReady && _childGroup)
    {
        // ADD: render parent content too (parent stays in LRU when ADD mode)
        if (_content && _tile.refine == Tiles3DRefine::ADD)
        {
            _tilesetNode->touchTile(const_cast<Tile3DNode*>(this));
            if (rv.getFrameStamp())
            {
                _lastCulledFrame = rv.getFrameStamp()->frameCount;
                _lastCulledTime  = rv.getFrameStamp()->simulationTime;
            }
            _content->accept(rv);
        }

        _childGroup->accept(rv);

        // Pre-request content for visible children only — requesting off-screen
        // children wastes compile budget and bypasses the frustum culling in their
        // own traverse(), since requestContent() is called without entering traverse().
        for (const auto& c : _childGroup->children)
        {
            if (auto* ct = c->cast<Tile3DNode>())
                if (ct->intersectsFrustum(rv))
                    ct->requestContent();
        }
    }
    else
    {
        // This tile IS the leaf being rendered — touch LRU so it stays alive
        if (hasContent())
        {
            _tilesetNode->touchTile(const_cast<Tile3DNode*>(this));
            if (rv.getFrameStamp())
            {
                _lastCulledFrame = rv.getFrameStamp()->frameCount;
                _lastCulledTime  = rv.getFrameStamp()->simulationTime;
            }
        }

        // Render this tile's content
        if (_content)
            _content->accept(rv);
        else
            requestContent();

        // Pre-fetch children while waiting (so they're ready when SSE grows).
        // Guard with frustum check — off-screen children must not be loaded,
        // as they bypass the per-tile frustum test since traverse() is never called.
        if (needsRefinement && _childGroup)
        {
            for (const auto& c : _childGroup->children)
            {
                if (auto* ct = c->cast<Tile3DNode>())
                {
                    if (ct->intersectsFrustum(rv))
                    {
                        ct->requestContent();
                        ct->resolveContent();
                    }
                }
            }
        }
    }
}

void Tile3DNode::traverse(vsg::Visitor& v)
{
    if (_content)   _content->accept(v);
    if (_childGroup) _childGroup->accept(v);
}

void Tile3DNode::traverse(vsg::ConstVisitor& v) const
{
    if (_content)   _content->accept(v);
    if (_childGroup) _childGroup->accept(v);
}
