package com.vectorra.maps.offline

import com.vectorra.maps.VectorraBetaApi
import com.vectorra.maps.network.TileCacheStatus
import com.vectorra.maps.network.TileRequest

@VectorraBetaApi("0.8.0-beta.1")
data class VectorraCacheBucketStatus(
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

@VectorraBetaApi("0.8.0-beta.1")
data class VectorraCacheStatus(
    val proxy: VectorraCacheBucketStatus,
    val resources: VectorraCacheBucketStatus
) {
    val totalEntryCount: Int
        get() = proxy.totalEntryCount + resources.totalEntryCount

    val totalBytes: Long
        get() = proxy.totalBytes + resources.totalBytes
}

@VectorraBetaApi("0.8.0-beta.1")
data class VectorraPrefetchTileResult(
    val request: TileRequest,
    val statusCode: Int,
    val cacheStatus: TileCacheStatus,
    val byteCount: Int,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean
        get() = statusCode in 200..299 && errorMessage == null
}

@VectorraBetaApi("0.8.0-beta.1")
data class VectorraPrefetchResult(
    val tiles: List<VectorraPrefetchTileResult>
) {
    val completedCount: Int
        get() = tiles.count { it.isSuccess }

    val failedCount: Int
        get() = tiles.size - completedCount

    val totalBytes: Long
        get() = tiles.sumOf { it.byteCount.toLong() }
}

@VectorraBetaApi("0.8.0-beta.1")
interface VectorraOfflineManager {
    fun cacheStatus(): VectorraCacheStatus

    fun clearCache()

    fun prefetchTiles(requests: List<TileRequest>): VectorraPrefetchResult

    fun prefetchRegion(
        region: VectorraOfflineRegion,
        sources: List<VectorraOfflineTileSource>
    ): VectorraPrefetchResult
}
