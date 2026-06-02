package com.vectorra.maps.network

class HeaderInjectingTileInterceptor(
    private val defaultHeaders: Map<String, String>,
    private val sourceHeaders: Map<String, Map<String, String>> = emptyMap(),
    private val overwriteExisting: Boolean = false
) : TileRequestInterceptor {
    constructor(config: TileNetworkConfig, overwriteExisting: Boolean = false) : this(
        defaultHeaders = config.defaultHeaders,
        sourceHeaders = config.sourceHeaders,
        overwriteExisting = overwriteExisting
    )

    override suspend fun intercept(chain: TileRequestInterceptorChain): TileResponse {
        val request = chain.request
        val configuredHeaders = defaultHeaders + (request.sourceId?.let(sourceHeaders::get).orEmpty())
        if (configuredHeaders.isEmpty()) {
            return chain.proceed()
        }

        val mergedHeaders = if (overwriteExisting) {
            request.headers + configuredHeaders
        } else {
            configuredHeaders + request.headers
        }
        return chain.proceed(request.copy(headers = mergedHeaders))
    }
}

