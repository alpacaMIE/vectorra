/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#pragma once
#include <rocky/Common.h>
#include <rocky/Result.h>
#include <rocky/URI.h>
#include <vsg/maths/mat4.h>
#include <vsg/maths/sphere.h>
#include <array>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace ROCKY_NAMESPACE
{
    enum class Tiles3DRefine { REPLACE, ADD };

    struct Tiles3DBoundingVolume
    {
        // box: 12 doubles — center(3) + half-extents along x(3), y(3), z(3) axes
        std::optional<std::array<double, 12>> box;
        // region: [west_rad, south_rad, east_rad, north_rad, minH_m, maxH_m]
        std::optional<std::array<double, 6>> region;
        // sphere: [cx, cy, cz, radius]
        std::optional<std::array<double, 4>> sphere;

        //! Convert whichever field is set to a world-space bounding sphere.
        vsg::dsphere asBoundingSphere() const;

        bool valid() const {
            return box.has_value() || region.has_value() || sphere.has_value();
        }
    };

    struct Tiles3DContent
    {
        std::string uri;      // original (possibly relative) URI
        std::string resolved; // absolute URI after base-URI resolution
    };

    struct Tiles3DTile
    {
        Tiles3DBoundingVolume boundingVolume;
        double geometricError = 0.0;
        Tiles3DRefine refine = Tiles3DRefine::REPLACE;
        std::optional<vsg::dmat4> transform;
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

        //! Parse a tileset from a JSON string.
        //! @param json      Raw JSON text of tileset.json
        //! @param baseURI   Absolute URI of tileset.json (used to resolve relative content URIs)
        static Result<Tiles3DTileset> fromJSON(
            const std::string& json,
            const std::string& baseURI);
    };

} // namespace ROCKY_NAMESPACE
