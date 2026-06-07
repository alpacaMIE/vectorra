/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#include "Tiles3D.h"
#include "json.h"

#include <cmath>
#include <filesystem>

using namespace ROCKY_NAMESPACE;

namespace
{
    // WGS84 semi-major axis (metres)
    constexpr double WGS84_A = 6378137.0;
    // WGS84 eccentricity squared
    constexpr double WGS84_E2 = 0.00669437999014;

    // Convert geodetic (lon_rad, lat_rad, height_m) to ECEF
    vsg::dvec3 geodeticToECEF(double lonRad, double latRad, double h)
    {
        const double sinLat = std::sin(latRad);
        const double cosLat = std::cos(latRad);
        const double sinLon = std::sin(lonRad);
        const double cosLon = std::cos(lonRad);
        const double N = WGS84_A / std::sqrt(1.0 - WGS84_E2 * sinLat * sinLat);
        return {
            (N + h) * cosLat * cosLon,
            (N + h) * cosLat * sinLon,
            (N * (1.0 - WGS84_E2) + h) * sinLat
        };
    }

    // Compute a bounding sphere enclosing 8 ECEF corners derived from a region
    vsg::dsphere regionToBoundingSphere(const std::array<double, 6>& r)
    {
        const double west  = r[0], south = r[1], east = r[2], north = r[3];
        const double minH  = r[4], maxH  = r[5];

        std::array<vsg::dvec3, 8> corners;
        int i = 0;
        for (double lat : { south, north })
            for (double lon : { west, east })
                for (double h : { minH, maxH })
                    corners[i++] = geodeticToECEF(lon, lat, h);

        vsg::dvec3 center{};
        for (auto& c : corners) center += c;
        center /= 8.0;

        double r2 = 0.0;
        for (auto& c : corners)
        {
            auto d = c - center;
            r2 = std::max(r2, vsg::dot(d, d));
        }
        return { center, std::sqrt(r2) };
    }

    // Resolve a (possibly relative) URI relative to a base URI
    std::string resolveURI(const std::string& uri, const std::string& base)
    {
        if (uri.empty()) return uri;
        // Already absolute
        if (uri.rfind("http://", 0) == 0 || uri.rfind("https://", 0) == 0 ||
            uri.rfind("file://", 0) == 0)
            return uri;

        // base = absolute URL of the tileset.json; strip filename part
        const auto lastSlash = base.find_last_of("/\\");
        const std::string dir = (lastSlash != std::string::npos)
            ? base.substr(0, lastSlash + 1)
            : base;
        return dir + uri;
    }

    std::shared_ptr<Tiles3DTile> parseTile(
        const json& j,
        const std::string& baseURI,
        Tiles3DRefine parentRefine);

    Tiles3DBoundingVolume parseBoundingVolume(const json& j)
    {
        Tiles3DBoundingVolume bv;
        JSON_TRY {
            if (j.contains("sphere")) {
                auto& s = j["sphere"];
                std::array<double,4> arr{};
                for (int i = 0; i < 4; ++i) arr[i] = s[i].get<double>();
                bv.sphere = arr;
            }
            else if (j.contains("region")) {
                auto& r = j["region"];
                std::array<double,6> arr{};
                for (int i = 0; i < 6; ++i) arr[i] = r[i].get<double>();
                bv.region = arr;
            }
            else if (j.contains("box")) {
                auto& b = j["box"];
                std::array<double,12> arr{};
                for (int i = 0; i < 12; ++i) arr[i] = b[i].get<double>();
                bv.box = arr;
            }
        }
        JSON_CATCH {}
        return bv;
    }

    std::shared_ptr<Tiles3DTile> parseTile(
        const json& j,
        const std::string& baseURI,
        Tiles3DRefine parentRefine)
    {
        auto tile = std::make_shared<Tiles3DTile>();
        JSON_TRY {
            if (j.contains("boundingVolume"))
                tile->boundingVolume = parseBoundingVolume(j["boundingVolume"]);

            if (j.contains("geometricError"))
                tile->geometricError = j["geometricError"].get<double>();

            // Refine policy (inherits parent if not specified)
            tile->refine = parentRefine;
            if (j.contains("refine")) {
                const auto& rs = j["refine"].get<std::string>();
                tile->refine = (rs == "ADD") ? Tiles3DRefine::ADD : Tiles3DRefine::REPLACE;
            }

            // 4×4 column-major transform
            if (j.contains("transform")) {
                auto& t = j["transform"];
                vsg::dmat4 m;
                for (int col = 0; col < 4; ++col)
                    for (int row = 0; row < 4; ++row)
                        m[col][row] = t[col * 4 + row].get<double>();
                tile->transform = m;
            }

            // Content (single)
            if (j.contains("content")) {
                const auto& c = j["content"];
                Tiles3DContent content;
                if (c.contains("uri"))
                    content.uri = c["uri"].get<std::string>();
                else if (c.contains("url"))
                    content.uri = c["url"].get<std::string>();
                content.resolved = resolveURI(content.uri, baseURI);
                tile->content = content;
            }

            // Children
            if (j.contains("children")) {
                for (const auto& child : j["children"])
                    tile->children.push_back(parseTile(child, baseURI, tile->refine));
            }
        }
        JSON_CATCH {}
        return tile;
    }
}

vsg::dsphere Tiles3DBoundingVolume::asBoundingSphere() const
{
    if (sphere.has_value()) {
        const auto& s = *sphere;
        return vsg::dsphere(vsg::dvec3(s[0], s[1], s[2]), s[3]);
    }
    if (region.has_value())
        return regionToBoundingSphere(*region);
    if (box.has_value()) {
        const auto& b = *box;
        const vsg::dvec3 center(b[0], b[1], b[2]);
        const vsg::dvec3 xAxis(b[3], b[4],  b[5]);
        const vsg::dvec3 yAxis(b[6], b[7],  b[8]);
        const vsg::dvec3 zAxis(b[9], b[10], b[11]);
        const double radius = vsg::length(xAxis) + vsg::length(yAxis) + vsg::length(zAxis);
        return vsg::dsphere(center, radius);
    }
    return vsg::dsphere();
}

Result<Tiles3DTileset> Tiles3DTileset::fromJSON(
    const std::string& jsonStr,
    const std::string& baseURI)
{
    auto j = parse_json(jsonStr);
    if (j.status.failed())
        return j.status.error();

    Tiles3DTileset ts;
    JSON_TRY {
        if (j.contains("asset")) {
            const auto& a = j["asset"];
            if (a.contains("version"))
                ts.asset.version = a["version"].get<std::string>();
            if (a.contains("gltfUpAxis"))
                ts.asset.gltfUpAxis = a["gltfUpAxis"].get<std::string>();
        }
        if (j.contains("geometricError"))
            ts.geometricError = j["geometricError"].get<double>();
        if (j.contains("root"))
            ts.root = parseTile(j["root"], baseURI, Tiles3DRefine::REPLACE);
    }
    JSON_CATCH {
        return Failure("3D Tiles JSON parsing failed");
    }

    if (!ts.root)
        return Failure("3D Tiles tileset has no root tile");

    return ts;
}
