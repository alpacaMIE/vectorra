package com.vectorra.maps.mvt

import com.vectorra.maps.internal.VectorraNative
import com.vectorra.maps.query.VectorraAnnotationGeometry
import com.vectorra.maps.vector.VectorraVectorTileLayer
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorraMvtRenderContractTest {
    @Test
    fun vectorTileLayersMapToNativeRenderStyles() {
        val line = VectorraVectorTileLayer.Line(
            id = "roads-layer",
            sourceId = "osm",
            sourceLayer = "roads",
            visible = false,
            color = 0xff336699.toInt(),
            opacity = 0.75,
            widthPixels = 2.5
        )
        val circle = VectorraVectorTileLayer.Circle(
            id = "poi-layer",
            sourceId = "osm",
            sourceLayer = "poi",
            color = 0xff224466.toInt(),
            opacity = 0.5,
            radiusPixels = 8.0
        )

        assertEquals(
            VectorraMvtRenderStyle(
                kind = VectorraMvtRenderLayerKind.LINE,
                visible = false,
                color = 0xff336699.toInt(),
                opacity = 0.75f,
                widthPixels = 2.5f
            ),
            line.toMvtRenderStyle()
        )
        assertEquals(
            VectorraMvtRenderStyle(
                kind = VectorraMvtRenderLayerKind.CIRCLE,
                color = 0xff224466.toInt(),
                opacity = 0.5f,
                radiusPixels = 8.0f
            ),
            circle.toMvtRenderStyle()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun renderStyleRejectsInvalidOpacity() {
        VectorraMvtRenderStyle(
            kind = VectorraMvtRenderLayerKind.FILL,
            opacity = 1.5f
        )
    }

    @Test
    fun nativeQueryResultMapsToDecodedFeatures() {
        val result = VectorraNative.MvtTileResult(
            nativeTileHandle = "",
            errorMessage = null,
            featureIds = arrayOf("road-1", "park-1"),
            sourceLayers = arrayOf("roads", "landuse"),
            geometryTypes = intArrayOf(
                VectorraMvtRenderGeometryType.LINE.ordinal,
                VectorraMvtRenderGeometryType.POLYGON.ordinal
            ),
            coordinateOffsets = intArrayOf(0, 4, 12),
            coordinates = doubleArrayOf(
                120.0, 30.0,
                120.1, 30.1,
                121.0, 31.0,
                121.1, 31.0,
                121.1, 31.1,
                121.0, 31.0
            ),
            ringOffsets = intArrayOf(0, 0, 1),
            ringEnds = intArrayOf(4),
            propertyOffsets = intArrayOf(0, 2, 3),
            propertyKeys = arrayOf("__vectorra-render-layer", "class", "name"),
            propertyValues = arrayOf("roads-layer", "primary", "park")
        )

        val features = result.toDecodedFeatures()

        assertEquals(2, features.size)
        assertEquals("road-1", features[0].id)
        assertEquals("roads", features[0].layerName)
        assertEquals("roads-layer", features[0].properties["__vectorra-render-layer"])
        assertEquals("primary", features[0].properties["class"])
        val line = features[0].geometry as VectorraAnnotationGeometry.LineString
        assertEquals(2, line.coordinates.size)
        assertEquals(120.1, line.coordinates[1].longitude, 0.0)

        assertEquals("park-1", features[1].id)
        val polygon = features[1].geometry as VectorraAnnotationGeometry.Polygon
        assertEquals(1, polygon.rings.size)
        assertEquals(4, polygon.rings[0].size)
        assertEquals("park", features[1].properties["name"])
    }
}
