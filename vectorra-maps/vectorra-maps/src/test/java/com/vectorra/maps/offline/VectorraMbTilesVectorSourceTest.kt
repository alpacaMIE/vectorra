package com.vectorra.maps.offline

import java.nio.file.Files
import org.junit.Assert.assertEquals
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
}
