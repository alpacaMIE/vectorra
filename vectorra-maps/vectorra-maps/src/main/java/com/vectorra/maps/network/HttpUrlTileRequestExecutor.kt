package com.vectorra.maps.network

import java.net.HttpURLConnection
import java.net.URL

class HttpUrlTileRequestExecutor(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000
) : TileRequestExecutor {
    override suspend fun execute(request: TileRequest): TileResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        request.headers.forEach { (name, value) ->
            connection.setRequestProperty(name, value)
        }
        return try {
            val statusCode = connection.responseCode
            val body = if (statusCode in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            TileResponse(
                request = request,
                statusCode = statusCode,
                headers = connection.headerFields
                    .orEmpty()
                    .filterKeys { it != null }
                    .mapKeys { it.key.orEmpty() }
                    .mapValues { it.value.joinToString(",") },
                body = body
            )
        } finally {
            connection.disconnect()
        }
    }
}

