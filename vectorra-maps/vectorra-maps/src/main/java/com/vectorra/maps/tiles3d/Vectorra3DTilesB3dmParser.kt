package com.vectorra.maps.tiles3d

import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Vectorra3DTilesB3dmContent(
    val version: Int,
    val batchLength: Int?,
    val rtcCenter: Vectorra3DTilesPoint3D?,
    val glb: ByteArray
) {
    init {
        require(version > 0) { "b3dm version must be greater than 0." }
        require(batchLength == null || batchLength >= 0) {
            "b3dm BATCH_LENGTH must be greater than or equal to 0."
        }
        require(glb.isNotEmpty()) { "b3dm inner GLB must not be empty." }
    }
}

internal object Vectorra3DTilesB3dmParser {
    fun parse(bytes: ByteArray): Vectorra3DTilesB3dmContent {
        require(bytes.size >= HEADER_LENGTH) { "b3dm content is shorter than the 28-byte header." }
        require(String(bytes, 0, MAGIC_LENGTH, Charsets.US_ASCII) == MAGIC) {
            "b3dm magic must be 'b3dm'."
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(MAGIC_LENGTH)
        val version = buffer.int
        val byteLength = buffer.int
        val featureTableJsonByteLength = buffer.int
        val featureTableBinaryByteLength = buffer.int
        val batchTableJsonByteLength = buffer.int
        val batchTableBinaryByteLength = buffer.int

        require(version == SUPPORTED_VERSION) { "Unsupported b3dm version: $version." }
        require(byteLength == bytes.size) {
            "b3dm byteLength $byteLength does not match actual size ${bytes.size}."
        }
        require(featureTableJsonByteLength >= 0) { "b3dm feature table JSON length must not be negative." }
        require(featureTableBinaryByteLength >= 0) { "b3dm feature table binary length must not be negative." }
        require(batchTableJsonByteLength >= 0) { "b3dm batch table JSON length must not be negative." }
        require(batchTableBinaryByteLength >= 0) { "b3dm batch table binary length must not be negative." }

        val featureJsonStart = HEADER_LENGTH
        val featureBinaryStart = featureJsonStart + featureTableJsonByteLength
        val batchJsonStart = featureBinaryStart + featureTableBinaryByteLength
        val batchBinaryStart = batchJsonStart + batchTableJsonByteLength
        val glbStart = batchBinaryStart + batchTableBinaryByteLength
        require(glbStart <= bytes.size) {
            "b3dm table lengths exceed byteLength."
        }
        require(bytes.size - glbStart >= GLB_HEADER_LENGTH) {
            "b3dm inner GLB is shorter than the 12-byte GLB header."
        }
        require(String(bytes, glbStart, GLB_MAGIC_LENGTH, Charsets.US_ASCII) == GLB_MAGIC) {
            "b3dm inner payload must start with GLB magic 'glTF'."
        }

        val featureTable = parseFeatureTable(bytes, featureJsonStart, featureTableJsonByteLength)
        return Vectorra3DTilesB3dmContent(
            version = version,
            batchLength = featureTable.batchLength,
            rtcCenter = featureTable.rtcCenter,
            glb = bytes.copyOfRange(glbStart, bytes.size)
        )
    }

    private fun parseFeatureTable(
        bytes: ByteArray,
        start: Int,
        length: Int
    ): FeatureTable {
        if (length == 0) {
            return FeatureTable(batchLength = null, rtcCenter = null)
        }
        val json = String(bytes, start, length, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        if (json.isBlank()) {
            return FeatureTable(batchLength = null, rtcCenter = null)
        }
        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "b3dm feature table JSON must be an object." }
        val featureTable = root.asJsonObject
        val batchLength = featureTable.get("BATCH_LENGTH")?.let { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
                "b3dm BATCH_LENGTH must be a number."
            }
            element.asInt.also { value ->
                require(value >= 0) { "b3dm BATCH_LENGTH must be greater than or equal to 0." }
            }
        }
        val rtcCenter = featureTable.get("RTC_CENTER")?.let { element ->
            require(element.isJsonArray && element.asJsonArray.size() == RTC_CENTER_SIZE) {
                "b3dm RTC_CENTER must contain 3 numbers."
            }
            val values = element.asJsonArray.mapIndexed { index, item ->
                require(item.isJsonPrimitive && item.asJsonPrimitive.isNumber) {
                    "b3dm RTC_CENTER item $index must be a number."
                }
                item.asDouble.also { value ->
                    require(value.isFinite()) { "b3dm RTC_CENTER item $index must be finite." }
                }
            }
            Vectorra3DTilesPoint3D(values[0], values[1], values[2])
        }
        return FeatureTable(batchLength = batchLength, rtcCenter = rtcCenter)
    }

    private data class FeatureTable(
        val batchLength: Int?,
        val rtcCenter: Vectorra3DTilesPoint3D?
    )

    private const val MAGIC = "b3dm"
    private const val MAGIC_LENGTH = 4
    private const val HEADER_LENGTH = 28
    private const val SUPPORTED_VERSION = 1
    private const val GLB_MAGIC = "glTF"
    private const val GLB_MAGIC_LENGTH = 4
    private const val GLB_HEADER_LENGTH = 12
    private const val RTC_CENTER_SIZE = 3
}
