package com.vectorra.maps.query

import java.io.Closeable

data class VectorraCoordinate(
    val longitude: Double,
    val latitude: Double
)

data class VectorraScreenPoint(
    val x: Double,
    val y: Double
)

sealed class VectorraAnnotationGeometry {
    data class Point(val coordinate: VectorraCoordinate) : VectorraAnnotationGeometry()
    data class LineString(val coordinates: List<VectorraCoordinate>) : VectorraAnnotationGeometry()
    data class Polygon(val rings: List<List<VectorraCoordinate>>) : VectorraAnnotationGeometry()
}

enum class VectorraGeometryType {
    POINT,
    LINE_STRING,
    POLYGON
}

data class VectorraAnnotationFeature(
    val id: String,
    val layerId: String,
    val geometry: VectorraAnnotationGeometry,
    val properties: Map<String, String> = emptyMap(),
    val sourceId: String? = null,
    val radiusPixels: Double = 16.0,
    val zIndex: Int = 0,
    val visible: Boolean = true,
    val opacity: Double = 1.0,
    val minZoom: Double = 0.0,
    val maxZoom: Double = 24.0
)

data class VectorraGeoJsonFeature(
    val id: String,
    val geometry: VectorraAnnotationGeometry,
    val properties: Map<String, String> = emptyMap()
)

data class VectorraGeoJsonSource(
    val id: String,
    val features: List<VectorraGeoJsonFeature>,
    val cluster: Boolean = false,
    val clusterRadiusPixels: Double = 50.0,
    val clusterMaxZoom: Double = 14.0
)

enum class VectorraGeoJsonLayerFilter {
    ALL,
    CLUSTER,
    UNCLUSTERED
}

data class VectorraGeoJsonLayer(
    val id: String,
    val sourceId: String,
    val geometryTypes: Set<VectorraGeometryType> = setOf(
        VectorraGeometryType.POINT,
        VectorraGeometryType.LINE_STRING,
        VectorraGeometryType.POLYGON
    ),
    val filter: VectorraGeoJsonLayerFilter = VectorraGeoJsonLayerFilter.ALL,
    val hitRadiusPixels: Double = 16.0,
    val visible: Boolean = true,
    val opacity: Double = 1.0,
    val minZoom: Double = 0.0,
    val maxZoom: Double = 24.0,
    val zIndex: Int = 0
)

data class VectorraQueryOptions(
    val layerIds: Set<String> = emptySet(),
    val sourceLayerIds: Set<String> = emptySet(),
    val radiusPixels: Double = 16.0
)

data class VectorraQueriedFeature(
    val id: String,
    val layerId: String,
    val geometryType: VectorraGeometryType,
    val properties: Map<String, String>,
    val distancePixels: Double,
    val sourceId: String? = null,
    val clusterId: Long? = null,
    val zIndex: Int = 0
)

data class VectorraMapClickEvent(
    val screenPoint: VectorraScreenPoint,
    val features: List<VectorraQueriedFeature>
)

typealias VectorraMapClickListener = (VectorraMapClickEvent) -> Boolean

internal fun closeable(block: () -> Unit): Closeable = Closeable(block)
