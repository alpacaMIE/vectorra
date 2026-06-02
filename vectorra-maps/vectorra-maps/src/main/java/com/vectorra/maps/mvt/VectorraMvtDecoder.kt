package com.vectorra.maps.mvt

import com.vectorra.maps.VectorraBetaApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

@VectorraBetaApi("0.3.0-beta.1")
object VectorraMvtDecoder {
    fun decode(bytes: ByteArray): VectorraMvtTile {
        val reader = PbfReader(bytes)
        val layers = mutableListOf<VectorraMvtLayer>()
        while (!reader.isAtEnd()) {
            when (val field = reader.readFieldOrNull()) {
                null -> break
                else -> {
                    if (field.number == TILE_LAYER_FIELD && field.wireType == WIRE_LENGTH_DELIMITED) {
                        layers += decodeLayer(reader.readBytes())
                    } else {
                        reader.skip(field.wireType)
                    }
                }
            }
        }
        return VectorraMvtTile(layers)
    }

    private fun decodeLayer(bytes: ByteArray): VectorraMvtLayer {
        val reader = PbfReader(bytes)
        var name = ""
        var version = 1
        var extent = DEFAULT_EXTENT
        val featureBytes = mutableListOf<ByteArray>()
        val keys = mutableListOf<String>()
        val values = mutableListOf<VectorraMvtValue>()

        while (!reader.isAtEnd()) {
            when (val field = reader.readFieldOrNull()) {
                null -> break
                else -> when (field.number) {
                    LAYER_NAME_FIELD -> name = reader.readString()
                    LAYER_FEATURES_FIELD -> featureBytes += reader.readBytes()
                    LAYER_KEYS_FIELD -> keys += reader.readString()
                    LAYER_VALUES_FIELD -> values += decodeValue(reader.readBytes())
                    LAYER_EXTENT_FIELD -> extent = reader.readVarint().toInt()
                    LAYER_VERSION_FIELD -> version = reader.readVarint().toInt()
                    else -> reader.skip(field.wireType)
                }
            }
        }

        val features = featureBytes.mapNotNull { decodeFeature(it, name, keys, values) }
        return VectorraMvtLayer(
            name = name,
            version = version,
            extent = extent.takeIf { it > 0 } ?: DEFAULT_EXTENT,
            features = features
        )
    }

    private fun decodeFeature(
        bytes: ByteArray,
        layerName: String,
        keys: List<String>,
        values: List<VectorraMvtValue>
    ): VectorraMvtFeature? {
        val reader = PbfReader(bytes)
        var id: Long? = null
        var geometryType = GEOMETRY_UNKNOWN
        var tagIndexes = emptyList<Int>()
        var geometryCommands = emptyList<Int>()

        while (!reader.isAtEnd()) {
            when (val field = reader.readFieldOrNull()) {
                null -> break
                else -> when (field.number) {
                    FEATURE_ID_FIELD -> id = reader.readVarint()
                    FEATURE_TAGS_FIELD -> tagIndexes = reader.readPackedVarints().map { it.toInt() }
                    FEATURE_TYPE_FIELD -> geometryType = reader.readVarint().toInt()
                    FEATURE_GEOMETRY_FIELD -> geometryCommands = reader.readPackedVarints().map { it.toInt() }
                    else -> reader.skip(field.wireType)
                }
            }
        }

        val geometry = decodeGeometry(geometryType, geometryCommands) ?: return null
        return VectorraMvtFeature(
            id = id,
            layerName = layerName,
            geometry = geometry,
            properties = propertiesFor(tagIndexes, keys, values)
        )
    }

    private fun decodeValue(bytes: ByteArray): VectorraMvtValue {
        val reader = PbfReader(bytes)
        var value: VectorraMvtValue = VectorraMvtValue.StringValue("")
        while (!reader.isAtEnd()) {
            when (val field = reader.readFieldOrNull()) {
                null -> break
                else -> value = when (field.number) {
                    VALUE_STRING_FIELD -> VectorraMvtValue.StringValue(reader.readString())
                    VALUE_FLOAT_FIELD -> VectorraMvtValue.FloatValue(reader.readFixed32Float())
                    VALUE_DOUBLE_FIELD -> VectorraMvtValue.DoubleValue(reader.readFixed64Double())
                    VALUE_INT_FIELD -> VectorraMvtValue.IntValue(reader.readVarint().toLong())
                    VALUE_UINT_FIELD -> VectorraMvtValue.UIntValue(reader.readVarint().toLong())
                    VALUE_SINT_FIELD -> VectorraMvtValue.SIntValue(reader.readSignedVarint())
                    VALUE_BOOL_FIELD -> VectorraMvtValue.BoolValue(reader.readVarint() != 0L)
                    else -> {
                        reader.skip(field.wireType)
                        value
                    }
                }
            }
        }
        return value
    }

    private fun propertiesFor(
        tagIndexes: List<Int>,
        keys: List<String>,
        values: List<VectorraMvtValue>
    ): Map<String, VectorraMvtValue> {
        val properties = linkedMapOf<String, VectorraMvtValue>()
        tagIndexes.chunked(2).forEach { pair ->
            if (pair.size != 2) return@forEach
            val key = keys.getOrNull(pair[0]) ?: return@forEach
            val value = values.getOrNull(pair[1]) ?: return@forEach
            properties[key] = value
        }
        return properties
    }

