package com.vectorra.maps.network

data class TileNetworkConfig(
    val defaultHeaders: Map<String, String> = VectorraTileNetworkDefaults.defaultHeaders,
    val sourceHeaders: Map<String, Map<String, String>> = emptyMap(),
    val interceptors: List<TileRequestInterceptor> = emptyList(),
    val redactQueryKeys: Set<String> = VectorraTileNetworkDefaults.redactQueryKeys,
    val cachePolicy: TileCachePolicy = TileCachePolicy(),
    val maxConcurrentRequests: Int = VectorraTileNetworkDefaults.maxConcurrentRequests,
    val maxQueuedRequests: Int = VectorraTileNetworkDefaults.maxQueuedRequests
) {
    init {
        require(maxConcurrentRequests > 0) { "maxConcurrentRequests must be greater than 0." }
        require(maxQueuedRequests >= 0) { "maxQueuedRequests must be greater than or equal to 0." }
    }

    fun headersFor(sourceId: String?): Map<String, String> {
        return defaultHeaders + (sourceId?.let(sourceHeaders::get).orEmpty())
    }
}

data class TileCachePolicy(
    val cacheableMethods: Set<String> = setOf("GET"),
    val successStatusCodes: IntRange = 200..299,
    val memoryMaxBytes: Long = 32L * 1024L * 1024L,
    val diskMaxBytes: Long = 256L * 1024L * 1024L
) {
    init {
        require(memoryMaxBytes >= 0L) { "memoryMaxBytes must be greater than or equal to 0." }
        require(diskMaxBytes >= 0L) { "diskMaxBytes must be greater than or equal to 0." }
    }

    fun isCacheable(request: TileRequest): Boolean {
        return request.method.uppercase() in cacheableMethods
    }

    fun isSuccessful(response: TileResponse): Boolean {
        return response.statusCode in successStatusCodes && response.body.isNotEmpty()
    }
}

object VectorraTileNetworkDefaults {
    const val USER_AGENT = "KingTop Vectorra Android SDK"
    const val maxConcurrentRequests: Int = 6
    const val maxQueuedRequests: Int = 256

    val defaultHeaders: Map<String, String> = mapOf("User-Agent" to USER_AGENT)

    val redactQueryKeys: Set<String> = setOf(
        "tk",
        "ak",
        "apikey",
        "api_key",
        "key",
        "token",
        "access_token",
        "mk",
        "url",
        "signature",
        "sign",
        "auth",
        "authorization"
    )
}
