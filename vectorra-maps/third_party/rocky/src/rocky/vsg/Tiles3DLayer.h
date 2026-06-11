/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 *
 * Tiles3DLayer: a rocky map layer that renders a 3D Tiles tileset.
 * Inherits NodeLayer; the scene-graph node is a Tiles3DNode.
 */
#pragma once
#include <rocky/vsg/NodeLayer.h>
#include <rocky/vsg/Tiles3DNode.h>
#include <rocky/URI.h>
#include <rocky/Tiles3D.h>
#include <rocky/Threading.h>
#include <atomic>

namespace ROCKY_NAMESPACE
{
    /**
     * Rocky map layer wrapping a 3D Tiles tileset.
     *
     * Usage (C++ / JNI):
     *   auto layer = Tiles3DLayer::create();
     *   layer->tilesetURI = URI("https://example.com/tileset.json", ctx);
     *   layer->maximumScreenSpaceError = 16.0f;
     *   layer->maximumLoadedTiles = 1024;
     *   layer->open(io);
     *   map->add(layer);
     */
    class ROCKY_EXPORT Tiles3DLayer : public Inherit<NodeLayer, Tiles3DLayer>
    {
    public:
        //! URI of the tileset.json (including any auth headers via URI::Context).
        URI tilesetURI;

        //! Maximum screen-space error (pixels). Higher = coarser LOD.
        float maximumScreenSpaceError = 16.0f;

        //! Maximum number of tile content nodes resident in memory.
        unsigned maximumLoadedTiles = 1024u;

        //! Approximate GPU byte budget (geometry + textures) for resident
        //! tile content; eviction triggers on whichever of this and
        //! maximumLoadedTiles is exceeded first.
        std::uint64_t maximumResidentBytes = 384ull * 1024 * 1024;

        //! VSG context — must be set before calling open().
        //! Typically: layer->vsgctx = app->vsgcontext;
        VSGContext vsgctx = nullptr;

        //! Update the viewport height (pixels) used for SSE calculation.
        //! Call this from the JNI when the surface is created or resized.
        void setViewportHeight(float height);

    protected:
        Result<> openImplementation(const IOOptions& io) override;
        void closeImplementation() override;

    private:
        vsg::ref_ptr<Tiles3DNode> _tilesNode;
        VSGContext _vsgctx = nullptr;
        std::atomic<float> _viewportHeight{ 1080.0f };
        // Background open state — token is set to true on close to prevent
        // the async callback from touching a destroyed layer
        mutable Future<int> _openFuture;
        std::shared_ptr<std::atomic<bool>> _openCanceled;
    };

} // namespace ROCKY_NAMESPACE
