/**
 * rocky c++
 * Copyright 2023 Pelican Mapping
 * MIT License
 */
#pragma once

#include <rocky/Common.h>
#include <rocky/Utils.h>
#include <functional>
#include <mutex>
#include <shared_mutex>
#include <list>
#include <optional>
#include <unordered_map>

namespace ROCKY_NAMESPACE
{
    /**
    * General-purpose caching interface
    */
    template<class K, class V>
    class Cache
    {
    public:
        virtual std::optional<V> get(const K& k) = 0;
        virtual void put(const K& k, const V& v) = 0;
        virtual void clear() = 0;
        virtual std::size_t capacity() const { return 0LL; }
        virtual std::size_t size() const { return 0LL; }
        virtual std::uint32_t hits() const { return 0LL; }
        virtual std::uint32_t misses() const { return 0LL; }
    };

    namespace detail
    {
        /**
        * ResidentCache cached std::weak_ptr's to shared objects. If the shared
        * object is resideny anywhere in memory, the ResidentCache will be able
        * to return it.
        * K = KEY. Any object that can be hashed for an unordered_map.
        * V = VALUE. Any object that is stored in a shared_ptr.
        */
        template<class K, class V, class METADATA = bool>
        class ResidentCache
        {
        public:
            using entry_t = std::pair<std::weak_ptr<V>, METADATA>;
            mutable std::unordered_map<K, entry_t> _lut;
            mutable std::shared_mutex _mutex;
            std::uint32_t _hits = 0;
            std::uint32_t _misses = 0;
            std::uint32_t _puts = 0;

            using data_t = std::pair<std::shared_ptr<V>, METADATA>;

            std::optional<data_t> get(const K& key)
            {
                std::shared_lock lock(_mutex);

                auto it = _lut.find(key);
                if (it != _lut.end() && !it->second.first.expired())
                {
                    ++_hits;
                    return std::make_pair(it->second.first.lock(), it->second.second);
                }
                else
                {
                    ++_misses;
                    return {};
                }
            }

            void put(const K& key, const std::shared_ptr<V>& value, const METADATA& m)
            {
                std::unique_lock lock(_mutex);
                _lut[key] = entry_t{ value, m };
                ++_puts;
                if (_puts % 64 == 0) {
                    // Clean up expired entries every N puts
                    for (auto it = _lut.begin(); it != _lut.end();) {
                        if (it->second.first.expired()) {
                            it = _lut.erase(it);
                        }
                        else {
                            ++it;
                        }
                    }
                }
            }

            void clear()
            {
                std::shared_lock lock(_mutex);
                _lut.clear();
                _hits = 0;
                _misses = 0;
                _puts = 0;
            }

            std::size_t capacity() const
            {
                return 0; // ResidentCache does not have a fixed capacity
            }

            std::size_t size() const
            {
                std::shared_lock lock(_mutex);
                return _lut.size();
            }

            std::uint32_t hits() const
            {
                return _hits;
            }

            std::uint32_t misses() const
            {
                return _misses;
            }
        };

        /**
        * LRUCache is a thread-safe implementation of a Least Recently Used (LRU) cache.
        * It stores key-value pairs and evicts the least recently used item when the cache reaches its capacity.
        * The cache is protected by a mutex to ensure thread safety.
        * Adapted from https://www.geeksforgeeks.org/lru-cache-implementation
        * Optimized by Copilot
        */
        template<class K, class V>
        class LRUCache : public rocky::Cache<K, V>
        {
        private:
            mutable std::mutex _mutex;
            size_t _capacity;
            size_t _capacityBytes = 0;   // 0 = no byte limit
            size_t _currentBytes = 0;    // tracked only when sizeOf is set
            using E = typename std::pair<K, V>;
            mutable typename std::list<E> _cache;
            mutable std::unordered_map<K, typename std::list<E>::iterator> _map;
            //mutable detail::vector_map<K, typename std::list<E>::iterator> _map;
            std::uint32_t _hits = 0, _misses = 0;

