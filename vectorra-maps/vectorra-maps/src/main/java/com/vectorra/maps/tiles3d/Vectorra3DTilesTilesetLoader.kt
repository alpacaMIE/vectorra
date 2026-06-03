package com.vectorra.maps.tiles3d

import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResourceType
import com.vectorra.maps.network.TileResponse
import java.io.File
import java.io.IOException
import java.net.URI

internal class Vectorra3DTilesTilesetLoader(
    private val fetchRemote: (TileRequest) -> TileResponse
) {
    fun load(
        source: Vectorra3DTilesSource,
        headers: Map<String, String> = source.headers
    ): VectorraTileset3D {
        val tilesetUri = normalizeTilesetUri(source.tilesetUri)
        val tilesetJson = when (tilesetUri.scheme?.lowercase()) {
            "http", "https" -> readRemoteTileset(source, tilesetUri, headers)
            "file" -> File(tilesetUri).readText()
            else -> throw IllegalArgumentException("Unsupported 3D Tiles tileset URI scheme: ${tilesetUri.scheme}")
        }
        return VectorraTileset3DParser.parse(
            json = tilesetJson,
            baseUri = tilesetUri.toString()
        )
    }

    private fun readRemoteTileset(
        source: Vectorra3DTilesSource,
        tilesetUri: URI,
        headers: Map<String, String>
    ): String {
        val request = TileRequest(
            sourceId = source.id,
            layerId = null,
            url = tilesetUri.toString(),
            headers = headers,
            resourceType = TileResourceType.TILES3D,
            metadata = mapOf("kind" to "tileset")
        )
        val response = fetchRemote(request)
        if (response.statusCode !in 200..299) {
            throw IOException(
                response.errorMessage ?: "3D Tiles tileset request failed with HTTP ${response.statusCode}."
            )
        }
        return response.body.toString(Charsets.UTF_8)
    }

    internal companion object {
        fun normalizeTilesetUri(value: String): URI {
            val trimmed = value.trim()
            require(trimmed.isNotBlank()) { "3D Tiles tilesetUri must not be blank." }

            val parsed = runCatching { URI(trimmed) }.getOrNull()
            return when {
                parsed == null -> File(trimmed).toURI()
                parsed.scheme == null -> File(trimmed).toURI()
                parsed.scheme.length == 1 && trimmed.getOrNull(1) == ':' -> File(trimmed).toURI()
                parsed.scheme.equals("http", ignoreCase = true) -> parsed
                parsed.scheme.equals("https", ignoreCase = true) -> parsed
                parsed.scheme.equals("file", ignoreCase = true) -> parsed
                else -> parsed
            }
        }
    }
}
