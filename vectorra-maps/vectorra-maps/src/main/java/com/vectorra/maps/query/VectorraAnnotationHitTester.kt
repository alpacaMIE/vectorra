package com.vectorra.maps.query

import com.vectorra.maps.CameraState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

internal class VectorraAnnotationHitTester {
    private val features = linkedMapOf<String, VectorraAnnotationFeature>()
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var cameraState = CameraState(
        longitude = 0.0,
        latitude = 0.0,
        zoom = 0.0,
        pitch = 0.0,
        bearing = 0.0
    )

    fun setViewport(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    fun setCamera(camera: CameraState) {
        cameraState = camera
    }

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
        val radiusOverride = options.radiusPixels.takeIf { it > 0.0 && it.isFinite() }
        return features.values.asSequence()
            .filter { it.visible }
            .filter { it.opacity > OPACITY_THRESHOLD }
            .filter { cameraState.zoom >= it.minZoom && cameraState.zoom <= it.maxZoom }
            .filter { options.layerIds.isEmpty() || it.layerId in options.layerIds }
            .mapNotNull { feature ->
                val threshold = radiusOverride ?: feature.radiusPixels
                val distance = distanceToFeature(screenPoint, feature)
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
                    zIndex = hit.feature.zIndex
                )
            }
            .toList()
    }

    fun pixelForCoordinate(coordinate: VectorraCoordinate): VectorraScreenPoint {
        val center = worldPoint(cameraState.longitude, cameraState.latitude)
        val target = worldPoint(coordinate.longitude, coordinate.latitude)
        val dx = wrapWorldDelta(target.x - center.x)
        val dy = target.y - center.y
        val bearingRadians = Math.toRadians(cameraState.bearing)
        val cosBearing = cos(bearingRadians)
        val sinBearing = sin(bearingRadians)
        val rotatedX = dx * cosBearing - dy * sinBearing
        val rotatedY = dx * sinBearing + dy * cosBearing
        return VectorraScreenPoint(
            x = viewportWidth / 2.0 + rotatedX,
            y = viewportHeight / 2.0 + rotatedY
        )
    }

    fun coordinateForPixel(screenPoint: VectorraScreenPoint): VectorraCoordinate {
        val dx = screenPoint.x - viewportWidth / 2.0
        val dy = screenPoint.y - viewportHeight / 2.0
        val bearingRadians = Math.toRadians(cameraState.bearing)
        val cosBearing = cos(bearingRadians)
        val sinBearing = sin(bearingRadians)
        val unrotatedX = dx * cosBearing + dy * sinBearing
        val unrotatedY = -dx * sinBearing + dy * cosBearing
        val center = worldPoint(cameraState.longitude, cameraState.latitude)
        return coordinateFromWorld(
            WorldPoint(center.x + unrotatedX, center.y + unrotatedY)
        )
    }

    private fun distanceToFeature(
        screenPoint: VectorraScreenPoint,
        feature: VectorraAnnotationFeature
    ): Double {
        return when (val geometry = feature.geometry) {
            is VectorraAnnotationGeometry.Point -> distanceToPoint(screenPoint, geometry.coordinate)
            is VectorraAnnotationGeometry.LineString -> distanceToLineString(screenPoint, geometry.coordinates)
            is VectorraAnnotationGeometry.Polygon -> distanceToPolygon(screenPoint, geometry.rings)
        }
    }

    private fun distanceToPoint(screenPoint: VectorraScreenPoint, coordinate: VectorraCoordinate): Double {
        val projected = pixelForCoordinate(coordinate)
        return hypot(screenPoint.x - projected.x, screenPoint.y - projected.y)
    }

    private fun distanceToLineString(
        screenPoint: VectorraScreenPoint,
        coordinates: List<VectorraCoordinate>
    ): Double {
        if (coordinates.isEmpty()) return Double.POSITIVE_INFINITY
        if (coordinates.size == 1) return distanceToPoint(screenPoint, coordinates.first())
        val points = coordinates.map(::pixelForCoordinate)
        return points.zipWithNext().minOf { (a, b) ->
            distanceToSegment(screenPoint, a, b)
        }
    }

    private fun distanceToPolygon(
        screenPoint: VectorraScreenPoint,
        rings: List<List<VectorraCoordinate>>
    ): Double {
        val projectedRings = rings
            .map { ring -> ring.map(::pixelForCoordinate) }
            .filter { it.size >= 3 }
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

    private fun worldPoint(longitude: Double, latitude: Double): WorldPoint {
        val worldSize = worldSize()
        val x = (wrapLongitude(longitude) + 180.0) / 360.0 * worldSize
        val clampedLatitude = latitude.coerceIn(MIN_LATITUDE, MAX_LATITUDE)
        val sinLatitude = sin(Math.toRadians(clampedLatitude))
        val y = (0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI)) * worldSize
        return WorldPoint(x, y)
    }

    private fun coordinateFromWorld(point: WorldPoint): VectorraCoordinate {
        val worldSize = worldSize()
        val normalizedX = ((point.x % worldSize) + worldSize) % worldSize
        val longitude = normalizedX / worldSize * 360.0 - 180.0
        val mercatorY = PI * (1.0 - 2.0 * point.y / worldSize)
        val latitude = Math.toDegrees(atan(sinh(mercatorY))).coerceIn(MIN_LATITUDE, MAX_LATITUDE)
        return VectorraCoordinate(longitude = longitude, latitude = latitude)
    }

    private fun worldSize(): Double {
        return TILE_SIZE * 2.0.pow(cameraState.zoom)
    }

    private fun wrapWorldDelta(delta: Double): Double {
        val worldSize = worldSize()
        var result = delta
        if (result > worldSize / 2.0) result -= worldSize
        if (result < -worldSize / 2.0) result += worldSize
        return result
    }

    private val VectorraAnnotationGeometry.geometryType: VectorraGeometryType
        get() = when (this) {
            is VectorraAnnotationGeometry.Point -> VectorraGeometryType.POINT
            is VectorraAnnotationGeometry.LineString -> VectorraGeometryType.LINE_STRING
            is VectorraAnnotationGeometry.Polygon -> VectorraGeometryType.POLYGON
        }

    private data class WorldPoint(val x: Double, val y: Double)

    private data class VectorraHit(
        val feature: VectorraAnnotationFeature,
        val distancePixels: Double
    )

    private companion object {
        const val TILE_SIZE = 512.0
        const val MIN_LATITUDE = -85.05112878
        const val MAX_LATITUDE = 85.05112878
        const val OPACITY_THRESHOLD = 0.01

        fun wrapLongitude(longitude: Double): Double {
            var result = longitude
            while (result < -180.0) result += 360.0
            while (result > 180.0) result -= 360.0
            return result
        }
    }
}