        public:
            //! Optional functor returning the size in bytes of a cached value.
            //! When set (together with setCapacityBytes), the cache evicts
            //! oldest entries to stay within the byte budget as well as the
            //! entry-count capacity. Set before first use; not thread-safe to
            //! change while the cache is in service.
            std::function<size_t(const V&)> sizeOf;

            //! Constructs an LRUCache with the specified capacity.
            //! \param capacity_ The maximum number of items the cache can hold.
            LRUCache(size_t capacity = 32) : _capacity(capacity)
            {
                //_map._container.reserve(capacity);
            }

            //! Sets the cache capacity and clears all current entries and statistics.
            //! \param value The new maximum number of items the cache can hold.
            inline void setCapacity(size_t value)
            {
                std::scoped_lock L(_mutex);
                _cache.clear();
                _map.clear();
                _capacity = std::max((size_t)0, value);
                _currentBytes = 0;
                //_map._container.reserve(_capacity);
                _hits = 0;
                _misses = 0;
            }

            //! Sets a total-bytes budget (0 = unlimited). Requires sizeOf to
            //! be set for byte accounting to take effect.
            inline void setCapacityBytes(size_t value)
            {
                std::scoped_lock L(_mutex);
                _capacityBytes = value;
            }

            //! Retrieves the value associated with the given key, if present.
            //! Moves the accessed item to the most recently used position.
            //! \param key The key to look up.
            //! \return An optional containing the value if found, or empty if not found.
            inline std::optional<V> get(const K& key) override
            {
                if (_capacity == 0)
                    return {};
                std::scoped_lock L(_mutex);
                auto it = _map.find(key);
                if (it == _map.end()) {
                    ++_misses;
                    return {};
                }
                if (it->second != std::prev(_cache.end()))
                    _cache.splice(_cache.end(), _cache, it->second);
                ++_hits;
                return it->second->second;
            }

            //! Inserts or updates the value for the given key.
            //! If the key already exists, updates its value and moves it to the most recently used position.
            //! If the cache is full, evicts the least recently used item.
            //! \param key The key to insert or update.
            //! \param value The value to associate with the key.
            inline void put(const K& key, const V& value) override
            {
                if (_capacity == 0)
                    return;
                std::scoped_lock L(_mutex);
                auto it = _map.find(key);
                if (it != _map.end()) {
                    // Update value and move to back
                    if (sizeOf) {
                        _currentBytes -= std::min(_currentBytes, sizeOf(it->second->second));
                        _currentBytes += sizeOf(value);
                    }
                    it->second->second = value;
                    _cache.splice(_cache.end(), _cache, it->second);
                }
                else {
                    if (sizeOf)
                        _currentBytes += sizeOf(value);
                    _cache.emplace_back(key, value);
                    _map[key] = std::prev(_cache.end());
                }

                // Trim oldest entries over the count capacity and (if byte
                // accounting is active) over the byte budget. Never evict the
                // entry just inserted.
                while (_cache.size() > 1 &&
                    (_cache.size() > _capacity ||
                        (sizeOf && _capacityBytes > 0 && _currentBytes > _capacityBytes)))
                {
                    if (sizeOf)
                        _currentBytes -= std::min(_currentBytes, sizeOf(_cache.front().second));
                    _map.erase(_cache.front().first);
                    _cache.pop_front();
                }
            }

            //! Returns the maximum number of items the cache can hold.
            inline std::size_t capacity() const override
            {
                return _capacity;
            }

            std::size_t size() const override
            {
                std::scoped_lock lock(_mutex);
                return _map.size();
            }

            std::uint32_t hits() const override
            {
                return _hits;
            }

            std::uint32_t misses() const override
            {
                return _misses;
            }

            //! Clears all entries from the cache and resets statistics.
            inline void clear()
            {
                std::scoped_lock L(_mutex);
                _cache.clear();
                _map.clear();
                _currentBytes = 0;
                _hits = 0, _misses = 0;
            }
        };
    }
}