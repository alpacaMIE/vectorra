/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#include "Tiles3DNode.h"
#include <rocky/IOTypes.h>
#include <rocky/Log.h>

#include <vsg/all.h>
#include <vsg/io/mem_stream.h>

#include <algorithm>
#include <set>
#include <thread>

#ifdef __ANDROID__
#include <android/log.h>
#define TILES3D_LOG_I(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  "rocky_tiles3d", fmt, ##__VA_ARGS__)
#define TILES3D_LOG_E(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, "rocky_tiles3d", fmt, ##__VA_ARGS__)
#else
#define TILES3D_LOG_I(fmt, ...) Log()->info(fmt, ##__VA_ARGS__)
#define TILES3D_LOG_E(fmt, ...) Log()->error(fmt, ##__VA_ARGS__)
#endif

using namespace ROCKY_NAMESPACE;

namespace
{
    // Sums the CPU-side data sizes of everything a tile subtree will upload
    // to the GPU (vertex/index buffers, textures, uniform data). Used for
    // the resident-bytes eviction budget.
    struct GPUBytesCounter : public vsg::ConstVisitor
    {
        std::set<const vsg::Data*> counted; // dedup data shared within the subtree
        std::uint64_t bytes = 0;

        void addData(const vsg::Data* d)
        {
            if (d && counted.insert(d).second)
                bytes += d->dataSize();
        }

        void apply(const vsg::Object& obj) override { obj.traverse(*this); }

        void apply(const vsg::StateGroup& sg) override
        {
            for (auto& sc : sg.stateCommands)
                if (sc) sc->accept(*this);
            sg.traverse(*this);
        }
        void apply(const vsg::BindDescriptorSet& bds) override
        {
            if (bds.descriptorSet) bds.descriptorSet->accept(*this);
        }
        void apply(const vsg::BindDescriptorSets& bds) override
        {
            for (auto& ds : bds.descriptorSets)
                if (ds) ds->accept(*this);
        }
        void apply(const vsg::DescriptorSet& ds) override
        {
            for (auto& d : ds.descriptors)
                if (d) d->accept(*this);
        }
        void apply(const vsg::DescriptorImage& di) override
        {
            for (auto& ii : di.imageInfoList)
                if (ii && ii->imageView && ii->imageView->image)
                    addData(ii->imageView->image->data);
        }
        void apply(const vsg::DescriptorBuffer& db) override
        {
            for (auto& bi : db.bufferInfoList)
                if (bi) addData(bi->data);
        }
        void apply(const vsg::Geometry& g) override
        {
            for (auto& a : g.arrays)
                if (a) addData(a->data);
            if (g.indices) addData(g.indices->data);
            g.traverse(*this);
        }
        void apply(const vsg::VertexIndexDraw& v) override
        {
            for (auto& a : v.arrays)
                if (a) addData(a->data);
            if (v.indices) addData(v.indices->data);
        }
        void apply(const vsg::VertexDraw& v) override
        {
            for (auto& a : v.arrays)
                if (a) addData(a->data);
        }
        void apply(const vsg::BindVertexBuffers& bvb) override
        {
            for (auto& a : bvb.arrays)
                if (a) addData(a->data);
        }
        void apply(const vsg::BindIndexBuffer& bib) override
        {
            if (bib.indices) addData(bib.indices->data);
        }
    };

    std::uint64_t computeGPUBytes(const vsg::Node* node)
    {
        GPUBytesCounter counter;
        if (node)
            node->accept(counter);
        return counter.bytes;
    }

    inline uint64_t frameOf(const vsg::RecordTraversal& rv)
    {
        // getFrameStamp() is non-const in VSG but this is a pure read
        auto* stamp = const_cast<vsg::RecordTraversal&>(rv).getFrameStamp();
        return stamp ? stamp->frameCount : 0u;
    }
}

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
    // One SharedObjects for every load in this tileset: shader sets, samplers
    // and identical pipeline states get created once instead of per tile,
    // which is the difference between one vkCreateGraphicsPipeline and
    // hundreds on mobile drivers.
    _sharedObjects = vsg::SharedObjects::create();

    _root = vsg::Group::create();
    addChild(_root);

    if (tileset.root)
    {
        auto rootTile = Tile3DNode::create(
            this, tileset.root, tileset.baseURI, vsg::dmat4(), /*immediateLoad=*/true);
        _root->addChild(rootTile);
    }

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

