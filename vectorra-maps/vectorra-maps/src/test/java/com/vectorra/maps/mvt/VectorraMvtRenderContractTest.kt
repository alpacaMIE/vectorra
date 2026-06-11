package com.vectorra.maps.mvt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMvtRenderContractTest {
    @Test
    fun nativeTileRequestKeepsStableHandleAndRawBytes() {
        val bytes = byteArrayOf(1, 2, 3)
        val request = VectorraMvtNativeTileRequest(
            sourceId = "osm",
            layerId = "roads-layer",
            sourceLayer = "roads",
            tileId = VectorraMvtTileId(z = 10, x = 823, y = 412),
            style = VectorraMvtRenderStyle(
                kind = VectorraMvtRenderLayerKind.LINE,
                color = 0xff336699.toInt(),
                opacity = 0.75f,
                widthPixels = 2.5f
            ),
            tileBytes = bytes,
            renderNow = false
        )

        assertEquals("roads-layer:10/823/412", request.nativeTileHandle)
        assertEquals("roads", request.sourceLayer)
        assertEquals(VectorraMvtRenderLayerKind.LINE, request.style.kind)
        assertTrue(request.tileBytes.contentEquals(bytes))
        assertEquals(false, request.renderNow)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nativeTileRequestRejectsBlankSourceLayer() {
        VectorraMvtNativeTileRequest(
            sourceId = "osm",
            layerId = "roads-layer",
            sourceLayer = "",
            tileId = VectorraMvtTileId(z = 0, x = 0, y = 0),
            style = VectorraMvtRenderStyle(kind = VectorraMvtRenderLayerKind.LINE),
            tileBytes = byteArrayOf(1),
            renderNow = true
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nativeTileRequestRejectsEmptyBytes() {
        VectorraMvtNativeTileRequest(
            sourceId = "osm",
            layerId = "roads-layer",
            sourceLayer = "roads",
            tileId = VectorraMvtTileId(z = 0, x = 0, y = 0),
            style = VectorraMvtRenderStyle(kind = VectorraMvtRenderLayerKind.LINE),
            tileBytes = byteArrayOf(),
            renderNow = true
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun renderStyleRejectsInvalidOpacity() {
        VectorraMvtRenderStyle(
            kind = VectorraMvtRenderLayerKind.FILL,
            opacity = 1.5f
        )
    }
}
