package com.vectorra.maps.annotation

import com.vectorra.maps.query.VectorraAnnotationGeometry
import com.vectorra.maps.query.VectorraCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraDrawAnnotationsTest {

    @Test
    fun drawPointBuildsHitFeatureWithBusinessPayload() {
        val annotation = VectorraDrawPointAnnotation(
            id = "point-1",
            coordinate = VectorraCoordinate(104.0, 30.0),
            properties = mapOf("payload" to "{\"id\":\"point-1\"}")
        )

        val feature = annotation.toHitFeature()

        assertEquals("draw-point:point-1", feature.id)
        assertEquals(DRAW_POINT_LAYER_ID, feature.layerId)
        assertEquals("point-1", feature.properties["id"])
        assertEquals("{\"id\":\"point-1\"}", feature.properties["payload"])
        assertTrue(feature.geometry is VectorraAnnotationGeometry.Point)
    }

    @Test
    fun drawLineBuildsLineHitFeature() {
        val annotation = VectorraDrawLineAnnotation(
            id = "line-1",
            coordinates = listOf(
                VectorraCoordinate(104.0, 30.0),
                VectorraCoordinate(104.1, 30.1)
            ),
            properties = mapOf("type" to "line")
        )

        val feature = annotation.toHitFeature()

        assertEquals("draw-polyline:line-1", feature.id)
        assertEquals(DRAW_LINE_LAYER_ID, feature.layerId)
        assertEquals("line", feature.properties["type"])
        assertTrue(feature.geometry is VectorraAnnotationGeometry.LineString)
    }

    @Test
    fun drawPolygonBuildsFillAndOutlineHitFeatures() {
        val ring = listOf(
            VectorraCoordinate(104.0, 30.0),
            VectorraCoordinate(104.1, 30.0),
            VectorraCoordinate(104.1, 30.1),
            VectorraCoordinate(104.0, 30.0)
        )
        val annotation = VectorraDrawPolygonAnnotation(
            id = "polygon-1",
            rings = listOf(ring),
            properties = mapOf("type" to "polygon")
        )

        val features = annotation.toHitFeatures()

        assertEquals(listOf("draw-polygon:polygon-1", "draw-polygon-outline:polygon-1"), features.map { it.id })
        assertEquals(DRAW_POLYGON_LAYER_ID, features[0].layerId)
        assertEquals(DRAW_POLYGON_OUTLINE_LAYER_ID, features[1].layerId)
        assertTrue(features[0].geometry is VectorraAnnotationGeometry.Polygon)
        assertTrue(features[1].geometry is VectorraAnnotationGeometry.LineString)
    }
}
