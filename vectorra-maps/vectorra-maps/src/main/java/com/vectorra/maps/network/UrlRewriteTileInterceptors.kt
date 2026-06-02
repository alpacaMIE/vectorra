package com.vectorra.maps.network

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.pow

class UrlRewriteTileInterceptor(
    private val rewrite: (TileRequest) -> TileRequest?
) : TileRequestInterceptor {
    override suspend fun intercept(chain: TileRequestInterceptorChain): TileResponse {
        return chain.proceed(rewrite(chain.request) ?: chain.request)
    }
}

class SuperResolutionTileInterceptor(
    private val enabled: Boolean,
    private val proxyBaseUrl: String,
    private val minimumZoom: Int = 0,
    private val markerQueryKey: String = "_kt_srs_",
    private val markerValue: String = "true",
    private val tdtLayerQueryKey: String = "_kt_srs_layer_",
    private val tdtTokenReplacement: String? = null
) : TileRequestInterceptor {
    override suspend fun intercept(chain: TileRequestInterceptorChain): TileResponse {
        val request = chain.request
        if (!enabled || proxyBaseUrl.isBlank() || !request.isEligible()) {
            return chain.proceed()
        }

        val rewrittenOriginal = if (tdtTokenReplacement != null && request.queryValue(tdtLayerQueryKey) == "tdt") {
            request.replaceQueryValue("tk", tdtTokenReplacement)
        } else {
            request.url
        }
        val encoded = URLEncoder.encode(rewrittenOriginal, StandardCharsets.UTF_8.name())
        return chain.proceed(request.copy(url = proxyBaseUrl + encoded))
    }

    private fun TileRequest.isEligible(): Boolean {
        if (queryValue(markerQueryKey) != markerValue) {
            return false
        }
        val zoom = tileId?.z ?: queryValue("z")?.toIntOrNull() ?: queryValue("TileMatrix")?.toIntOrNull()
        return zoom == null || zoom >= minimumZoom
    }
}

class WmtsTileRowShiftInterceptor(
    private val hostContains: String = "gansu.tianditu.gov.cn"
) : TileRequestInterceptor {
    override suspend fun intercept(chain: TileRequestInterceptorChain): TileResponse {
        val request = chain.request
        val uri = runCatching { URI(request.url) }.getOrNull()
        if (uri?.host?.contains(hostContains, ignoreCase = true) != true) {
            return chain.proceed()
        }

        val params = request.queryParams()
        if (!params.valueEquals("Request", "GetTile")) {
            return chain.proceed()
        }
        val z = params.value("TileMatrix")?.toIntOrNull()
        val row = params.value("TileRow")?.toIntOrNull()
        if (z == null || row == null) {
            return chain.proceed()
        }

        val shift = if (z >= 2) 2.0.pow(z - 2).toInt() else 0
        val shiftedUrl = request.replaceQueryValue("TileRow", (row - shift).toString())
        return chain.proceed(request.copy(url = shiftedUrl))
    }
}

private data class QueryPart(val key: String, val value: String?)

internal fun TileRequest.queryValue(key: String): String? = queryParams().value(key)

private fun TileRequest.queryParams(): List<QueryPart> {
    val rawQuery = rawQueryString(url) ?: return emptyList()
    return rawQuery.split("&").mapNotNull { part ->
        if (part.isEmpty()) {
            null
        } else {
            val index = part.indexOf('=')
            if (index < 0) QueryPart(part, null) else QueryPart(part.substring(0, index), part.substring(index + 1))
        }
    }
}

private fun List<QueryPart>.value(key: String): String? {
    return firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
}

private fun List<QueryPart>.valueEquals(key: String, expected: String): Boolean {
    return value(key)?.equals(expected, ignoreCase = true) == true
}

internal fun TileRequest.replaceQueryValue(key: String, value: String): String {
    val rawQuery = rawQueryString(url) ?: return url
    var replaced = false
    val nextQuery = rawQuery.split("&").joinToString("&") { part ->
        val index = part.indexOf('=')
        val partKey = if (index < 0) part else part.substring(0, index)
        if (partKey.equals(key, ignoreCase = true)) {
            replaced = true
            "$partKey=$value"
        } else {
            part
        }
    }
    val finalQuery = if (replaced) nextQuery else "$nextQuery&$key=$value"
    return url.replaceFirst(rawQuery, finalQuery)
}

private fun rawQueryString(url: String): String? {
    val questionIndex = url.indexOf('?')
    if (questionIndex < 0 || questionIndex == url.lastIndex) {
        return null
    }
    val fragmentIndex = url.indexOf('#', startIndex = questionIndex + 1).let { if (it < 0) url.length else it }
    return url.substring(questionIndex + 1, fragmentIndex)
}
