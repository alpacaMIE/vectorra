package com.vectorra.maps.network

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class TileCacheAndSchedulerTest {
    @Test
    fun tileCacheStoreServesMemoryHit() {
        val memoryCache = TileMemoryCache(maxBytes = 1024)
        val store = TileCacheStore(memoryCache = memoryCache)
        val request = TileRequest(url = "https://tiles.example/memory.png")

        store.put(TileResponse(request = request, statusCode = 200, body = "memory".toByteArray()))
        val cached = store.get(request)

        requireNotNull(cached)
        assertEquals(TileCacheStatus.MEMORY, cached.cacheStatus)
        assertEquals("memory", cached.body.decodeToString())
    }

    @Test
    fun tileCacheStoreServesDiskHit() {
        val root = Files.createTempDirectory("vectorra-tile-cache-store").toFile()
        try {
            val request = TileRequest(url = "https://tiles.example/disk.png")
            TileCacheStore(diskCache = TileDiskCache(root, maxBytes = 1024))
                .put(TileResponse(request = request, statusCode = 200, body = "disk".toByteArray()))

            val cached = TileCacheStore(diskCache = TileDiskCache(root, maxBytes = 1024)).get(request)

            requireNotNull(cached)
            assertEquals(TileCacheStatus.DISK, cached.cacheStatus)
            assertEquals("disk", cached.body.decodeToString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tileCacheStoreWritesSuccessfulResponse() {
        val memoryCache = TileMemoryCache(maxBytes = 1024)
        val store = TileCacheStore(memoryCache = memoryCache)
        val request = TileRequest(url = "https://tiles.example/write.png")

        store.put(TileResponse(request = request, statusCode = 204, body = "write".toByteArray()))

        assertEquals(TileCacheStatus.MEMORY, store.get(request)?.cacheStatus)
        assertEquals(1, memoryCache.entryCount())
    }

    @Test
    fun tileCacheStoreReportsMemoryAndDiskStatus() {
        val root = Files.createTempDirectory("vectorra-tile-cache-status").toFile()
        try {
            val store = TileCacheStore(
                memoryCache = TileMemoryCache(maxBytes = 1024),
                diskCache = TileDiskCache(root, maxBytes = 1024)
            )
            val request = TileRequest(url = "https://tiles.example/status.png")

            store.put(TileResponse(request = request, statusCode = 200, body = "status".toByteArray()))

            val status = store.status()
            assertEquals(1, status.memoryEntryCount)
            assertEquals(6L, status.memoryBytes)
            assertEquals(1, status.diskEntryCount)
            assertEquals(6L, status.diskBytes)
            assertEquals(2, status.totalEntryCount)
            assertEquals(12L, status.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tileCacheStoreClearRemovesMemoryAndDiskEntries() {
        val root = Files.createTempDirectory("vectorra-tile-cache-clear").toFile()
        try {
            val store = TileCacheStore(
                memoryCache = TileMemoryCache(maxBytes = 1024),
                diskCache = TileDiskCache(root, maxBytes = 1024)
            )
            val request = TileRequest(url = "https://tiles.example/clear.png")
            store.put(TileResponse(request = request, statusCode = 200, body = "clear".toByteArray()))

            store.clear()

            val status = store.status()
            assertEquals(0, status.totalEntryCount)
            assertEquals(0L, status.totalBytes)
            assertNull(store.get(request))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tileCacheStoreSkipsUncacheableRequest() {
        val memoryCache = TileMemoryCache(maxBytes = 1024)
        val store = TileCacheStore(memoryCache = memoryCache)
        val request = TileRequest(method = "POST", url = "https://tiles.example/post.png")

        store.put(TileResponse(request = request, statusCode = 200, body = "post".toByteArray()))

        assertNull(store.get(request))
        assertEquals(0, memoryCache.entryCount())
    }

    @Test
    fun servesSuccessfulTileFromMemoryCacheBeforeDelegate() {
        var calls = 0
        val executor = CachingTileRequestExecutor(
            delegate = TileRequestExecutor { request ->
                calls += 1
                TileResponse(request = request, statusCode = 200, body = "tile".toByteArray())
            },
            memoryCache = TileMemoryCache(maxBytes = 1024)
        )
        val request = TileRequest(url = "https://tiles.example/0/0/0.png")

        val first = runSuspend { executor.execute(request) }
        val second = runSuspend { executor.execute(request) }

        assertEquals(1, calls)
        assertEquals(TileCacheStatus.MISS, first.cacheStatus)
        assertEquals(TileCacheStatus.MEMORY, second.cacheStatus)
        assertEquals("tile", second.body.decodeToString())
    }

    @Test
    fun tileResourceFetcherUsesInterceptorsAndCacheStore() {
        val root = Files.createTempDirectory("vectorra-resource-fetcher-cache").toFile()
        try {
            var calls = 0
            val fetcher = TileResourceFetcher(
                cacheDirectory = root,
                initialConfig = TileNetworkConfig(
                    interceptors = listOf(
                        TileRequestInterceptor { chain ->
                            calls += 1
                            TileResponse(
                                request = chain.request,
                                statusCode = 200,
                                body = "resource".toByteArray()
                            )
                        }
                    )
                )
            )
            val request = TileRequest(
                sourceId = "tiles3d",
                url = "https://assets.example.com/tileset.json",
                resourceType = TileResourceType.TILES3D
            )

            val first = fetcher.fetch(request)
            val second = fetcher.fetch(request)

            assertEquals(1, calls)
            assertEquals(TileCacheStatus.MISS, first.cacheStatus)
            assertEquals(TileCacheStatus.MEMORY, second.cacheStatus)
            assertEquals("resource", second.body.decodeToString())
            fetcher.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun servesSuccessfulTileFromDiskCacheAcrossExecutors() {
        val root = Files.createTempDirectory("rocky-tile-disk-cache").toFile()
        try {
            var calls = 0
            val request = TileRequest(url = "https://tiles.example/0/0/1.png")
            val firstExecutor = CachingTileRequestExecutor(
                delegate = TileRequestExecutor { tileRequest ->
                    calls += 1
                    TileResponse(tileRequest, 200, body = "disk-tile".toByteArray())
                },
                diskCache = TileDiskCache(root, maxBytes = 1024)
            )
            val secondExecutor = CachingTileRequestExecutor(
                delegate = TileRequestExecutor { tileRequest ->
                    calls += 1
                    TileResponse(tileRequest, 200, body = "unexpected".toByteArray())
                },
                diskCache = TileDiskCache(root, maxBytes = 1024)
            )

            runSuspend { firstExecutor.execute(request) }
            val second = runSuspend { secondExecutor.execute(request) }

            assertEquals(1, calls)
            assertEquals(TileCacheStatus.DISK, second.cacheStatus)
            assertEquals("disk-tile", second.body.decodeToString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun doesNotCacheHttpErrorTiles() {
        var calls = 0
        val executor = CachingTileRequestExecutor(
            delegate = TileRequestExecutor { request ->
                calls += 1
                TileResponse(request = request, statusCode = 500, body = "server-error".toByteArray())
            },
            memoryCache = TileMemoryCache(maxBytes = 1024)
        )
        val request = TileRequest(url = "https://tiles.example/0/0/2.png")

        val first = runSuspend { executor.execute(request) }
        val second = runSuspend { executor.execute(request) }

        assertEquals(2, calls)
        assertEquals(500, first.statusCode)
        assertEquals(500, second.statusCode)
        assertEquals(TileCacheStatus.MISS, second.cacheStatus)
    }

    @Test
    fun resilientExecutorConvertsIoExceptionToErrorResponse() {
        val executor = ResilientTileRequestExecutor(
            TileRequestExecutor {
                throw IOException("timeout")
            }
        )

        val response = runSuspend {
            executor.execute(TileRequest(url = "https://tiles.example/timeout.png"))
        }

        assertEquals(599, response.statusCode)
        assertEquals("timeout", response.errorMessage)
    }

    @Test
    fun schedulerDeduplicatesSameTileRequest() {
        val calls = AtomicInteger()
        val scheduler = TileRequestScheduler(
            executor = TileRequestExecutor { request ->
                calls.incrementAndGet()
                TileResponse(request, 200, body = "shared".toByteArray())
            },
            maxConcurrentRequests = 1
        )
        try {
            val request = TileRequest(url = "https://tiles.example/1/1/1.png")
            val first = scheduler.enqueue(request)
            val second = scheduler.enqueue(request)

            assertEquals("shared", first.await().body.decodeToString())
            assertEquals("shared", second.await().body.decodeToString())
            assertEquals(1, calls.get())
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun schedulerRunsHigherPriorityQueuedRequestFirst() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val scheduler = TileRequestScheduler(
            executor = TileRequestExecutor { request ->
                order += request.url
                if (request.url.endsWith("blocking.png")) {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
                TileResponse(request, 200, body = request.url.toByteArray())
            },
            maxConcurrentRequests = 1
        )
        try {
            val blocking = scheduler.enqueue(TileRequest(url = "https://tiles.example/blocking.png"))
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            val low = scheduler.enqueue(TileRequest(url = "https://tiles.example/low.png"), priority = 1)
            val high = scheduler.enqueue(TileRequest(url = "https://tiles.example/high.png"), priority = 10)

            releaseFirst.countDown()
            blocking.await()
            high.await()
            low.await()

            assertEquals(
                listOf(
                    "https://tiles.example/blocking.png",
                    "https://tiles.example/high.png",
                    "https://tiles.example/low.png"
                ),
                order
            )
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun schedulerSkipsCancelledQueuedRequest() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val scheduler = TileRequestScheduler(
            executor = TileRequestExecutor { request ->
                order += request.url
                if (request.url.endsWith("blocking.png")) {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
                TileResponse(request, 200)
            },
            maxConcurrentRequests = 1
        )
        try {
            val blocking = scheduler.enqueue(TileRequest(url = "https://tiles.example/blocking.png"))
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            val cancelled = scheduler.enqueue(TileRequest(url = "https://tiles.example/cancelled.png"))

            assertTrue(cancelled.cancel())
            releaseFirst.countDown()
            blocking.await()
            Thread.sleep(200L)

            assertTrue(cancelled.isCancelled())
            assertFalse(order.contains("https://tiles.example/cancelled.png"))
        } finally {
            scheduler.close()
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    result
                        .onSuccess { value = it }
                        .onFailure { failure = it }
                }
            }
        )
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
