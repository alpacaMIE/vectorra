package com.vectorra.maps.query

import com.vectorra.maps.CameraState
import kotlin.math.abs
import kotlin.math.hypot

internal class VectorraAnnotationHitTester(
    private val projector: VectorraCoordinateProjector,
    private val camera: () -> CameraState
) {
    private val features = linkedMapOf<String, VectorraAnnotationFeature>()

    fun add(feature: VectorraAnnotationFeature) {
        features[feature.id] = feature
    }

    fun set(nextFeatures: List<VectorraAnnotationFeature>) {
        features.clear()
        nextFeatures.forEach { add(it) }
    }

    fun remove(id: String) {
        features.remove(id)
    }

    fun clear() {
        features.clear()
    }

    fun query(screenPoint: VectorraScreenPoint, options: VectorraQueryOptions): List<VectorraQueriedFeature> {
        return queryFeatures(features.values, screenPoint, options)
    }

    fun queryFeatures(
        candidates: Iterable<VectorraAnnotationFeature>,
        screenPoint: VectorraScreenPoint,
        options: VectorraQueryOptions
    ): List<VectorraQueriedFeature> {
        val zoom = camera().zoom
        val visibleCandidates = candidates.asSequence()
            .filter { it.visible }
            .filter { it.opacity > OPACITY_THRESHOLD }
            .filter { zoom >= it.minZoom && zoom <= it.maxZoom }
            .filter { options.layerIds.isEmpty() || it.layerId in options.layerIds }
            .filter { options.sourceLayerIds.isEmpty() || it.properties["source-layer"] in options.sourceLayerIds }
            .toList()
        if (visibleCandidates.isEmpty()) {
            return emptyList()
        }

        val projection = VectorraProjectionCache.create(
            visibleCandidates.asSequence().flatMap { it.geometry.coordinates().asSequence() }.asIterable(),
            projector
        )
        val radiusOverride = options.radiusPixels.takeIf { it > 0.0 && it.isFinite() }
        return visibleCandidates.asSequence()
            .mapNotNull { feature ->
                val threshold = radiusOverride ?: feature.radiusPixels
                val distance = distanceToFeature(screenPoint, feature, projection)
                if (distance <= threshold) {
                    VectorraHit(feature, distance)
                } else {
                    null
                }
            }
            .sortedWith(compareByDescending<VectorraHit> { it.feature.zIndex }.thenBy { it.distancePixels })
            .map { hit ->
                VectorraQueriedFeature(
                    id = hit.feature.id,
                    layerId = hit.feature.layerId,
                    geometryType = hit.feature.geometry.geometryType,
                    properties = hit.feature.properties,
                    distancePixels = hit.distancePixels,
                    sourceId = hit.feature.sourceId,
                    zIndex = hit.feature.zIndex
                )
            }
            .toList()
    }

    private fun distanceToFeature(
        screenPoint: VectorraScreenPoint,
        feature: VectorraAnnotationFeature,
        projection: VectorraProjectionCache
    ): Double {
        return when (val geometry = feature.geometry) {
            is VectorraAnnotationGeometry.Point -> distanceToPoint(screenPoint, geometry.coordinate, projection)
            is VectorraAnnotationGeometry.LineString -> distanceToLineString(screenPoint, geometry.coordinates, projection)
            is VectorraAnnotationGeometry.Polygon -> distanceToPolygon(screenPoint, geometry.rings, projection)
        }
    }

    private fun distanceToPoint(
        screenPoint: VectorraScreenPoint,
        coordinate: VectorraCoordinate,
        projection: VectorraProjectionCache
    ): Double {
        val projected = projection.screenPoint(coordinate) ?: return Double.POSITIVE_INFINITY
        return hypot(screenPoint.x - projected.x, screenPoint.y - projected.y)
    }

    private fun distanceToLineString(
        screenPoint: VectorraScreenPoint,
        coordinates: List<VectorraCoordinate>,
        projection: VectorraProjectionCache
    ): Double {
        if (coordinates.isEmpty()) return Double.POSITIVE_INFINITY
        val points = coordinates.map { projection.screenPoint(it) ?: return Double.POSITIVE_INFINITY }
        if (points.size == 1) return hypot(screenPoint.x - points.first().x, screenPoint.y - points.first().y)
        return points.zipWithNext().minOf { (a, b) ->
            distanceToSegment(screenPoint, a, b)
        }
    }

    private fun distanceToPolygon(
        screenPoint: VectorraScreenPoint,
        rings: List<List<VectorraCoordinate>>,
        projection: VectorraProjectionCache
    ): Double {
        val projectedRings = rings
            .mapNotNull { ring ->
                if (ring.size < 3) {
                    null
                } else {
                    ring.map { projection.screenPoint(it) ?: return@mapNotNull null }
                }
            }
        if (projectedRings.isEmpty()) return Double.POSITIVE_INFINITY

        val insideOuter = pointInRing(screenPoint, projectedRings.first())
        val insideHole = projectedRings.drop(1).any { pointInRing(screenPoint, it) }
        if (insideOuter && !insideHole) return 0.0

        return projectedRings.minOf { ring ->
            closedSegments(ring).minOf { (a, b) -> distanceToSegment(screenPoint, a, b) }
        }
    }

    private fun closedSegments(ring: List<VectorraScreenPoint>): List<Pair<VectorraScreenPoint, VectorraScreenPoint>> {
        return ring.indices.map { index ->
            ring[index] to ring[(index + 1) % ring.size]
        }
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

    private fun distanceToSegment(
        point: VectorraScreenPoint,
        a: VectorraScreenPoint,
        b: VectorraScreenPoint
    ): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9) return hypot(point.x - a.x, point.y - a.y)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val projectionX = a.x + t * dx
        val projectionY = a.y + t * dy
        return hypot(point.x - projectionX, point.y - projectionY)
    }

    private val VectorraAnnotationGeometry.geometryType: VectorraGeometryType
        get() = when (this) {
            is VectorraAnnotationGeometry.Point -> VectorraGeometryType.POINT
            is VectorraAnnotationGeometry.LineString -> VectorraGeometryType.LINE_STRING
            is VectorraAnnotationGeometry.Polygon -> VectorraGeometryType.POLYGON
        }

    private data class VectorraHit(
        val feature: VectorraAnnotationFeature,
        val distancePixels: Double
    )

    private companion object {
        const val OPACITY_THRESHOLD = 0.01
    }
}
