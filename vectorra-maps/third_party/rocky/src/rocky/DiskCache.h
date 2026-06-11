/**
 * rocky c++
 * Copyright 2026 Pelican Mapping
 * MIT License
 */
#pragma once
#include <rocky/Common.h>

#include <atomic>
#include <cstdint>
#include <filesystem>
#include <mutex>
#include <optional>
#include <string>

namespace ROCKY_NAMESPACE
{
    struct Content;

    namespace detail
    {
        /**
         * Persistent content cache: one file per URL under a root directory,
         * trimmed to a byte budget by oldest write time.
         *
         * v1 semantics: no ETag / expiry handling — a cached entry is served
         * forever (until trimmed). Intended for payloads that are immutable
         * in practice, like 3D Tiles content and basemap tiles.
         *
         * Thread-safe. get/put go straight to the filesystem; the mutex only
         * serializes the periodic trim pass.
         */
        class ROCKY_EXPORT DiskContentCache
        {
        public:
            //! @param directory  Root folder (created if missing)
            //! @param maxBytes   Total size budget for the folder
            DiskContentCache(
                const std::string& directory,
                std::uint64_t maxBytes = 512ull * 1024 * 1024);

            //! False if the directory could not be created/accessed.
            bool valid() const { return _valid; }

            //! Fetch a cached entry; refreshes its write time on hit.
            std::optional<Content> get(const std::string& url);

            //! Store an entry (atomically: temp file + rename).
            void put(const std::string& url, const Content& content);

        private:
            std::filesystem::path pathFor(const std::string& url) const;
            void trim();

            std::filesystem::path _dir;
            std::uint64_t _maxBytes;
            bool _valid = false;
            std::mutex _trimMutex;
            std::atomic<unsigned> _putsSinceTrim{ 0 };
            std::atomic<unsigned> _tempCounter{ 0 };
        };
    }
}
