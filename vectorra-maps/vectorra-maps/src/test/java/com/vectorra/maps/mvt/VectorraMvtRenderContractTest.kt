package com.vectorra.maps.mvt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorraMvtRenderContractTest {
    @Test
    fun renderTileInputFlattensFeatureBatchForNativeContract() {
        val input = VectorraMvtRenderTileInput(
            sourceId = "osm",
            layerId = "roads-layer",
            tileId = VectorraMvtTileId(z = 10, x = 823, y = 412),
            style = VectorraMvtRenderStyle(
                kind = VectorraMvtRenderLayerKind.LINE,
                color = 0xff336699.toInt(),
                opacity = 0.75f,
                widthPixels = 2.5f
            ),
            features = listOf(
                VectorraMvtRenderFeature(
                    featureId = "road-1",
                    sourceLayer = "roads",
                    geometryType = VectorraMvtRenderGeometryType.LINE,
                    coordinates = listOf(1.0, 2.0, 3.0, 4.0)
                ),
                VectorraMvtRenderFeature(
                    featureId = "park-1",
                    sourceLayer = "landuse",
                    geometryType = VectorraMvtRenderGeometryType.POLYGON,
                    coordinates = listOf(5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 5.0, 6.0),
                    ringEnds = listOf(4)
                )
            )
        )

        assertEquals("roads-layer:10/823/412", input.nativeTileHandle)
        assertArrayEquals(arrayOf("road-1", "park-1"), input.nativeFeatureIds())
        assertArrayEquals(arrayOf("roads", "landuse"), input.nativeSourceLayers())
        assertArrayEquals(intArrayOf(1, 2), input.nativeGeometryTypes())
        assertArrayEquals(intArrayOf(0, 4, 12), input.nativeCoordinateOffsets())
        assertArrayEquals(
            doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 5.0, 6.0),
            input.nativeCoordinates(),
            0.0
        )
        assertArrayEquals(intArrayOf(0, 0, 1), input.nativeRingOffsets())
        assertArrayEquals(intArrayOf(4), input.nativeRingEnds())
    }

    @Test(expected = IllegalArgumentException::class)
    fun renderFeatureRejectsOddCoordinateCount() {
        VectorraMvtRenderFeature(
            featureId = "bad",
            sourceLayer = "roads",
            geometryType = VectorraMvtRenderGeometryType.LINE,
            coordinates = listOf(1.0, 2.0, 3.0)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun renderFeatureRejectsLineRingEnds() {
        VectorraMvtRenderFeature(
            featureId = "bad",
            sourceLayer = "roads",
            geometryType = VectorraMvtRenderGeometryType.LINE,
            coordinates = listOf(1.0, 2.0, 3.0, 4.0),
            ringEnds = listOf(2)
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
