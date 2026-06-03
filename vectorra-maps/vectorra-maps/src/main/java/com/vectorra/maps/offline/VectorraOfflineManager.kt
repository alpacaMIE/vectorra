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
enum class VectorraPrefetchTaskState {
    RUNNING,
    COMPLETED,
    CANCELED
}

@VectorraBetaApi("0.8.0-beta.1")
data class VectorraPrefetchProgress(
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val totalBytes: Long,
    val state: VectorraPrefetchTaskState
) {
    init {
        require(totalCount >= 0) { "Prefetch progress totalCount must be greater than or equal to 0." }
        require(completedCount >= 0) { "Prefetch progress completedCount must be greater than or equal to 0." }
        require(failedCount >= 0) { "Prefetch progress failedCount must be greater than or equal to 0." }
        require(completedCount + failedCount <= totalCount) {
            "Prefetch progress completed and failed counts must not exceed totalCount."
        }
        require(totalBytes >= 0L) { "Prefetch progress totalBytes must be greater than or equal to 0." }
    }

    val finishedCount: Int
        get() = completedCount + failedCount

    val pendingCount: Int
        get() = totalCount - finishedCount
}

@VectorraBetaApi("0.8.0-beta.1")
fun interface VectorraPrefetchProgressListener {
    fun onProgress(progress: VectorraPrefetchProgress)
}

@VectorraBetaApi("0.8.0-beta.1")
interface VectorraPrefetchTask {
    val progress: VectorraPrefetchProgress

    val state: VectorraPrefetchTaskState
        get() = progress.state

    fun cancel(): Boolean

    fun await(): VectorraPrefetchResult
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

    fun prefetchTilesAsync(
        requests: List<TileRequest>,
        listener: VectorraPrefetchProgressListener? = null
    ): VectorraPrefetchTask

    fun prefetchRegionAsync(
        region: VectorraOfflineRegion,
        sources: List<VectorraOfflineTileSource>,
        listener: VectorraPrefetchProgressListener? = null
    ): VectorraPrefetchTask
}
