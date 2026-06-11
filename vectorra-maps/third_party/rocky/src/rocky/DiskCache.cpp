/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#include "DiskCache.h"
#include "IOTypes.h"
#include "Log.h"

#include <algorithm>
#include <fstream>
#include <vector>

using namespace ROCKY_NAMESPACE;
using namespace ROCKY_NAMESPACE::detail;

namespace
{
    // FNV-1a 64-bit with a configurable offset basis; two passes with
    // different bases give a 128-bit key, making collisions implausible.
    std::uint64_t fnv1a64(const std::string& s, std::uint64_t basis)
    {
        std::uint64_t h = basis;
        for (unsigned char c : s)
        {
            h ^= c;
            h *= 1099511628211ull;
        }
        return h;
    }

    std::string hexKey(const std::string& url)
    {
        char buf[33];
        std::snprintf(buf, sizeof(buf), "%016llx%016llx",
            (unsigned long long)fnv1a64(url, 14695981039346656037ull),
            (unsigned long long)fnv1a64(url, 0x9e3779b97f4a7c15ull));
        return std::string(buf);
    }
}

DiskContentCache::DiskContentCache(const std::string& directory, std::uint64_t maxBytes) :
    _dir(directory),
    _maxBytes(maxBytes)
{
    std::error_code ec;
    std::filesystem::create_directories(_dir, ec);
    _valid = !ec && std::filesystem::is_directory(_dir, ec);
    if (!_valid)
        Log()->warn("DiskContentCache: cannot use directory \"{}\"", directory);
}

std::filesystem::path
DiskContentCache::pathFor(const std::string& url) const
{
    return _dir / (hexKey(url) + ".bin");
}

std::optional<Content>
DiskContentCache::get(const std::string& url)
{
    if (!_valid)
        return {};

    const auto path = pathFor(url);

    std::ifstream in(path, std::ios::binary);
    if (!in)
        return {};

    Content content;
    if (!std::getline(in, content.type))
        return {};

    std::error_code ec;
    const auto fileSize = std::filesystem::file_size(path, ec);
    if (ec)
        return {};

    const auto bodySize = fileSize > content.type.size() + 1
        ? (std::size_t)(fileSize - content.type.size() - 1)
        : 0u;

    content.data.resize(bodySize);
    in.read(content.data.data(), (std::streamsize)bodySize);
    if (!in)
        return {};

    content.timestamp = std::chrono::system_clock::now();

    // refresh write time so the trim pass treats this entry as fresh
    std::filesystem::last_write_time(path, std::filesystem::file_time_type::clock::now(), ec);

    return content;
}

void
DiskContentCache::put(const std::string& url, const Content& content)
{
    if (!_valid)
        return;

    // type is stored as the first line of the file; a newline in it would
    // corrupt the framing (never happens with real content types)
    if (content.type.find('\n') != std::string::npos)
        return;

    const auto path = pathFor(url);
    const auto temp = _dir / (hexKey(url) + ".tmp" + std::to_string(_tempCounter.fetch_add(1) & 0xffff));

    {
        std::ofstream out(temp, std::ios::binary | std::ios::trunc);
        if (!out)
            return;
        out << content.type << '\n';
        out.write(content.data.data(), (std::streamsize)content.data.size());
        if (!out)
        {
            out.close();
            std::error_code ec;
            std::filesystem::remove(temp, ec);
            return;
        }
    }

    std::error_code ec;
    std::filesystem::rename(temp, path, ec);
    if (ec)
    {
        // Windows can refuse to replace a file another thread has open;
        // the existing entry is identical anyway, so just drop the temp.
        std::filesystem::remove(temp, ec);
    }

    if (_putsSinceTrim.fetch_add(1) % 64 == 63)
        trim();
}

void
DiskContentCache::trim()
{
    std::unique_lock lock(_trimMutex, std::try_to_lock);
    if (!lock.owns_lock())
        return; // another thread is already trimming

    struct Entry
    {
        std::filesystem::path path;
        std::uint64_t size;
        std::filesystem::file_time_type mtime;
    };

    std::vector<Entry> entries;
    std::uint64_t total = 0;

    std::error_code ec;
    for (auto& de : std::filesystem::directory_iterator(_dir, ec))
    {
        std::error_code fec;
        if (!de.is_regular_file(fec))
            continue;
        const auto size = de.file_size(fec);
        if (fec)
            continue;
        const auto mtime = de.last_write_time(fec);
        if (fec)
            continue;
        entries.push_back({ de.path(), size, mtime });
        total += size;
    }

    if (total <= _maxBytes)
        return;

    std::sort(entries.begin(), entries.end(),
        [](const Entry& a, const Entry& b) { return a.mtime < b.mtime; });

    // trim to 90% of budget so we don't re-trim on every subsequent put
    const std::uint64_t target = _maxBytes - _maxBytes / 10;
    for (auto& e : entries)
    {
        if (total <= target)
            break;
        std::error_code rec;
        if (std::filesystem::remove(e.path, rec))
            total -= std::min(total, e.size);
    }
}
