package com.vectorra.maps.network

import java.net.URI

data class TileLogEvent(
    val phase: Phase,
    val sourceId: String?,
    val layerId: String?,
    val method: String,
    val redactedUrl: String,
    val statusCode: Int? = null
) {
    enum class Phase {
        REQUEST,
        RESPONSE,
        ERROR
    }
}

fun interface TileRequestLogger {
    fun log(event: TileLogEvent)
}

class RedactingTileLogger(
    private val logger: TileRequestLogger,
    private val redactQueryKeys: Set<String> = VectorraTileNetworkDefaults.redactQueryKeys
) : TileRequestInterceptor {
    override suspend fun intercept(chain: TileRequestInterceptorChain): TileResponse {
        val request = chain.request
        logger.log(request.toLogEvent(TileLogEvent.Phase.REQUEST))
        return try {
            val response = chain.proceed()
            logger.log(response.request.toLogEvent(TileLogEvent.Phase.RESPONSE, response.statusCode))
            response
        } catch (error: Throwable) {
            logger.log(request.toLogEvent(TileLogEvent.Phase.ERROR))
            throw error
        }
    }

    private fun TileRequest.toLogEvent(phase: TileLogEvent.Phase, statusCode: Int? = null): TileLogEvent {
        return TileLogEvent(
            phase = phase,
            sourceId = sourceId,
            layerId = layerId,
            method = method,
            redactedUrl = TileUrlRedactor.redact(url, redactQueryKeys),
            statusCode = statusCode
        )
    }
}

object TileUrlRedactor {
    fun redact(url: String, redactQueryKeys: Set<String> = VectorraTileNetworkDefaults.redactQueryKeys): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return redactRawQuery(url, redactQueryKeys)
        val rawQuery = uri.rawQuery ?: return url
        val redactedQuery = rawQuery
            .split("&")
            .joinToString("&") { part ->
                val separatorIndex = part.indexOf('=')
                if (separatorIndex < 0) {
                    part
                } else {
                    val key = part.substring(0, separatorIndex)
                    if (redactQueryKeys.any { it.equals(key, ignoreCase = true) }) {
                        "$key=<redacted>"
                    } else {
                        part
                    }
                }
            }
        return url.replaceFirst(rawQuery, redactedQuery)
    }

    private fun redactRawQuery(url: String, redactQueryKeys: Set<String>): String {
        var result = url
        redactQueryKeys.forEach { key ->
            result = result.replace(Regex("(?i)([?&]${Regex.escape(key)}=)[^&#]*")) {
                it.groupValues[1] + "<redacted>"
            }
        }
        return result
    }
}
