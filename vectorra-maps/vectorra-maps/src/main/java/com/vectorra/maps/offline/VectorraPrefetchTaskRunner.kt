package com.vectorra.maps.offline

import com.vectorra.maps.network.TileCacheStatus
import com.vectorra.maps.network.TileRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal class VectorraPrefetchTaskRunner(
    private val requests: List<TileRequest>,
    private val options: VectorraPrefetchOptions = VectorraPrefetchOptions(),
    private val listener: VectorraPrefetchProgressListener? = null,
    private val fetchTile: (TileRequest) -> VectorraPrefetchTileResult
) : VectorraPrefetchTask {
    private val canceled = AtomicBoolean(false)
    private val done = CountDownLatch(1)
    private val lock = Any()
    private val results = mutableListOf<VectorraPrefetchTileResult>()
    private var currentProgress = VectorraPrefetchProgress(
        totalCount = requests.size,
        completedCount = 0,
        failedCount = 0,
        totalBytes = 0L,
        state = VectorraPrefetchTaskState.RUNNING
    )
    private val worker = Thread(::run, "VectorraPrefetchTask")

    init {
        listener?.onProgress(progress)
        worker.start()
    }

    override val progress: VectorraPrefetchProgress
        get() = synchronized(lock) { currentProgress }

    override fun cancel(): Boolean {
        if (progress.state != VectorraPrefetchTaskState.RUNNING) {
            return false
        }
        canceled.set(true)
        return true
    }

    override fun await(): VectorraPrefetchResult {
        done.await()
        return synchronized(lock) { VectorraPrefetchResult(results.toList()) }
    }

    private fun run() {
        try {
            for (request in requests) {
                if (canceled.get()) {
                    break
                }
                val result = fetchWithPolicy(request)
                val nextProgress = synchronized(lock) {
                    results += result
                    currentProgress = currentProgress.copy(
                        completedCount = results.count { it.isSuccess },
                        failedCount = results.count { !it.isSuccess },
                        totalBytes = results.sumOf { it.byteCount.toLong() }
                    )
                    currentProgress
                }
                listener?.onProgress(nextProgress)
            }
            finish(if (canceled.get()) VectorraPrefetchTaskState.CANCELED else VectorraPrefetchTaskState.COMPLETED)
        } finally {
            done.countDown()
        }
    }

    private fun finish(state: VectorraPrefetchTaskState) {
        val finished = synchronized(lock) {
            currentProgress = currentProgress.copy(state = state)
            currentProgress
        }
        listener?.onProgress(finished)
    }

    private fun fetchWithPolicy(request: TileRequest): VectorraPrefetchTileResult {
        var attempt = 0
        var lastResult: VectorraPrefetchTileResult
        do {
            attempt += 1
            lastResult = try {
                fetchTile(request).copy(attemptCount = attempt)
            } catch (error: Throwable) {
                VectorraPrefetchTileResult(
                    request = request,
                    statusCode = 599,
                    cacheStatus = TileCacheStatus.MISS,
                    byteCount = 0,
                    errorMessage = error.message,
                    attemptCount = attempt
                )
            }
        } while (shouldRetry(lastResult, attempt))
        return lastResult
    }

    private fun shouldRetry(result: VectorraPrefetchTileResult, attempt: Int): Boolean {
        return !result.isSuccess &&
            attempt < options.maxAttempts &&
            result.statusCode in options.retryStatusCodes
    }
}
