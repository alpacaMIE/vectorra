package com.vectorra.maps.query

internal fun interface VectorraCoordinateProjector {
    fun project(coordinates: List<VectorraCoordinate>): List<VectorraScreenPoint?>
}

internal class VectorraProjectionCache private constructor(
    private val projected: Map<VectorraCoordinate, VectorraScreenPoint?>
) {
    fun screenPoint(coordinate: VectorraCoordinate): VectorraScreenPoint? {
        return projected[coordinate]
    }

    companion object {
        fun create(
            coordinates: Iterable<VectorraCoordinate>,
            projector: VectorraCoordinateProjector
        ): VectorraProjectionCache {
            val distinctCoordinates = coordinates.distinct()
            if (distinctCoordinates.isEmpty()) {
                return VectorraProjectionCache(emptyMap())
            }

            val projectedCoordinates = projector.project(distinctCoordinates)
            check(projectedCoordinates.size == distinctCoordinates.size) {
                "Coordinate projector returned ${projectedCoordinates.size} points for " +
                    "${distinctCoordinates.size} coordinates."
            }
            return VectorraProjectionCache(
                distinctCoordinates.zip(projectedCoordinates).toMap()
            )
        }
    }
}

internal fun VectorraAnnotationGeometry.coordinates(): List<VectorraCoordinate> {
    return when (this) {
        is VectorraAnnotationGeometry.Point -> listOf(coordinate)
        is VectorraAnnotationGeometry.LineString -> coordinates
        is VectorraAnnotationGeometry.Polygon -> rings.flatten()
    }
}
