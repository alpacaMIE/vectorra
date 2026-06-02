package com.vectorra.maps.tiles3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraTileset3DParserTest {
    @Test
    fun parsesMinimalTilesetWithB3dmContent() {
        val tileset = VectorraTileset3DParser.parse(
            """
            {
              "asset": { "version": "1.0", "generator": "Vectorra test" },
              "geometricError": 500,
              "root": {
                "boundingVolume": { "region": [0, 0, 1, 1, 0, 100] },
                "geometricError": 250,
                "refine": "REPLACE",
                "content": { "uri": "tiles/root.b3dm" }
              }
            }
            """.trimIndent()
        )

        assertEquals("1.0", tileset.asset.version)
        assertEquals("Vectorra test", tileset.asset.generator)
        assertEquals(500.0, tileset.geometricError, 0.0)
        assertEquals(VectorraTileset3DRefine.REPLACE, tileset.root.refine)
        assertEquals("tiles/root.b3dm", tileset.root.content?.uri)
        assertEquals(VectorraTileset3DContentKind.B3DM, tileset.root.content?.kind)
    }

    @Test
    fun resolvesRelativeContentUrisAgainstTilesetBaseUri() {
        val tileset = VectorraTileset3DParser.parse(
            """
            {
              "asset": { "version": "1.1" },
              "geometricError": 10,
              "root": {
                "boundingVolume": { "sphere": [0, 0, 0, 10] },
                "geometricError": 0,
                "content": { "uri": "models/building.glb?token=dev" }
              }
            }
            """.trimIndent(),
            baseUri = "https://assets.example.com/city/tileset.json"
        )

        assertEquals(
            "https://assets.example.com/city/models/building.glb?token=dev",
            tileset.root.content?.resolvedUri
        )
        assertEquals(VectorraTileset3DContentKind.GLB, tileset.root.content?.kind)
    }

    @Test
    fun parsesBoundingVolumeVariantsAndChildren() {
        val tileset = VectorraTileset3DParser.parse(
            """
            {
              "asset": { "version": "1.0" },
              "geometricError": 100,
              "root": {
                "boundingVolume": { "box": [0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1] },
                "geometricError": 50,
                "children": [
                  {
                    "boundingVolume": { "sphere": [1, 2, 3, 4] },
                    "geometricError": 0,
                    "content": { "uri": "child.gltf" }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertTrue(tileset.root.boundingVolume is VectorraTileset3DBoundingVolume.Box)
        assertTrue(tileset.root.children.first().boundingVolume is VectorraTileset3DBoundingVolume.Sphere)
        assertEquals(VectorraTileset3DContentKind.GLTF, tileset.root.children.first().content?.kind)
    }

    @Test
    fun collectsTilesetStatistics() {
        val tileset = VectorraTileset3DParser.parse(
            """
            {
              "asset": { "version": "1.1" },
              "geometricError": 100,
              "root": {
                "boundingVolume": { "region": [0, 0, 1, 1, 0, 10] },
                "geometricError": 50,
                "contents": [
                  { "uri": "root.b3dm" },
                  { "uri": "root.glb" }
                ],
                "children": [
                  {
                    "boundingVolume": { "region": [0, 0, 0.5, 0.5, 0, 5] },
                    "geometricError": 0
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val statistics = tileset.statistics()

        assertEquals(2, statistics.totalTiles)
        assertEquals(1, statistics.contentTileCount)
        assertEquals(1, statistics.maxDepth)
        assertEquals(1, statistics.contentKinds[VectorraTileset3DContentKind.B3DM])
        assertEquals(1, statistics.contentKinds[VectorraTileset3DContentKind.GLB])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingRootTile() {
        VectorraTileset3DParser.parse(
            """
            {
              "asset": { "version": "1.0" },
              "geometricError": 100
            }
            """.trimIndent()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingAssetVersion() {
        VectorraTileset3DParser.parse(
            """
            {
              "asset": {},
              "geometricError": 100,
              "root": {
                "boundingVolume": { "region": [0, 0, 1, 1, 0, 10] },
                "geometricError": 0
              }
            }
            """.trimIndent()
        )
    }
}
