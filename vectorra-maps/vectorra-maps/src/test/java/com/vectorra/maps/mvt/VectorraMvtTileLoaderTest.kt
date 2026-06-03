package com.vectorra.maps.mvt

import com.vectorra.maps.network.TileCacheStatus
import com.vectorra.maps.VectorraResourceErrorType
import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResourceType
import com.vectorra.maps.network.TileResponse
import com.vectorra.maps.network.TileScheme
import com.vectorra.maps.vector.VectorraVectorTileSource
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMvtTileLoaderTest {
    @Test
    fun loadsXyzTileThroughVectorRequestAndDecodesBody() {
        val requests = mutableListOf<TileRequest>()
        val loader = VectorraMvtTileLoader { request, priority ->
            requests += request
            assertEquals(8, priority)
            TileResponse(
                request = request,
                statusCode = 200,
                body = mvtTile("roads"),
                cacheStatus = TileCacheStatus.MISS
            )
        }

        val result = loader.loadTile(
            source = VectorraVectorTileSource.xyz(
                id = "vector",
                templateUrl = "https://tiles.example/{z}/{x}/{y}.mvt",
                headers = mapOf("Authorization" to "Bearer token")
            ),
            layerId = "roads-line",
            tileId = VectorraMvtTileId(z = 8, x = 41, y = 99)
        ) as VectorraMvtTileLoadResult.Loaded

        assertEquals("https://tiles.example/8/41/99.mvt", requests.single().url)
        assertEquals("vector", requests.single().sourceId)
        assertEquals("roads-line", requests.single().layerId)
        assertEquals(TileResourceType.VECTOR, requests.single().resourceType)
        assertEquals(TileScheme.XYZ, requests.single().tileId?.scheme)
        assertEquals("Bearer token", requests.single().headers["Authorization"])
        assertEquals(TileCacheStatus.MISS, result.response.cacheStatus)
        assertEquals("roads", result.decodedTile.layers.single().name)
    }

    @Test
    fun tmsSourceFlipsRequestYButKeepsStoreTileIdAsXyz() {
        lateinit var request: TileRequest
        val loader = VectorraMvtTileLoader { tileRequest, _ ->
            request = tileRequest
            TileResponse(request = tileRequest, statusCode = 200, body = mvtTile("roads"))
        }

        loader.loadTile(
            source = VectorraVectorTileSource.tms(
                id = "vector",
                templateUrl = "https://tiles.example/\${z}/\${x}/\${y}.pbf"
            ),
            layerId = "roads-line",
            tileId = VectorraMvtTileId(z = 3, x = 2, y = 1)
        )

        assertEquals("https://tiles.example/3/2/6.pbf", request.url)
        assertEquals(1, request.tileId?.y)
        assertEquals(TileScheme.TMS, request.tileId?.scheme)
    }

    @Test
    fun cacheHitResponseDecodesWithSameLoadedSemantics() {
        val loader = VectorraMvtTileLoader { request, _ ->
            TileResponse(
                request = request,
                statusCode = 200,
                body = mvtTile("roads"),
                cacheStatus = TileCacheStatus.MEMORY
            )
        }

        val result = loader.loadTile(
            source = VectorraVectorTileSource.xyz(
                id = "vector",
                templateUrl = "https://tiles.example/{z}/{x}/{y}.mvt"
            ),
            layerId = "roads-line",
            tileId = VectorraMvtTileId(z = 4, x = 2, y = 3)
        ) as VectorraMvtTileLoadResult.Loaded

        assertEquals(TileCacheStatus.MEMORY, result.response.cacheStatus)
        assertEquals("roads", result.decodedTile.layers.single().name)
        assertEquals(1L, result.decodedTile.layers.single().features.single().id)
    }

    @Test
    fun failedHttpResponseDoesNotDecode() {
        val loader = VectorraMvtTileLoader { request, _ ->
            TileResponse(request = request, statusCode = 404, errorMessage = "not found")
        }

        val result = loader.loadTile(
            source = VectorraVectorTileSource.xyz(
                id = "vector",
                templateUrl = "https://tiles.example/{z}/{x}/{y}.mvt"
            ),
            layerId = "roads-line",
            tileId = VectorraMvtTileId(z = 0, x = 0, y = 0)
        ) as VectorraMvtTileLoadResult.Failed

        assertEquals(404, result.statusCode)
        assertEquals(VectorraResourceErrorType.NETWORK, result.errorType)
        assertEquals("not found", result.message)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTileOutsideSourceZoomRange() {
        val loader = VectorraMvtTileLoader { request, _ ->
            TileResponse(request = request, statusCode = 200, body = mvtTile("roads"))
        }

        loader.loadTile(
            source = VectorraVectorTileSource.xyz(
                id = "vector",
                templateUrl = "https://tiles.example/{z}/{x}/{y}.mvt",
                minZoom = 5,
                maxZoom = 6
            ),
            layerId = "roads-line",
            tileId = VectorraMvtTileId(z = 4, x = 0, y = 0)
        )
    }

    @Test
    fun decodeFailureReturnsFailedResult() {
        val loader = VectorraMvtTileLoader { request, _ ->
            TileResponse(request = request, statusCode = 200, body = byteArrayOf(0xff.toByte()))
        }

        val result = loader.loadTile(
            source = VectorraVectorTileSource.xyz(
                id = "vector",
                templateUrl = "https://tiles.example/{z}/{x}/{y}.mvt"
            ),
            layerId = "roads-line",
            tileId = VectorraMvtTileId(z = 0, x = 0, y = 0)
        )

        assertTrue(result is VectorraMvtTileLoadResult.Failed)
        assertEquals(VectorraResourceErrorType.RESOURCE, (result as VectorraMvtTileLoadResult.Failed).errorType)
    }

    private fun mvtTile(layerName: String): ByteArray {
        return message {
            bytes(
                3,
                message {
                    string(1, layerName)
                    bytes(
                        2,
                        message {
                            uint(1, 1)
                            uint(3, 1)
                            packed(4, listOf(command(1, 1), zigZag(1), zigZag(1)))
                        }
                    )
                    uint(5, 4096)
                    uint(15, 2)
                }
            )
        }
    }

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun message(block: Encoder.() -> Unit): ByteArray {
        return Encoder().apply(block).toByteArray()
    }

    private class Encoder {
        private val output = ByteArrayOutputStream()

        fun uint(field: Int, value: Long) {
            tag(field, 0)
            varint(value)
        }

        fun string(field: Int, value: String) {
            bytes(field, value.toByteArray(Charsets.UTF_8))
        }

        fun bytes(field: Int, value: ByteArray) {
            tag(field, 2)
            varint(value.size.toLong())
            output.write(value)
        }

        fun packed(field: Int, values: List<Int>) {
            if (values.isEmpty()) return
            val packed = Encoder()
            values.forEach { packed.varint(it.toLong()) }
            bytes(field, packed.toByteArray())
        }

        fun toByteArray(): ByteArray = output.toByteArray()

        private fun tag(field: Int, wireType: Int) {
            varint(((field shl 3) or wireType).toLong())
        }

        fun varint(value: Long) {
            var remaining = value
            while (remaining >= 0x80L) {
                output.write(((remaining and 0x7fL) or 0x80L).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
    }
}
