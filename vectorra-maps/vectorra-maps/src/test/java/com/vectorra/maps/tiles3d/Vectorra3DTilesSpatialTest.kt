package com.vectorra.maps.tiles3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Vectorra3DTilesSpatialTest {
    @Test
    fun composeTransformAppliesParentTileAndRtcCenterInOrder() {
        val parent = Vectorra3DTilesSpatial.translation(10.0, 0.0, 0.0)
        val tile = Vectorra3DTilesSpatial.translation(0.0, 20.0, 0.0)
        val composed = Vectorra3DTilesSpatial.composeTransform(
            parentTransform = parent,
            tileTransform = tile,
            rtcCenter = Vectorra3DTilesPoint3D(0.0, 0.0, 30.0)
        )

        val point = Vectorra3DTilesSpatial.transformPoint(Vectorra3DTilesPoint3D(1.0, 2.0, 3.0), composed)

        assertClose(11.0, point.x)
        assertClose(22.0, point.y)
        assertClose(33.0, point.z)
    }

    @Test
    fun sphereBoundsApplyTranslationAndLargestScale() {
        val sphere = Vectorra3DTilesSpatial.boundingSphere(
            volume = VectorraTileset3DBoundingVolume.Sphere(
                centerX = 1.0,
                centerY = 2.0,
                centerZ = 3.0,
                radius = 4.0
            ),
            transform = listOf(
                2.0, 0.0, 0.0, 0.0,
                0.0, 3.0, 0.0, 0.0,
                0.0, 0.0, 4.0, 0.0,
                10.0, 20.0, 30.0, 1.0
            )
        )

        assertClose(12.0, sphere.center.x)
        assertClose(26.0, sphere.center.y)
        assertClose(42.0, sphere.center.z)
        assertClose(16.0, sphere.radius)
    }

    @Test
    fun boxBoundsUseHalfAxisDiagonal() {
        val sphere = Vectorra3DTilesSpatial.boundingSphere(
            volume = VectorraTileset3DBoundingVolume.Box(
                listOf(
                    1.0, 2.0, 3.0,
                    4.0, 0.0, 0.0,
                    0.0, 5.0, 0.0,
                    0.0, 0.0, 12.0
                )
            )
        )

        assertClose(1.0, sphere.center.x)
        assertClose(2.0, sphere.center.y)
        assertClose(3.0, sphere.center.z)
        assertClose(Math.sqrt(185.0), sphere.radius)
    }

    @Test
    fun regionBoundsProduceEcefSphere() {
        val sphere = Vectorra3DTilesSpatial.boundingSphere(
            volume = VectorraTileset3DBoundingVolume.Region(
                west = 0.0,
                south = 0.0,
                east = 0.001,
                north = 0.001,
                minimumHeight = 0.0,
                maximumHeight = 100.0
            )
        )

        assertTrue(sphere.center.x > 6_377_000.0)
        assertTrue(sphere.center.y > 0.0)
        assertTrue(sphere.center.z > 0.0)
        assertTrue(sphere.radius > 100.0)
    }

    @Test
    fun wgs84EcefUsesEllipsoidGeodeticLatitude() {
        val point = Vectorra3DTilesSpatial.wgs84DegreesToEcef(
            longitude = -75.61209430782448,
            latitude = 40.04253061142592,
            height = 503.75
        )

        assertEquals(1_215_107.7612304366, point.x, 0.001)
        assertEquals(-4_736_682.902037748, point.y, 0.001)
        assertEquals(4_081_926.095098698, point.z, 0.001)
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertEquals(expected, actual, 0.000001)
    }
}
