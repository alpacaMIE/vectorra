package com.vectorra.maps.source

import com.vectorra.maps.layer.VectorraRasterLayer
import com.vectorra.maps.network.TileScheme

data class VectorraRasterTileSource(
    val id: String,
    val templateUrl: String,
    val tileSize: Int = DEFAULT_TILE_SIZE,
    val minZoom: Int = DEFAULT_MIN_ZOOM,
    val maxZoom: Int = DEFAULT_MAX_ZOOM,
    val scheme: TileScheme = TileScheme.XYZ,
    val matrixSet: String? = null,
    val headers: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "Raster tile source id must not be blank." }
        require(templateUrl.isNotBlank()) { "Raster tile source templateUrl must not be blank." }
        require(tileSize > 0) { "Raster tile source tileSize must be greater than 0." }
        require(minZoom >= 0) { "Raster tile source minZoom must be greater than or equal to 0." }
        require(maxZoom >= minZoom) { "Raster tile source maxZoom must be greater than or equal to minZoom." }
        if (scheme == TileScheme.WMTS) {
            require(!matrixSet.isNullOrBlank()) { "WMTS raster tile source requires matrixSet." }
        }
    }

    fun toRasterLayer(
        layerId: String,
        visible: Boolean = true,
        opacity: Double = 1.0,
        saturation: Double = 0.0,
        contrast: Double = 0.0,
        headers: Map<String, String> = emptyMap()
    ): VectorraRasterLayer {
        return VectorraRasterLayer(
            id = layerId,
            sourceId = id,
            templateUrl = templateUrl,
            minZoom = minZoom,
            maxZoom = maxZoom,
            visible = visible,
            opacity = opacity,
            saturation = saturation,
            contrast = contrast,
            headers = this.headers + headers,
            tileSize = tileSize,
            scheme = scheme,
            matrixSet = matrixSet
        )
    }

    companion object {
        const val DEFAULT_TILE_SIZE = 256
        const val DEFAULT_MIN_ZOOM = 0
        const val DEFAULT_MAX_ZOOM = 18

        fun xyz(
            id: String,
            templateUrl: String,
            tileSize: Int = DEFAULT_TILE_SIZE,
            minZoom: Int = DEFAULT_MIN_ZOOM,
            maxZoom: Int = DEFAULT_MAX_ZOOM,
            headers: Map<String, String> = emptyMap()
        ): VectorraRasterTileSource {
            return VectorraRasterTileSource(
                id = id,
                templateUrl = templateUrl,
                tileSize = tileSize,
                minZoom = minZoom,
                maxZoom = maxZoom,
                scheme = TileScheme.XYZ,
                headers = headers
            )
        }

        fun tms(
            id: String,
            templateUrl: String,
            tileSize: Int = DEFAULT_TILE_SIZE,
            minZoom: Int = DEFAULT_MIN_ZOOM,
            maxZoom: Int = DEFAULT_MAX_ZOOM,
            headers: Map<String, String> = emptyMap()
        ): VectorraRasterTileSource {
            return VectorraRasterTileSource(
                id = id,
                templateUrl = templateUrl,
                tileSize = tileSize,
                minZoom = minZoom,
                maxZoom = maxZoom,
                scheme = TileScheme.TMS,
                headers = headers
            )
        }

        fun wmts(
            id: String,
            serviceUrl: String,
            layer: String,
            matrixSet: String,
            tileSize: Int = DEFAULT_TILE_SIZE,
            minZoom: Int = DEFAULT_MIN_ZOOM,
            maxZoom: Int = DEFAULT_MAX_ZOOM,
            style: String = "default",
            format: String = "tiles",
            version: String = "1.0.0",
            matrixParam: String = "TileMatrix",
            rowParam: String = "TileRow",
            colParam: String = "TileCol",
            extraQuery: Map<String, String> = emptyMap(),
            headers: Map<String, String> = emptyMap()
        ): VectorraRasterTileSource {
            require(serviceUrl.isNotBlank()) { "WMTS serviceUrl must not be blank." }
            require(layer.isNotBlank()) { "WMTS layer must not be blank." }
            require(matrixSet.isNotBlank()) { "WMTS matrixSet must not be blank." }
            val template = appendQuery(
                serviceUrl,
                linkedMapOf(
                    "service" to "wmts",
                    "request" to "GetTile",
                    "version" to version,
                    "LAYER" to layer,
                    "STYLE" to style,
                    "tileMatrixSet" to matrixSet,
                    matrixParam to "{z}",
                    rowParam to "{y}",
                    colParam to "{x}",
                    "FORMAT" to format
                ) + extraQuery
            )
            return VectorraRasterTileSource(
                id = id,
                templateUrl = template,
                tileSize = tileSize,
                minZoom = minZoom,
                maxZoom = maxZoom,
                scheme = TileScheme.WMTS,
                matrixSet = matrixSet,
                headers = headers
            )
        }

        private fun appendQuery(baseUrl: String, query: Map<String, String>): String {
            val separator = when {
                baseUrl.endsWith("?") || baseUrl.endsWith("&") -> ""
                "?" in baseUrl -> "&"
                else -> "?"
            }
            return baseUrl + separator + query.entries.joinToString("&") { (key, value) -> "$key=$value" }
        }
    }
}
