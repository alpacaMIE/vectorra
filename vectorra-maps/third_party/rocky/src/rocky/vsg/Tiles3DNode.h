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

#include <list>
#include <mutex>
#include <atomic>

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
            unsigned maxTiles = 256u);

        void traverse(vsg::RecordTraversal&) const override;
        void traverse(vsg::Visitor&) override;
        void traverse(vsg::ConstVisitor&) const override;

        //! Configuration
        float maximumScreenSpaceError = 16.0f;
        unsigned maximumLoadedTiles   = 256u;

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

        void expireTiles(uint64_t frameNumber, double referenceTime) const;

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

        // child tile nodes (created lazily from _tile.children)
        mutable vsg::ref_ptr<vsg::Group> _childGroup;
        mutable bool _childrenCreated = false;

        void createChildren() const;
        bool allChildrenReady() const;

        // SSE and frustum helpers
        double computeScreenSpaceError(const vsg::RecordTraversal& rv) const;
        bool intersectsFrustum(const vsg::RecordTraversal& rv) const;

        vsg::ref_ptr<vsg::Node> loadContentSync() const; // runs in thread pool
    };

} // namespace ROCKY_NAMESPACE

EVSG_type_name(rocky::Tiles3DNode)
EVSG_type_name(rocky::Tile3DNode)
