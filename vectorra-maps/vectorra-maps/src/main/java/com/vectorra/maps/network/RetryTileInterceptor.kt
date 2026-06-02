package com.vectorra.maps.network

class RetryTileInterceptor(
    private val maxAttempts: Int = 2,
    private val retryStatusCodes: Set<Int> = setOf(408, 429, 500, 502, 503, 504),
    private val retryOnException: Boolean = true
) : TileRequestInterceptor {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    override suspend fun intercept(chain: TileRequestInterceptorChain): TileResponse {
        var attempt = 0
        var lastFailure: Throwable? = null
        while (attempt < maxAttempts) {
            attempt += 1
            try {
                val response = chain.proceed(
                    chain.request.copy(
                        metadata = chain.request.metadata + ("retryAttempt" to attempt.toString())
                    )
                )
                if (response.statusCode !in retryStatusCodes || attempt == maxAttempts) {
                    return response
                }
            } catch (error: Throwable) {
                if (!retryOnException || attempt == maxAttempts) {
                    throw error
                }
                lastFailure = error
            }
        }
        throw IllegalStateException("Tile retry exhausted", lastFailure)
    }
}

