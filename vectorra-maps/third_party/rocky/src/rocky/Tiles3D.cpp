/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#include "Tiles3D.h"
#include "json.h"

#include <cmath>
#include <unordered_set>
#include <vector>

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
    // [west_rad, south_rad, east_rad, north_rad, minH_m, maxH_m]
    vsg::dsphere regionToBoundingSphere(const std::vector<double>& r)
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

    /**
     * Streaming SAX handler for tileset.json. Builds the Tiles3DTile tree
     * directly, never materializing a JSON DOM — a 50 MB monolithic
     * tileset.json would otherwise spike memory by hundreds of MB during
     * parsing.
     *
     * Models nlohmann's json_sax interface (duck-typed via json::sax_parse).
     */
    struct TilesetSaxHandler
    {
        using number_integer_t  = json::number_integer_t;
        using number_unsigned_t = json::number_unsigned_t;
        using number_float_t    = json::number_float_t;
        using string_t          = json::string_t;
        using binary_t          = json::binary_t;

        Tiles3DTileset tileset;
        std::string error;

        // Tiles whose "refine" was given explicitly in the JSON. Refine
        // inherits from the parent, but JSON key order is arbitrary — a
        // tile's refine key may appear after its children — so inheritance
        // is resolved in one propagation pass after parsing.
        std::unordered_set<const Tiles3DTile*> explicitRefine;

        struct Frame
        {
            enum Type : uint8_t
            {
                TopLevel,        // the outermost tileset object
                Asset,           // "asset": { ... }
                Tile,            // a tile object (root or child)
                TileChildren,    // "children": [ ... ]
                BoundingVolume,  // "boundingVolume": { ... }
                Content,         // "content": { ... }
                NumberArray,     // box/region/sphere/transform: [ ... ]
                Skip             // any value we don't care about
            };

            Type type;
            Tiles3DTile* tile = nullptr; // owning tile for tile-scoped frames
            std::string key;             // pending key inside this object
            int skipDepth = 0;           // balance counter for Skip frames
            bool sawUri = false;         // Content: "uri" beats legacy "url"
            std::vector<double> numbers; // NumberArray accumulator
        };

        std::vector<Frame> stack;

        bool fail(const std::string& msg)
        {
            if (error.empty())
                error = msg;
            return false;
        }

        Frame& top() { return stack.back(); }

        void push(Frame::Type type, Tiles3DTile* tile = nullptr)
        {
            Frame f;
            f.type = type;
            f.tile = tile;
            if (type == Frame::Skip)
                f.skipDepth = 1;
            stack.push_back(std::move(f));
        }

        static Tiles3DContent& ensureContent(Tiles3DTile* tile)
        {
            if (!tile->content.has_value())
                tile->content = Tiles3DContent{};
            return *tile->content;
        }

        // ---- sax interface --------------------------------------------

        bool key(string_t& val)
        {
            if (stack.empty())
                return fail("unexpected key");
            if (top().type == Frame::Skip)
                return true;
            top().key = std::move(val);
            return true;
        }

        bool start_object(std::size_t)
        {
            if (stack.empty())
            {
                push(Frame::TopLevel);
                return true;
            }

            if (top().type == Frame::Skip)
            {
                ++top().skipDepth;
                return true;
            }

            // snapshot before push_back invalidates the reference
            const Frame::Type ptype = top().type;
            const std::string k = std::move(top().key);
            top().key.clear();
            Tiles3DTile* ptile = top().tile;

            switch (ptype)
            {
            case Frame::TopLevel:
                if (k == "asset")
                    push(Frame::Asset);
                else if (k == "root")
                {
                    auto tile = std::make_shared<Tiles3DTile>();
                    tileset.root = tile;
                    push(Frame::Tile, tile.get());
                }
                else
                    push(Frame::Skip);
                break;

            case Frame::Tile:
                if (k == "boundingVolume")
                    push(Frame::BoundingVolume, ptile);
                else if (k == "content")
                    push(Frame::Content, ptile);
                else
                    push(Frame::Skip);
                break;

            case Frame::TileChildren:
            {
                auto child = std::make_shared<Tiles3DTile>();
                ptile->children.push_back(child);
                push(Frame::Tile, child.get());
                break;
            }

            default:
                // content.boundingVolume, asset extras, objects inside
                // number arrays (malformed), bv extensions, ...
                push(Frame::Skip);
                break;
            }
            return true;
        }

        bool end_object()
        {
            if (stack.empty())
                return fail("unbalanced object");

            if (top().type == Frame::Skip)
            {
                if (--top().skipDepth == 0)
                    stack.pop_back();
                return true;
            }

            stack.pop_back();
            return true;
        }

        bool start_array(std::size_t)
        {
            if (stack.empty())
                return fail("tileset root must be an object");

            if (top().type == Frame::Skip)
            {
                ++top().skipDepth;
                return true;
            }

            const Frame::Type ptype = top().type;
            std::string k = std::move(top().key);
            top().key.clear();
            Tiles3DTile* ptile = top().tile;

            switch (ptype)
            {
            case Frame::Tile:
                if (k == "children")
                    push(Frame::TileChildren, ptile);
                else if (k == "transform")
                {
                    push(Frame::NumberArray, ptile);
                    top().key = std::move(k);
                    top().numbers.reserve(16);
                }
                else
                    push(Frame::Skip);
                break;

            case Frame::BoundingVolume:
                if (k == "box" || k == "region" || k == "sphere")
                {
                    push(Frame::NumberArray, ptile);
                    top().key = std::move(k);
                    top().numbers.reserve(12);
                }
                else
                    push(Frame::Skip);
                break;

            default:
                push(Frame::Skip);
                break;
            }
            return true;
        }

        bool end_array()
        {
            if (stack.empty())
                return fail("unbalanced array");

            auto& t = top();

            if (t.type == Frame::Skip)
            {
                if (--t.skipDepth == 0)
                    stack.pop_back();
                return true;
            }

            if (t.type == Frame::NumberArray)
                commitNumbers(t);

            stack.pop_back();
            return true;
        }

        void commitNumbers(Frame& f)
        {
            const auto& v = f.numbers;
            Tiles3DTile* tile = f.tile;

            if (f.key == "transform" && v.size() == 16)
            {
                auto m = std::make_unique<vsg::dmat4>();
                for (int col = 0; col < 4; ++col)
                    for (int row = 0; row < 4; ++row)
                        (*m)[col][row] = v[col * 4 + row];
                tile->transform = std::move(m);
            }
            else if (f.key == "box" && v.size() == 12)
            {
                // center(3) + half-extents along x(3), y(3), z(3) axes;
                // enclosing sphere radius = sum of the axis lengths
                const vsg::dvec3 center(v[0], v[1], v[2]);
                const double radius =
                    vsg::length(vsg::dvec3(v[3], v[4],  v[5])) +
                    vsg::length(vsg::dvec3(v[6], v[7],  v[8])) +
                    vsg::length(vsg::dvec3(v[9], v[10], v[11]));
                tile->boundingSphere = vsg::dsphere(center, radius);
                tile->regionVolume = false;
            }
            else if (f.key == "region" && v.size() == 6)
            {
                tile->boundingSphere = regionToBoundingSphere(v);
                tile->regionVolume = true;
            }
            else if (f.key == "sphere" && v.size() == 4)
            {
                tile->boundingSphere = vsg::dsphere(vsg::dvec3(v[0], v[1], v[2]), v[3]);
                tile->regionVolume = false;
            }
        }

        bool handleNumber(double d)
        {
            if (stack.empty())
                return fail("unexpected number");

            auto& t = top();
            switch (t.type)
            {
            case Frame::Skip:
                return true;
            case Frame::NumberArray:
                t.numbers.push_back(d);
                return true;
            case Frame::TopLevel:
                if (t.key == "geometricError")
                    tileset.geometricError = d;
                t.key.clear();
                return true;
            case Frame::Tile:
                if (t.key == "geometricError")
                    t.tile->geometricError = d;
                t.key.clear();
                return true;
            default:
                t.key.clear();
                return true;
            }
        }

        bool number_integer(number_integer_t val)   { return handleNumber(static_cast<double>(val)); }
        bool number_unsigned(number_unsigned_t val) { return handleNumber(static_cast<double>(val)); }
        bool number_float(number_float_t val, const string_t&) { return handleNumber(val); }

        bool string(string_t& val)
        {
            if (stack.empty())
                return fail("unexpected string");

            auto& t = top();
            switch (t.type)
            {
            case Frame::Skip:
            case Frame::NumberArray:
                return true;

            case Frame::Asset:
                if (t.key == "version")
                    tileset.asset.version = std::move(val);
                else if (t.key == "gltfUpAxis")
                    tileset.asset.gltfUpAxis = std::move(val);
                t.key.clear();
                return true;

            case Frame::Tile:
                if (t.key == "refine")
                {
                    t.tile->refine = (val == "ADD") ? Tiles3DRefine::ADD : Tiles3DRefine::REPLACE;
                    explicitRefine.insert(t.tile);
                }
                t.key.clear();
                return true;

            case Frame::Content:
                if (t.key == "uri")
                {
                    ensureContent(t.tile).uri = std::move(val);
                    t.sawUri = true;
                }
                else if (t.key == "url" && !t.sawUri) // pre-1.0 legacy key
                {
                    ensureContent(t.tile).uri = std::move(val);
                }
                t.key.clear();
                return true;

            default:
                t.key.clear();
                return true;
            }
        }

        bool null()
        {
            if (!stack.empty() && top().type != Frame::Skip && top().type != Frame::NumberArray)
                top().key.clear();
            return true;
        }

        bool boolean(bool)
        {
            if (!stack.empty() && top().type != Frame::Skip && top().type != Frame::NumberArray)
                top().key.clear();
            return true;
        }

        bool binary(binary_t&)
        {
            if (!stack.empty() && top().type != Frame::Skip && top().type != Frame::NumberArray)
                top().key.clear();
            return true;
        }

        bool parse_error(std::size_t position, const std::string&, const nlohmann::detail::exception& ex)
        {
            return fail("at byte " + std::to_string(position) + ": " + ex.what());
        }

        // ---- post pass ------------------------------------------------

        void propagateRefine(Tiles3DTile* tile, Tiles3DRefine inherited)
        {
            if (explicitRefine.find(tile) == explicitRefine.end())
                tile->refine = inherited;
            for (auto& child : tile->children)
                propagateRefine(child.get(), tile->refine);
        }
    };
}

std::string Tiles3DTileset::resolveContentURI(
    const std::string& uri,
    const std::string& base)
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

Result<Tiles3DTileset> Tiles3DTileset::fromJSON(
    const std::string& jsonStr,
    const std::string& baseURI)
{
    TilesetSaxHandler handler;

    const bool ok = json::sax_parse(jsonStr, &handler);

    if (!ok || !handler.error.empty())
    {
        return Failure("3D Tiles JSON parsing failed" +
            (handler.error.empty() ? std::string() : (": " + handler.error)));
    }

    if (!handler.tileset.root)
        return Failure("3D Tiles tileset has no root tile");

    handler.propagateRefine(handler.tileset.root.get(), Tiles3DRefine::REPLACE);
    handler.tileset.baseURI = std::make_shared<std::string>(baseURI);

    return std::move(handler.tileset);
}
