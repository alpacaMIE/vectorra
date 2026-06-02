package com.vectorra.maps.offline

import com.vectorra.maps.network.TileId
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorraMbTilesRasterSourceTest {
    @Test
    fun parsesMetadataWithBoundsCenterAndXyzScheme() {
        val metadata = VectorraMbTilesMetadataParser.parse(
            values = mapOf(
                "name" to "Local Basemap",
                "format" to "jpg",
                "minzoom" to "2",
                "maxzoom" to "12",
                "bounds" to "-180,-85,180,85",
                "center" to "104.0,30.0,5",
                "scheme" to "xyz"
            ),
            fallbackMinZoom = 0,
            fallbackMaxZoom = 14
        )

        assertEquals("Local Basemap", metadata.name)
        assertEquals("jpeg", metadata.format)
        assertEquals("image/jpeg", metadata.contentType)
        assertEquals(2, metadata.minZoom)
        assertEquals(12, metadata.maxZoom)
        assertEquals(VectorraMbTilesBounds(-180.0, -85.0, 180.0, 85.0), metadata.bounds)
        assertEquals(VectorraMbTilesCenter(104.0, 30.0, 5.0), metadata.center)
        assertEquals(VectorraMbTilesTileRowScheme.XYZ, metadata.tileRowScheme)
    }

    @Test
    fun usesFallbackZoomsAndTmsSchemeByDefault() {
        val metadata = VectorraMbTilesMetadataParser.parse(
            values = mapOf("format" to "png"),
            fallbackMinZoom = 3,
            fallbackMaxZoom = 8
        )

        assertEquals("png", metadata.format)
        assertEquals("image/png", metadata.contentType)
        assertEquals(3, metadata.minZoom)
        assertEquals(8, metadata.maxZoom)
        assertEquals(VectorraMbTilesTileRowScheme.TMS, metadata.tileRowScheme)
        assertNull(metadata.bounds)
        assertNull(metadata.center)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedVectorFormat() {
        VectorraMbTilesMetadataParser.parse(
            values = mapOf("format" to "pbf"),
            fallbackMinZoom = 0,
            fallbackMaxZoom = 14
        )
    }

    @Test
    fun convertsXyzRowsToTmsStorageRows() {
        assertEquals(5, VectorraMbTilesTileRows.storageRow(3, 2, VectorraMbTilesTileRowScheme.TMS))
        assertEquals(2, VectorraMbTilesTileRows.storageRow(3, 2, VectorraMbTilesTileRowScheme.XYZ))
    }

    @Test
    fun validatesTileIdsAgainstZoomDimension() {
        assertEquals(true, VectorraMbTilesTileRows.isValid(TileId(z = 2, x = 3, y = 3)))
        assertEquals(false, VectorraMbTilesTileRows.isValid(TileId(z = 2, x = 4, y = 3)))
        assertEquals(false, VectorraMbTilesTileRows.isValid(TileId(z = 31, x = 0, y = 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingMbTilesFileBeforeOpeningSqlite() {
        val file = Files.createTempDirectory("vectorra-mbtiles-missing").resolve("missing.mbtiles").toFile()
        VectorraMbTilesRasterSource.open(file)
    }
}
