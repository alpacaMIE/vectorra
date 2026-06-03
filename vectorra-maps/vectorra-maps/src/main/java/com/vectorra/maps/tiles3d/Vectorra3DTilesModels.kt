package com.vectorra.maps.tiles3d

import com.vectorra.maps.VectorraBetaApi

@VectorraBetaApi("0.8.0-beta.1")
data class Vectorra3DTilesSource(
    val id: String,
    val tilesetUri: String,
    val headers: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "3D Tiles source id must not be blank." }
        require(tilesetUri.isNotBlank()) { "3D Tiles source tilesetUri must not be blank." }
        require(headers.keys.none { it.isBlank() }) { "3D Tiles source header names must not be blank." }
    }
}

@VectorraBetaApi("0.8.0-beta.1")
data class Vectorra3DTilesLayer(
    val id: String,
    val sourceId: String,
    val options: Vectorra3DTilesOptions = Vectorra3DTilesOptions()
) {
    init {
        require(id.isNotBlank()) { "3D Tiles layer id must not be blank." }
        require(sourceId.isNotBlank()) { "3D Tiles layer sourceId must not be blank." }
    }
}

@VectorraBetaApi("0.8.0-beta.1")
data class Vectorra3DTilesOptions(
    val visible: Boolean = true,
    val maximumScreenSpaceError: Double = DEFAULT_MAXIMUM_SCREEN_SPACE_ERROR,
    val maximumLoadedTiles: Int = DEFAULT_MAXIMUM_LOADED_TILES
) {
    init {
        require(maximumScreenSpaceError.isFinite() && maximumScreenSpaceError > 0.0) {
            "3D Tiles maximumScreenSpaceError must be finite and greater than 0."
        }
        require(maximumLoadedTiles > 0) { "3D Tiles maximumLoadedTiles must be greater than 0." }
    }

    companion object {
        const val DEFAULT_MAXIMUM_SCREEN_SPACE_ERROR = 16.0
        const val DEFAULT_MAXIMUM_LOADED_TILES = 256
    }
}