void Tiles3DNode::registerPending(Tile3DNode* tile) const
{
    std::lock_guard<std::mutex> lock(_registryMutex);
    for (auto& t : _pending)
        if (t.get() == tile)
            return;
    _pending.emplace_back(vsg::ref_ptr<Tile3DNode>(tile));
}

void Tiles3DNode::unregisterPending(Tile3DNode* tile) const
{
    std::lock_guard<std::mutex> lock(_registryMutex);
    _pending.erase(
        std::remove_if(_pending.begin(), _pending.end(),
            [tile](const auto& t) { return t.get() == tile; }),
        _pending.end());
}

void Tiles3DNode::registerResident(Tile3DNode* tile) const
{
    std::lock_guard<std::mutex> lock(_registryMutex);
    for (auto& t : _resident)
        if (t.get() == tile)
            return;
    _resident.emplace_back(vsg::ref_ptr<Tile3DNode>(tile));
}

void Tiles3DNode::unregisterResident(Tile3DNode* tile) const
{
    std::lock_guard<std::mutex> lock(_registryMutex);
    _resident.erase(
        std::remove_if(_resident.begin(), _resident.end(),
            [tile](const auto& t) { return t.get() == tile; }),
        _resident.end());
}

void Tiles3DNode::sweepPending(uint64_t frameNumber) const
{
    // Tiles not seen (traversed or pre-fetched) for this many frames get
    // their loads abandoned: in-flight downloads abort (the curl progress
    // hook observes the canceled future), queued jobs are skipped, and
    // results that arrived after the tile scrolled away are released
    // instead of lingering until the camera happens to revisit.
    constexpr uint64_t staleFrames = 30u;

    std::vector<vsg::ref_ptr<Tile3DNode>> stale;
    {
        std::lock_guard<std::mutex> lock(_registryMutex);
        for (auto& t : _pending)
        {
            if (t->_compilePending.load())
                continue; // owned by the compile worker right now
            if (frameNumber > t->_lastSeenFrame + staleFrames)
                stale.push_back(t);
        }
    }

    for (auto& t : stale)
    {
        t->_downloadFuture = {};
        t->_decodeFuture = {};
        t->_contentRequested = false;
        unregisterPending(t.get());
    }
}

void Tiles3DNode::expireTiles(uint64_t frameNumber, double referenceTime) const
{
    (void)referenceTime;

    if (frameNumber <= _lastExpireFrame)
        return;
    _lastExpireFrame = frameNumber;

    sweepPending(frameNumber);

    // Drop single-referenced shared objects (pipeline configs of evicted
    // tiles hold refs to their texture data) so the share cache doesn't
    // grow without bound.
    if (_sharedObjects && (frameNumber % 120u) == 0u)
        _sharedObjects->prune();

    std::vector<vsg::ref_ptr<Tile3DNode>> snapshot;
    {
        std::lock_guard<std::mutex> lock(_registryMutex);
        if (_resident.size() <= maximumLoadedTiles &&
            _residentBytes.load() <= maximumResidentBytes)
            return;
        snapshot = _resident;
    }

    // oldest first
    std::sort(snapshot.begin(), snapshot.end(),
        [](const vsg::ref_ptr<Tile3DNode>& a, const vsg::ref_ptr<Tile3DNode>& b)
        { return a->_lastUsedFrame < b->_lastUsedFrame; });

    size_t count = snapshot.size();
    for (auto& tile : snapshot)
    {
        if (count <= maximumLoadedTiles &&
            _residentBytes.load() <= maximumResidentBytes)
            break;

        // Never evict the live visible set (rendered this frame or the last):
        // that just forces an immediate reload and turns into a permanent
        // download/compile/evict churn loop.
        if (!tile->autoUnload || frameNumber <= tile->_lastUsedFrame + 1u)
            continue;

        if (tile->unloadContent())
            --count;
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
        // Compile on a dedicated worker so the (fence-blocking) compile never
        // stalls the frame loop. Same pattern as rocky's NodePager, which
        // calls VSGContext::compile from its loader jobs.
        vsg::ref_ptr<const Tiles3DNode> self(this);
        auto ctx = _vsgctx;
        auto& j = ctx->io.services().jobs;
        jobs::context jc;
        jc.name = "rocky::Tiles3DNode::compile";
        jc.pool = j.get_pool("rocky::Tiles3DNode.compile", 1u);
        j.dispatch([self, ctx]() { self->compileWorker(ctx); }, jc);
    }
}

