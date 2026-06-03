package com.vectorra.maps.model

import com.vectorra.maps.VectorraBetaApi

@VectorraBetaApi("0.6.0-beta.1")
sealed class VectorraModelRenderError(
    open val layerId: String,
    open val uri: String,
    open val message: String,
    open val cause: Throwable? = null
) {
    data class UnsupportedFormat(
        override val layerId: String,
        override val uri: String,
        override val message: String = "Model format is not supported.",
        override val cause: Throwable? = null
    ) : VectorraModelRenderError(layerId, uri, message, cause)

    data class LoadFailure(
        override val layerId: String,
        override val uri: String,
        override val message: String,
        override val cause: Throwable? = null
    ) : VectorraModelRenderError(layerId, uri, message, cause)
}
