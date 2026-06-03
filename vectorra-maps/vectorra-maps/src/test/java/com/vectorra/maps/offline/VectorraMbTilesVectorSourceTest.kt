package com.vectorra.maps.offline

import com.vectorra.maps.network.TileCacheStatus
import com.vectorra.maps.network.TileId
import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResourceType
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMbTilesVectorSourceTest {
    @Test
    fun parsesPbfMetadataWithBoundsAndXyzScheme() {
        val metadata = VectorraMbTilesVectorMetadataParser.parse(
            values = mapOf(
                "name" to "Local Vector",
                "format" to "pbf",
                "minzoom" to "1",
                "maxzoom" to "14",
                "bounds" to "-122.6,37.7,-122.2,37.9",
                "scheme" to "xyz"
            ),
            fallbackMinZoom = 0,
            fallbackMaxZoom = 10
        )

        assertEquals("Local Vector", metadata.name)
        assertEquals("pbf", metadata.format)
        assertEquals("application/x-protobuf", metadata.contentType)
        assertEquals(1, metadata.minZoom)
        assertEquals(14, metadata.maxZoom)
        assertEquals(VectorraMbTilesTileRowScheme.XYZ, metadata.tileRowScheme)
        assertEquals(VectorraMbTilesBounds(-122.6, 37.7, -122.2, 37.9), metadata.bounds)
    }

    @Test
    fun normalizesApplicationProtobufFormatToPbf() {
        val metadata = VectorraMbTilesVectorMetadataParser.parse(
            values = mapOf("format" to "application/x-protobuf"),
            fallbackMinZoom = 3,
            fallbackMaxZoom = 8
        )

        assertEquals("pbf", metadata.format)
        assertEquals(3, metadata.minZoom)
        assertEquals(8, metadata.maxZoom)
        assertEquals(VectorraMbTilesTileRowScheme.TMS, metadata.tileRowScheme)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRasterFormat() {
        VectorraMbTilesVectorMetadataParser.parse(
            values = mapOf("format" to "png"),
            fallbackMinZoom = 0,
            fallbackMaxZoom = 14
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingMbTilesFileBeforeOpeningSqlite() {
        val file = Files.createTempDirectory("vectorra-mbtiles-vector-missing").resolve("missing.mbtiles").toFile()
        VectorraMbTilesVectorSource.open(file)
    }

    @Test
    fun loadTileReadsVectorBytesUsingMetadataRowScheme() {
        val tileId = TileId(z = 2, x = 1, y = 2)
        val tileBytes = byteArrayOf(0x1a, 0x02, 0x78, 0x00)
        val reader = RecordingTileReader(mapOf(tileId to tileBytes))
        val source = VectorraMbTilesVectorSource(
            id = "local-vector",
            file = Files.createTempFile("vectorra-vector", ".mbtiles").toFile(),
            metadata = metadata(format = "pbf", scheme = VectorraMbTilesTileRowScheme.TMS),
            reader = reader
        )

        val response = source.loadTile(
            TileRequest(
                sourceId = "local-vector",
                layerId = "roads",
                url = "vectorra-local://tile/local-vector/2/1/2",
                tileId = tileId,
                resourceType = TileResourceType.VECTOR
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("application/x-protobuf", response.headers["Content-Type"])
        assertEquals(TileCacheStatus.DISK, response.cacheStatus)
        assertTrue(tileBytes.contentEquals(response.body))
        assertEquals(tileId to VectorraMbTilesTileRowScheme.TMS, reader.reads.single())
    }

    @Test
    fun loadTileReturns404ForMissingOrInvalidTile() {
        val source = VectorraMbTilesVectorSource(
            id = "local-vector",
            file = Files.createTempFile("vectorra-vector-missing", ".mbtiles").toFile(),
            metadata = metadata(format = "mvt", scheme = VectorraMbTilesTileRowScheme.XYZ),
            reader = RecordingTileReader(emptyMap())
        )

        val missing = source.loadTile(
            TileRequest(
                url = "vectorra-local://tile/local-vector/2/1/2",
                tileId = TileId(z = 2, x = 1, y = 2),
                resourceType = TileResourceType.VECTOR
            )
        )
        val invalid = source.loadTile(
            TileRequest(
                url = "vectorra-local://tile/local-vector/31/0/0",
                tileId = TileId(z = 31, x = 0, y = 0),
                resourceType = TileResourceType.VECTOR
            )
        )

        assertEquals(404, missing.statusCode)
        assertEquals(404, invalid.statusCode)
    }

    @Test
    fun closeClosesReader() {
        val reader = RecordingTileReader(emptyMap())
        val source = VectorraMbTilesVectorSource(
            id = "local-vector",
            file = Files.createTempFile("vectorra-vector-close", ".mbtiles").toFile(),
            metadata = metadata(format = "pbf", scheme = VectorraMbTilesTileRowScheme.TMS),
            reader = reader
        )

        source.close()

        assertEquals(true, reader.closed)
    }

    private fun metadata(
        format: String,
        scheme: VectorraMbTilesTileRowScheme
    ): VectorraMbTilesMetadata {
        return VectorraMbTilesMetadata(
            name = "Local Vector",
            format = format,
            minZoom = 0,
            maxZoom = 14,
            bounds = null,
            center = null,
            tileRowScheme = scheme,
            raw = mapOf("format" to format)
        )
    }

    private class RecordingTileReader(
        private val tiles: Map<TileId, ByteArray>
    ) : VectorraMbTilesTileReader {
        val reads = mutableListOf<Pair<TileId, VectorraMbTilesTileRowScheme>>()
        var closed = false

        override fun readMetadata(): Map<String, String> = emptyMap()

        override fun readZoomRange(): Pair<Int, Int> = 0 to 14

        override fun readTile(tileId: TileId, scheme: VectorraMbTilesTileRowScheme): ByteArray? {
            reads += tileId to scheme
            return tiles[tileId]
        }

        override fun close() {
            closed = true
        }
    }
}
