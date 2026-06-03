package com.vectorra.maps.model

import com.vectorra.maps.VectorraBetaApi

@VectorraBetaApi("0.6.0-beta.1")
data class VectorraGlbModelSource(
    val id: String,
    val uri: String,
    val longitude: Double,
    val latitude: Double,
    val heightMeters: Double = 0.0,
    val scale: Double = 1.0,
    val yawDegrees: Double = 0.0
) {
    init {
        require(id.isNotBlank()) { "Model source id must not be blank." }
        require(uri.isNotBlank()) { "Model source uri must not be blank." }
        require(uri.hasSupportedModelExtension()) { "Model source uri must end with .glb or .gltf." }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Model source longitude must be finite and within -180.0..180.0."
        }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Model source latitude must be finite and within -90.0..90.0."
        }
        require(heightMeters.isFinite()) { "Model source heightMeters must be finite." }
        require(scale.isFinite() && scale > 0.0) { "Model source scale must be finite and greater than 0.0." }
        require(yawDegrees.isFinite()) { "Model source yawDegrees must be finite." }
    }

    private fun String.hasSupportedModelExtension(): Boolean {
        val path = substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".glb") || path.endsWith(".gltf")
    }
}
