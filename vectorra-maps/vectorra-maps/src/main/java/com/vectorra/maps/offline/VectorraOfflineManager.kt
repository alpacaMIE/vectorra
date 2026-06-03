package com.vectorra.maps.offline

import com.vectorra.maps.VectorraBetaApi

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
interface VectorraOfflineManager {
    fun cacheStatus(): VectorraCacheStatus

    fun clearCache()
}
