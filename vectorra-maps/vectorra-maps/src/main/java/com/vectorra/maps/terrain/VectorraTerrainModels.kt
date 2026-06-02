package com.vectorra.maps.terrain

import com.vectorra.maps.VectorraBetaApi

@VectorraBetaApi("0.4.0-beta.1")
enum class VectorraDemEncoding {
    TERRARIUM
}

@VectorraBetaApi("0.4.0-beta.1")
data class VectorraTerrainSource(
    val id: String,
    val templateUrl: String,
    val minZoom: Int = DEFAULT_MIN_ZOOM,
    val maxZoom: Int = DEFAULT_MAX_ZOOM,
    val encoding: VectorraDemEncoding = VectorraDemEncoding.TERRARIUM,
    val headers: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "Terrain source id must not be blank." }
        require(templateUrl.isNotBlank()) { "Terrain source templateUrl must not be blank." }
        require(minZoom >= 0) { "Terrain source minZoom must be greater than or equal to 0." }
        require(maxZoom >= minZoom) { "Terrain source maxZoom must be greater than or equal to minZoom." }
    }

    companion object {
        const val DEFAULT_MIN_ZOOM = 0
        const val DEFAULT_MAX_ZOOM = 14
    }
}

@VectorraBetaApi("0.4.0-beta.1")
data class VectorraTerrainOptions(
    val exaggeration: Double = DEFAULT_EXAGGERATION,
    val visible: Boolean = true
) {
    init {
        require(exaggeration.isFinite()) { "Terrain exaggeration must be finite." }
    }

    val clampedExaggeration: Double
        get() = exaggeration.coerceIn(MIN_EXAGGERATION, MAX_EXAGGERATION)

    companion object {
        const val DEFAULT_EXAGGERATION = 1.0
        const val MIN_EXAGGERATION = 0.0
        const val MAX_EXAGGERATION = 10.0
    }
}
