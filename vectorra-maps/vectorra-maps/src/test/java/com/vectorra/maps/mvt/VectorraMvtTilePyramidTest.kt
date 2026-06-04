package com.vectorra.maps.mvt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMvtTilePyramidTest {
    @Test
    fun rendersIdealTileWhenLoaded() {
        val idealTile = VectorraMvtTileId(z = 4, x = 8, y = 7)

        val renderTiles = VectorraMvtTilePyramid.renderTileIds(
            idealTileIds = setOf(idealTile),
            loadedTileIds = setOf(idealTile, VectorraMvtTileId(z = 3, x = 4, y = 3)),
            minZoom = 0,
            maxZoom = 14
        )

        assertEquals(setOf(idealTile), renderTiles)
    }

    @Test
    fun rendersLoadedParentWhenIdealTileIsMissing() {
        val idealTile = VectorraMvtTileId(z = 4, x = 8, y = 7)
        val parentTile = VectorraMvtTileId(z = 3, x = 4, y = 3)

        val renderTiles = VectorraMvtTilePyramid.renderTileIds(
            idealTileIds = setOf(idealTile),
            loadedTileIds = setOf(VectorraMvtTileId(z = 2, x = 2, y = 1), parentTile),
            minZoom = 0,
            maxZoom = 14
        )

        assertEquals(setOf(parentTile), renderTiles)
    }

    @Test
    fun rendersCompleteChildCoverWhenIdealTileIsMissing() {
        val idealTile = VectorraMvtTileId(z = 4, x = 8, y = 7)
        val childTiles = setOf(
            VectorraMvtTileId(z = 5, x = 16, y = 14),
            VectorraMvtTileId(z = 5, x = 17, y = 14),
            VectorraMvtTileId(z = 5, x = 16, y = 15),
            VectorraMvtTileId(z = 5, x = 17, y = 15)
        )

        val renderTiles = VectorraMvtTilePyramid.renderTileIds(
            idealTileIds = setOf(idealTile),
            loadedTileIds = childTiles,
            minZoom = 0,
            maxZoom = 14
        )

        assertEquals(childTiles, renderTiles)
    }

    @Test
    fun ignoresPartialChildCoverWhenNoParentIsLoaded() {
        val idealTile = VectorraMvtTileId(z = 4, x = 8, y = 7)

        val renderTiles = VectorraMvtTilePyramid.renderTileIds(
            idealTileIds = setOf(idealTile),
            loadedTileIds = setOf(
                VectorraMvtTileId(z = 5, x = 16, y = 14),
                VectorraMvtTileId(z = 5, x = 17, y = 14),
                VectorraMvtTileId(z = 5, x = 16, y = 15)
            ),
            minZoom = 0,
            maxZoom = 14
        )

        assertTrue(renderTiles.isEmpty())
    }
}
