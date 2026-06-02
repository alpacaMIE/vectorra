package com.vectorra.maps.turf

import org.junit.Assert.assertEquals
import org.junit.Test

class TurfMeasurementTest {
    @Test
    fun distanceReturnsMetersForOneDegreeAtEquator() {
        val from = TurfCoordinate(0.0, 0.0)
        val to = TurfCoordinate(1.0, 0.0)

        val meters = TurfMeasurement.distance(from, to, TurfConstants.UNIT_METERS)

        assertEquals(111195.08, meters, 0.5)
    }

    @Test
    fun lengthSumsSegments() {
        val coordinates = listOf(
            TurfCoordinate(0.0, 0.0),
            TurfCoordinate(1.0, 0.0),
            TurfCoordinate(1.0, 1.0)
        )

        val meters = TurfMeasurement.length(coordinates, TurfConstants.UNIT_METERS)

        assertEquals(222390.16, meters, 2.0)
    }

    @Test
    fun areaReturnsSquareMetersForOneDegreeCellAtEquator() {
        val ring = listOf(
            TurfCoordinate(0.0, 0.0),
            TurfCoordinate(1.0, 0.0),
            TurfCoordinate(1.0, 1.0),
            TurfCoordinate(0.0, 1.0),
            TurfCoordinate(0.0, 0.0)
        )

        val area = TurfMeasurement.area(listOf(ring))

        assertEquals(12_363_718_145.0, area, 100_000.0)
    }

    @Test
    fun areaSubtractsHoles() {
        val outer = listOf(
            TurfCoordinate(0.0, 0.0),
            TurfCoordinate(2.0, 0.0),
            TurfCoordinate(2.0, 2.0),
            TurfCoordinate(0.0, 2.0),
            TurfCoordinate(0.0, 0.0)
        )
        val hole = listOf(
            TurfCoordinate(0.5, 0.5),
            TurfCoordinate(1.5, 0.5),
            TurfCoordinate(1.5, 1.5),
            TurfCoordinate(0.5, 1.5),
            TurfCoordinate(0.5, 0.5)
        )

        val areaWithHole = TurfMeasurement.area(listOf(outer, hole))
        val outerArea = TurfMeasurement.area(listOf(outer))
        val holeArea = TurfMeasurement.area(listOf(hole))

        assertEquals(outerArea - holeArea, areaWithHole, 0.001)
    }

    @Test
    fun centerReturnsBoundingBoxCenter() {
        val center = TurfMeasurement.center(
            listOf(
                TurfCoordinate(100.0, 20.0),
                TurfCoordinate(110.0, 45.0),
                TurfCoordinate(120.0, 30.0)
            )
        )

        assertEquals(110.0, center.longitude, 0.0)
        assertEquals(32.5, center.latitude, 0.0)
    }
}
