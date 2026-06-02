package com.vectorra.maps.turf.geojson

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

sealed interface Geometry {
    fun type(): String
    fun toJson(): String
}

data class Point(
    private val longitude: Double,
    private val latitude: Double,
    private val altitude: Double? = null
) : Geometry {
    override fun type(): String = "Point"
    fun longitude(): Double = longitude
    fun latitude(): Double = latitude
    fun altitude(): Double? = altitude
    fun coordinates(): List<Double> = listOfNotNull(longitude, latitude, altitude)

    override fun toJson(): String {
        return Gson().toJson(
            JsonObject().apply {
                addProperty("type", type())
                add("coordinates", coordinateArray(this@Point))
            }
        )
    }

    companion object {
        fun fromLngLat(longitude: Double, latitude: Double): Point = Point(longitude, latitude)
        fun fromLngLat(longitude: Double, latitude: Double, altitude: Double): Point =
            Point(longitude, latitude, altitude)

        fun fromJson(json: String): Point {
            val root = JsonParser.parseString(json).asJsonObject
            val coordinates = root.getAsJsonArray("coordinates")
            return fromCoordinateArray(coordinates)
        }
    }
}

data class LineString(
    private val coordinates: List<Point>
) : Geometry {
    override fun type(): String = "LineString"
    fun coordinates(): List<Point> = coordinates

    override fun toJson(): String {
        return Gson().toJson(
            JsonObject().apply {
                addProperty("type", type())
                add("coordinates", pointsArray(coordinates))
            }
        )
    }

    companion object {
        fun fromLngLats(points: List<Point>): LineString = LineString(points)

        fun fromJson(json: String?): LineString {
            if (json.isNullOrBlank()) return LineString(emptyList())
            val root = JsonParser.parseString(json).asJsonObject
            val coordinates = root.getAsJsonArray("coordinates") ?: JsonArray()
            return LineString(coordinates.mapArray { fromCoordinateArray(it.asJsonArray) })
        }
    }
}

data class Polygon(
    private val coordinates: List<List<Point>>
) : Geometry {
    override fun type(): String = "Polygon"
    fun coordinates(): List<List<Point>> = coordinates

    override fun toJson(): String {
        return Gson().toJson(
            JsonObject().apply {
                addProperty("type", type())
                add("coordinates", JsonArray().apply {
                    coordinates.forEach { ring -> add(pointsArray(ring)) }
                })
            }
        )
    }

    companion object {
        fun fromLngLats(rings: List<List<Point>>): Polygon = Polygon(rings)

        fun fromJson(json: String?): Polygon {
            if (json.isNullOrBlank()) return Polygon(emptyList())
            val root = JsonParser.parseString(json).asJsonObject
            val coordinates = root.getAsJsonArray("coordinates") ?: JsonArray()
            return Polygon(
                coordinates.mapArray { ring ->
                    ring.asJsonArray.mapArray { fromCoordinateArray(it.asJsonArray) }
                }
            )
        }
    }
}

data class BoundingBox(
    private val southwest: Point,
    private val northeast: Point
) {
    fun southwest(): Point = southwest
    fun northeast(): Point = northeast

    companion object {
        fun fromLngLats(minLongitude: Double, minLatitude: Double, maxLongitude: Double, maxLatitude: Double): BoundingBox {
            return BoundingBox(
                southwest = Point.fromLngLat(minLongitude, minLatitude),
                northeast = Point.fromLngLat(maxLongitude, maxLatitude)
            )
        }
    }
}