void Tiles3DNode::compileWorker(VSGContext ctx) const
{
    // One fence wait covers the whole batch.
    constexpr size_t maxCompilesPerBatch = 8;

    while (true)
    {
        std::vector<std::pair<vsg::ref_ptr<Tile3DNode>, vsg::ref_ptr<vsg::Node>>> batch;
        {
            std::lock_guard<std::mutex> lock(_readyQueueMutex);
            if (_readyQueue.empty())
            {
                _compileScheduled = false;
                return;
            }
            const size_t n = std::min(maxCompilesPerBatch, _readyQueue.size());
            batch.assign(_readyQueue.begin(), _readyQueue.begin() + n);
            _readyQueue.erase(_readyQueue.begin(), _readyQueue.begin() + n);
        }

        auto group = vsg::Group::create();
        for (auto& [tile, node] : batch)
        {
            if (tile->_compilePending.load()) // skip tiles evicted while queued
                group->addChild(node);
        }

        // callerHandlesFailure: nodes that fail to compile never enter the
        // scene graph (attachment below is skipped), so a failure here must
        // not trip the global rendering kill switch — one bad tile must not
        // freeze the whole map.
        bool batchOK = true;
        if (!group->children.empty())
        {
            auto cr = ctx->compile(group, /*callerHandlesFailure=*/true);
            batchOK = static_cast<bool>(cr);
            if (!batchOK)
            {
                TILES3D_LOG_E("Tiles3D batch compile failed (%zu tiles): %s — isolating per tile",
                    group->children.size(), cr.message.c_str());
            }
        }

        struct Attach
        {
            vsg::ref_ptr<Tile3DNode> tile;
            vsg::ref_ptr<vsg::Node> node;
            bool ok;
            std::uint64_t bytes;
        };
        std::vector<Attach> attaches;
        attaches.reserve(batch.size());

        for (auto& [tile, node] : batch)
        {
            if (!tile->_compilePending.load())
                continue; // evicted while queued; node never compiled or attached

            bool ok = batchOK;
            if (!ok)
            {
                // One bad tile fails the whole batch and the result doesn't
                // say which; recompile individually to salvage the good tiles
                // and pin down the bad one.
                ok = static_cast<bool>(ctx->compile(node, /*callerHandlesFailure=*/true));
            }

            attaches.push_back({ tile, node, ok, ok ? computeGPUBytes(node.get()) : 0ull });
        }

        if (!attaches.empty())
        {
            // Attach on the update pass: all tile state mutations stay on the
            // frame loop thread, so traverse() never races a worker.
            vsg::ref_ptr<const Tiles3DNode> self(this);
            ctx->onNextUpdate([self, ctx, attaches](VSGContext)
                {
                    const auto& viewer = ctx->viewer();
                    auto* stamp = viewer ? viewer->getFrameStamp() : nullptr;
                    const uint64_t frame = stamp ? stamp->frameCount : 0u;
                    const double simTime = stamp ? stamp->simulationTime : 0.0;

                    for (auto& a : attaches)
                    {
                        if (!a.tile->_compilePending.exchange(false))
                        {
                            // evicted between compile and attach
                            ctx->dispose(a.node);
                            continue;
                        }

                        if (a.ok)
                        {
                            a.tile->_content = a.node;
                            a.tile->_gpuBytes = a.bytes;
                            a.tile->touch(frame, simTime, true);
                            self->_residentBytes.fetch_add(a.bytes);
                            self->registerResident(a.tile.get());
                            self->unregisterPending(a.tile.get());
                        }
                        else
                        {
                            ctx->dispose(a.node);
                            a.tile->onCompileFailed();
                        }
                    }
                    // render the newly attached content
                    ctx->requestFrame();
                });
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

        // Camera eye in ECEF = translation of the inverted view matrix.
        const auto invView = vsg::inverse(state->modelviewMatrixStack.top());
        _eyeECEF = vsg::dvec3(invView[3][0], invView[3][1], invView[3][2]);

        // For perspective: proj[1][1] = cot(fovY/2) → sseDenom = 2/proj[1][1]
        // (Vulkan: proj[1][1] < 0)
        const vsg::dmat4& proj = state->projectionMatrixStack.top();
        _sseDenominator = (std::abs(proj[1][1]) > 1e-10)
            ? 2.0 / std::abs(proj[1][1])
            : 1.0;
    }

    Inherit::traverse(rv);

    // Per-frame housekeeping after traversal so the current visible and
    // refinement-candidate set has a chance to refresh its LRU stamps first.
    if (rv.getFrameStamp())
    {
        const auto frame = rv.getFrameStamp()->frameCount;
        expireTiles(frame, rv.getFrameStamp()->simulationTime);
    }
}

void Tiles3DNode::traverse(vsg::Visitor& v) { Inherit::traverse(v); }
void Tiles3DNode::traverse(vsg::ConstVisitor& v) const { Inherit::traverse(v); }


// ---------------------------------------------------------------------------
// Tile3DNode
// ---------------------------------------------------------------------------

Tile3DNode::Tile3DNode(
    Tiles3DNode* tilesetNode,
    std::shared_ptr<const Tiles3DTile> tile,
    std::shared_ptr<const std::string> baseURI,
    const vsg::dmat4& parentWorld,
    bool immediateLoad)
    : _tilesetNode(tilesetNode)
    , _tile(std::move(tile))
    , _baseURI(std::move(baseURI))
{
    if (!_baseURI)
        _baseURI = std::make_shared<std::string>();

    _worldGeometricError = _tile->geometricError;

    // Apply tile's local transform (column-major matrix from tileset.json)
    if (_tile->transform)
        this->matrix = *_tile->transform;

    _worldMatrix = _tile->transform
        ? parentWorld * (*_tile->transform)
        : parentWorld;

    const auto& localSphere = _tile->boundingSphere;
    if (_tile->regionVolume)
    {
        // Region volumes are defined in ECEF and are unaffected by tile
        // transforms (3D Tiles spec).
        _worldBoundingSphere = localSphere;
    }
    else
    {
        // Box/sphere volumes are in the tile's local frame; place them in
        // ECEF. Radius (and geometric error, below) scale with the largest
        // axis scale of the accumulated transform.
        const double maxScale = std::max({
            vsg::length(vsg::dvec3(_worldMatrix[0][0], _worldMatrix[0][1], _worldMatrix[0][2])),
            vsg::length(vsg::dvec3(_worldMatrix[1][0], _worldMatrix[1][1], _worldMatrix[1][2])),
            vsg::length(vsg::dvec3(_worldMatrix[2][0], _worldMatrix[2][1], _worldMatrix[2][2])) });

        _worldBoundingSphere = vsg::dsphere(
            _worldMatrix * localSphere.center,
            localSphere.radius * maxScale);

        _worldGeometricError = _tile->geometricError * maxScale;
    }

    if (immediateLoad && _tile->content.has_value())
    {
        requestContent();
        resolveContent();
    }
}

bool Tile3DNode::hasContent() const
{
    return _tile->content.has_value();
}

void Tile3DNode::touch(uint64_t frame, double simulationTime, bool protectResident) const
{
    _lastSeenFrame = frame;
    if (protectResident && hasContent())
    {
        _lastUsedFrame = frame;
        _lastUsedTime = simulationTime;
    }
}

void Tile3DNode::touch(const vsg::RecordTraversal& rv, bool protectResident) const
{
    const uint64_t frame = frameOf(rv);
    auto* stamp = const_cast<vsg::RecordTraversal&>(rv).getFrameStamp();
    const double simTime = stamp ? stamp->simulationTime : 0.0;
    touch(frame, simTime, protectResident);
}

bool Tile3DNode::isContentReady() const
{
    // A permanently failed tile is as ready as it will ever be: report ready
    // so it never gates parent refinement (which would pin the whole region
    // at the parent's LOD forever waiting on content that cannot arrive).
    if (_permanentlyFailed)
        return true;

    if (!_content)
        return false;

    // External tileset graft: only count as ready once the grafted root's own
    // content is — otherwise REPLACE refinement switches the parent off while
    // this tile is still an empty stub, flashing a hole for one network
    // round-trip per level. Driving the graft's load from here is what breaks
    // the deadlock: the graft isn't traversed until the switch happens, so
    // nobody else would request its content during the gating phase.
    if (auto* graft = _content->cast<Tile3DNode>())
    {
        graft->_screenSpaceError.store(_screenSpaceError.load());
        graft->touch(_lastUsedFrame, _lastUsedTime, true);

        if (graft->hasContent() && !graft->isContentReady())
        {
            // inherit the pointing tile's SSE and liveness so the load queue
            // prioritizes the graft like the tile it stands in for and the
            // stale sweep doesn't cancel it
            graft->requestContent();
            graft->resolveContent();
            return false;
        }
    }

    return true;
}

void Tile3DNode::requestContent() const
{
    if (_permanentlyFailed || _contentRequested || !_tile->content.has_value())
        return;

    // failure backoff: don't hammer a URI that just failed (load failures
    // count via _failCount; GPU compile failures via _compileFailCount,
    // since the success path resets _failCount on every completed download)
    if ((_failCount > 0 || _compileFailCount > 0) &&
        std::chrono::steady_clock::now() < _retryNotBefore)
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
    const auto uri = Tiles3DTileset::resolveContentURI(_tile->content->uri, *_baseURI);
    const auto uriContext = _tilesetNode->uriContext();
    auto completed = _tilesetNode->_loadsCompleted;

    // RAII gate held by the lambda captures: counts the download in flight
    // and signals completion. The job system destroys the lambda whether the
    // job runs, is skipped after cancellation, or is dropped at shutdown, so
    // the in-flight budget can never leak.
    struct LoadGate
    {
        std::shared_ptr<std::atomic<int>> inFlight;
        std::shared_ptr<std::atomic<int>> completed;
        LoadGate(std::shared_ptr<std::atomic<int>> i, std::shared_ptr<std::atomic<int>> c)
            : inFlight(std::move(i)), completed(std::move(c))
        {
            inFlight->fetch_add(1);
        }
        ~LoadGate()
        {
            inFlight->fetch_sub(1);
            completed->fetch_add(1);
        }
    };
    auto gate = std::make_shared<LoadGate>(inFlight, completed);

    // Download stage: network only. Decode runs on a separate pool (see
    // dispatchDecode) so CPU-heavy Draco/WebP work never starves the pipe.
    auto download = [uri, uriContext, io, gate](Cancelable& c) -> ContentResult
    {
        if (c.canceled())
            return Failure_OperationCanceled;

        URI u(uri, uriContext);

        auto localIO = io.with(c);     // lets eviction abort the transfer mid-flight
        localIO.maxNetworkAttempts = 1; // retry/backoff is the tile's job, not a pool thread's
        localIO.useContentCache = false; // tile payloads are large and low-reuse

        auto rr = u.read(localIO);
        if (!rr)
            return rr.error();

        return std::make_shared<Content>(std::move(rr.value().content));
    };

    auto& j = io.services().jobs;
    jobs::context jobctx{ uri, j.get_pool("rocky::Tiles3DNode.net", 8u) };

    // Order the queue by screen-space error so the tiles the camera needs
    // most load first; tiles that scroll away decay in priority naturally.
    vsg::ref_ptr<const Tile3DNode> holdSelf(this);
    jobctx.priority = [holdSelf]() { return holdSelf->_screenSpaceError.load(); };

    _downloadFuture = j.dispatch(download, jobctx);

    _tilesetNode->registerPending(const_cast<Tile3DNode*>(this));
}

void Tile3DNode::dispatchDecode(std::shared_ptr<Content> content) const
{
    auto* vsgctx = _tilesetNode->vsgContext();
    auto& io = vsgctx->io;
    auto opts = vsgctx->readerWriterOptions;
    auto sharedObjects = _tilesetNode->_sharedObjects;
    const auto uri = Tiles3DTileset::resolveContentURI(_tile->content->uri, *_baseURI);
    auto completed = _tilesetNode->_loadsCompleted;

    // For grafting external tilesets: keeps the tileset node alive for the
    // duration of the job and provides the transform chain to compose under.
    vsg::ref_ptr<Tiles3DNode> tilesetNode(_tilesetNode);
    const auto worldMatrix = _worldMatrix;

    // requests a frame when the job finishes so the next record traversal
    // consumes the result (fires even if the job is skipped)
    struct DoneGate
    {
        std::shared_ptr<std::atomic<int>> completed;
        ~DoneGate() { completed->fetch_add(1); }
    };
    auto gate = std::make_shared<DoneGate>(DoneGate{ completed });

    auto decode = [content, uri, opts, sharedObjects, tilesetNode, worldMatrix, gate](Cancelable& c) -> NodeResult
    {
        if (c.canceled())
            return Failure_OperationCanceled;

        std::filesystem::path path(uri);

        // External tileset: content that is itself a tileset.json. Parse it
        // with rocky's own parser — relative URIs resolve against the external
        // tileset's own location — and graft its root as this tile's content
        // node so it runs through the same traversal/SSE/LRU machinery. This
        // must be intercepted before vsg::read_cast: vsgXchange also claims
        // .json but has no network access and would bypass rocky's paging.
        if (path.extension() == ".json" ||
            content->type.find("json") != std::string::npos)
        {
            auto tileset = Tiles3DTileset::fromJSON(content->data, uri);
            if (!tileset)
                return tileset.error();
            if (!tileset.value().root)
                return Failure("Tiles3D: external tileset has no root tile: " + uri);

            // The external root composes under this tile's accumulated transform.
            return vsg::ref_ptr<vsg::Node>(Tile3DNode::create(
                tilesetNode.get(), tileset.value().root, tileset.value().baseURI, worldMatrix));
        }

        auto localOpts = opts ? vsg::clone(opts) : vsg::Options::create();
        localOpts->sharedObjects = sharedObjects;
        localOpts->extensionHint = path.extension();
        if (localOpts->extensionHint.empty())
            localOpts->extensionHint = content->type;

        // zero-copy istream view over the downloaded bytes (content is held
        // alive by this lambda for the duration of the read)
        vsg::mem_stream buf(
            reinterpret_cast<const uint8_t*>(content->data.data()),
            content->data.size());

        auto node = vsg::read_cast<vsg::Node>(buf, localOpts);
        if (!node)
            return Failure("Tiles3D: vsg::read_cast failed for " + uri);

        return node;
    };

    auto& j = io.services().jobs;
    const unsigned decodeThreads =
        std::clamp(std::thread::hardware_concurrency() / 2u, 2u, 4u);
    jobs::context jobctx{ uri, j.get_pool("rocky::Tiles3DNode.decode", decodeThreads) };

    vsg::ref_ptr<const Tile3DNode> holdSelf(this);
    jobctx.priority = [holdSelf]() { return holdSelf->_screenSpaceError.load(); };

    _decodeFuture = j.dispatch(decode, jobctx);
}

// Failure path: back off, then allow a retry. Leaving _contentRequested
// set would turn any transient network error into a permanent hole.
void Tile3DNode::failWithBackoff(const std::string& msg) const
{
    ++_failCount;
    const auto secs = std::min<int64_t>(30, int64_t(2) << std::min(_failCount - 1, 5));
    _retryNotBefore = std::chrono::steady_clock::now() + std::chrono::seconds(secs);
    _downloadFuture = {};
    _decodeFuture = {};
    _contentRequested = false;
    _tilesetNode->unregisterPending(const_cast<Tile3DNode*>(this));
    TILES3D_LOG_E("Tile3DNode load failed (attempt %d, retry in %llds): %s",
        _failCount, static_cast<long long>(secs), msg.c_str());
}

void Tile3DNode::onCompileFailed() const
{
    ++_compileFailCount;

    // Compile failures are usually deterministic (bad content), so retrying
    // forever would just burn bandwidth and GPU time on every backoff cycle.
    // Allow a few attempts in case the failure was a transient resource issue,
    // then give up on this tile for good and leave it out of the scene.
    constexpr int maxCompileAttempts = 4;
    if (_compileFailCount >= maxCompileAttempts)
    {
        _permanentlyFailed = true;
        _downloadFuture = {};
        _decodeFuture = {};
        _tilesetNode->unregisterPending(const_cast<Tile3DNode*>(this));
        TILES3D_LOG_E("Tile3DNode giving up after %d failed GPU compiles: %s",
            _compileFailCount,
            _tile->content.has_value() ? _tile->content->uri.c_str() : "?");
        return;
    }

    // Schedule a retry with escalating backoff. Pace by _compileFailCount:
    // each retry cycle re-downloads successfully, which resets _failCount.
    const auto secs = std::min<int64_t>(30, int64_t(2) << std::min(_compileFailCount - 1, 5));
    _retryNotBefore = std::chrono::steady_clock::now() + std::chrono::seconds(secs);
    _downloadFuture = {};
    _decodeFuture = {};
    _contentRequested = false;
    _tilesetNode->unregisterPending(const_cast<Tile3DNode*>(this));
    TILES3D_LOG_E("Tile3DNode GPU compile failed (attempt %d/%d, retry in %llds): %s",
        _compileFailCount, maxCompileAttempts, static_cast<long long>(secs),
        _tile->content.has_value() ? _tile->content->uri.c_str() : "?");
}

void Tile3DNode::resolveContent() const
{
    // Stage 2: decode finished?
    if (_decodeFuture.available())
    {
        if (_decodeFuture->failed())
        {
            failWithBackoff(_decodeFuture->error().string());
            return;
        }

        auto node = _decodeFuture->value();
        _decodeFuture = {};

        if (!node)
        {
            failWithBackoff("decoder returned empty node");
            return;
        }

        _failCount = 0;

        // Grafted external tileset: a Tile3DNode subtree with no GPU resources
        // of its own — nothing to compile. Pin this tile (autoUnload=false) so
        // the grafted structure is never disposed while its descendants are
        // alive; the descendants still evict their own content individually,
        // so GPU memory stays bounded.
        if (node->cast<Tile3DNode>())
        {
            autoUnload = false;
            _content = node;
            _tilesetNode->unregisterPending(const_cast<Tile3DNode*>(this));
            return;
        }

        if (_tilesetNode->vsgContext())
        {
            // Hand off to the batched compile worker; the result attaches on
            // a later update pass. The tile stays in the pending registry
            // (guarded by _compilePending) until then.
            _compilePending.store(true);
            vsg::ref_ptr<Tile3DNode> self(const_cast<Tile3DNode*>(this));
            _tilesetNode->enqueueCompile(self, node);
        }
        else
        {
            _content = node;
            _tilesetNode->unregisterPending(const_cast<Tile3DNode*>(this));
        }
        return;
    }

    if (_decodeFuture.working())
        return;

    // Stage 1: download finished? hand off to the decode pool.
    if (!_downloadFuture.available())
        return;

    if (_downloadFuture->failed())
    {
        failWithBackoff(_downloadFuture->error().string());
        return;
    }

    auto content = _downloadFuture->value();
    _downloadFuture = {};

    if (!content || content->data.empty())
    {
        failWithBackoff("download returned empty content");
        return;
    }

    dispatchDecode(std::move(content));
}

bool Tile3DNode::unloadContent()
{
    const bool pipelineActive =
        _downloadFuture.working() || _downloadFuture.available() ||
        _decodeFuture.working() || _decodeFuture.available() ||
        _compilePending.load();

    if (!_content && !pipelineActive)
        return false;

    // Defer GPU resource destruction: an in-flight frame may still reference
    // this content's Vulkan objects. dispose() parks the ref for several
    // frames before releasing it (same pattern as the rest of rocky).
    if (_content && _tilesetNode && _tilesetNode->vsgContext())
        _tilesetNode->vsgContext()->dispose(_content);

    if (_gpuBytes)
    {
        _tilesetNode->_residentBytes.fetch_sub(_gpuBytes);
        _gpuBytes = 0;
    }

    _content = nullptr;
    _downloadFuture = {};
    _decodeFuture = {};
    _contentRequested = false;
    _compilePending.store(false); // cancels a queued batch compile

    _tilesetNode->unregisterResident(this);
    _tilesetNode->unregisterPending(this);
    return true;
}

void Tile3DNode::createChildren() const
{
    if (_childrenCreated)
        return;
    _childrenCreated = true;

    if (_tile->children.empty())
        return;

    _childGroup = vsg::Group::create();
    for (const auto& childData : _tile->children)
        _childGroup->addChild(Tile3DNode::create(_tilesetNode, childData, _baseURI, _worldMatrix));
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
        if (ct && ct->intersectsFrustum(rv))
        {
            ct->touch(rv, true);
            if (ct->hasContent() && !ct->isContentReady())
                return false;
        }
    }
    return true;
}

