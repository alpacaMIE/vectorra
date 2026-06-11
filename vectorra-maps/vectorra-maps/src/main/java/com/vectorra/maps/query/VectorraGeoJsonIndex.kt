package com.vectorra.maps.query

import com.vectorra.maps.CameraState
import kotlin.math.abs
import kotlin.math.hypot

internal class VectorraGeoJsonIndex(
    private val projector: VectorraCoordinateProjector,
    private val camera: () -> CameraState
) {
    private val sources = linkedMapOf<String, VectorraGeoJsonSource>()
    private val layers = linkedMapOf<String, VectorraGeoJsonLayer>()
    private val clusterLeaves = linkedMapOf<ClusterKey, List<VectorraGeoJsonFeature>>()

    fun setSource(source: VectorraGeoJsonSource) {
        sources[source.id] = source
        clusterLeaves.keys.filter { it.sourceId == source.id }.forEach(clusterLeaves::remove)
    }

    fun removeSource(sourceId: String) {
        sources.remove(sourceId)
        layers.values.filter { it.sourceId == sourceId }.map { it.id }.forEach(layers::remove)
        clusterLeaves.keys.filter { it.sourceId == sourceId }.forEach(clusterLeaves::remove)
    }

    fun setLayer(layer: VectorraGeoJsonLayer) {
        layers[layer.id] = layer
    }

    fun removeLayer(layerId: String) {
        layers.remove(layerId)
    }

    fun clear() {
        sources.clear()
        layers.clear()
        clusterLeaves.clear()
    }

    fun query(screenPoint: VectorraScreenPoint, options: VectorraQueryOptions): List<VectorraQueriedFeature> {
        clusterLeaves.clear()
        val cameraState = camera()
        val activeLayers = layers.values.asSequence()
            .filter { it.visible }
            .filter { it.opacity > OPACITY_THRESHOLD }
            .filter { cameraState.zoom >= it.minZoom && cameraState.zoom <= it.maxZoom }
            .filter { options.layerIds.isEmpty() || it.id in options.layerIds }
            .sortedByDescending { it.zIndex }
            .toList()
        if (activeLayers.isEmpty()) {
            return emptyList()
        }

        val projection = VectorraProjectionCache.create(
            activeLayers.asSequence()
                .mapNotNull { sources[it.sourceId] }
                .distinctBy { it.id }
                .flatMap { source -> source.features.asSequence().flatMap { it.geometry.coordinates().asSequence() } }
                .asIterable(),
            projector
        )
        return activeLayers.asSequence()
            .flatMap { layer -> queryLayer(screenPoint, layer, options, cameraState, projection).asSequence() }
            .sortedWith(compareByDescending<VectorraQueriedFeature> { layerZIndex(it.layerId) }.thenBy { it.distancePixels })
            .toList()
    }

    fun getClusterLeaves(
        sourceId: String,
        clusterId: Long,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0
    ): List<VectorraGeoJsonFeature> {
        return clusterLeaves[ClusterKey(sourceId, clusterId)]
            ?.drop(offset.coerceAtLeast(0))
            ?.take(limit.coerceAtLeast(0))
            ?: emptyList()
    }

    private fun queryLayer(
        screenPoint: VectorraScreenPoint,
        layer: VectorraGeoJsonLayer,
        options: VectorraQueryOptions,
        cameraState: CameraState,
        projection: VectorraProjectionCache
    ): List<VectorraQueriedFeature> {
        val source = sources[layer.sourceId] ?: return emptyList()
        val radius = options.radiusPixels.takeIf { it > 0.0 && it.isFinite() } ?: layer.hitRadiusPixels
        val entries = if (source.cluster && cameraState.zoom <= source.clusterMaxZoom) {
            clusteredEntries(source, layer, projection)
        } else {
            source.features.mapNotNull { feature ->
                if (feature.geometry.geometryType in layer.geometryTypes && layer.filter != VectorraGeoJsonLayerFilter.CLUSTER) {
                    GeoJsonEntry(feature = feature, layer = layer, cluster = null)
                } else {
                    null
                }
            }
        }

        return entries
            .filter { options.sourceLayerIds.isEmpty() || it.feature.properties["source-layer"] in options.sourceLayerIds }
            .mapNotNull { entry ->
                val bounds = screenBounds(entry, projection)
                if (!bounds.intersects(screenPoint, radius)) return@mapNotNull null
                val distance = distanceToGeometry(screenPoint, entry, projection)
                if (distance > radius) return@mapNotNull null
                entry.toQueriedFeature(distance)
            }
    }

    private fun clusteredEntries(
        source: VectorraGeoJsonSource,
        layer: VectorraGeoJsonLayer,
        projection: VectorraProjectionCache
    ): List<GeoJsonEntry> {
        val pointFeatures = source.features.filter {
            it.geometry is VectorraAnnotationGeometry.Point && VectorraGeometryType.POINT in layer.geometryTypes
        }
        val nonPointFeatures = source.features.filterNot { it.geometry is VectorraAnnotationGeometry.Point }
            .mapNotNull { feature ->
                if (feature.geometry.geometryType in layer.geometryTypes && layer.filter != VectorraGeoJsonLayerFilter.CLUSTER) {
                    GeoJsonEntry(feature = feature, layer = layer, cluster = null)
                } else {
                    null
                }
            }
        if (pointFeatures.isEmpty()) return nonPointFeatures

        val remaining = pointFeatures.toMutableList()
        val clusters = mutableListOf<GeoJsonEntry>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val seedPoint = projection.screenPoint((seed.geometry as VectorraAnnotationGeometry.Point).coordinate)
                ?: continue
            val grouped = mutableListOf(seed)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val candidatePoint = projection.screenPoint((candidate.geometry as VectorraAnnotationGeometry.Point).coordinate)
                    ?: continue
                if (hypot(candidatePoint.x - seedPoint.x, candidatePoint.y - seedPoint.y) <= source.clusterRadiusPixels) {
                    grouped.add(candidate)
                    iterator.remove()
                }
            }

            if (grouped.size > 1) {
                if (layer.filter != VectorraGeoJsonLayerFilter.UNCLUSTERED) {
                    val clusterId = clusterIdFor(grouped)
                    val centroid = centroid(grouped)
                    val clusterScreenPoint = grouped
                        .mapNotNull { projection.screenPoint((it.geometry as VectorraAnnotationGeometry.Point).coordinate) }
                        .averageScreenPoint()
                    clusterLeaves[ClusterKey(source.id, clusterId)] = grouped
                    val properties = mapOf(
                        "cluster" to "true",
                        "cluster_id" to clusterId.toString(),
                        "point_count" to grouped.size.toString(),
                        "sourceId" to source.id
                    )
                    clusters.add(
                        GeoJsonEntry(
                            feature = VectorraGeoJsonFeature(
                                id = "cluster:$clusterId",
                                geometry = VectorraAnnotationGeometry.Point(centroid),
                                properties = properties
                            ),
                            layer = layer,
                            cluster = ClusterInfo(source.id, clusterId, grouped),
                            projectedPoint = clusterScreenPoint
                        )
                    )
                }
            } else if (layer.filter != VectorraGeoJsonLayerFilter.CLUSTER) {
                clusters.add(GeoJsonEntry(feature = seed, layer = layer, cluster = null))
            }
        }
        return nonPointFeatures + clusters
    }

    private fun GeoJsonEntry.toQueriedFeature(distance: Double): VectorraQueriedFeature {
        val clusterInfo = cluster
        val properties = if (clusterInfo == null) {
            feature.properties + mapOf("cluster" to "false")
        } else {
            feature.properties
        }
        return VectorraQueriedFeature(
            id = feature.id,
            layerId = layer.id,
            geometryType = feature.geometry.geometryType,
            properties = properties,
            distancePixels = distance,
            sourceId = layer.sourceId,
            clusterId = clusterInfo?.clusterId,
            zIndex = layer.zIndex
        )
    }

    private fun screenBounds(
        entry: GeoJsonEntry,
        projection: VectorraProjectionCache
    ): ScreenBounds {
        val geometry = entry.geometry
        if (geometry is VectorraAnnotationGeometry.Point && entry.projectedPoint != null) {
            return ScreenBounds(
                minX = entry.projectedPoint.x,
                minY = entry.projectedPoint.y,
                maxX = entry.projectedPoint.x,
                maxY = entry.projectedPoint.y
            )
        }
        val points = geometry.coordinates().map { projection.screenPoint(it) ?: return ScreenBounds.empty() }
        if (points.isEmpty()) return ScreenBounds.empty()
        return ScreenBounds(
            minX = points.minOf { it.x },
            minY = points.minOf { it.y },
            maxX = points.maxOf { it.x },
            maxY = points.maxOf { it.y }
        )
    }

    private fun distanceToGeometry(
        point: VectorraScreenPoint,
        entry: GeoJsonEntry,
        projection: VectorraProjectionCache
    ): Double {
        val geometry = entry.geometry
        return when (geometry) {
            is VectorraAnnotationGeometry.Point -> {
                val projected = entry.projectedPoint
                    ?: projection.screenPoint(geometry.coordinate)
                    ?: return Double.POSITIVE_INFINITY
                hypot(point.x - projected.x, point.y - projected.y)
            }
            is VectorraAnnotationGeometry.LineString -> distanceToLineString(point, geometry.coordinates, projection)
            is VectorraAnnotationGeometry.Polygon -> distanceToPolygon(point, geometry.rings, projection)
        }
    }

    private fun distanceToLineString(
        point: VectorraScreenPoint,
        coordinates: List<VectorraCoordinate>,
        projection: VectorraProjectionCache
    ): Double {
        if (coordinates.isEmpty()) return Double.POSITIVE_INFINITY
        val projected = coordinates.map { projection.screenPoint(it) ?: return Double.POSITIVE_INFINITY }
        if (projected.size == 1) {
            return hypot(point.x - projected.first().x, point.y - projected.first().y)
        }
        return projected.zipWithNext().minOf { (a, b) -> distanceToSegment(point, a, b) }
    }

    private fun distanceToPolygon(
        point: VectorraScreenPoint,
        rings: List<List<VectorraCoordinate>>,
        projection: VectorraProjectionCache
    ): Double {
        val projectedRings = rings.mapNotNull { ring ->
            if (ring.size < 3) {
                null
            } else {
                ring.map { projection.screenPoint(it) ?: return@mapNotNull null }
            }
        }
        if (projectedRings.isEmpty()) return Double.POSITIVE_INFINITY
        val insideOuter = pointInRing(point, projectedRings.first())
        val insideHole = projectedRings.drop(1).any { pointInRing(point, it) }
        if (insideOuter && !insideHole) return 0.0
        return projectedRings.minOf { ring ->
            ring.indices.minOf { index ->
                distanceToSegment(point, ring[index], ring[(index + 1) % ring.size])
            }
        }
    }

    private fun distanceToSegment(point: VectorraScreenPoint, a: VectorraScreenPoint, b: VectorraScreenPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9) return hypot(point.x - a.x, point.y - a.y)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(point.x - (a.x + t * dx), point.y - (a.y + t * dy))
    }

    private fun pointInRing(point: VectorraScreenPoint, ring: List<VectorraScreenPoint>): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val pi = ring[i]
            val pj = ring[j]
            val intersects = (pi.y > point.y) != (pj.y > point.y) &&
                point.x < (pj.x - pi.x) * (point.y - pi.y) / ((pj.y - pi.y).takeIf { abs(it) > 1e-9 } ?: 1e-9) + pi.x
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    private fun centroid(features: List<VectorraGeoJsonFeature>): VectorraCoordinate {
        val coordinates = features.mapNotNull { (it.geometry as? VectorraAnnotationGeometry.Point)?.coordinate }
        return VectorraCoordinate(
            longitude = coordinates.map { it.longitude }.average(),
            latitude = coordinates.map { it.latitude }.average()
        )
    }

    private fun clusterIdFor(features: List<VectorraGeoJsonFeature>): Long {
        return features.map { it.id }.sorted().joinToString("|").hashCode().toLong() and 0xffffffffL
    }

    private fun List<VectorraScreenPoint>.averageScreenPoint(): VectorraScreenPoint {
        return VectorraScreenPoint(
            x = map { it.x }.average(),
            y = map { it.y }.average()
        )
    }

    private fun layerZIndex(layerId: String): Int = layers[layerId]?.zIndex ?: 0

    private val VectorraAnnotationGeometry.geometryType: VectorraGeometryType
        get() = when (this) {
            is VectorraAnnotationGeometry.Point -> VectorraGeometryType.POINT
            is VectorraAnnotationGeometry.LineString -> VectorraGeometryType.LINE_STRING
            is VectorraAnnotationGeometry.Polygon -> VectorraGeometryType.POLYGON
        }

    private data class GeoJsonEntry(
        val feature: VectorraGeoJsonFeature,
        val layer: VectorraGeoJsonLayer,
        val cluster: ClusterInfo?,
        val projectedPoint: VectorraScreenPoint? = null
    ) {
        val geometry: VectorraAnnotationGeometry
            get() = feature.geometry
    }

    private data class ClusterInfo(
        val sourceId: String,
        val clusterId: Long,
        val leaves: List<VectorraGeoJsonFeature>
    )

    private data class ClusterKey(val sourceId: String, val clusterId: Long)

    private data class ScreenBounds(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double
    ) {
        fun intersects(point: VectorraScreenPoint, radius: Double): Boolean {
            return point.x >= minX - radius &&
                point.x <= maxX + radius &&
                point.y >= minY - radius &&
                point.y <= maxY + radius
        }

        companion object {
            fun empty(): ScreenBounds {
                return ScreenBounds(
                    minX = Double.POSITIVE_INFINITY,
                    minY = Double.POSITIVE_INFINITY,
                    maxX = Double.NEGATIVE_INFINITY,
                    maxY = Double.NEGATIVE_INFINITY
                )
            }
        }
    }

    private companion object {
        const val OPACITY_THRESHOLD = 0.01
    }
}
