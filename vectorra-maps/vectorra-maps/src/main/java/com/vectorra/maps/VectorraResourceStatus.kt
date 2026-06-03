package com.vectorra.maps

@VectorraBetaApi("0.7.0-beta.1")
enum class VectorraResourceKind {
    RASTER,
    DEM,
    MODEL,
    MBTILES
}

@VectorraBetaApi("0.7.0-beta.1")
enum class VectorraResourceLoadState {
    LOADING,
    LOADED,
    FAILED,
    REMOVED
}

@VectorraBetaApi("0.7.0-beta.1")
enum class VectorraResourceEventSource {
    ENGINE,
    NATIVE,
    TILE_PROXY,
    LOCAL_PROVIDER
}

@VectorraBetaApi("0.7.0-beta.1")
enum class VectorraResourceErrorType {
    NETWORK,
    NATIVE_RENDERER,
    RESOURCE,
    UNSUPPORTED,
    CACHE,
    UNKNOWN
}

@VectorraBetaApi("0.7.0-beta.1")
data class VectorraResourceLoadError(
    val type: VectorraResourceErrorType,
    val message: String,
    val cause: Throwable? = null
) {
    init {
        require(message.isNotBlank()) { "Resource load error message must not be blank." }
    }
}

@VectorraBetaApi("0.7.0-beta.1")
data class VectorraResourceStatus(
    val kind: VectorraResourceKind,
    val sourceId: String,
    val layerId: String,
    val generation: Long,
    val state: VectorraResourceLoadState,
    val eventSource: VectorraResourceEventSource,
    val error: VectorraResourceLoadError? = null
) {
    init {
        require(sourceId.isNotBlank()) { "Resource status sourceId must not be blank." }
        require(layerId.isNotBlank()) { "Resource status layerId must not be blank." }
        require(generation > 0L) { "Resource status generation must be greater than 0." }
        require(state == VectorraResourceLoadState.FAILED || error == null) {
            "Resource status error is only valid for failed state."
        }
        require(state != VectorraResourceLoadState.FAILED || error != null) {
            "Failed resource status requires an error."
        }
    }
}
