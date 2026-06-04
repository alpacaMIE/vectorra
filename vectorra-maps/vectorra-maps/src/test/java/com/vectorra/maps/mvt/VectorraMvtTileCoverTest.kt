package com.vectorra.maps.mvt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMvtTileCoverTest {
    @Test
    fun returnsCenterTileForSmallViewport() {
        assertEquals(
            setOf(VectorraMvtTileId(z = 12, x = 655, y = 1583)),
            visibleTiles(
                longitude = -122.4194,
                latitude = 37.7749,
                zoom = 12.0,
                viewportWidthPixels = 1,
                viewportHeightPixels = 1
            )
        )
    }

    @Test
    fun coversViewportWithoutAddingTilesPastExactEdges() {
        val tiles = visibleTiles(
            longitude = 0.0,
            latitude = 0.0,
            zoom = 2.0,
            viewportWidthPixels = 1024,
            viewportHeightPixels = 512,
            tileMaxZoom = 4
        )

        assertEquals(
            setOf(
                VectorraMvtTileId(z = 2, x = 0, y = 1),
                VectorraMvtTileId(z = 2, x = 0, y = 2),
                VectorraMvtTileId(z = 2, x = 1, y = 1),
                VectorraMvtTileId(z = 2, x = 1, y = 2),
                VectorraMvtTileId(z = 2, x = 2, y = 1),
                VectorraMvtTileId(z = 2, x = 2, y = 2),
                VectorraMvtTileId(z = 2, x = 3, y = 1),
                VectorraMvtTileId(z = 2, x = 3, y = 2)
            ),
            tiles
        )
    }

    @Test
    fun wrapsTileXAcrossAntiMeridian() {
        val tiles = visibleTiles(
            longitude = 179.0,
            latitude = 0.0,
            zoom = 2.0,
            viewportWidthPixels = 1024,
            viewportHeightPixels = 1,
            tileMaxZoom = 4
        )

        assertEquals(setOf(0, 1, 2, 3), tiles.map { it.x }.toSet())
        assertEquals(setOf(2), tiles.map { it.z }.toSet())
    }

    @Test
    fun paddingPrefetchesNeighborTiles() {
        val tiles = visibleTiles(
            longitude = 1.0,
            latitude = -1.0,
            zoom = 3.0,
            viewportWidthPixels = 1,
            viewportHeightPixels = 1,
            tilePadding = 1,
            tileMaxZoom = 4
        )

        assertEquals(
            setOf(
                VectorraMvtTileId(z = 3, x = 3, y = 3),
                VectorraMvtTileId(z = 3, x = 3, y = 4),
                VectorraMvtTileId(z = 3, x = 3, y = 5),
                VectorraMvtTileId(z = 3, x = 4, y = 3),
                VectorraMvtTileId(z = 3, x = 4, y = 4),
                VectorraMvtTileId(z = 3, x = 4, y = 5),
                VectorraMvtTileId(z = 3, x = 5, y = 3),
                VectorraMvtTileId(z = 3, x = 5, y = 4),
                VectorraMvtTileId(z = 3, x = 5, y = 5)
            ),
            tiles
        )
    }

    @Test
    fun overscalesTileZoomPastSourceMaxWhileLayerIsVisible() {
        val tiles = visibleTiles(
            longitude = -122.4194,
            latitude = 37.7749,
            zoom = 16.0,
            viewportWidthPixels = 1080,
            viewportHeightPixels = 1920,
            visibleMaxZoom = 22,
            tileMaxZoom = 14
        )

        assertTrue(tiles.isNotEmpty())
        assertEquals(setOf(14), tiles.map { it.z }.toSet())
    }

    @Test
    fun returnsEmptyWhenHiddenOrOutsideLayerZoomRange() {
        assertTrue(visibleTiles(visible = false).isEmpty())
        assertTrue(visibleTiles(zoom = 23.0, visibleMaxZoom = 22).isEmpty())
    }

    private fun visibleTiles(
        longitude: Double = 0.0,
        latitude: Double = 0.0,
        zoom: Double = 12.0,
        bearing: Double = 0.0,
        viewportWidthPixels: Int = 512,
        viewportHeightPixels: Int = 512,
        tileSizePixels: Int = 256,
        tilePadding: Int = 0,
        visible: Boolean = true,
        visibleMinZoom: Int = 0,
        visibleMaxZoom: Int = 22,
        tileMinZoom: Int = 0,
        tileMaxZoom: Int = 14
    ): Set<VectorraMvtTileId> {
        return VectorraMvtTileCover.visibleTiles(
            VectorraMvtTileCoverRequest(
                longitude = longitude,
                latitude = latitude,
                zoom = zoom,
                bearing = bearing,
                viewportWidthPixels = viewportWidthPixels,
                viewportHeightPixels = viewportHeightPixels,
                tileSizePixels = tileSizePixels,
                tilePadding = tilePadding,
                visible = visible,
                visibleMinZoom = visibleMinZoom,
                visibleMaxZoom = visibleMaxZoom,
                tileMinZoom = tileMinZoom,
                tileMaxZoom = tileMaxZoom
            )
        )
    }
}
