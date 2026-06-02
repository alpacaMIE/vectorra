package com.vectorra.maps.source

import com.vectorra.maps.network.TileScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraRasterTileSourceTest {
    @Test
    fun createsXyzLayerWithTileSizeAndZoomRange() {
        val source = VectorraRasterTileSource.xyz(
            id = "custom-source",
            templateUrl = "https://tiles.example/{z}/{x}/{y}.png",
            tileSize = 512,
            minZoom = 2,
            maxZoom = 19
        )

        val layer = source.toRasterLayer(layerId = "layer", opacity = 0.7)

        assertEquals("custom-source", layer.sourceId)
        assertEquals("https://tiles.example/{z}/{x}/{y}.png", layer.templateUrl)
        assertEquals(512, layer.tileSize)
        assertEquals(2, layer.minZoom)
        assertEquals(19, layer.maxZoom)
        assertEquals(TileScheme.XYZ, layer.scheme)
        assertEquals(0.7, layer.opacity, 0.0)
    }

    @Test
    fun createsTmsLayerForBottomLeftYTemplates() {
        val source = VectorraRasterTileSource.tms(
            id = "tms-source",
            templateUrl = "https://tiles.example/{z}/{x}/{y}.png"
        )

        assertEquals(TileScheme.TMS, source.scheme)
        assertEquals(TileScheme.TMS, source.toRasterLayer("layer").scheme)
    }

    @Test
    fun buildsWmtsTemplateWithMatrixSetAndTileParams() {
        val source = VectorraRasterTileSource.wmts(
            id = "tdt-img-source",
            serviceUrl = "https://t0.tianditu.gov.cn/img_w/wmts",
            layer = "img",
            matrixSet = "w",
            tileSize = 128,
            extraQuery = mapOf("tk" to "token")
        )

        assertEquals(TileScheme.WMTS, source.scheme)
        assertEquals("w", source.matrixSet)
        assertEquals(128, source.tileSize)
        assertTrue("tileMatrixSet=w" in source.templateUrl)
        assertTrue("TileMatrix={z}" in source.templateUrl)
        assertTrue("TileRow={y}" in source.templateUrl)
        assertTrue("TileCol={x}" in source.templateUrl)
        assertTrue("tk=token" in source.templateUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWmtsWithoutMatrixSet() {
        VectorraRasterTileSource(
            id = "broken-wmts",
            templateUrl = "https://tiles.example/wmts?TileMatrix={z}&TileRow={y}&TileCol={x}",
            scheme = TileScheme.WMTS
        )
    }
}