    private fun decodeGeometry(type: Int, commands: List<Int>): VectorraMvtGeometry? {
        if (commands.isEmpty()) return null
        var index = 0
        var cursorX = 0
        var cursorY = 0
        val parts = mutableListOf<MutableList<VectorraMvtPoint>>()
        var current: MutableList<VectorraMvtPoint>? = null

        while (index < commands.size) {
            val commandInteger = commands[index++]
            val command = commandInteger and COMMAND_MASK
            val count = commandInteger shr COMMAND_COUNT_SHIFT
            when (command) {
                COMMAND_MOVE_TO -> repeat(count) {
                    if (index + 1 >= commands.size) return@repeat
                    cursorX += zigZagDecode(commands[index++])
                    cursorY += zigZagDecode(commands[index++])
                    current = mutableListOf(VectorraMvtPoint(cursorX, cursorY)).also(parts::add)
                }
                COMMAND_LINE_TO -> repeat(count) {
                    if (index + 1 >= commands.size) return@repeat
                    cursorX += zigZagDecode(commands[index++])
                    cursorY += zigZagDecode(commands[index++])
                    current?.add(VectorraMvtPoint(cursorX, cursorY))
                }
                COMMAND_CLOSE_PATH -> repeat(count) {
                    val ring = current
                    if (ring != null && ring.isNotEmpty() && ring.first() != ring.last()) {
                        ring.add(ring.first())
                    }
                }
                else -> return null
            }
        }

        return when (type) {
            GEOMETRY_POINT -> VectorraMvtGeometry.Point(parts.flatten())
            GEOMETRY_LINE_STRING -> VectorraMvtGeometry.LineString(parts.filter { it.size >= 2 })
            GEOMETRY_POLYGON -> VectorraMvtGeometry.Polygon(parts.filter { it.size >= 4 })
            else -> null
        }
    }

    private fun zigZagDecode(value: Int): Int {
        return (value ushr 1) xor -(value and 1)
    }

    private class PbfReader(private val bytes: ByteArray) {
        private var position = 0

        fun isAtEnd(): Boolean = position >= bytes.size

        fun readFieldOrNull(): Field? {
            if (isAtEnd()) return null
            val tag = readVarint()
            if (tag == 0L) return null
            return Field(number = (tag shr 3).toInt(), wireType = (tag and 0x7).toInt())
        }

        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (shift < 64) {
                val byte = readByte().toLong() and 0xffL
                result = result or ((byte and 0x7fL) shl shift)
                if ((byte and 0x80L) == 0L) return result
                shift += 7
            }
            throw IllegalArgumentException("Malformed protobuf varint.")
        }

        fun readSignedVarint(): Long {
            val value = readVarint()
            return (value ushr 1) xor -(value and 1L)
        }

        fun readPackedVarints(): List<Long> {
            val packed = PbfReader(readBytes())
            val values = mutableListOf<Long>()
            while (!packed.isAtEnd()) {
                values += packed.readVarint()
            }
            return values
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)

        fun readBytes(): ByteArray {
            val size = readVarint().toInt()
            require(size >= 0 && position + size <= bytes.size) { "Invalid protobuf length-delimited field size." }
            val result = bytes.copyOfRange(position, position + size)
            position += size
            return result
        }

        fun readFixed32Float(): Float {
            val data = readFixed(4)
            return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).float
        }

        fun readFixed64Double(): Double {
            val data = readFixed(8)
            return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).double
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> readFixed(8)
                WIRE_LENGTH_DELIMITED -> readBytes()
                WIRE_FIXED32 -> readFixed(4)
                else -> throw IllegalArgumentException("Unsupported protobuf wire type: $wireType")
            }
        }

        private fun readFixed(size: Int): ByteArray {
            require(position + size <= bytes.size) { "Invalid protobuf fixed field size." }
            val result = bytes.copyOfRange(position, position + size)
            position += size
            return result
        }

        private fun readByte(): Byte {
            require(position < bytes.size) { "Unexpected end of protobuf data." }
            return bytes[position++]
        }
    }

    private data class Field(val number: Int, val wireType: Int)

    private const val TILE_LAYER_FIELD = 3
    private const val LAYER_NAME_FIELD = 1
    private const val LAYER_FEATURES_FIELD = 2
    private const val LAYER_KEYS_FIELD = 3
    private const val LAYER_VALUES_FIELD = 4
    private const val LAYER_EXTENT_FIELD = 5
    private const val LAYER_VERSION_FIELD = 15
    private const val FEATURE_ID_FIELD = 1
    private const val FEATURE_TAGS_FIELD = 2
    private const val FEATURE_TYPE_FIELD = 3
    private const val FEATURE_GEOMETRY_FIELD = 4
    private const val VALUE_STRING_FIELD = 1
    private const val VALUE_FLOAT_FIELD = 2
    private const val VALUE_DOUBLE_FIELD = 3
    private const val VALUE_INT_FIELD = 4
    private const val VALUE_UINT_FIELD = 5
    private const val VALUE_SINT_FIELD = 6
    private const val VALUE_BOOL_FIELD = 7
    private const val GEOMETRY_UNKNOWN = 0
    private const val GEOMETRY_POINT = 1
    private const val GEOMETRY_LINE_STRING = 2
    private const val GEOMETRY_POLYGON = 3
    private const val COMMAND_MOVE_TO = 1
    private const val COMMAND_LINE_TO = 2
    private const val COMMAND_CLOSE_PATH = 7
    private const val COMMAND_MASK = 0x7
    private const val COMMAND_COUNT_SHIFT = 3
    private const val DEFAULT_EXTENT = 4096
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5
}
