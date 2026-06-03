package com.vectorra.maps

import org.junit.Assert.assertEquals
import org.junit.Test
import com.vectorra.maps.tiles3d.Vectorra3DTilesSpatial
import com.vectorra.maps.tiles3d.VectorraTileset3D
import com.vectorra.maps.tiles3d.VectorraTileset3DAsset
import com.vectorra.maps.tiles3d.VectorraTileset3DBoundingVolume
import com.vectorra.maps.tiles3d.VectorraTileset3DTile
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

    @Test
    fun tiles3dCameraTargetHeightComesFromRootBoundingVolume() {
        val center = Vectorra3DTilesSpatial.wgs84DegreesToEcef(
            longitude = -75.61209430782448,
            latitude = 40.04253061142592,
            height = 503.75
        )
        val tileset = VectorraTileset3D(
            asset = VectorraTileset3DAsset(version = "1.0"),
            geometricError = 0.0,
            root = VectorraTileset3DTile(
                boundingVolume = VectorraTileset3DBoundingVolume.Sphere(
                    centerX = center.x,
                    centerY = center.y,
                    centerZ = center.z,
                    radius = 16.0
                ),
                geometricError = 0.0
            )
        )

        assertEquals(503.75, tileset.cameraTargetHeightMeters(), 0.001)
    }

    @Test
    fun tiles3dCameraTargetHeightAppliesRootTransform() {
        val tileset = VectorraTileset3D(
            asset = VectorraTileset3DAsset(version = "1.0"),
            geometricError = 500.0,
            root = VectorraTileset3DTile(
                boundingVolume = VectorraTileset3DBoundingVolume.Box(
                    listOf(
                        0.0, 0.0, 0.0,
                        7.0955, 0.0, 0.0,
                        0.0, 3.1405, 0.0,
                        0.0, 0.0, 5.0375
                    )
                ),
                geometricError = 1.0,
                transform = listOf(
                    96.86356343768793, 24.848542777253734, 0.0, 0.0,
                    -15.986465724980844, 62.317780594908875, 76.5566922962899, 0.0,
                    19.02322243409411, -74.15554020821229, 64.3356267137516, 0.0,
                    1215107.7612304366, -4736682.902037748, 4081926.095098698, 1.0
                )
            )
        )

        assertEquals(503.75, tileset.cameraTargetHeightMeters(), 0.001)
    }

    private companion object {
        const val DEFAULT_VIEWPORT_HEIGHT_PIXELS = 1080
        const val VECTORRA_CAMERA_FOVY_DEGREES = 45.0
        const val WEB_MERCATOR_TILE_SIZE = 512.0
        const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.68557849
    }
}
