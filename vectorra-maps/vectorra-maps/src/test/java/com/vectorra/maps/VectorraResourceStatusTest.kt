package com.vectorra.maps

import org.junit.Assert.assertEquals
import org.junit.Test

class VectorraResourceStatusTest {
    @Test
    fun createsLoadingStatusPayload() {
        val status = VectorraResourceStatus(
            kind = VectorraResourceKind.VECTOR,
            sourceId = "imagery-source",
            layerId = "imagery-layer",
            generation = 1L,
            state = VectorraResourceLoadState.LOADING,
            eventSource = VectorraResourceEventSource.ENGINE
        )

        assertEquals(VectorraResourceKind.VECTOR, status.kind)
        assertEquals("imagery-source", status.sourceId)
        assertEquals("imagery-layer", status.layerId)
        assertEquals(1L, status.generation)
        assertEquals(VectorraResourceLoadState.LOADING, status.state)
        assertEquals(VectorraResourceEventSource.ENGINE, status.eventSource)
    }

    @Test
    fun createsFailedStatusWithRequiredError() {
        val status = VectorraResourceStatus(
            kind = VectorraResourceKind.MODEL,
            sourceId = "model",
            layerId = "model",
            generation = 2L,
            state = VectorraResourceLoadState.FAILED,
            eventSource = VectorraResourceEventSource.NATIVE,
            error = VectorraResourceLoadError(
                type = VectorraResourceErrorType.RESOURCE,
                message = "Model file is missing."
            )
        )

        assertEquals(VectorraResourceLoadState.FAILED, status.state)
        assertEquals(VectorraResourceErrorType.RESOURCE, status.error?.type)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFailedStatusWithoutError() {
        VectorraResourceStatus(
            kind = VectorraResourceKind.DEM,
            sourceId = "dem",
            layerId = "dem",
            generation = 1L,
            state = VectorraResourceLoadState.FAILED,
            eventSource = VectorraResourceEventSource.NATIVE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsErrorOnNonFailedStatus() {
        VectorraResourceStatus(
            kind = VectorraResourceKind.MBTILES,
            sourceId = "offline",
            layerId = "offline",
            generation = 1L,
            state = VectorraResourceLoadState.LOADING,
            eventSource = VectorraResourceEventSource.LOCAL_PROVIDER,
            error = VectorraResourceLoadError(
                type = VectorraResourceErrorType.CACHE,
                message = "Cache miss."
            )
        )
    }
}
