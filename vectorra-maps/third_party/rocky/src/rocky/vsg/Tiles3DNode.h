/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 *
 * VSG scene-graph nodes for 3D Tiles rendering.
 * Tiles3DNode is the root (analogous to OSGEarth's ThreeDTilesetNode).
 * Tile3DNode is the per-tile LOD node  (analogous to ThreeDTileNode).
 */
#pragma once
#include <rocky/vsg/Common.h>
#include <rocky/vsg/VSGContext.h>
#include <rocky/Tiles3D.h>
#include <rocky/URI.h>
#include <rocky/Threading.h>
#include <rocky/Result.h>
#include <rocky/Callbacks.h>

#include <vsg/utils/SharedObjects.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

namespace ROCKY_NAMESPACE
{
    class Tile3DNode;

    /**
     * Root VSG node managing a 3D Tiles tileset.
     * Analogous to OSGEarth's ThreeDTilesetNode.
     */
    class ROCKY_EXPORT Tiles3DNode : public vsg::Inherit<vsg::Group, Tiles3DNode>
    {
    public:
        //! Construct from a parsed tileset.
        //! @param tileset   Parsed tileset data model
        //! @param baseURI   Absolute URI of the tileset.json (used to resolve relative URIs)
        //! @param context   URI context carrying custom HTTP headers
        //! @param vsgctx    VSG context for async compilation
        //! @param maxSSE    Maximum screen-space error threshold
        //! @param maxTiles  Maximum number of tiles to keep resident
        Tiles3DNode(
            const Tiles3DTileset& tileset,
            const URI::Context& uriContext,
            VSGContext vsgctx,
            float maxSSE = 16.0f,
            unsigned maxTiles = 1024u);

        void traverse(vsg::RecordTraversal&) const override;
        void traverse(vsg::Visitor&) override;
        void traverse(vsg::ConstVisitor&) const override;

        //! Configuration
        float maximumScreenSpaceError = 16.0f;
        unsigned maximumLoadedTiles   = 1024u;

        //! Approximate GPU byte budget (geometry + texture data) across all
        //! resident tile content. Whichever of this and maximumLoadedTiles is
        //! exceeded first triggers eviction.
        std::uint64_t maximumResidentBytes = 384ull * 1024 * 1024;

        //! Maximum number of tile content downloads in flight at once.
        //! Requests over budget are simply skipped and retried on a later
        //! traversal.
        unsigned maxConcurrentLoads = 32u;

        //! Viewport height in pixels; set from the JNI after surface creation/resize.
        std::atomic<float> viewportHeight{ 1080.0f };

        //! URI context (base URL + auth headers) passed to every tile content request.
        const URI::Context& uriContext() const { return _uriContext; }

        //! VSG context for GPU compilation.
        VSGContext vsgContext() const { return _vsgctx; }

    private:
        URI::Context _uriContext;
        VSGContext _vsgctx;
        vsg::ref_ptr<vsg::Group> _root; // wraps the root Tile3DNode

        // Shared across all tile content loads so identical shader sets,
        // samplers and pipeline states are created once instead of per tile.
        // Periodically pruned (in expireTiles) so configs belonging to
        // evicted tiles don't pin their texture data forever.
        vsg::ref_ptr<vsg::SharedObjects> _sharedObjects;

        // Tile registries. _resident holds tiles whose content is attached
        // (eviction works off this); _pending holds tiles with a download or
        // decode in flight, or a finished result not yet consumed (the stale
        // sweep works off this). Mutations are rare (load completion and
        // eviction), so a mutex is fine; the per-tile per-frame "touch" is
        // just a frame-number store.
        mutable std::mutex _registryMutex;
        mutable std::vector<vsg::ref_ptr<Tile3DNode>> _resident;
        mutable std::vector<vsg::ref_ptr<Tile3DNode>> _pending;

        mutable std::atomic<std::uint64_t> _residentBytes{ 0 };
        mutable uint64_t _lastExpireFrame = 0u;

        // ECEF-space view frustum, updated once per frame in traverse() before any
        // tile-local transforms are applied. Tile3DNode reads this for frustum culling.
        mutable vsg::Frustum _ecefFrustum;

        // ECEF camera eye position and SSE projection denominator (2/|proj[1][1]|),
        // captured alongside _ecefFrustum. Tile3DNode computes screen-space error in
        // world space from these so the result is valid regardless of which matrices
        // happen to be on the record-traversal stack at call time.
        mutable vsg::dvec3 _eyeECEF;
        mutable double _sseDenominator = 1.0;

        // Downloads in flight (shared with worker lambdas so it stays valid even
        // if this node is destroyed while a download is still running).
        std::shared_ptr<std::atomic<int>> _inFlightLoads;

        // Jobs that completed since the last update pass. Workers bump this;
        // the onUpdate subscription below converts it into a frame request so
        // newly downloaded content gets resolved/compiled even when the app is
        // in on-demand rendering mode and the user is not interacting.
        std::shared_ptr<std::atomic<int>> _loadsCompleted;
        CallbackSub _updateSub;

        // Tiles whose content finished decoding and awaits GPU compilation.
        // Drained in batches by a dedicated worker thread; results are
        // attached to the tiles on the next update pass so the frame loop
        // never blocks on a compile fence.
        mutable std::mutex _readyQueueMutex;
        mutable std::vector<std::pair<vsg::ref_ptr<Tile3DNode>, vsg::ref_ptr<vsg::Node>>> _readyQueue;
        mutable bool _compileScheduled = false;

        void registerPending(Tile3DNode* tile) const;
        void unregisterPending(Tile3DNode* tile) const;
        void registerResident(Tile3DNode* tile) const;
        void unregisterResident(Tile3DNode* tile) const;

        //! Per-frame housekeeping: stale-load sweep, byte/count eviction,
        //! periodic SharedObjects prune.
        void expireTiles(uint64_t frameNumber, double referenceTime) const;

        //! Abandon loads for tiles that have not been seen for a while —
        //! cancels in-flight downloads and releases results that arrived
        //! after their tile scrolled out of view.
        void sweepPending(uint64_t frameNumber) const;

        //! Queue a decoded tile for the batched compile worker (called by Tile3DNode).
        void enqueueCompile(vsg::ref_ptr<Tile3DNode> tile, vsg::ref_ptr<vsg::Node> node) const;

        //! Drains the ready queue on a dedicated worker thread, compiling in
        //! batches; attaches results on the update pass.
        void compileWorker(VSGContext ctx) const;

        friend class Tile3DNode;
    };


    /**
     * VSG node rendering one 3D Tiles tile (LOD + async content).
     * Analogous to OSGEarth's ThreeDTileNode.
     */
    class ROCKY_EXPORT Tile3DNode : public vsg::Inherit<vsg::MatrixTransform, Tile3DNode>
    {
    public:
        //! @param tile         Shared tile data model (not copied — the parse
        //!                     tree is the single source of truth)
        //! @param baseURI      Base URI relative content URIs resolve against
        //! @param parentWorld  Accumulated world (ECEF) matrix of the parent tile;
        //!                     composed with this tile's own transform, it places
        //!                     local-space bounding volumes in ECEF for culling/SSE.
        Tile3DNode(
            Tiles3DNode* tilesetNode,
            std::shared_ptr<const Tiles3DTile> tile,
            std::shared_ptr<const std::string> baseURI,
            const vsg::dmat4& parentWorld = vsg::dmat4(),
            bool immediateLoad = false);

        void traverse(vsg::RecordTraversal&) const override;
        void traverse(vsg::Visitor&) override;
        void traverse(vsg::ConstVisitor&) const override;

        bool hasContent() const;
        bool isContentReady() const;
        void requestContent() const;
        void resolveContent() const;
        bool unloadContent();

        // Public so parent tiles can skip off-screen children in pre-fetch loops.
        bool intersectsFrustum(const vsg::RecordTraversal& rv) const;

        // mutable: cleared from resolveContent() (const) when this tile's
        // content turns out to be a grafted external tileset subtree.
        mutable bool autoUnload = true;

    private:
        Tiles3DNode* _tilesetNode;
        std::shared_ptr<const Tiles3DTile> _tile;
        std::shared_ptr<const std::string> _baseURI;

        // Accumulated world matrix: parent's world matrix times this tile's
        // transform. Identity for transform-less tilesets (e.g. NLSC).
        vsg::dmat4 _worldMatrix;

        // Bounding sphere in world (ECEF) space. Region volumes are already
        // ECEF per spec (unaffected by tile transforms); box/sphere volumes
        // are local and get mapped through _worldMatrix.
        vsg::dsphere _worldBoundingSphere;

        // Geometric error scaled by the accumulated transform's largest axis
        // scale (3D Tiles spec: transforms scale geometric error too).
        double _worldGeometricError = 0.0;

        // content load pipeline: download (network pool) -> decode (CPU pool)
        // -> compile (dedicated worker) -> attach (update pass)
        using ContentResult = Result<std::shared_ptr<Content>>;
        using NodeResult = Result<vsg::ref_ptr<vsg::Node>>;
        mutable vsg::ref_ptr<vsg::Node> _content;
        mutable Future<ContentResult> _downloadFuture;
        mutable Future<NodeResult> _decodeFuture;
        mutable bool _contentRequested = false;
        // true while queued/being compiled (prevents the stale sweep and
        // eviction from yanking a tile mid-compile)
        mutable std::atomic<bool> _compilePending{ false };

        // last computed screen-space error; drives the load queue priority
        // (read by job priority lambdas on worker threads)
        mutable std::atomic<float> _screenSpaceError{ 0.0f };

        // approximate GPU bytes of _content (counted at compile time)
        mutable std::uint64_t _gpuBytes = 0;

        // Frame stamps, all written on the frame loop (record/update) thread.
        // _lastSeenFrame: last frame this tile was traversed or pre-fetched —
        //   drives the stale-load sweep.
        // _lastUsedFrame/_lastUsedTime: last frame this tile's content was
        //   wanted for rendering — drives LRU eviction.
        mutable uint64_t _lastSeenFrame = 0u;
        mutable uint64_t _lastUsedFrame = 0u;
        mutable double _lastUsedTime = 0.0;

        // per-frame caches: the same tile is frustum-tested and SSE-evaluated
        // several times per frame (own traversal, parent's readiness check,
        // parent's pre-fetch loop)
        mutable uint64_t _frustumFrame = ~0ull;
        mutable bool _frustumResult = true;
        mutable uint64_t _sseFrame = ~0ull;
        mutable double _sseValue = 0.0;

        // failure backoff so a bad/unreachable URI doesn't become a permanent
        // hole (no retry) or a hammering loop (instant retry)
        mutable int _failCount = 0;
        mutable std::chrono::steady_clock::time_point _retryNotBefore{};

        // GPU compile failures are counted separately from _failCount: the
        // download succeeds on every retry cycle and resets _failCount, which
        // would otherwise keep a deterministically-bad tile retrying forever.
        mutable int _compileFailCount = 0;

        // set after repeated GPU compile failures (deterministic bad content);
        // the tile stops requesting and no longer gates parent refinement
        mutable bool _permanentlyFailed = false;

        //! Common failure path: bump the fail counter, schedule a backoff
        //! retry, and release the load state so requestContent() can re-fire.
        void failWithBackoff(const std::string& msg) const;

        //! Called when this tile's content failed GPU compilation. Retries a
        //! few times with backoff, then gives up on the tile permanently.
        void onCompileFailed() const;

        //! Dispatch the decode stage for a completed download.
        void dispatchDecode(std::shared_ptr<Content> content) const;

        // child tile nodes (created lazily from _tile.children)
        mutable vsg::ref_ptr<vsg::Group> _childGroup;
        mutable bool _childrenCreated = false;

        void createChildren() const;

        //! Whether every content-bearing child that is inside the view frustum
        //! has its content ready. Off-screen children are excluded: they are
        //! never requested, so requiring them would block refinement forever.
        bool allChildrenReady(const vsg::RecordTraversal& rv) const;

        // SSE helper (caches per frame; also updates _screenSpaceError)
        double computeScreenSpaceError(const vsg::RecordTraversal& rv) const;

        friend class Tiles3DNode;
    };

} // namespace ROCKY_NAMESPACE

EVSG_type_name(rocky::Tiles3DNode)
EVSG_type_name(rocky::Tile3DNode)
