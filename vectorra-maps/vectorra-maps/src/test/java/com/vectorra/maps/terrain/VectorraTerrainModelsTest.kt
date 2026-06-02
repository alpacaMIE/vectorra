package com.vectorra.maps.terrain

import org.junit.Assert.assertEquals
import org.junit.Test

class VectorraTerrainModelsTest {
    @Test
    fun createsTerrariumSourceWithDefaults() {
        val source = VectorraTerrainSource(
            id = "terrain",
            templateUrl = "https://example.com/dem/{z}/{x}/{y}.png"
        )

        assertEquals("terrain", source.id)
        assertEquals(VectorraDemEncoding.TERRARIUM, source.encoding)
        assertEquals(0, source.minZoom)
        assertEquals(14, source.maxZoom)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankTerrainSourceId() {
        VectorraTerrainSource(id = "", templateUrl = "https://example.com/{z}/{x}/{y}.png")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidZoomRange() {
        VectorraTerrainSource(
            id = "terrain",
            templateUrl = "https://example.com/{z}/{x}/{y}.png",
            minZoom = 10,
            maxZoom = 3
        )
    }

    @Test
    fun clampsExaggerationForNativeRenderer() {
        assertEquals(0.0, VectorraTerrainOptions(exaggeration = -1.0).clampedExaggeration, 0.0)
        assertEquals(3.0, VectorraTerrainOptions(exaggeration = 3.0).clampedExaggeration, 0.0)
        assertEquals(10.0, VectorraTerrainOptions(exaggeration = 12.0).clampedExaggeration, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteExaggeration() {
        VectorraTerrainOptions(exaggeration = Double.NaN)
    }
}
