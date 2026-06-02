package com.vectorra.maps.layer

import com.vectorra.maps.network.TileScheme

data class VectorraRasterLayer(
    val id: String,
    val sourceId: String = id,
    val templateUrl: String,
    val minZoom: Int = 0,
    val maxZoom: Int = 18,
    val visible: Boolean = true,
    val opacity: Double = 1.0,
    val saturation: Double = 0.0,
    val contrast: Double = 0.0,
    val headers: Map<String, String> = emptyMap(),
    val tileSize: Int = 256,
    val scheme: TileScheme = TileScheme.XYZ,
    val matrixSet: String? = null
)
