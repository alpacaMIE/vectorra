package com.vectorra.maps.turf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object TurfMeasurement {
    const val EARTH_RADIUS_METERS = 6371008.8

    fun distance(
        from: TurfCoordinate,
        to: TurfCoordinate,
        units: String = TurfConstants.UNIT_KILOMETERS
    ): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(deltaLat / 2).pow(2.0) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2.0)
        val radians = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return radiansToLength(radians, units)
    }

    fun length(
        coordinates: List<TurfCoordinate>,
        units: String = TurfConstants.UNIT_KILOMETERS
    ): Double {
        if (coordinates.size < 2) return 0.0
        var meters = 0.0
        for (index in 1 until coordinates.size) {
            meters += distance(coordinates[index - 1], coordinates[index], TurfConstants.UNIT_METERS)
        }
        return convertLength(meters, TurfConstants.UNIT_METERS, units)
    }

    fun area(rings: List<List<TurfCoordinate>>): Double {
        if (rings.isEmpty()) return 0.0
        val outerArea = abs(ringArea(rings.first()))
        val holesArea = rings.drop(1).sumOf { abs(ringArea(it)) }
        return (outerArea - holesArea).coerceAtLeast(0.0)
    }

    fun center(coordinates: List<TurfCoordinate>): TurfCoordinate {
        require(coordinates.isNotEmpty()) { "coordinates must not be empty" }
        var minLon = Double.POSITIVE_INFINITY
        var minLat = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        coordinates.forEach { coordinate ->
            minLon = min(minLon, coordinate.longitude)
            minLat = min(minLat, coordinate.latitude)
            maxLon = max(maxLon, coordinate.longitude)
            maxLat = max(maxLat, coordinate.latitude)
        }
        return TurfCoordinate(
            longitude = (minLon + maxLon) / 2.0,
            latitude = (minLat + maxLat) / 2.0
        )
    }

    fun convertLength(
        value: Double,
        fromUnits: String = TurfConstants.UNIT_KILOMETERS,
        toUnits: String = TurfConstants.UNIT_KILOMETERS
    ): Double {
        val meters = when (fromUnits.normalizedUnit()) {
            TurfConstants.UNIT_METERS -> value
            TurfConstants.UNIT_KILOMETERS -> value * 1000.0
            TurfConstants.UNIT_MILES -> value * 1609.344
            TurfConstants.UNIT_NAUTICAL_MILES -> value * 1852.0
            TurfConstants.UNIT_RADIANS -> value * EARTH_RADIUS_METERS
            TurfConstants.UNIT_DEGREES -> Math.toRadians(value) * EARTH_RADIUS_METERS
            else -> value * 1000.0
        }
        return when (toUnits.normalizedUnit()) {
            TurfConstants.UNIT_METERS -> meters
            TurfConstants.UNIT_KILOMETERS -> meters / 1000.0
            TurfConstants.UNIT_MILES -> meters / 1609.344
            TurfConstants.UNIT_NAUTICAL_MILES -> meters / 1852.0
            TurfConstants.UNIT_RADIANS -> meters / EARTH_RADIUS_METERS
            TurfConstants.UNIT_DEGREES -> Math.toDegrees(meters / EARTH_RADIUS_METERS)
            else -> meters / 1000.0
        }
    }

    private fun radiansToLength(radians: Double, units: String): Double {
        return convertLength(radians, TurfConstants.UNIT_RADIANS, units)
    }

    private fun ringArea(ring: List<TurfCoordinate>): Double {
        if (ring.size < 3) return 0.0
        val coordinates = if (ring.first() == ring.last()) ring else ring + ring.first()
        var sum = 0.0
        for (index in 0 until coordinates.lastIndex) {
            val current = coordinates[index]
            val next = coordinates[index + 1]
            val lonDelta = Math.toRadians(next.longitude - current.longitude)
            val currentLat = Math.toRadians(current.latitude)
            val nextLat = Math.toRadians(next.latitude)
            sum += lonDelta * (2.0 + sin(currentLat) + sin(nextLat))
        }
        return sum * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS / 2.0
    }

    private fun String.normalizedUnit(): String {
        return when (lowercase()) {
            TurfConstants.UNIT_METRES -> TurfConstants.UNIT_METERS
            TurfConstants.UNIT_KILOMETRES -> TurfConstants.UNIT_KILOMETERS
            else -> lowercase()
        }
    }
}
