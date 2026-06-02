package com.vectorra.maps.mvt

import com.vectorra.maps.VectorraBetaApi

@VectorraBetaApi("0.3.0-beta.1")
data class VectorraMvtTile(
    val layers: List<VectorraMvtLayer>
)

@VectorraBetaApi("0.3.0-beta.1")
data class VectorraMvtLayer(
    val name: String,
    val version: Int,
    val extent: Int,
    val features: List<VectorraMvtFeature>
)

@VectorraBetaApi("0.3.0-beta.1")
data class VectorraMvtFeature(
    val id: Long?,
    val layerName: String,
    val geometry: VectorraMvtGeometry,
    val properties: Map<String, VectorraMvtValue>
)

@VectorraBetaApi("0.3.0-beta.1")
sealed class VectorraMvtGeometry {
    data class Point(val points: List<VectorraMvtPoint>) : VectorraMvtGeometry()
    data class LineString(val lines: List<List<VectorraMvtPoint>>) : VectorraMvtGeometry()
    data class Polygon(val rings: List<List<VectorraMvtPoint>>) : VectorraMvtGeometry()
}

@VectorraBetaApi("0.3.0-beta.1")
data class VectorraMvtPoint(
    val x: Int,
    val y: Int
)

@VectorraBetaApi("0.3.0-beta.1")
data class VectorraMvtTileId(
    val z: Int,
    val x: Int,
    val y: Int
) {
    init {
        require(z in 0..30) { "MVT tile zoom must be in 0..30." }
        require(x >= 0) { "MVT tile x must be greater than or equal to 0." }
        require(y >= 0) { "MVT tile y must be greater than or equal to 0." }
    }
}

@VectorraBetaApi("0.3.0-beta.1")
sealed class VectorraMvtValue {
    data class StringValue(val value: String) : VectorraMvtValue()
    data class FloatValue(val value: Float) : VectorraMvtValue()
    data class DoubleValue(val value: Double) : VectorraMvtValue()
    data class IntValue(val value: Long) : VectorraMvtValue()
    data class UIntValue(val value: Long) : VectorraMvtValue()
    data class SIntValue(val value: Long) : VectorraMvtValue()
    data class BoolValue(val value: Boolean) : VectorraMvtValue()

    fun asString(): String {
        return when (this) {
            is StringValue -> value
            is FloatValue -> value.toString()
            is DoubleValue -> value.toString()
            is IntValue -> value.toString()
            is UIntValue -> value.toString()
            is SIntValue -> value.toString()
            is BoolValue -> value.toString()
        }
    }
}

@VectorraBetaApi("0.3.0-beta.1")
data class VectorraMvtDecodedFeature(
    val id: String,
    val layerName: String,
    val geometry: com.vectorra.maps.query.VectorraAnnotationGeometry,
    val properties: Map<String, String>
)
