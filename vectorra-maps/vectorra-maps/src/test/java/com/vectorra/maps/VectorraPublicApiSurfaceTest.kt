package com.vectorra.maps

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraPublicApiSurfaceTest {
    @Test
    fun add3DTilesModelLayerRemainsDeprecatedExperimentalSmokeApi() {
        assertDeprecatedExperimentalMethod(
            sourceFile = File("src/main/java/com/vectorra/maps/VectorraMap.kt"),
            signature = "fun add3DTilesModelLayer("
        )
        assertDeprecatedExperimentalMethod(
            sourceFile = File("src/main/java/com/vectorra/maps/VectorraMapEngine.kt"),
            signature = "override fun add3DTilesModelLayer("
        )
    }

    @Test
    fun publicApiInventoryKeeps3DTilesModelSmokeOutOfPublishedBaseline() {
        val doc = File("../docs/beta/public-api-surface.md").readText()
        val publishedBaseline = doc.section(
            start = "## Published Baseline SDK Entry Points",
            end = "## Unpublished Development APIs"
        )
        val deprecatedExperimental = doc.section(
            start = "## Deprecated Experimental Entry Point",
            end = "## Not Stable App Contracts"
        )

        assertFalse(publishedBaseline.contains("add3DTilesModelLayer"))
        assertTrue(deprecatedExperimental.contains("VectorraMap.add3DTilesModelLayer(...)"))
        assertTrue(deprecatedExperimental.contains("not the Android 1.0 3D Tiles API"))
    }

    private fun assertDeprecatedExperimentalMethod(sourceFile: File, signature: String) {
        val source = sourceFile.readText()
        val signatureIndex = source.indexOf(signature)
        assertTrue("$signature must exist in ${sourceFile.path}", signatureIndex >= 0)

        val annotations = source.substring(0, signatureIndex).takeLast(520)
        assertTrue(
            "$signature must stay marked @VectorraExperimentalApi in ${sourceFile.path}",
            annotations.contains("@VectorraExperimentalApi(\"0.7.0-beta.1\")")
        )
        assertTrue(
            "$signature must stay marked @Deprecated in ${sourceFile.path}",
            annotations.contains("@Deprecated(")
        )
        assertTrue(
            "$signature must keep a smoke-entry deprecation message in ${sourceFile.path}",
            annotations.contains("model-rendering smoke entry")
        )
    }

    private fun String.section(start: String, end: String): String {
        val startIndex = indexOf(start)
        assertTrue("Missing section start: $start", startIndex >= 0)
        val endIndex = indexOf(end, startIndex + start.length)
        assertTrue("Missing section end: $end", endIndex >= 0)
        return substring(startIndex, endIndex)
    }
}
