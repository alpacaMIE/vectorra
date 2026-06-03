package com.vectorra.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorraNativeResourceStatusMapperTest {
    @Test
    fun nativeRasterCallbackPreservesMbTilesIdentity() {
        val update = VectorraNativeResourceStatusMapper.map(
            previous = status(VectorraResourceKind.MBTILES, sourceId = "offline-source", layerId = "offline-layer"),
            rawKind = "RASTER",
            rawState = "LOADED",
            rawErrorType = null,
            rawErrorMessage = null
        )

        requireNotNull(update)
        assertEquals(VectorraResourceKind.MBTILES, update.kind)
        assertEquals("offline-source", update.sourceId)
        assertEquals("offline-layer", update.layerId)
        assertEquals(VectorraResourceLoadState.LOADED, update.state)
    }

    @Test
    fun failedNativeCallbackRequiresErrorMessage() {
        val update = VectorraNativeResourceStatusMapper.map(
            previous = status(VectorraResourceKind.MODEL, sourceId = "model", layerId = "model"),
            rawKind = "MODEL",
            rawState = "FAILED",
            rawErrorType = "NATIVE_RENDERER",
            rawErrorMessage = ""
        )

        assertNull(update)
    }

    @Test
    fun nativeCallbackAfterRemovedStatusIsIgnored() {
        val update = VectorraNativeResourceStatusMapper.map(
            previous = status(
                kind = VectorraResourceKind.RASTER,
                sourceId = "source",
                layerId = "layer",
                state = VectorraResourceLoadState.REMOVED
            ),
            rawKind = "RASTER",
            rawState = "LOADED",
            rawErrorType = null,
            rawErrorMessage = null
        )

        assertNull(update)
    }

    @Test
    fun mismatchedNativeKindIsIgnored() {
        val update = VectorraNativeResourceStatusMapper.map(
            previous = status(VectorraResourceKind.DEM, sourceId = "terrain", layerId = "terrain"),
            rawKind = "MODEL",
            rawState = "LOADED",
            rawErrorType = null,
            rawErrorMessage = null
        )

        assertNull(update)
    }

    private fun status(
        kind: VectorraResourceKind,
        sourceId: String,
        layerId: String,
        state: VectorraResourceLoadState = VectorraResourceLoadState.LOADING
    ): VectorraResourceStatus {
        return VectorraResourceStatus(
            kind = kind,
            sourceId = sourceId,
            layerId = layerId,
            generation = 1L,
            state = state,
            eventSource = VectorraResourceEventSource.ENGINE
        )
    }
}
