package com.vectorra.maps.mvt

import com.vectorra.maps.VectorraResourceErrorType
import com.vectorra.maps.network.TileId
import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResourceFetcher
import com.vectorra.maps.network.TileResourceType
import com.vectorra.maps.network.TileResponse
import com.vectorra.maps.network.TileScheme
import com.vectorra.maps.vector.VectorraVectorTileSource
import kotlin.math.max

internal class VectorraMvtTileLoader(
    private val fetch: (TileRequest, Int) -> TileResponse
) {
    fun loadTile(
        source: VectorraVectorTileSource,
        layerId: String,
        tileId: VectorraMvtTileId,
        priority: Int = tileId.z
    ): VectorraMvtTileLoadResult {
        require(layerId.isNotBlank()) { "MVT tile load layerId must not be blank." }
        require(tileId.z in source.minZoom..source.maxZoom) {
            "MVT tile ${tileId.z}/${tileId.x}/${tileId.y} is outside source zoom range."
        }
        val request = source.toTileRequest(layerId, tileId)
        val response = fetch(request, priority)
        if (response.statusCode !in 200..299 || response.body.isEmpty()) {
            return VectorraMvtTileLoadResult.Failed(
                request = request,
                statusCode = response.statusCode,
                errorType = VectorraResourceErrorType.NETWORK,
                message = response.errorMessage ?: "MVT tile request failed with HTTP ${response.statusCode}."
            )
        }
        return runCatching {
            VectorraMvtTileLoadResult.Loaded(
                request = request,
                response = response,
                decodedTile = VectorraMvtDecoder.decode(response.body)
            )
        }.getOrElse { error ->
            VectorraMvtTileLoadResult.Failed(
                request = request,
                statusCode = response.statusCode,
                errorType = VectorraResourceErrorType.RESOURCE,
                message = error.message ?: "MVT tile decode failed."
            )
        }
    }

    private fun VectorraVectorTileSource.toTileRequest(
        layerId: String,
        tileId: VectorraMvtTileId
    ): TileRequest {
        val requestY = when (scheme) {
            TileScheme.XYZ -> tileId.y
            TileScheme.TMS -> tmsY(tileId.z, tileId.y)
            TileScheme.WMTS -> error("Vector tile source supports XYZ and TMS schemes only.")
        }
        return TileRequest(
            sourceId = id,
            layerId = layerId,
            url = templateUrl
                .replace("{z}", tileId.z.toString())
                .replace("{x}", tileId.x.toString())
                .replace("{y}", requestY.toString())
                .replace("\${z}", tileId.z.toString())
                .replace("\${x}", tileId.x.toString())
                .replace("\${y}", requestY.toString()),
            headers = headers,
            tileId = TileId(
                z = tileId.z,
                x = tileId.x,
                y = tileId.y,
                scheme = scheme
            ),
            resourceType = TileResourceType.VECTOR,
            metadata = mapOf("kind" to "mvt")
        )
    }

    private fun tmsY(z: Int, y: Int): Int {
        val maxY = (1 shl z) - 1
        return max(0, maxY - y)
    }
}

internal sealed class VectorraMvtTileLoadResult {
    abstract val request: TileRequest

    data class Loaded(
        override val request: TileRequest,
        val response: TileResponse,
        val decodedTile: VectorraMvtTile
    ) : VectorraMvtTileLoadResult()

    data class Failed(
        override val request: TileRequest,
        val statusCode: Int,
        val errorType: VectorraResourceErrorType,
        val message: String
    ) : VectorraMvtTileLoadResult() {
        init {
            require(message.isNotBlank()) { "MVT tile load failure message must not be blank." }
        }
    }
}

internal fun TileResourceFetcher.asMvtTileLoader(): VectorraMvtTileLoader {
    return VectorraMvtTileLoader { request, priority ->
        fetch(request, priority = priority)
    }
}
