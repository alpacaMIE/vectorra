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
    : maximumScreenSpaceError(maxSSE)
    , maximumLoadedTiles(maxTiles)
    , _uriContext(uriContext)
    , _vsgctx(vsgctx)
    , _inFlightLoads(std::make_shared<std::atomic<int>>(0))
    , _loadsCompleted(std::make_shared<std::atomic<int>>(0))
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

    // The app renders on demand. When a content download completes on a worker
    // thread, request a frame so the next record traversal can resolve it —
    // otherwise tiles that finish loading after the user stops interacting
    // would never appear.
    if (_vsgctx)
    {
        auto completed = _loadsCompleted;
        _updateSub = _vsgctx->onUpdate([completed](VSGContext ctx)
            {
                if (completed->exchange(0) > 0)
                    ctx->requestFrame();
            });
    }
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

    // Pass 2: if still over limit, force-evict the oldest tiles regardless of
    // age — but never tiles that were rendered this frame or the last one.
    // Evicting the live visible set just forces an immediate reload and turns
    // into a permanent download/compile/evict churn loop.
    itr = _tracker.begin();
    while (_tracker.size() > static_cast<size_t>(maximumLoadedTiles) + 1u && itr != sentinelItr)
    {
        Tile3DNode* tile = *itr;
        if (tile && tile->autoUnload &&
            frameNumber > tile->_lastCulledFrame + 1u &&
            tile->unloadContent())
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

void Tiles3DNode::enqueueCompile(vsg::ref_ptr<Tile3DNode> tile, vsg::ref_ptr<vsg::Node> node) const
{
    bool schedule = false;
    {
        std::lock_guard<std::mutex> lock(_readyQueueMutex);
        _readyQueue.emplace_back(std::move(tile), std::move(node));
        if (!_compileScheduled)
        {
            _compileScheduled = true;
            schedule = true;
        }
    }

    if (schedule && _vsgctx)
    {
        vsg::ref_ptr<const Tiles3DNode> self(this);
        _vsgctx->onNextUpdate([self](VSGContext ctx) { self->processCompileQueue(ctx); });
    }
}

void Tiles3DNode::processCompileQueue(VSGContext ctx) const
{
    // Compile at most a few tiles per update pass. compile() blocks on a
    // fence, so draining an unbounded backlog here would stall the frame
    // loop for hundreds of milliseconds while the user is dragging.
    constexpr size_t maxCompilesPerPass = 4;

    std::vector<std::pair<vsg::ref_ptr<Tile3DNode>, vsg::ref_ptr<vsg::Node>>> batch;
    {
        std::lock_guard<std::mutex> lock(_readyQueueMutex);
        const size_t n = std::min(maxCompilesPerPass, _readyQueue.size());
        batch.assign(_readyQueue.begin(), _readyQueue.begin() + n);
        _readyQueue.erase(_readyQueue.begin(), _readyQueue.begin() + n);
    }

    // Group the batch into a single compile call (one fence wait for all).
    auto group = vsg::Group::create();
    for (auto& [tile, node] : batch)
    {
        if (tile->_compilePending.load()) // skip tiles evicted while queued
            group->addChild(node);
    }

    if (!group->children.empty())
        ctx->compile(group);

    for (auto& [tile, node] : batch)
    {
        if (tile->_compilePending.load())
        {
            tile->_content = node;
            tile->_compilePending.store(false);
        }
    }

    bool more = false;
    {
        std::lock_guard<std::mutex> lock(_readyQueueMutex);
        if (_readyQueue.empty())
            _compileScheduled = false;
        else
            more = true;
    }

    if (more)
    {
        // keep draining on subsequent update passes
        vsg::ref_ptr<const Tiles3DNode> self(this);
        ctx->onNextUpdate([self](VSGContext c) { self->processCompileQueue(c); });
    }

    // render the newly compiled content (and keep frames coming while backlogged)
    ctx->requestFrame();
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

    // failure backoff: don't hammer a URI that just failed
    if (_failCount > 0 && std::chrono::steady_clock::now() < _retryNotBefore)
        return;

    auto* vsgctx = _tilesetNode->vsgContext();
    if (!vsgctx)
        return;

    // In-flight budget: when saturated, skip — the next traversal of this
    // (still visible) tile will retry, naturally prioritized by SSE.
    auto inFlight = _tilesetNode->_inFlightLoads;
    if (inFlight->load(std::memory_order_relaxed) >=
        static_cast<int>(_tilesetNode->maxConcurrentLoads))
        return;

    _contentRequested = true;

    auto& io = vsgctx->io;
    auto opts = vsgctx->readerWriterOptions;
    const auto uri = _tile.content->resolved;
    const auto uriContext = _tilesetNode->uriContext();
    auto completed = _tilesetNode->_loadsCompleted;

    inFlight->fetch_add(1);

    auto load = [uri, uriContext, io, opts, inFlight, completed](Cancelable& c) -> NodeResult
    {
        // On exit (success, failure, or cancel): release the in-flight slot
        // and signal completion so the update pass requests a frame, which
        // lets resolveContent() pick up the result in on-demand render mode.
        struct Completion
        {
            std::shared_ptr<std::atomic<int>> inFlight;
            std::shared_ptr<std::atomic<int>> completed;
            ~Completion()
            {
                inFlight->fetch_sub(1);
                completed->fetch_add(1);
            }
        } completion{ inFlight, completed };

        if (c.canceled())
            return Failure_OperationCanceled;

        URI u(uri, uriContext);
        auto rr = u.read(io);
        if (!rr)
            return rr.error();

        // The tile may have been evicted or scrolled out of view while the
        // download ran; don't pay for parsing in that case.
        if (c.canceled())
            return Failure_OperationCanceled;

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
    // Loading is network-bound; a few threads keep the pipe full without
    // starving the CPU (decode runs on the same job).
    jobs::context jobctx{ uri, j.get_pool("rocky::Tiles3DNode", 6) };

    // Order the queue by screen-space error so the tiles the camera needs
    // most load first; tiles that scroll away decay in priority naturally.
    vsg::ref_ptr<const Tile3DNode> holdSelf(this);
    jobctx.priority = [holdSelf]() { return holdSelf->_screenSpaceError.load(); };

    _loadFuture = j.dispatch(load, jobctx);
}

void Tile3DNode::resolveContent() const
{
    if (!_loadFuture.available())
        return;

    // Failure path: back off, then allow a retry. Leaving _contentRequested
    // set would turn any transient network error into a permanent hole.
    auto failWithBackoff = [this](const std::string& msg)
    {
        ++_failCount;
        const auto secs = std::min<int64_t>(30, int64_t(2) << std::min(_failCount - 1, 5));
        _retryNotBefore = std::chrono::steady_clock::now() + std::chrono::seconds(secs);
        _loadFuture = {};
        _contentRequested = false;
        TILES3D_LOG_E("Tile3DNode load failed (attempt %d, retry in %llds): %s",
            _failCount, static_cast<long long>(secs), msg.c_str());
    };

    if (_loadFuture->failed())
    {
        failWithBackoff(_loadFuture->error().string());
        return;
    }

    auto node = _loadFuture->value();
    _loadFuture = {};

    if (!node)
    {
        failWithBackoff("loader returned empty node");
        return;
    }

    _failCount = 0;

    if (_tilesetNode && _tilesetNode->vsgContext())
    {
        // Queue for the batched compile pass that runs between frames.
        // Compiling inline in traverse() races with command recording, and
        // compiling per-tile without a budget stalls the frame loop.
        _compilePending.store(true);
        vsg::ref_ptr<Tile3DNode> self(const_cast<Tile3DNode*>(this));
        _tilesetNode->enqueueCompile(self, node);
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

    // Defer GPU resource destruction: an in-flight frame may still reference
    // this content's Vulkan objects. dispose() parks the ref for several
    // frames before releasing it (same pattern as the rest of rocky).
    if (_content && _tilesetNode && _tilesetNode->vsgContext())
        _tilesetNode->vsgContext()->dispose(_content);

    _content = nullptr;
    _loadFuture = {};
    _contentRequested = false;
    _compilePending.store(false); // cancel any queued batch compile
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

bool Tile3DNode::allChildrenReady(const vsg::RecordTraversal& rv) const
{
    if (!_childGroup)
        return false;

    for (const auto& child : _childGroup->children)
    {
        auto* ct = child->cast<Tile3DNode>();
        // Only children that will actually be drawn (inside the frustum) gate
        // the refinement switch. Off-screen children are never requested, so
        // requiring them here would block refinement forever near the
        // viewport edges.
        if (ct && ct->hasContent() && !ct->isContentReady() && ct->intersectsFrustum(rv))
            return false;
    }
    return true;
}

bool Tile3DNode::intersectsFrustum(const vsg::RecordTraversal& rv) const
{
    (void)rv;
    if (_boundingSphere.radius <= 0.0)
        return true;

    // Test against the ECEF frustum captured in Tiles3DNode::traverse() before
    // any tile transforms were pushed. Note: this is only correct for bounding
    // volumes expressed in ECEF (region volumes, or sphere/box volumes in a
    // tileset with no transforms, like the NLSC data). Datasets that combine
    // sphere/box volumes with tile transforms need the transform chain applied
    // here first (tracked as a known limitation).
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

    const double sse = computeScreenSpaceError(rv);
    _screenSpaceError.store(static_cast<float>(sse)); // drives load priority

    // Check children readiness (only the visible ones gate refinement)
    const bool childrenReady = allChildrenReady(rv);

    // Per 3D Tiles spec: tiles with no content are spatial hierarchy nodes and
    // must always recurse to children to show anything at all.
    const bool needsRefinement = !hasContent() || sse > _tilesetNode->maximumScreenSpaceError;

    if (needsRefinement && childrenReady && _childGroup)
    {
        // Keep this tile in the LRU even while its children render — it is
        // the fallback when a child gets evicted or a new child scrolls into
        // view before its content is loaded.
        if (hasContent())
        {
            _tilesetNode->touchTile(const_cast<Tile3DNode*>(this));
            if (rv.getFrameStamp())
            {
                _lastCulledFrame = rv.getFrameStamp()->frameCount;
                _lastCulledTime  = rv.getFrameStamp()->simulationTime;
            }
        }

        // ADD: render parent content too
        if (_content && _tile.refine == Tiles3DRefine::ADD)
            _content->accept(rv);

        _childGroup->accept(rv);

        // Pre-request content for visible children only — requesting off-screen
        // children wastes load budget and bypasses the frustum culling in their
        // own traverse(), since requestContent() is called without entering traverse().
        for (const auto& c : _childGroup->children)
        {
            if (auto* ct = c->cast<Tile3DNode>())
            {
                if (ct->intersectsFrustum(rv))
                {
                    ct->_screenSpaceError.store(
                        static_cast<float>(ct->computeScreenSpaceError(rv)));
                    ct->requestContent();
                }
            }
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
                        ct->_screenSpaceError.store(
                            static_cast<float>(ct->computeScreenSpaceError(rv)));
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
