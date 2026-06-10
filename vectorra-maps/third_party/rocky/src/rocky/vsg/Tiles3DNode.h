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

#include <atomic>
#include <chrono>
#include <list>
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

        //! Maximum number of tile content loads in flight at once. Requests
        //! over budget are simply skipped and retried on a later traversal.
        unsigned maxConcurrentLoads = 32u;

        //! Viewport height in pixels; set from the JNI after surface creation/resize.
        std::atomic<float> viewportHeight{ 1080.0f };

        //! Move a tile to the back of the LRU tracker (called by Tile3DNode).
        void touchTile(Tile3DNode* tile);

        //! URI context (base URL + auth headers) passed to every tile content request.
        const URI::Context& uriContext() const { return _uriContext; }

        //! VSG context for GPU compilation.
        VSGContext vsgContext() const { return _vsgctx; }

    private:
        using TileTracker = std::list<Tile3DNode*>;

        URI::Context _uriContext;
        VSGContext _vsgctx;
        vsg::ref_ptr<vsg::Group> _root; // wraps the root Tile3DNode
        mutable TileTracker _tracker;
        mutable std::mutex _trackerMutex;
        float _maxTileAge = 5.0f;    // seconds before an idle tile is evicted
        mutable uint64_t _lastExpireFrame = 0u;

        // ECEF-space view frustum, updated once per frame in traverse() before any
        // tile-local transforms are applied. Tile3DNode reads this for frustum culling.
        mutable vsg::Frustum _ecefFrustum;

        // Loads in flight (shared with worker lambdas so it stays valid even if
        // this node is destroyed while a download is still running).
        std::shared_ptr<std::atomic<int>> _inFlightLoads;

        // Loads that completed since the last update pass. Workers bump this;
        // the onUpdate subscription below converts it into a frame request so
        // newly downloaded content gets resolved/compiled even when the app is
        // in on-demand rendering mode and the user is not interacting.
        std::shared_ptr<std::atomic<int>> _loadsCompleted;
        CallbackSub _updateSub;

        // Tiles whose content finished loading and awaits GPU compilation.
        // Drained a few tiles per update pass so the (blocking) compile never
        // stalls the frame loop for an entire backlog.
        mutable std::mutex _readyQueueMutex;
        mutable std::vector<std::pair<vsg::ref_ptr<Tile3DNode>, vsg::ref_ptr<vsg::Node>>> _readyQueue;
        mutable bool _compileScheduled = false;

        void expireTiles(uint64_t frameNumber, double referenceTime) const;

        //! Queue a loaded tile for the batched compile pass (called by Tile3DNode).
        void enqueueCompile(vsg::ref_ptr<Tile3DNode> tile, vsg::ref_ptr<vsg::Node> node) const;

        //! Compile a bounded batch of queued tiles; reschedules itself while backlogged.
        void processCompileQueue(VSGContext ctx) const;

        friend class Tile3DNode;
    };


    /**
     * VSG node rendering one 3D Tiles tile (LOD + async content).
     * Analogous to OSGEarth's ThreeDTileNode.
     */
    class ROCKY_EXPORT Tile3DNode : public vsg::Inherit<vsg::MatrixTransform, Tile3DNode>
    {
    public:
        Tile3DNode(
            Tiles3DNode* tilesetNode,
            const Tiles3DTile& tile,
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

        // LRU tracker linkage (managed by Tiles3DNode)
        mutable std::list<Tile3DNode*>::iterator _trackerItr;
        mutable bool _trackerItrValid = false;
        mutable uint64_t _lastCulledFrame = 0u;
        mutable double _lastCulledTime = 0.0;
        bool autoUnload = true;

    private:
        Tiles3DNode* _tilesetNode;
        Tiles3DTile _tile;
        vsg::dsphere _boundingSphere;

        // content load state
        using NodeResult = Result<vsg::ref_ptr<vsg::Node>>;
        mutable vsg::ref_ptr<vsg::Node> _content;
        mutable Future<NodeResult> _loadFuture;
        mutable bool _contentRequested = false;
        // true while a queued batch compile is pending (prevents false-negative eviction)
        mutable std::atomic<bool> _compilePending{ false };

        // last computed screen-space error; drives the load queue priority
        mutable std::atomic<float> _screenSpaceError{ 0.0f };

        // failure backoff so a bad/unreachable URI doesn't become a permanent
        // hole (no retry) or a hammering loop (instant retry)
        mutable int _failCount = 0;
        mutable std::chrono::steady_clock::time_point _retryNotBefore{};

        // child tile nodes (created lazily from _tile.children)
        mutable vsg::ref_ptr<vsg::Group> _childGroup;
        mutable bool _childrenCreated = false;

        void createChildren() const;

        //! Whether every content-bearing child that is inside the view frustum
        //! has its content ready. Off-screen children are excluded: they are
        //! never requested, so requiring them would block refinement forever.
        bool allChildrenReady(const vsg::RecordTraversal& rv) const;

        // SSE helper
        double computeScreenSpaceError(const vsg::RecordTraversal& rv) const;

        friend class Tiles3DNode;
    };

} // namespace ROCKY_NAMESPACE

EVSG_type_name(rocky::Tiles3DNode)
EVSG_type_name(rocky::Tile3DNode)
