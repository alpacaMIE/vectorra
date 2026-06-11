package com.vectorra.maps

import com.vectorra.maps.network.TileProxyServer
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraNativeDiskCacheBypassTest {
    @Test
    fun disablesNativeDiskCacheOnlyForCurrentProxyTemplates() {
        val cacheRoot = Files.createTempDirectory("vectorra-native-cache-bypass").toFile()
        try {
            val proxy = TileProxyServer(cacheDirectory = cacheRoot)
            proxy.use {
                val proxied = it.proxyTemplateFor(
                    sourceId = "source",
                    layerId = "layer",
                    templateUrl = "https://example.test/tiles/{z}/{x}/{y}.png",
                    headers = emptyMap()
                )

                assertTrue(shouldDisableNativeDiskCache(it, proxied))
                assertFalse(
                    shouldDisableNativeDiskCache(
                        it,
                        "https://example.test/tiles/{z}/{x}/{y}.png"
                    )
                )
                assertFalse(
                    shouldDisableNativeDiskCache(
                        it,
                        "file:///tmp/offline/{z}/{x}/{y}.png"
                    )
                )
            }
        } finally {
            cacheRoot.deleteRecursively()
        }
    }
}
