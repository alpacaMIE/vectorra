package com.vectorra.maps.network

import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

internal class TileResourceFetcher(
    private val cacheDirectory: File,
    initialConfig: TileNetworkConfig = TileNetworkConfig()
) : Closeable {
    @Volatile private var scheduler: TileRequestScheduler = schedulerFor(initialConfig)
    @Volatile private var cacheStore: TileCacheStore = TileCacheStore(cacheDirectory, initialConfig.cachePolicy)

    fun updateConfig(config: TileNetworkConfig) {
        cacheStore = TileCacheStore(cacheDirectory, config.cachePolicy)
        val next = schedulerFor(config)
        val previous = scheduler
        scheduler = next
        previous.close()
    }

    fun fetch(
        request: TileRequest,
        priority: Int = 0,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): TileResponse {
        val currentCacheStore = cacheStore
        if (currentCacheStore.isCacheable(request)) {
            currentCacheStore.get(request)?.let { return it }
        }

        val response = scheduler.enqueue(request, priority = priority).awaitOrError(timeoutMillis)
        currentCacheStore.put(response)
        return response
    }

    fun cacheStatus(): TileCacheStoreStatus {
        return cacheStore.status()
    }

    fun clearCache() {
        cacheStore.clear()
    }

    override fun close() {
        scheduler.close()
    }

    private fun schedulerFor(config: TileNetworkConfig): TileRequestScheduler {
        val chain = TileRequestInterceptorChainExecutor(
            interceptors = config.interceptors,
            terminalExecutor = ResilientTileRequestExecutor(HttpUrlTileRequestExecutor())
        )
        return TileRequestScheduler(
            executor = TileRequestExecutor { request -> chain.execute(request) },
            maxConcurrentRequests = config.maxConcurrentRequests,
            maxQueuedRequests = config.maxQueuedRequests
        )
    }

    private fun TileRequestHandle.awaitOrError(timeoutMillis: Long): TileResponse {
        return try {
            await(timeoutMillis)
        } catch (error: TimeoutException) {
            cancel()
            TileResponse(request = request, statusCode = 504, errorMessage = error.message)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            TileResponse(request = request, statusCode = 499, errorMessage = error.message)
        } catch (error: ExecutionException) {
            TileResponse(request = request, statusCode = 599, errorMessage = error.cause?.message ?: error.message)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}
