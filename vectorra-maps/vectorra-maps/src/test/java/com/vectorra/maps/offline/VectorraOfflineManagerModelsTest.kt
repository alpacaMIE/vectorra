package com.vectorra.maps.offline

import com.vectorra.maps.network.TileCacheStatus
import com.vectorra.maps.network.TileRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraOfflineManagerModelsTest {
    @Test
    fun cacheBucketStatusReportsTotals() {
        val status = VectorraCacheBucketStatus(
            memoryEntryCount = 2,
            memoryBytes = 100L,
            diskEntryCount = 3,
            diskBytes = 500L
        )

        assertEquals(5, status.totalEntryCount)
        assertEquals(600L, status.totalBytes)
    }

    @Test
    fun cacheStatusAggregatesProxyAndResourceBuckets() {
        val status = VectorraCacheStatus(
            proxy = VectorraCacheBucketStatus(
                memoryEntryCount = 1,
                memoryBytes = 10L,
                diskEntryCount = 2,
                diskBytes = 20L
            ),
            resources = VectorraCacheBucketStatus(
                memoryEntryCount = 3,
                memoryBytes = 30L,
                diskEntryCount = 4,
                diskBytes = 40L
            )
        )

        assertEquals(10, status.totalEntryCount)
        assertEquals(100L, status.totalBytes)
    }

    @Test
    fun prefetchResultReportsSuccessFailureAndBytes() {
        val success = VectorraPrefetchTileResult(
            request = TileRequest(url = "https://tiles.example.com/success.pbf"),
            statusCode = 200,
            cacheStatus = TileCacheStatus.MISS,
            byteCount = 12
        )
        val failure = VectorraPrefetchTileResult(
            request = TileRequest(url = "https://tiles.example.com/failure.pbf"),
            statusCode = 504,
            cacheStatus = TileCacheStatus.MISS,
            byteCount = 0,
            errorMessage = "timeout"
        )

        val result = VectorraPrefetchResult(listOf(success, failure))

        assertTrue(success.isSuccess)
        assertFalse(failure.isSuccess)
        assertEquals(1, result.completedCount)
        assertEquals(1, result.failedCount)
        assertEquals(12L, result.totalBytes)
    }
}
