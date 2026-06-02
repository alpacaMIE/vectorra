package com.vectorra.maps.mvt

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMvtDecoderTest {
    @Test
    fun decodesPointFeatureWithProperties() {
        val tile = VectorraMvtDecoder.decode(
            tile(
                layer(
                    name = "places",
                    keys = listOf("name", "rank"),
                    values = listOf(stringValue("Peak"), intValue(3)),
                    features = listOf(
                        feature(
                            id = 7,
                            type = 1,
                            tags = listOf(0, 0, 1, 1),
                            geometry = listOf(command(1, 1), zigZag(10), zigZag(20))
                        )
                    )
                )
            )
        )

        val layer = tile.layers.single()
        val feature = layer.features.single()
        assertEquals("places", layer.name)
        assertEquals(4096, layer.extent)
        assertEquals(7L, feature.id)
        assertEquals(VectorraMvtValue.StringValue("Peak"), feature.properties["name"])
        assertEquals(VectorraMvtValue.IntValue(3), feature.properties["rank"])
        assertEquals(VectorraMvtGeometry.Point(listOf(VectorraMvtPoint(10, 20))), feature.geometry)
    }

    @Test
    fun decodesLineStringDeltas() {
        val tile = VectorraMvtDecoder.decode(
            tile(
                layer(
                    name = "roads",
                    features = listOf(
                        feature(
                            type = 2,
                            geometry = listOf(
                                command(1, 1), zigZag(1), zigZag(1),
                                command(2, 2), zigZag(2), zigZag(0), zigZag(0), zigZag(3)
                            )
                        )
                    )
                )
            )
        )

        val geometry = tile.layers.single().features.single().geometry
        assertEquals(
            VectorraMvtGeometry.LineString(
                listOf(
                    listOf(
                        VectorraMvtPoint(1, 1),
                        VectorraMvtPoint(3, 1),
                        VectorraMvtPoint(3, 4)
                    )
                )
            ),
            geometry
        )
    }

    @Test
    fun decodesPolygonAndClosesRing() {
        val tile = VectorraMvtDecoder.decode(
            tile(
                layer(
                    name = "landuse",
                    features = listOf(
                        feature(
                            type = 3,
                            geometry = listOf(
                                command(1, 1), zigZag(0), zigZag(0),
                                command(2, 3), zigZag(10), zigZag(0), zigZag(0), zigZag(10), zigZag(-10), zigZag(0),
                                command(7, 1)
                            )
                        )
                    )
                )
            )
        )

        val polygon = tile.layers.single().features.single().geometry as VectorraMvtGeometry.Polygon
        assertEquals(VectorraMvtPoint(0, 0), polygon.rings.single().first())
        assertEquals(VectorraMvtPoint(0, 0), polygon.rings.single().last())
        assertEquals(5, polygon.rings.single().size)
    }

    private fun tile(vararg layers: ByteArray): ByteArray {
        return message {
            layers.forEach { bytes(3, it) }
        }
    }

    private fun layer(
        name: String,
        keys: List<String> = emptyList(),
        values: List<ByteArray> = emptyList(),
        features: List<ByteArray> = emptyList()
    ): ByteArray {
        return message {
            string(1, name)
            features.forEach { bytes(2, it) }
            keys.forEach { string(3, it) }
            values.forEach { bytes(4, it) }
            uint(5, 4096)
            uint(15, 2)
        }
    }

    private fun feature(
        id: Long? = null,
        type: Int,
        tags: List<Int> = emptyList(),
        geometry: List<Int>
    ): ByteArray {
        return message {
            if (id != null) uint(1, id)
            packed(2, tags)
            uint(3, type.toLong())
            packed(4, geometry)
        }
    }

    private fun stringValue(value: String): ByteArray = message { string(1, value) }

    private fun intValue(value: Long): ByteArray = message { uint(4, value) }

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun message(block: Encoder.() -> Unit): ByteArray {
        return Encoder().apply(block).toByteArray()
    }

    private class Encoder {
        private val output = ByteArrayOutputStream()

        fun uint(field: Int, value: Long) {
            tag(field, 0)
            varint(value)
        }

        fun string(field: Int, value: String) {
            bytes(field, value.toByteArray(Charsets.UTF_8))
        }

        fun bytes(field: Int, value: ByteArray) {
            tag(field, 2)
            varint(value.size.toLong())
            output.write(value)
        }

        fun packed(field: Int, values: List<Int>) {
            if (values.isEmpty()) return
            val packed = Encoder()
            values.forEach { packed.varint(it.toLong()) }
            bytes(field, packed.toByteArray())
        }

        fun toByteArray(): ByteArray = output.toByteArray()

        private fun tag(field: Int, wireType: Int) {
            varint(((field shl 3) or wireType).toLong())
        }

        fun varint(value: Long) {
            var remaining = value
            while (remaining >= 0x80L) {
                output.write(((remaining and 0x7fL) or 0x80L).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
    }
}
