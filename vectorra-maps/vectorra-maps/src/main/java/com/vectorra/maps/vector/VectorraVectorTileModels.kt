package com.vectorra.maps.vector

import com.vectorra.maps.VectorraBetaApi
import com.vectorra.maps.network.TileScheme

@VectorraBetaApi("0.8.0-beta.1")
data class VectorraVectorTileSource(
    val id: String,
    val templateUrl: String,
    val tileSize: Int = DEFAULT_TILE_SIZE,
    val minZoom: Int = DEFAULT_MIN_ZOOM,
    val maxZoom: Int = DEFAULT_MAX_ZOOM,
    val scheme: TileScheme = TileScheme.XYZ,
    val headers: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "Vector tile source id must not be blank." }
        require(templateUrl.isNotBlank()) { "Vector tile source templateUrl must not be blank." }
        require(tileSize > 0) { "Vector tile source tileSize must be greater than 0." }
        require(minZoom >= 0) { "Vector tile source minZoom must be greater than or equal to 0." }
        require(maxZoom >= minZoom) { "Vector tile source maxZoom must be greater than or equal to minZoom." }
        require(scheme != TileScheme.WMTS) { "Vector tile source supports XYZ and TMS schemes only." }
        require(headers.keys.none { it.isBlank() }) { "Vector tile source header names must not be blank." }
    }

    companion object {
        const val DEFAULT_TILE_SIZE = 512
        const val DEFAULT_MIN_ZOOM = 0
        const val DEFAULT_MAX_ZOOM = 14

        fun xyz(
            id: String,
            templateUrl: String,
            tileSize: Int = DEFAULT_TILE_SIZE,
            minZoom: Int = DEFAULT_MIN_ZOOM,
            maxZoom: Int = DEFAULT_MAX_ZOOM,
            headers: Map<String, String> = emptyMap()
        ): VectorraVectorTileSource {
            return VectorraVectorTileSource(
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
        ): VectorraVectorTileSource {
            return VectorraVectorTileSource(
                id = id,
                templateUrl = templateUrl,
                tileSize = tileSize,
                minZoom = minZoom,
                maxZoom = maxZoom,
                scheme = TileScheme.TMS,
                headers = headers
            )
        }
    }
}

@VectorraBetaApi("0.8.0-beta.1")
sealed class VectorraVectorTileLayer {
    abstract val id: String
    abstract val sourceId: String
    abstract val sourceLayer: String
    abstract val visible: Boolean
    abstract val minZoom: Int
    abstract val maxZoom: Int

    data class Line(
        override val id: String,
        override val sourceId: String,
        override val sourceLayer: String,
        override val visible: Boolean = true,
        override val minZoom: Int = DEFAULT_MIN_ZOOM,
        override val maxZoom: Int = DEFAULT_MAX_ZOOM,
        val color: Int = DEFAULT_LINE_COLOR,
        val opacity: Double = 1.0,
        val widthPixels: Double = 1.0
    ) : VectorraVectorTileLayer() {
        init {
            requireLayerIdentity()
            requireUnitOpacity(opacity, "Vector tile line opacity")
            require(widthPixels.isFinite() && widthPixels >= 0.0) {
                "Vector tile line widthPixels must be finite and greater than or equal to 0."
            }
        }
    }

    data class Fill(
        override val id: String,
        override val sourceId: String,
        override val sourceLayer: String,
        override val visible: Boolean = true,
        override val minZoom: Int = DEFAULT_MIN_ZOOM,
        override val maxZoom: Int = DEFAULT_MAX_ZOOM,
        val color: Int = DEFAULT_FILL_COLOR,
        val opacity: Double = 1.0
    ) : VectorraVectorTileLayer() {
        init {
            requireLayerIdentity()
            requireUnitOpacity(opacity, "Vector tile fill opacity")
        }
    }

    data class Circle(
        override val id: String,
        override val sourceId: String,
        override val sourceLayer: String,
        override val visible: Boolean = true,
        override val minZoom: Int = DEFAULT_MIN_ZOOM,
        override val maxZoom: Int = DEFAULT_MAX_ZOOM,
        val color: Int = DEFAULT_CIRCLE_COLOR,
        val opacity: Double = 1.0,
        val radiusPixels: Double = 4.0
    ) : VectorraVectorTileLayer() {
        init {
            requireLayerIdentity()
            requireUnitOpacity(opacity, "Vector tile circle opacity")
            require(radiusPixels.isFinite() && radiusPixels >= 0.0) {
                "Vector tile circle radiusPixels must be finite and greater than or equal to 0."
            }
        }
    }

    data class Symbol(
        override val id: String,
        override val sourceId: String,
        override val sourceLayer: String,
        override val visible: Boolean = true,
        override val minZoom: Int = DEFAULT_MIN_ZOOM,
        override val maxZoom: Int = DEFAULT_MAX_ZOOM,
        val textField: String,
        val textColor: Int = DEFAULT_SYMBOL_TEXT_COLOR,
        val textOpacity: Double = 1.0,
        val textSizeSp: Double = 12.0
    ) : VectorraVectorTileLayer() {
        init {
            requireLayerIdentity()
            require(textField.isNotBlank()) { "Vector tile symbol textField must not be blank." }
            requireUnitOpacity(textOpacity, "Vector tile symbol textOpacity")
            require(textSizeSp.isFinite() && textSizeSp >= 0.0) {
                "Vector tile symbol textSizeSp must be finite and greater than or equal to 0."
            }
        }
    }

    companion object {
        const val DEFAULT_MIN_ZOOM = 0
        const val DEFAULT_MAX_ZOOM = 24
        const val DEFAULT_LINE_COLOR = -0x1000000
        const val DEFAULT_FILL_COLOR = 0x66000000
        const val DEFAULT_CIRCLE_COLOR = -0x1000000
        const val DEFAULT_SYMBOL_TEXT_COLOR = -0x1000000

        private fun requireUnitOpacity(value: Double, name: String) {
            require(value.isFinite() && value in 0.0..1.0) {
                "$name must be finite and within 0.0..1.0."
            }
        }
    }

    protected fun requireLayerIdentity() {
        require(id.isNotBlank()) { "Vector tile layer id must not be blank." }
        require(sourceId.isNotBlank()) { "Vector tile layer sourceId must not be blank." }
        require(sourceLayer.isNotBlank()) { "Vector tile layer sourceLayer must not be blank." }
        require(minZoom >= 0) { "Vector tile layer minZoom must be greater than or equal to 0." }
        require(maxZoom >= minZoom) { "Vector tile layer maxZoom must be greater than or equal to minZoom." }
    }
}