data class Feature(
    private val geometry: Geometry?,
    val properties: JsonObject = JsonObject(),
    private val id: String? = null
) {
    fun geometry(): Geometry? = geometry
    fun id(): String? = id

    fun addStringProperty(name: String, value: String?) {
        properties.addProperty(name, value)
    }

    fun addNumberProperty(name: String, value: Number?) {
        properties.addProperty(name, value)
    }

    fun addBooleanProperty(name: String, value: Boolean?) {
        properties.addProperty(name, value)
    }

    fun getProperty(name: String): JsonElement? = properties.get(name)

    fun getStringProperty(name: String): String? {
        val value = properties.get(name) ?: return null
        return if (value.isJsonNull) null else value.asString
    }

    fun getNumberProperty(name: String): Number? {
        val value = properties.get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asNumber else null
    }

    fun getBooleanProperty(name: String): Boolean? {
        val value = properties.get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else null
    }

    fun toJson(): String {
        return Gson().toJson(
            JsonObject().apply {
                addProperty("type", "Feature")
                id?.let { addProperty("id", it) }
                add("geometry", geometry?.let { JsonParser.parseString(it.toJson()) })
                add("properties", properties)
            }
        )
    }

    companion object {
        fun fromGeometry(geometry: Geometry?): Feature = Feature(geometry)
        fun fromGeometry(geometry: Geometry?, properties: JsonObject?, id: String?): Feature =
            Feature(geometry, properties ?: JsonObject(), id)

        fun fromJson(json: String): Feature {
            val root = JsonParser.parseString(json).asJsonObject
            val geometry = root.get("geometry")?.takeUnless { it.isJsonNull }?.let { parseGeometry(it.asJsonObject) }
            val properties = root.getAsJsonObject("properties") ?: JsonObject()
            val id = root.get("id")?.takeUnless { it.isJsonNull }?.asString
            return Feature(geometry, properties, id)
        }
    }
}

data class FeatureCollection(
    private val features: List<Feature>
) {
    fun features(): List<Feature> = features

    fun toJson(): String {
        return Gson().toJson(
            JsonObject().apply {
                addProperty("type", "FeatureCollection")
                add("features", JsonArray().apply {
                    features.forEach { add(JsonParser.parseString(it.toJson())) }
                })
            }
        )
    }

    companion object {
        fun fromFeatures(features: List<Feature>): FeatureCollection = FeatureCollection(features)
        fun fromFeature(feature: Feature): FeatureCollection = FeatureCollection(listOf(feature))

        fun fromJson(json: String): FeatureCollection {
            val root = JsonParser.parseString(json).asJsonObject
            val features = root.getAsJsonArray("features")?.mapArray { Feature.fromJson(it.toString()) }.orEmpty()
            return FeatureCollection(features)
        }
    }
}

object GeoJsonReader {
    fun geometryFromJson(json: String): Geometry = parseGeometry(JsonParser.parseString(json).asJsonObject)
    fun pointFromJson(json: String): Point = Point.fromJson(json)
    fun lineStringFromJson(json: String?): LineString = LineString.fromJson(json)
    fun polygonFromJson(json: String?): Polygon = Polygon.fromJson(json)
    fun featureFromJson(json: String): Feature = Feature.fromJson(json)
    fun featureCollectionFromJson(json: String): FeatureCollection = FeatureCollection.fromJson(json)
}

object GeoJsonWriter {
    fun toJson(geometry: Geometry): String = geometry.toJson()
    fun toJson(feature: Feature): String = feature.toJson()
    fun toJson(featureCollection: FeatureCollection): String = featureCollection.toJson()
}

private fun parseGeometry(root: JsonObject): Geometry {
    return when (root.get("type")?.asString) {
        "Point" -> Point.fromJson(root.toString())
        "LineString" -> LineString.fromJson(root.toString())
        "Polygon" -> Polygon.fromJson(root.toString())
        else -> throw IllegalArgumentException("Unsupported GeoJSON geometry type: ${root.get("type")}")
    }
}

private fun coordinateArray(point: Point): JsonArray {
    return JsonArray().apply {
        add(point.longitude())
        add(point.latitude())
        point.altitude()?.let { add(it) }
    }
}

private fun pointsArray(points: List<Point>): JsonArray {
    return JsonArray().apply {
        points.forEach { add(coordinateArray(it)) }
    }
}

private fun fromCoordinateArray(coordinates: JsonArray): Point {
    val longitude = coordinates.get(0).asDouble
    val latitude = coordinates.get(1).asDouble
    val altitude = coordinates.getOrNull(2)?.asDouble
    return if (altitude == null) Point.fromLngLat(longitude, latitude) else Point.fromLngLat(longitude, latitude, altitude)
}

private fun <T> JsonArray.mapArray(transform: (JsonElement) -> T): List<T> {
    val result = ArrayList<T>(size())
    for (index in 0 until size()) {
        result.add(transform(get(index)))
    }
    return result
}

private fun JsonArray.getOrNull(index: Int): JsonElement? {
    return if (index in 0 until size()) get(index) else null
}
