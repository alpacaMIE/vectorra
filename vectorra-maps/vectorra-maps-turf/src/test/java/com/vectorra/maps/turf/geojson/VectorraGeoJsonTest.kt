package com.vectorra.maps.turf.geojson

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraGeoJsonTest {
    @Test
    fun pointRoundTripKeepsCoordinates() {
        val point = Point.fromLngLat(116.391, 39.907, 45.0)
        val parsed = Point.fromJson(point.toJson())

        assertEquals(116.391, parsed.longitude(), 0.0)
        assertEquals(39.907, parsed.latitude(), 0.0)
        assertEquals(45.0, parsed.altitude() ?: 0.0, 0.0)
    }

    @Test
    fun lineStringRoundTripKeepsOrder() {
        val line = LineString.fromLngLats(
            listOf(
                Point.fromLngLat(100.0, 30.0),
                Point.fromLngLat(101.0, 31.0),
                Point.fromLngLat(102.0, 32.0)
            )
        )

        val parsed = LineString.fromJson(line.toJson())

        assertEquals(3, parsed.coordinates().size)
        assertEquals(101.0, parsed.coordinates()[1].longitude(), 0.0)
        assertEquals(31.0, parsed.coordinates()[1].latitude(), 0.0)
    }

    @Test
    fun polygonRoundTripKeepsHoles() {
        val polygon = Polygon.fromLngLats(
            listOf(
                listOf(
                    Point.fromLngLat(0.0, 0.0),
                    Point.fromLngLat(2.0, 0.0),
                    Point.fromLngLat(2.0, 2.0),
                    Point.fromLngLat(0.0, 0.0)
                ),
                listOf(
                    Point.fromLngLat(0.5, 0.5),
                    Point.fromLngLat(1.0, 0.5),
                    Point.fromLngLat(0.5, 0.5)
                )
            )
        )

        val parsed = Polygon.fromJson(polygon.toJson())

        assertEquals(2, parsed.coordinates().size)
        assertEquals(4, parsed.coordinates()[0].size)
        assertEquals(3, parsed.coordinates()[1].size)
    }

    @Test
    fun featureCollectionWritesStandardGeoJson() {
        val feature = Feature.fromGeometry(Point.fromLngLat(1.0, 2.0)).apply {
            addStringProperty("name", "debug")
        }
        val collection = FeatureCollection.fromFeature(feature)
        val root = JsonParser.parseString(collection.toJson()).asJsonObject

        assertEquals("FeatureCollection", root.get("type").asString)
        assertEquals(1, root.getAsJsonArray("features").size())
        assertTrue(root.getAsJsonArray("features")[0].asJsonObject.has("properties"))
    }
}
