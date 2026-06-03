package com.vectorra.maps.offline

import com.vectorra.maps.network.TileScheme
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraOfflineRasterSourceTest {
    @Test
    fun createsDefaultDirectorySourceForExtractedZipTiles() {
        val root = Files.createTempDirectory(VectorraOfflineRasterSource.DEFAULT_DIRECTORY_NAME).toFile()
        try {
            val source = VectorraOfflineRasterSource.directory(root)

            assertEquals(VectorraOfflineRasterSource.DEFAULT_SOURCE_ID, source.id)
            assertEquals(VectorraOfflineRasterSource.DEFAULT_TILE_SIZE, source.tileSize)
            assertEquals(VectorraOfflineRasterSource.DEFAULT_MIN_ZOOM, source.minZoom)
            assertEquals(VectorraOfflineRasterSource.DEFAULT_MAX_ZOOM, source.maxZoom)
            assertEquals(TileScheme.XYZ, source.scheme)
            assertTrue(source.templateUrl.startsWith(root.absoluteFile.toURI().toString().trimEnd('/')))
            assertTrue(source.templateUrl.endsWith("/{z}/{x}/{y}.jpg"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun supportsCustomExtensionZoomRangeAndTileSize() {
        val root = Files.createTempDirectory("rocky-offline-custom").toFile()
        try {
            val source = VectorraOfflineRasterSource.directory(
                rootDirectory = root,
                id = "offline-preview",
                tileExtension = "png",
                minZoom = 2,
                maxZoom = 9,
                tileSize = 512
            )

            assertEquals("offline-preview", source.id)
            assertEquals(512, source.tileSize)
            assertEquals(2, source.minZoom)
            assertEquals(9, source.maxZoom)
            assertTrue(source.templateUrl.endsWith("/{z}/{x}/{y}.png"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingRootDirectory() {
        val root = Files.createTempDirectory("rocky-offline-missing").toFile()
        root.deleteRecursively()
        VectorraOfflineRasterSource.directory(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExtensionWithDot() {
        val root = Files.createTempDirectory("rocky-offline-extension").toFile()
        try {
            VectorraOfflineRasterSource.directory(root, tileExtension = ".jpg")
        } finally {
            root.deleteRecursively()
        }
    }
}
