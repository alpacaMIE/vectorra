package com.vectorra.maps.network

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class TileRequestScheduler(
    private val executor: TileRequestExecutor,
    maxConcurrentRequests: Int = VectorraTileNetworkDefaults.maxConcurrentRequests,
    private val maxQueuedRequests: Int = VectorraTileNetworkDefaults.maxQueuedRequests
) : Closeable {
    private val sequence = AtomicLong()
    private val inFlight = linkedMapOf<String, CompletableFuture<TileResponse>>()
    private val pool = ThreadPoolExecutor(
        maxConcurrentRequests,
        maxConcurrentRequests,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue()
    )

    init {
        require(maxConcurrentRequests > 0) { "maxConcurrentRequests must be greater than 0." }
        require(maxQueuedRequests >= 0) { "maxQueuedRequests must be greater than or equal to 0." }
    }

    fun enqueue(
        request: TileRequest,
        priority: Int = 0
    ): TileRequestHandle {
        val key = TileCacheKeys.from(request)
        synchronized(inFlight) {
            inFlight[key]?.takeIf { !it.isDone }?.let {
                return TileRequestHandle(request, it)
            }
            if (pool.queue.size >= maxQueuedRequests) {
                val rejected = CompletableFuture.completedFuture(
                    TileResponse(
                        request = request,
                        statusCode = 429,
                        errorMessage = "Tile request queue is full."
                    )
                )
                return TileRequestHandle(request, rejected)
            }
            val future = CompletableFuture<TileResponse>()
            inFlight[key] = future
            pool.execute(ScheduledTileTask(request, key, priority, sequence.incrementAndGet(), future))
            return TileRequestHandle(request, future)
        }
    }

    fun cancelAll() {
        synchronized(inFlight) {
            inFlight.values.forEach { it.cancel(true) }
            inFlight.clear()
        }
        pool.queue.clear()
    }

    override fun close() {
        cancelAll()
        pool.shutdownNow()
    }

    private inner class ScheduledTileTask(
        private val request: TileRequest,
        private val key: String,
        private val priority: Int,
        private val sequenceNumber: Long,
        private val future: CompletableFuture<TileResponse>
    ) : Runnable, Comparable<ScheduledTileTask> {
        override fun run() {
            if (future.isCancelled) {
                removeInFlight()
                return
            }
            try {
                val response = runSuspend { executor.execute(request) }
                future.complete(response)
            } catch (e: CancellationException) {
                future.cancel(true)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                future.complete(
                    TileResponse(
                        request = request,
                        statusCode = 499,
                        errorMessage = e.message
                    )
                )
            } catch (e: Exception) {
                future.complete(
                    TileResponse(
                        request = request,
                        statusCode = 599,
                        errorMessage = e.message
                    )
                )
            } finally {
                removeInFlight()
            }
        }

        override fun compareTo(other: ScheduledTileTask): Int {
            val priorityCompare = other.priority.compareTo(priority)
            return if (priorityCompare != 0) priorityCompare else sequenceNumber.compareTo(other.sequenceNumber)
        }

        private fun removeInFlight() {
            synchronized(inFlight) {
                if (inFlight[key] === future) {
                    inFlight.remove(key)
                }
            }
        }
    }
}

class TileRequestHandle internal constructor(
    val request: TileRequest,
    private val future: CompletableFuture<TileResponse>
) {
    val id: String = UUID.randomUUID().toString()

    fun cancel(): Boolean = future.cancel(true)

    fun isCancelled(): Boolean = future.isCancelled

    fun isDone(): Boolean = future.isDone

    fun await(timeoutMillis: Long = 30_000L): TileResponse {
        val response = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        return if (response.request === request) response else response.copy(request = request)
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    val done = java.util.concurrent.CountDownLatch(1)
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result
                    .onSuccess { value = it }
                    .onFailure { failure = it }
                done.countDown()
            }
        }
    )
    done.await()
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
