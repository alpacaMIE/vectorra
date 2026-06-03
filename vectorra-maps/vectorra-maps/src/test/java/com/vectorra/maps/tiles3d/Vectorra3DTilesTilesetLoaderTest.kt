package com.vectorra.maps.tiles3d

import com.vectorra.maps.network.TileCacheStatus
import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResourceType
import com.vectorra.maps.network.TileResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Vectorra3DTilesTilesetLoaderTest {
    @Test
    fun remoteTilesetUses3DTilesRequestAndResolvesContentAgainstTilesetUri() {
        var capturedRequest: TileRequest? = null
        val loader = Vectorra3DTilesTilesetLoader { request ->
            capturedRequest = request
            TileResponse(
                request = request,
                statusCode = 200,
                body = minimalTilesetJson("tiles/root.glb").toByteArray()
            )
        }

        val tileset = loader.load(
            source = Vectorra3DTilesSource(
                id = "city",
                tilesetUri = "https://assets.example.com/tiles/city/tileset.json"
            ),
            headers = mapOf("Authorization" to "Bearer dev")
        )

        val request = requireNotNull(capturedRequest)
        assertEquals("city", request.sourceId)
        assertEquals(TileResourceType.TILES3D, request.resourceType)
        assertEquals("tileset", request.metadata["kind"])
        assertEquals("Bearer dev", request.headers["Authorization"])
        assertEquals(
            "https://assets.example.com/tiles/city/tiles/root.glb",
            tileset.root.content?.resolvedUri
        )
    }

    @Test
    fun localTilesetPathResolvesRelativeContentAgainstFileUri() {
        val root = Files.createTempDirectory("vectorra-tileset-loader").toFile()
        try {
            val tilesetFile = File(root, "tileset.json")
            tilesetFile.writeText(minimalTilesetJson("models/root.glb"))
            val loader = Vectorra3DTilesTilesetLoader { request ->
                TileResponse(request, 500, cacheStatus = TileCacheStatus.MISS)
            }

            val tileset = loader.load(
                Vectorra3DTilesSource(id = "local", tilesetUri = tilesetFile.absolutePath)
            )

            assertEquals(
                File(root, "models/root.glb").toURI().toString(),
                tileset.root.content?.resolvedUri
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun remoteHttpErrorFailsEarly() {
        val loader = Vectorra3DTilesTilesetLoader { request ->
            TileResponse(request, statusCode = 404, errorMessage = "missing tileset")
        }

        loader.load(Vectorra3DTilesSource(id = "bad", tilesetUri = "https://assets.example.com/missing.json"))
    }

    private fun minimalTilesetJson(contentUri: String): String {
        return """
            {
              "asset": { "version": "1.0" },
              "geometricError": 10,
              "root": {
                "boundingVolume": { "sphere": [0, 0, 0, 1] },
                "geometricError": 0,
                "content": { "uri": "$contentUri" }
              }
            }
        """.trimIndent()
    }
}
