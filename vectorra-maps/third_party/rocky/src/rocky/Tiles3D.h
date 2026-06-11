/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#pragma once
#include <rocky/Common.h>
#include <rocky/Result.h>
#include <vsg/maths/mat4.h>
#include <vsg/maths/sphere.h>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace ROCKY_NAMESPACE
{
    enum class Tiles3DRefine { REPLACE, ADD };

    struct Tiles3DContent
    {
        // Original (usually relative) URI. Resolved against the tileset's
        // baseURI at request time — storing one absolute URI string per tile
        // costs tens of MB on datasets with hundreds of thousands of tiles.
        std::string uri;
    };

    //! One tile of a 3D Tiles hierarchy. Kept deliberately small: city-scale
    //! tilesets carry this struct a few hundred thousand times.
    struct Tiles3DTile
    {
        // Bounding volume, folded into a bounding sphere at parse time.
        // For region volumes the sphere is in ECEF per spec (unaffected by
        // tile transforms); box/sphere volumes are in the tile's local frame.
        vsg::dsphere boundingSphere;
        bool regionVolume = false;

        double geometricError = 0.0;
        Tiles3DRefine refine = Tiles3DRefine::REPLACE;

        // optional column-major 4x4 transform: 8 bytes when absent instead of
        // optional<dmat4>'s 136 — most tiles in real datasets have none.
        std::unique_ptr<vsg::dmat4> transform;

        std::optional<Tiles3DContent> content;
        std::vector<std::shared_ptr<Tiles3DTile>> children;
    };

    struct Tiles3DAsset
    {
        std::string version;
        std::string gltfUpAxis; // "Y" or "Z"
    };

    struct Tiles3DTileset
    {
        Tiles3DAsset asset;
        double geometricError = 0.0;
        std::shared_ptr<Tiles3DTile> root;

        // Absolute URI of the tileset.json; all relative content URIs
        // resolve against this. Shared by every tile node of the tileset.
        std::shared_ptr<const std::string> baseURI;

        //! Parse a tileset from a JSON string (streaming SAX parse — no DOM
        //! is built, so a 50 MB tileset.json does not spike memory).
        //! @param json      Raw JSON text of tileset.json
        //! @param baseURI   Absolute URI of tileset.json (used to resolve relative content URIs)
        static Result<Tiles3DTileset> fromJSON(
            const std::string& json,
            const std::string& baseURI);

        //! Resolve a (possibly relative) content URI against a base URI.
        static std::string resolveContentURI(
            const std::string& uri,
            const std::string& base);
    };

} // namespace ROCKY_NAMESPACE
