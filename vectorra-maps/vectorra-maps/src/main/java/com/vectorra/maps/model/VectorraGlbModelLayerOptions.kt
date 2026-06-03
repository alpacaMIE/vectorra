package com.vectorra.maps.model

import com.vectorra.maps.VectorraBetaApi

@VectorraBetaApi("0.6.0-beta.1")
data class VectorraGlbModelLayerOptions(
    val visible: Boolean = true,
    val orientationScale: Double = DEFAULT_ORIENTATION_SCALE,
    val loadPriority: Int = DEFAULT_LOAD_PRIORITY
) {
    init {
        require(orientationScale.isFinite() && orientationScale > 0.0) {
            "Model layer orientationScale must be finite and greater than 0.0."
        }
        require(loadPriority >= 0) { "Model layer loadPriority must be greater than or equal to 0." }
    }

    val effectiveScaleMultiplier: Double
        get() = orientationScale.coerceIn(MIN_ORIENTATION_SCALE, MAX_ORIENTATION_SCALE)

    companion object {
        const val DEFAULT_ORIENTATION_SCALE = 1.0
        const val MIN_ORIENTATION_SCALE = 0.001
        const val MAX_ORIENTATION_SCALE = 1000.0
        const val DEFAULT_LOAD_PRIORITY = 0
    }
}
