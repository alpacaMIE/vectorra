package com.vectorra.maps.offline

import com.vectorra.maps.network.TileCacheStatus
import com.vectorra.maps.network.TileRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraPrefetchTaskRunnerTest {
    @Test
    fun taskReportsProgressAndCompletedResult() {
        val progressEvents = mutableListOf<VectorraPrefetchProgress>()
        val requests = listOf(
            TileRequest(url = "https://tiles.example.com/0.png"),
            TileRequest(url = "https://tiles.example.com/1.png")
        )

        val task = VectorraPrefetchTaskRunner(
            requests = requests,
            listener = VectorraPrefetchProgressListener { progressEvents += it }
        ) { request ->
            if (request.url.endsWith("0.png")) {
                VectorraPrefetchTileResult(
                    request = request,
                    statusCode = 200,
                    cacheStatus = TileCacheStatus.MISS,
                    byteCount = 4
                )
            } else {
                VectorraPrefetchTileResult(
                    request = request,
                    statusCode = 500,
                    cacheStatus = TileCacheStatus.MISS,
                    byteCount = 0,
                    errorMessage = "server error"
                )
            }
        }

        val result = task.await()

        assertEquals(VectorraPrefetchTaskState.COMPLETED, task.state)
        assertEquals(1, result.completedCount)
        assertEquals(1, result.failedCount)
        assertEquals(4L, result.totalBytes)
        assertEquals(VectorraPrefetchTaskState.RUNNING, progressEvents.first().state)
        assertEquals(VectorraPrefetchTaskState.COMPLETED, progressEvents.last().state)
        assertEquals(2, progressEvents.last().finishedCount)
        assertEquals(0, progressEvents.last().pendingCount)
    }

    @Test
    fun cancelStopsBeforeStartingRemainingRequests() {
        val taskRef = AtomicReference<VectorraPrefetchTask>()
        val refReady = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val requests = listOf(
            TileRequest(url = "https://tiles.example.com/0.png"),
            TileRequest(url = "https://tiles.example.com/1.png"),
            TileRequest(url = "https://tiles.example.com/2.png")
        )

        val task = VectorraPrefetchTaskRunner(
            requests = requests,
            listener = VectorraPrefetchProgressListener { progress ->
                if (progress.finishedCount == 1) {
                    taskRef.get().cancel()
                }
            }
        ) { request ->
            assertTrue(refReady.await(1, TimeUnit.SECONDS))
            calls.incrementAndGet()
            VectorraPrefetchTileResult(
                request = request,
                statusCode = 200,
                cacheStatus = TileCacheStatus.MISS,
                byteCount = 1
            )
        }
        taskRef.set(task)
        refReady.countDown()

        val result = task.await()

        assertEquals(VectorraPrefetchTaskState.CANCELED, task.state)
        assertEquals(1, calls.get())
        assertEquals(1, result.completedCount)
        assertEquals(0, result.failedCount)
        assertEquals(1L, result.totalBytes)
        assertEquals(2, task.progress.pendingCount)
    }

    @Test
    fun emptyTaskCompletesWithoutFetching() {
        val calls = AtomicInteger(0)
        val task = VectorraPrefetchTaskRunner(
            requests = emptyList()
        ) { request ->
            calls.incrementAndGet()
            VectorraPrefetchTileResult(
                request = request,
                statusCode = 200,
                cacheStatus = TileCacheStatus.MISS,
                byteCount = 1
            )
        }

        val result = task.await()

        assertEquals(VectorraPrefetchTaskState.COMPLETED, task.state)
        assertEquals(0, calls.get())
        assertEquals(0, result.tiles.size)
        assertEquals(0, task.progress.totalCount)
    }
}
