package com.vectorra.maps.tiles3d

import org.junit.Assert.assertEquals
import org.junit.Test

class Vectorra3DTilesModelsTest {
    @Test
    fun createsFormalSourceLayerAndOptions() {
        val source = Vectorra3DTilesSource(
            id = "city",
            tilesetUri = "https://assets.example.com/city/tileset.json",
            headers = mapOf("Authorization" to "Bearer test")
        )
        val options = Vectorra3DTilesOptions(
            visible = false,
            maximumScreenSpaceError = 8.0,
            maximumLoadedTiles = 32
        )
        val layer = Vectorra3DTilesLayer(
            id = "city-buildings",
            sourceId = source.id,
            options = options
        )

        assertEquals("city", source.id)
        assertEquals("city-buildings", layer.id)
        assertEquals(8.0, layer.options.maximumScreenSpaceError, 0.0)
        assertEquals(32, layer.options.maximumLoadedTiles)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankSourceId() {
        Vectorra3DTilesSource(id = "", tilesetUri = "file:///tileset.json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankLayerSourceId() {
        Vectorra3DTilesLayer(id = "layer", sourceId = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidScreenSpaceError() {
        Vectorra3DTilesOptions(maximumScreenSpaceError = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLoadedTileBudget() {
        Vectorra3DTilesOptions(maximumLoadedTiles = 0)
    }
}
