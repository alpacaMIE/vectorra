package com.vectorra.maps.vector

import com.vectorra.maps.network.TileScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraVectorTileModelsTest {
    @Test
    fun xyzSourceUsesVectorDefaults() {
        val source = VectorraVectorTileSource.xyz(
            id = "openmaptiles",
            templateUrl = "https://example.test/{z}/{x}/{y}.mvt",
            headers = mapOf("Authorization" to "Bearer token")
        )

        assertEquals("openmaptiles", source.id)
        assertEquals(TileScheme.XYZ, source.scheme)
        assertEquals(512, source.tileSize)
        assertEquals(0, source.minZoom)
        assertEquals(14, source.maxZoom)
        assertEquals("Bearer token", source.headers["Authorization"])
    }

    @Test
    fun simplifiedLayerOptionsExposeExpectedKinds() {
        val line = VectorraVectorTileLayer.Line(
            id = "roads-line",
            sourceId = "openmaptiles",
            sourceLayer = "transportation",
            widthPixels = 2.5
        )
        val fill = VectorraVectorTileLayer.Fill(
            id = "water-fill",
            sourceId = "openmaptiles",
            sourceLayer = "water",
            opacity = 0.6
        )
        val circle = VectorraVectorTileLayer.Circle(
            id = "poi-circle",
            sourceId = "openmaptiles",
            sourceLayer = "poi",
            radiusPixels = 6.0
        )
        val symbol = VectorraVectorTileLayer.Symbol(
            id = "place-labels",
            sourceId = "openmaptiles",
            sourceLayer = "place",
            textField = "name"
        )

        assertEquals("transportation", line.sourceLayer)
        assertEquals(2.5, line.widthPixels, 0.0)
        assertEquals(0.6, fill.opacity, 0.0)
        assertEquals(6.0, circle.radiusPixels, 0.0)
        assertEquals("name", symbol.textField)
        assertTrue(symbol.visible)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWmtsVectorSource() {
        VectorraVectorTileSource(
            id = "vector",
            templateUrl = "https://example.test/wmts",
            scheme = TileScheme.WMTS
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankSourceLayer() {
        VectorraVectorTileLayer.Line(
            id = "roads",
            sourceId = "vector",
            sourceLayer = ""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidOpacity() {
        VectorraVectorTileLayer.Fill(
            id = "land",
            sourceId = "vector",
            sourceLayer = "landuse",
            opacity = 1.2
        )
    }

    @Test
    fun tmsSourceKeepsScheme() {
        val source = VectorraVectorTileSource.tms(
            id = "legacy-vector",
            templateUrl = "https://example.test/{z}/{x}/{y}.pbf"
        )

        assertFalse(source.scheme == TileScheme.XYZ)
        assertEquals(TileScheme.TMS, source.scheme)
    }
}