bool Tile3DNode::intersectsFrustum(const vsg::RecordTraversal& rv) const
{
    // The same tile is tested several times per frame (its own traversal,
    // the parent's readiness check, the parent's pre-fetch loop); cache the
    // result per frame.
    const uint64_t frame = frameOf(rv);
    if (frame != 0u && _frustumFrame == frame)
        return _frustumResult;

    bool result = true;
    if (_worldBoundingSphere.radius > 0.0)
    {
        // Test the world-space (ECEF) bounding sphere against the ECEF frustum
        // captured in Tiles3DNode::traverse() before any tile transforms were
        // pushed. Local box/sphere volumes were already mapped into ECEF through
        // the accumulated tile transform chain at construction time.
        result = _tilesetNode->_ecefFrustum.intersect(_worldBoundingSphere);
    }

    _frustumFrame = frame;
    _frustumResult = result;
    return result;
}

double Tile3DNode::computeScreenSpaceError(const vsg::RecordTraversal& rv) const
{
    const uint64_t frame = frameOf(rv);
    if (frame != 0u && _sseFrame == frame)
        return _sseValue;

    double sse = 0.0;
    if (_worldGeometricError > 0.0)
    {
        // World-space distance against the camera eye captured per frame in
        // Tiles3DNode::traverse(). Unlike the modelview-stack approach this is
        // valid from any caller — including the parent's pre-fetch loops, where
        // this tile's own matrix is not on the traversal stack.
        double dist = vsg::length(_tilesetNode->_eyeECEF - _worldBoundingSphere.center)
            - _worldBoundingSphere.radius;
        dist = std::max(dist, 0.0000001);

        const double vh = static_cast<double>(_tilesetNode->viewportHeight.load());
        sse = (_worldGeometricError * vh) / (dist * _tilesetNode->_sseDenominator);
    }

    _sseFrame = frame;
    _sseValue = sse;
    _screenSpaceError.store(static_cast<float>(sse)); // drives load priority
    return sse;
}

