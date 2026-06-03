package com.vectorra.maps.offline

import org.junit.Assert.assertEquals
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
}
