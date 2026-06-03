package com.vectorra.maps.tiles3d

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Vectorra3DTilesB3dmParserTest {
    @Test
    fun parsesHeaderFeatureTableBatchLengthRtcCenterAndInnerGlb() {
        val glb = glbBytes()
        val bytes = b3dmBytes(
            featureJson = """{"BATCH_LENGTH":7,"RTC_CENTER":[1.0,2.0,3.0]}""",
            batchJson = """{"name":{"byteOffset":0}}""",
            glb = glb
        )

        val content = Vectorra3DTilesB3dmParser.parse(bytes)

        assertEquals(1, content.version)
        assertEquals(7, content.batchLength)
        assertEquals(Vectorra3DTilesPoint3D(1.0, 2.0, 3.0), content.rtcCenter)
        assertArrayEquals(glb, content.glb)
    }

    @Test
    fun parsesB3dmWithoutFeatureTable() {
        val glb = glbBytes()

        val content = Vectorra3DTilesB3dmParser.parse(
            b3dmBytes(featureJson = "", batchJson = "", glb = glb)
        )

        assertNull(content.batchLength)
        assertNull(content.rtcCenter)
        assertArrayEquals(glb, content.glb)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidMagic() {
        val bytes = b3dmBytes(featureJson = "{}", batchJson = "", glb = glbBytes())
        bytes[0] = 'x'.code.toByte()

        Vectorra3DTilesB3dmParser.parse(bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedByteLength() {
        val bytes = b3dmBytes(featureJson = "{}", batchJson = "", glb = glbBytes())
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, bytes.size + 1)

        Vectorra3DTilesB3dmParser.parse(bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPayloadWithoutGlbMagic() {
        val glb = glbBytes().also { it[0] = 'x'.code.toByte() }

        Vectorra3DTilesB3dmParser.parse(b3dmBytes(featureJson = "{}", batchJson = "", glb = glb))
    }

    companion object {
        fun glbBytes(): ByteArray {
            return ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("glTF".toByteArray(Charsets.US_ASCII))
                .putInt(2)
                .putInt(12)
                .array()
        }

        fun b3dmBytes(
            featureJson: String,
            batchJson: String,
            glb: ByteArray = glbBytes()
        ): ByteArray {
            val featureBytes = paddedJson(featureJson)
            val batchBytes = paddedJson(batchJson)
            val byteLength = 28 + featureBytes.size + batchBytes.size + glb.size
            return ByteBuffer.allocate(byteLength)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("b3dm".toByteArray(Charsets.US_ASCII))
                .putInt(1)
                .putInt(byteLength)
                .putInt(featureBytes.size)
                .putInt(0)
                .putInt(batchBytes.size)
                .putInt(0)
                .put(featureBytes)
                .put(batchBytes)
                .put(glb)
                .array()
        }

        private fun paddedJson(json: String): ByteArray {
            if (json.isBlank()) {
                return ByteArray(0)
            }
            val bytes = json.toByteArray(Charsets.UTF_8)
            val paddedSize = ((bytes.size + 7) / 8) * 8
            return ByteArray(paddedSize) { index ->
                if (index < bytes.size) bytes[index] else ' '.code.toByte()
            }
        }
    }
}
