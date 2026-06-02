package com.vectorra.maps.annotation

import com.vectorra.maps.query.VectorraAnnotationFeature
import com.vectorra.maps.query.VectorraAnnotationGeometry
import com.vectorra.maps.query.VectorraCoordinate

data class VectorraDrawPointAnnotation(
    val id: String,
    val coordinate: VectorraCoordinate,
    val text: String = "",
    val textSizeSp: Float = 12.0f,
    val textColor: Int = WHITE,
    val textHaloColor: Int = BLACK,
    val textHaloWidthPx: Float = 2.0f,
    val iconColor: Int = DEFAULT_POINT_COLOR,
    val iconRadiusPx: Float = 7.0f,
    val layerId: String = DRAW_POINT_LAYER_ID,
    val properties: Map<String, String> = emptyMap(),
    val hitRadiusPixels: Double = 24.0,
    val zIndex: Int = 300,
    val visible: Boolean = true,
    val opacity: Double = 1.0,
    val minZoom: Double = 0.0,
    val maxZoom: Double = 24.0
)

data class VectorraDrawLineAnnotation(
    val id: String,
    val coordinates: List<VectorraCoordinate>,
    val lineColor: Int = DEFAULT_LINE_COLOR,
    val lineWidthPixels: Float = 3.0f,
    val layerId: String = DRAW_LINE_LAYER_ID,
    val properties: Map<String, String> = emptyMap(),
    val hitRadiusPixels: Double = 18.0,
    val zIndex: Int = 220,
    val visible: Boolean = true,
    val opacity: Double = 1.0,
    val minZoom: Double = 0.0,
    val maxZoom: Double = 24.0
)

data class VectorraDrawPolygonAnnotation(
    val id: String,
    val rings: List<List<VectorraCoordinate>>,
    val fillColor: Int = DEFAULT_POLYGON_FILL_COLOR,
    val fillOpacity: Double = 0.35,
    val outlineColor: Int = DEFAULT_LINE_COLOR,
    val outlineWidthPixels: Float = 2.0f,
    val layerId: String = DRAW_POLYGON_LAYER_ID,
    val outlineLayerId: String = DRAW_POLYGON_OUTLINE_LAYER_ID,
    val properties: Map<String, String> = emptyMap(),
    val hitRadiusPixels: Double = 8.0,
    val outlineHitRadiusPixels: Double = 18.0,
    val zIndex: Int = 200,
    val outlineZIndex: Int = 240,
    val visible: Boolean = true,
    val opacity: Double = 1.0,
    val minZoom: Double = 0.0,
    val maxZoom: Double = 24.0
)

const val DRAW_POINT_LAYER_ID = "draw-point"
const val DRAW_LINE_LAYER_ID = "draw-polyline"
const val DRAW_POLYGON_LAYER_ID = "draw-polygon"
const val DRAW_POLYGON_OUTLINE_LAYER_ID = "draw-polygon-outline"

private const val WHITE: Int = -0x1
private const val BLACK: Int = -0x1000000
private const val DEFAULT_POINT_COLOR: Int = -0x10000
private const val DEFAULT_LINE_COLOR: Int = -0xff0100
private const val DEFAULT_POLYGON_FILL_COLOR: Int = 0x5533aaff

internal fun VectorraDrawPointAnnotation.toHitFeature(): VectorraAnnotationFeature {
    return VectorraAnnotationFeature(
        id = hitId,
        layerId = layerId,
        geometry = VectorraAnnotationGeometry.Point(coordinate),
        properties = properties + mapOf("id" to id),
        radiusPixels = hitRadiusPixels,
        zIndex = zIndex,
        visible = visible,
        opacity = opacity,
        minZoom = minZoom,
        maxZoom = maxZoom
    )
}

internal fun VectorraDrawLineAnnotation.toHitFeature(): VectorraAnnotationFeature {
    return VectorraAnnotationFeature(
        id = hitId,
        layerId = layerId,
        geometry = VectorraAnnotationGeometry.LineString(coordinates),
        properties = properties + mapOf("id" to id),
        radiusPixels = hitRadiusPixels,
        zIndex = zIndex,
        visible = visible,
        opacity = opacity,
        minZoom = minZoom,
        maxZoom = maxZoom
    )
}

internal fun VectorraDrawPolygonAnnotation.toHitFeatures(): List<VectorraAnnotationFeature> {
    val outline = rings.firstOrNull().orEmpty()
    return listOf(
        VectorraAnnotationFeature(
            id = hitId,
            layerId = layerId,
            geometry = VectorraAnnotationGeometry.Polygon(rings),
            properties = properties + mapOf("id" to id),
            radiusPixels = hitRadiusPixels,
            zIndex = zIndex,
            visible = visible,
            opacity = opacity,
            minZoom = minZoom,
            maxZoom = maxZoom
        ),
        VectorraAnnotationFeature(
            id = outlineHitId,
            layerId = outlineLayerId,
            geometry = VectorraAnnotationGeometry.LineString(outline),
            properties = properties + mapOf("id" to id),
            radiusPixels = outlineHitRadiusPixels,
            zIndex = outlineZIndex,
            visible = visible,
            opacity = opacity,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
    )
}

internal val VectorraDrawPointAnnotation.hitId: String
    get() = "$DRAW_POINT_LAYER_ID:$id"

internal val VectorraDrawLineAnnotation.hitId: String
    get() = "$DRAW_LINE_LAYER_ID:$id"

internal val VectorraDrawPolygonAnnotation.hitId: String
    get() = "$DRAW_POLYGON_LAYER_ID:$id"

internal val VectorraDrawPolygonAnnotation.outlineHitId: String
    get() = "$DRAW_POLYGON_OUTLINE_LAYER_ID:$id"
