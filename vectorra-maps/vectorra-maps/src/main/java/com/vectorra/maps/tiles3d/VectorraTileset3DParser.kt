package com.vectorra.maps.tiles3d

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vectorra.maps.VectorraBetaApi
import java.net.URI
import java.util.Locale

@VectorraBetaApi("0.5.0-beta.1")
object VectorraTileset3DParser {
    fun parse(json: String, baseUri: String? = null): VectorraTileset3D {
        val rootElement = JsonParser.parseString(json)
        require(rootElement.isJsonObject) { "3D Tiles tileset JSON root must be an object." }

        val tilesetObject = rootElement.asJsonObject
        val asset = parseAsset(tilesetObject.requiredObject("asset"))
        val rootTile = parseTile(
            tileObject = tilesetObject.requiredObject("root"),
            baseUri = baseUri?.takeIf { it.isNotBlank() }?.let(::URI)
        )

        return VectorraTileset3D(
            asset = asset,
            geometricError = tilesetObject.requiredDouble("geometricError"),
            root = rootTile
        )
    }

    private fun parseAsset(assetObject: JsonObject): VectorraTileset3DAsset {
        return VectorraTileset3DAsset(
            version = assetObject.requiredString("version"),
            tilesetVersion = assetObject.optionalString("tilesetVersion"),
            generator = assetObject.optionalString("generator")
        )
    }

    private fun parseTile(
        tileObject: JsonObject,
        baseUri: URI?
    ): VectorraTileset3DTile {
        return VectorraTileset3DTile(
            boundingVolume = parseBoundingVolume(tileObject.requiredObject("boundingVolume")),
            geometricError = tileObject.requiredDouble("geometricError"),
            refine = tileObject.optionalString("refine")?.let(::parseRefine),
            transform = tileObject.optionalDoubleArray("transform", expectedSize = 16),
            contents = parseContents(tileObject, baseUri),
            children = tileObject.optionalArray("children")
                ?.mapObjects { child -> parseTile(child, baseUri) }
                ?: emptyList()
        )
    }

    private fun parseContents(tileObject: JsonObject, baseUri: URI?): List<VectorraTileset3DContent> {
        val contents = mutableListOf<VectorraTileset3DContent>()
        tileObject.optionalObject("content")?.let { contents += parseContent(it, baseUri) }
        tileObject.optionalArray("contents")
            ?.mapObjects { contentObject -> parseContent(contentObject, baseUri) }
            ?.let { contents += it }
        return contents
    }

    private fun parseContent(contentObject: JsonObject, baseUri: URI?): VectorraTileset3DContent {
        val uri = contentObject.optionalString("uri")
            ?: contentObject.optionalString("url")
            ?: throw IllegalArgumentException("3D Tiles content must include uri or url.")
        val resolvedUri = resolveContentUri(uri, baseUri)
        return VectorraTileset3DContent(
            uri = uri,
            resolvedUri = resolvedUri,
            kind = VectorraTileset3DContentKind.fromUri(resolvedUri)
        )
    }

    private fun parseBoundingVolume(volumeObject: JsonObject): VectorraTileset3DBoundingVolume {
        volumeObject.optionalDoubleArray("region", expectedSize = 6)?.let { values ->
            return VectorraTileset3DBoundingVolume.Region(
                west = values[0],
                south = values[1],
                east = values[2],
                north = values[3],
                minimumHeight = values[4],
                maximumHeight = values[5]
            )
        }

        volumeObject.optionalDoubleArray("box", expectedSize = 12)?.let { values ->
            return VectorraTileset3DBoundingVolume.Box(values)
        }

        volumeObject.optionalDoubleArray("sphere", expectedSize = 4)?.let { values ->
            return VectorraTileset3DBoundingVolume.Sphere(
                centerX = values[0],
                centerY = values[1],
                centerZ = values[2],
                radius = values[3]
            )
        }

        throw IllegalArgumentException("3D Tiles boundingVolume must include region, box, or sphere.")
    }

    private fun parseRefine(value: String): VectorraTileset3DRefine {
        return when (value.uppercase(Locale.US)) {
            "ADD" -> VectorraTileset3DRefine.ADD
            "REPLACE" -> VectorraTileset3DRefine.REPLACE
            else -> throw IllegalArgumentException("Unsupported 3D Tiles refine value: $value")
        }
    }

    private fun resolveContentUri(uri: String, baseUri: URI?): String {
        val contentUri = URI(uri)
        if (contentUri.isAbsolute || baseUri == null) return uri
        return baseUri.resolve(contentUri).toString()
    }

    private fun JsonObject.requiredObject(name: String): JsonObject {
        return optionalObject(name) ?: throw IllegalArgumentException("3D Tiles JSON object '$name' is required.")
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        val element = optionalElement(name) ?: return null
        require(element.isJsonObject) { "3D Tiles JSON field '$name' must be an object." }
        return element.asJsonObject
    }

    private fun JsonObject.optionalArray(name: String): JsonArray? {
        val element = optionalElement(name) ?: return null
        require(element.isJsonArray) { "3D Tiles JSON field '$name' must be an array." }
        return element.asJsonArray
    }

    private fun JsonObject.requiredString(name: String): String {
        return optionalString(name) ?: throw IllegalArgumentException("3D Tiles JSON string '$name' is required.")
    }

    private fun JsonObject.optionalString(name: String): String? {
        val element = optionalElement(name) ?: return null
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "3D Tiles JSON field '$name' must be a string."
        }
        return element.asString
    }

    private fun JsonObject.requiredDouble(name: String): Double {
        val element = optionalElement(name)
            ?: throw IllegalArgumentException("3D Tiles JSON number '$name' is required.")
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            "3D Tiles JSON field '$name' must be a number."
        }
        return element.asDouble.also { value ->
            require(value.isFinite()) { "3D Tiles JSON field '$name' must be finite." }
        }
    }

    private fun JsonObject.optionalDoubleArray(name: String, expectedSize: Int): List<Double>? {
        val array = optionalArray(name) ?: return null
        require(array.size() == expectedSize) {
            "3D Tiles JSON array '$name' must contain $expectedSize numbers."
        }
        return array.mapIndexed { index, element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
                "3D Tiles JSON array '$name' item $index must be a number."
            }
            element.asDouble.also { value ->
                require(value.isFinite()) { "3D Tiles JSON array '$name' item $index must be finite." }
            }
        }
    }

    private fun JsonObject.optionalElement(name: String): JsonElement? {
        val element = get(name) ?: return null
        return element.takeUnless { it.isJsonNull }
    }

    private fun <T> JsonArray.mapObjects(transform: (JsonObject) -> T): List<T> {
        return mapIndexed { index, element ->
            require(element.isJsonObject) { "3D Tiles JSON array item $index must be an object." }
            transform(element.asJsonObject)
        }
    }
}
