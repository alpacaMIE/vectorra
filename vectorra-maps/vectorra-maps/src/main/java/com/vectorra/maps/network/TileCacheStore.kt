package com.vectorra.maps.network

import java.io.File

internal data class TileCacheStoreStatus(
    val memoryEntryCount: Int,
    val memoryBytes: Long,
    val diskEntryCount: Int,
    val diskBytes: Long
) {
    val totalEntryCount: Int
        get() = memoryEntryCount + diskEntryCount

    val totalBytes: Long
        get() = memoryBytes + diskBytes
}

internal class TileCacheStore(
    private val memoryCache: TileMemoryCache? = null,
    private val diskCache: TileDiskCache? = null,
    private val policy: TileCachePolicy = TileCachePolicy()
) {
    constructor(cacheDirectory: File, policy: TileCachePolicy) : this(
        memoryCache = TileMemoryCache(policy.memoryMaxBytes),
        diskCache = TileDiskCache(cacheDirectory, policy.diskMaxBytes),
        policy = policy
    )

    fun isCacheable(request: TileRequest): Boolean {
        return policy.isCacheable(request)
    }

    fun get(request: TileRequest): TileResponse? {
        if (!isCacheable(request)) {
            return null
        }
        val key = TileCacheKeys.from(request)
        memoryCache?.get(key, request)?.let { return it }
        diskCache?.get(key)?.let { body ->
            val cached = TileResponse(
                request = request,
                statusCode = 200,
                body = body,
                cacheStatus = TileCacheStatus.DISK
            )
            memoryCache?.put(key, cached)
            return cached
        }
        return null
    }

    fun put(response: TileResponse) {
        val request = response.request
        if (!isCacheable(request) || !policy.isSuccessful(response)) {
            return
        }
        val key = TileCacheKeys.from(request)
        memoryCache?.put(key, response)
        diskCache?.put(key, response.body)
    }

    fun status(): TileCacheStoreStatus {
        return TileCacheStoreStatus(
            memoryEntryCount = memoryCache?.entryCount() ?: 0,
            memoryBytes = memoryCache?.sizeBytes() ?: 0L,
            diskEntryCount = diskCache?.entryCount() ?: 0,
            diskBytes = diskCache?.sizeBytes() ?: 0L
        )
    }

    fun clear() {
        memoryCache?.clear()
        diskCache?.clear()
    }
}
