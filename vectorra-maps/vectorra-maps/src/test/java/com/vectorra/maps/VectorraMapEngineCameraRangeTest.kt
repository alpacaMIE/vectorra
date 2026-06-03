package com.vectorra.maps

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.tan

class VectorraMapEngineCameraRangeTest {
    @Test
    fun tiles3dTraversalCameraRangeMatchesNativeFormula() {
        val zoom = 16.0
        val latitude = 39.853105846881554
        val metersPerPixel = EARTH_CIRCUMFERENCE_METERS * cos(Math.toRadians(latitude)) /
            (WEB_MERCATOR_TILE_SIZE * 2.0.pow(zoom))
        val expected = metersPerPixel * DEFAULT_VIEWPORT_HEIGHT_PIXELS /
            (2.0 * tan(Math.toRadians(VECTORRA_CAMERA_FOVY_DEGREES) * 0.5))

        assertEquals(
            expected,
            vectorraNativeCameraRangeMetersForZoom(
                zoom = zoom,
                latitude = latitude,
                viewportHeightPixels = DEFAULT_VIEWPORT_HEIGHT_PIXELS
            ),
            1e-9
        )
    }

    @Test
    fun tiles3dTraversalCameraRangeKeepsNativeMinimumAtHighZoom() {
        assertEquals(
            100.0,
            vectorraNativeCameraRangeMetersForZoom(
                zoom = 22.0,
                latitude = 39.853105846881554,
                viewportHeightPixels = DEFAULT_VIEWPORT_HEIGHT_PIXELS
            ),
            0.0
        )
    }

    private companion object {
        const val DEFAULT_VIEWPORT_HEIGHT_PIXELS = 1080
        const val VECTORRA_CAMERA_FOVY_DEGREES = 45.0
        const val WEB_MERCATOR_TILE_SIZE = 512.0
        const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.68557849
    }
}