void Tile3DNode::traverse(vsg::RecordTraversal& rv) const
{
    const uint64_t frame = frameOf(rv);
    const double simTime = rv.getFrameStamp() ? rv.getFrameStamp()->simulationTime : 0.0;
    touch(frame, simTime, false);

    if (!intersectsFrustum(rv))
        return;

    // Resolve any completed async load (download->decode or decode->compile)
    resolveContent();

    // Lazily create child nodes
    if (!_childrenCreated && !_tile->children.empty())
        createChildren();

    const double sse = computeScreenSpaceError(rv);

    // Check children readiness (only the visible ones gate refinement)
    const bool childrenReady = allChildrenReady(rv);

    // Per 3D Tiles spec: tiles with no content are spatial hierarchy nodes and
    // must always recurse to children to show anything at all.
    const bool needsRefinement = !hasContent() || sse > _tilesetNode->maximumScreenSpaceError;

    if (needsRefinement && childrenReady && _childGroup)
    {
        // Keep this tile fresh in the LRU even while its children render — it
        // is the fallback when a child gets evicted or a new child scrolls
        // into view before its content is loaded.
        touch(frame, simTime, true);

        // ADD: render parent content too
        if (_content && _tile->refine == Tiles3DRefine::ADD)
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
                    ct->touch(frame, simTime, true);
                    ct->computeScreenSpaceError(rv);
                    ct->requestContent();
                }
            }
        }
    }
    else
    {
        // This tile IS the leaf being rendered — touch it so it stays alive
        touch(frame, simTime, true);

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
                        ct->touch(frame, simTime, true);
                        ct->computeScreenSpaceError(rv);
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
