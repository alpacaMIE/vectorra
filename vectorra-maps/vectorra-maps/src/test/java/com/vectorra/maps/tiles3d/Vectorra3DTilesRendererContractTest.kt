package com.vectorra.maps.tiles3d

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Vectorra3DTilesRendererContractTest {
    @Test
    fun matrixTransformPreservesFullNativeMatrix() {
        val matrix = (0 until 16).map(Int::toDouble)
        val transform = Vectorra3DTilesRendererTransform.Matrix(matrix)

        assertEquals(Vectorra3DTilesRendererTransformKind.MATRIX, transform.kind)
        assertArrayEquals(matrix.toDoubleArray(), transform.nativeMatrix(), 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), transform.nativeEcefOrigin(), 0.0)
    }

    @Test
    fun ecefTransformPreservesOriginAndLocalMatrix() {
        val local = Vectorra3DTilesRendererTransform.IDENTITY_MATRIX
        val transform = Vectorra3DTilesRendererTransform.Ecef(
            x = 1.0,
            y = 2.0,
            z = 3.0,
            localTransform = local
        )

        assertEquals(Vectorra3DTilesRendererTransformKind.ECEF, transform.kind)
        assertArrayEquals(local.toDoubleArray(), transform.nativeMatrix(), 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), transform.nativeEcefOrigin(), 0.0)
    }

    @Test
    fun b3dmContentRequiresCacheUriForInnerGlbPayload() {
        val input = Vectorra3DTilesRendererContentInput(
            layerId = "buildings",
            tileId = "root",
            contentUri = "root.b3dm",
            contentKind = VectorraTileset3DContentKind.B3DM,
            renderUri = "file:///cache/tiles/root-inner.glb",
            payloadSource = Vectorra3DTilesRendererPayloadSource.B3DM_INNER_GLB_CACHE_URI,
            transform = Vectorra3DTilesRendererTransform.Matrix(
                Vectorra3DTilesRendererTransform.IDENTITY_MATRIX
            )
        )

        assertEquals("buildings:root", input.nativeContentId)
        assertEquals("MATRIX", input.nativeTransformKind())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsB3dmContentWithoutCachePayloadSource() {
        Vectorra3DTilesRendererContentInput(
            layerId = "buildings",
            tileId = "root",
            contentUri = "root.b3dm",
            contentKind = VectorraTileset3DContentKind.B3DM,
            renderUri = "https://example.com/root.b3dm",
            payloadSource = Vectorra3DTilesRendererPayloadSource.GLB_URI,
            transform = Vectorra3DTilesRendererTransform.Matrix(
                Vectorra3DTilesRendererTransform.IDENTITY_MATRIX
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownContentKind() {
        Vectorra3DTilesRendererContentInput.expectedPayloadSource(
            VectorraTileset3DContentKind.UNKNOWN
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidMatrixSize() {
        Vectorra3DTilesRendererTransform.Matrix(listOf(1.0, 2.0))
    }
}
