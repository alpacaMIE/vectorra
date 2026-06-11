package com.vectorra.maps.mvt

import com.vectorra.maps.internal.VectorraNative
import com.vectorra.maps.query.VectorraAnnotationGeometry
import com.vectorra.maps.query.VectorraCoordinate
import com.vectorra.maps.vector.VectorraVectorTileLayer

internal enum class VectorraMvtRenderGeometryType {
    POINT,
    LINE,
    POLYGON
}

internal enum class VectorraMvtRenderLayerKind {
    LINE,
    FILL,
    CIRCLE,
    SYMBOL
}

internal data class VectorraMvtRenderStyle(
    val kind: VectorraMvtRenderLayerKind,
    val visible: Boolean = true,
    val color: Int = 0xffffffff.toInt(),
    val opacity: Float = 1.0f,
    val widthPixels: Float = 1.0f,
    val radiusPixels: Float = 4.0f,
    val textSizeSp: Float = 12.0f
) {
    init {
        require(opacity.isFinite() && opacity in 0.0f..1.0f) {
            "MVT render style opacity must be finite and in 0..1."
        }
        require(widthPixels.isFinite() && widthPixels >= 0.0f) {
            "MVT render style widthPixels must be finite and non-negative."
        }
        require(radiusPixels.isFinite() && radiusPixels >= 0.0f) {
            "MVT render style radiusPixels must be finite and non-negative."
        }
        require(textSizeSp.isFinite() && textSizeSp >= 0.0f) {
            "MVT render style textSizeSp must be finite and non-negative."
        }
    }
}

internal fun VectorraVectorTileLayer.toMvtRenderStyle(): VectorraMvtRenderStyle {
    return when (this) {
        is VectorraVectorTileLayer.Line -> VectorraMvtRenderStyle(
            kind = VectorraMvtRenderLayerKind.LINE,
            visible = visible,
            color = color,
            opacity = opacity.toFloat(),
            widthPixels = widthPixels.toFloat()
        )
        is VectorraVectorTileLayer.Fill -> VectorraMvtRenderStyle(
            kind = VectorraMvtRenderLayerKind.FILL,
            visible = visible,
            color = color,
            opacity = opacity.toFloat()
        )
        is VectorraVectorTileLayer.Circle -> VectorraMvtRenderStyle(
            kind = VectorraMvtRenderLayerKind.CIRCLE,
            visible = visible,
            color = color,
            opacity = opacity.toFloat(),
            radiusPixels = radiusPixels.toFloat()
        )
        is VectorraVectorTileLayer.Symbol -> VectorraMvtRenderStyle(
            kind = VectorraMvtRenderLayerKind.SYMBOL,
            visible = visible,
            color = textColor,
            opacity = textOpacity.toFloat(),
            textSizeSp = textSizeSp.toFloat()
        )
    }
}

internal fun VectorraVectorTileLayer.hitRadiusPixels(): Double {
    return when (this) {
        is VectorraVectorTileLayer.Circle -> radiusPixels
        is VectorraVectorTileLayer.Line -> maxOf(16.0, widthPixels)
        is VectorraVectorTileLayer.Fill -> 16.0
        is VectorraVectorTileLayer.Symbol -> 16.0
    }
}

internal fun VectorraVectorTileLayer.layerOpacity(): Double {
    return when (this) {
        is VectorraVectorTileLayer.Circle -> opacity
        is VectorraVectorTileLayer.Line -> opacity
        is VectorraVectorTileLayer.Fill -> opacity
        is VectorraVectorTileLayer.Symbol -> textOpacity
    }
}

internal fun VectorraNative.MvtTileResult.toDecodedFeatures(): List<VectorraMvtDecodedFeature> {
    val featureCount = featureIds.size
    require(sourceLayers.size == featureCount) { "MVT native query source layer count mismatch." }
    require(geometryTypes.size == featureCount) { "MVT native query geometry type count mismatch." }
    require(coordinateOffsets.size == featureCount + 1) { "MVT native query coordinate offsets mismatch." }
    require(ringOffsets.size == featureCount + 1) { "MVT native query ring offsets mismatch." }
    require(propertyOffsets.size == featureCount + 1) { "MVT native query property offsets mismatch." }

    return List(featureCount) { index ->
        VectorraMvtDecodedFeature(
            id = featureIds[index],
            layerName = sourceLayers[index],
            geometry = geometryAt(index),
            properties = propertiesAt(index)
        )
    }
}

private fun VectorraNative.MvtTileResult.geometryAt(index: Int): VectorraAnnotationGeometry {
    val coordinateStart = coordinateOffsets[index]
    val coordinateEnd = coordinateOffsets[index + 1]
    require(coordinateStart >= 0 && coordinateEnd >= coordinateStart && coordinateEnd <= coordinates.size) {
        "MVT native query coordinate range is invalid."
    }
    require((coordinateEnd - coordinateStart) % 2 == 0) {
        "MVT native query coordinates must be longitude/latitude pairs."
    }
    val coordinatePairs = coordinates
        .asList()
        .subList(coordinateStart, coordinateEnd)
        .chunked(2)
        .map { (longitude, latitude) -> VectorraCoordinate(longitude, latitude) }
    return when (geometryTypes[index]) {
        VectorraMvtRenderGeometryType.POINT.ordinal -> {
            require(coordinatePairs.isNotEmpty()) { "MVT native point query geometry is empty." }
            VectorraAnnotationGeometry.Point(coordinatePairs.first())
        }
        VectorraMvtRenderGeometryType.LINE.ordinal -> {
            VectorraAnnotationGeometry.LineString(coordinatePairs)
        }
        VectorraMvtRenderGeometryType.POLYGON.ordinal -> {
            val ringStart = ringOffsets[index]
            val ringEnd = ringOffsets[index + 1]
            require(ringStart >= 0 && ringEnd >= ringStart && ringEnd <= ringEnds.size) {
                "MVT native query ring range is invalid."
            }
            var previous = 0
            val rings = ringEnds
                .asList()
                .subList(ringStart, ringEnd)
                .map { next ->
                    require(next > previous && next <= coordinatePairs.size) {
                        "MVT native query ring end is invalid."
                    }
                    coordinatePairs.subList(previous, next).also {
                        previous = next
                    }
                }
            VectorraAnnotationGeometry.Polygon(rings)
        }
        else -> error("Unsupported MVT native query geometry type: ${geometryTypes[index]}")
    }
}

private fun VectorraNative.MvtTileResult.propertiesAt(index: Int): Map<String, String> {
    val propertyStart = propertyOffsets[index]
    val propertyEnd = propertyOffsets[index + 1]
    require(propertyStart >= 0 && propertyEnd >= propertyStart && propertyEnd <= propertyKeys.size) {
        "MVT native query property range is invalid."
    }
    require(propertyValues.size == propertyKeys.size) { "MVT native query property value count mismatch." }
    return (propertyStart until propertyEnd).associate { propertyIndex ->
        propertyKeys[propertyIndex] to propertyValues[propertyIndex]
    }
}
