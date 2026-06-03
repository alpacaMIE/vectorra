package com.vectorra.maps.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VectorraGlbModelModelsTest {
    @Test
    fun createsModelSourceWithDefaults() {
        val source = VectorraGlbModelSource(
            id = "building",
            uri = "file:///models/building.glb",
            longitude = 104.293174,
            latitude = 32.2857965
        )

        assertEquals("building", source.id)
        assertEquals("file:///models/building.glb", source.uri)
        assertEquals(0.0, source.heightMeters, 0.0)
        assertEquals(1.0, source.scale, 0.0)
        assertEquals(0.0, source.yawDegrees, 0.0)
    }

    @Test
    fun acceptsGltfUriWithQueryString() {
        val source = VectorraGlbModelSource(
            id = "building",
            uri = "https://example.com/model.gltf?token=test",
            longitude = 0.0,
            latitude = 0.0
        )

        assertEquals("https://example.com/model.gltf?token=test", source.uri)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankModelSourceId() {
        VectorraGlbModelSource(id = "", uri = "file:///model.glb", longitude = 0.0, latitude = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedModelExtension() {
        VectorraGlbModelSource(id = "model", uri = "file:///model.b3dm", longitude = 0.0, latitude = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidModelCoordinate() {
        VectorraGlbModelSource(id = "model", uri = "file:///model.glb", longitude = 181.0, latitude = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidModelScale() {
        VectorraGlbModelSource(id = "model", uri = "file:///model.glb", longitude = 0.0, latitude = 0.0, scale = 0.0)
    }

    @Test
    fun clampsOrientationScaleForNativeRenderer() {
        assertEquals(0.001, VectorraGlbModelLayerOptions(orientationScale = 0.0001).effectiveScaleMultiplier, 0.0)
        assertEquals(2.0, VectorraGlbModelLayerOptions(orientationScale = 2.0).effectiveScaleMultiplier, 0.0)
        assertEquals(1000.0, VectorraGlbModelLayerOptions(orientationScale = 2000.0).effectiveScaleMultiplier, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeModelLoadPriority() {
        VectorraGlbModelLayerOptions(loadPriority = -1)
    }
}
