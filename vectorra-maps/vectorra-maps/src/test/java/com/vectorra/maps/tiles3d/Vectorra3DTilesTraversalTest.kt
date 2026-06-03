package com.vectorra.maps.tiles3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Vectorra3DTilesTraversalTest {
    private val traversal = Vectorra3DTilesTraversal()
    private val camera = Vectorra3DTilesCamera(
        positionX = 0.0,
        positionY = 0.0,
        positionZ = 100.0,
        directionX = 0.0,
        directionY = 0.0,
        directionZ = -1.0,
        verticalFovDegrees = 60.0,
        viewportHeightPixels = 1000
    )

    @Test
    fun replaceRefinementSelectsChildrenWhenSseExceedsThreshold() {
        val result = traversal.traverse(
            tileset = tileset(
                root = tile(
                    sphere = sphere(z = 0.0, radius = 10.0),
                    geometricError = 100.0,
                    refine = VectorraTileset3DRefine.REPLACE,
                    content = content("root.glb"),
                    children = listOf(
                        tile(
                            sphere = sphere(z = 0.0, radius = 5.0),
                            geometricError = 0.0,
                            content = content("child.glb")
                        )
                    )
                )
            ),
            camera = camera,
            options = options(maximumScreenSpaceError = 1.0)
        )

        assertEquals(listOf("root/0"), result.selectedTiles.map { it.id })
        assertEquals(listOf("root/0"), result.requests.map { it.tileId })
    }

    @Test
    fun addRefinementKeepsParentAndChildrenWhenSseExceedsThreshold() {
        val result = traversal.traverse(
            tileset = tileset(
                root = tile(
                    sphere = sphere(z = 0.0, radius = 10.0),
                    geometricError = 100.0,
                    refine = VectorraTileset3DRefine.ADD,
                    content = content("root.glb"),
                    children = listOf(
                        tile(
                            sphere = sphere(x = 5.0, z = 0.0, radius = 5.0),
                            geometricError = 0.0,
                            content = content("child.glb")
                        )
                    )
                )
            ),
            camera = camera,
            options = options(maximumScreenSpaceError = 1.0)
        )

        assertEquals(listOf("root", "root/0"), result.selectedTiles.map { it.id })
        assertEquals(listOf("root", "root/0"), result.requests.map { it.tileId })
    }

    @Test
    fun lowSseSelectsParentWithoutChildRequests() {
        val result = traversal.traverse(
            tileset = tileset(
                root = tile(
                    sphere = sphere(z = 0.0, radius = 10.0),
                    geometricError = 1.0,
                    refine = VectorraTileset3DRefine.REPLACE,
                    content = content("root.glb"),
                    children = listOf(
                        tile(
                            sphere = sphere(z = 0.0, radius = 5.0),
                            geometricError = 0.0,
                            content = content("child.glb")
                        )
                    )
                )
            ),
            camera = camera,
            options = options(maximumScreenSpaceError = 1000.0)
        )

        assertEquals(listOf("root"), result.selectedTiles.map { it.id })
        assertEquals(listOf("root"), result.requests.map { it.tileId })
    }

    @Test
    fun frustumAndDistanceFilteringSkipInvisibleTiles() {
        val behindCamera = traversal.traverse(
            tileset = tileset(
                root = tile(
                    sphere = sphere(z = 300.0, radius = 5.0),
                    geometricError = 0.0,
                    content = content("behind.glb")
                )
            ),
            camera = camera,
            options = options()
        )
        val beyondDistance = traversal.traverse(
            tileset = tileset(
                root = tile(
                    sphere = sphere(z = 0.0, radius = 5.0),
                    geometricError = 0.0,
                    content = content("far.glb")
                )
            ),
            camera = camera.copy(maximumDistanceMeters = 10.0),
            options = options()
        )

        assertTrue(behindCamera.selectedTiles.isEmpty())
        assertTrue(beyondDistance.selectedTiles.isEmpty())
    }

    @Test
    fun loadedTilesAreNotRequestedAndStaleLoadedTilesUnload() {
        val result = traversal.traverse(
            tileset = tileset(
                root = tile(
                    sphere = sphere(z = 0.0, radius = 5.0),
                    geometricError = 0.0,
                    content = content("root.glb")
                )
            ),
            camera = camera,
            options = options(),
            tileStates = mapOf(
                "root" to Vectorra3DTilesRuntimeTileLoadState.LOADED,
                "stale" to Vectorra3DTilesRuntimeTileLoadState.LOADED
            )
        )

        assertTrue(result.requests.isEmpty())
        assertEquals(setOf("stale"), result.unloadTileIds)
    }

    @Test
    fun loadedTileBudgetKeepsHighestPriorityTilesAndUnloadsOverflow() {
        val result = traversal.traverse(
            tileset = tileset(
                root = tile(
                    sphere = sphere(z = 0.0, radius = 10.0),
                    geometricError = 100.0,
                    refine = VectorraTileset3DRefine.ADD,
                    content = content("root.glb"),
                    children = listOf(
                        tile(
                            sphere = sphere(x = 0.0, z = 10.0, radius = 3.0),
                            geometricError = 0.0,
                            content = content("near.glb")
                        ),
                        tile(
                            sphere = sphere(x = 20.0, z = 0.0, radius = 3.0),
                            geometricError = 0.0,
                            content = content("far.glb")
                        )
                    )
                )
            ),
            camera = camera,
            options = options(maximumScreenSpaceError = 1.0, maximumLoadedTiles = 1)
        )

        assertEquals(1, result.selectedTiles.count { it.hasRenderableContent })
        assertTrue(result.overBudgetTileIds.isNotEmpty())
        assertTrue(result.unloadTileIds.containsAll(result.overBudgetTileIds))
        assertFalse(result.requests.any { it.tileId in result.overBudgetTileIds })
    }

    @Test
    fun tileTransformsAreComposedAndUsedForVisibility() {
        val result = traversal.traverse(
            tileset = tileset(
                root = tile(
                    sphere = sphere(z = 0.0, radius = 10.0),
                    geometricError = 100.0,
                    refine = VectorraTileset3DRefine.ADD,
                    content = content("root.glb"),
                    transform = Vectorra3DTilesSpatial.translation(0.0, 0.0, -100.0),
                    children = listOf(
                        tile(
                            sphere = sphere(z = 0.0, radius = 5.0),
                            geometricError = 0.0,
                            content = content("child.glb"),
                            transform = Vectorra3DTilesSpatial.translation(10.0, 0.0, 0.0)
                        )
                    )
                )
            ),
            camera = camera,
            options = options(maximumScreenSpaceError = 1.0)
        )

        assertEquals(listOf("root", "root/0"), result.requests.map { it.tileId })
        assertEquals(
            Vectorra3DTilesSpatial.translation(0.0, 0.0, -100.0),
            result.requests.first { it.tileId == "root" }.transform
        )
        assertEquals(
            Vectorra3DTilesSpatial.translation(10.0, 0.0, -100.0),
            result.requests.first { it.tileId == "root/0" }.transform
        )
    }

    @Test
    fun transformedBoundsCanCullTileOutsideMaximumDistance() {
        val result = traversal.traverse(
            tileset = tileset(
                root = tile(
                    sphere = sphere(z = 0.0, radius = 5.0),
                    geometricError = 0.0,
                    content = content("root.glb"),
                    transform = Vectorra3DTilesSpatial.translation(0.0, 0.0, -1_000.0)
                )
            ),
            camera = camera.copy(maximumDistanceMeters = 50.0),
            options = options()
        )

        assertTrue(result.selectedTiles.isEmpty())
        assertTrue(result.requests.isEmpty())
    }

    private fun options(
        maximumScreenSpaceError: Double = 16.0,
        maximumLoadedTiles: Int = 256
    ): Vectorra3DTilesTraversalOptions {
        return Vectorra3DTilesTraversalOptions(
            maximumScreenSpaceError = maximumScreenSpaceError,
            maximumLoadedTiles = maximumLoadedTiles
        )
    }

    private fun tileset(root: VectorraTileset3DTile): VectorraTileset3D {
        return VectorraTileset3D(
            asset = VectorraTileset3DAsset(version = "1.0"),
            geometricError = root.geometricError,
            root = root
        )
    }

    private fun tile(
        sphere: VectorraTileset3DBoundingVolume.Sphere,
        geometricError: Double,
        refine: VectorraTileset3DRefine? = null,
        content: VectorraTileset3DContent? = null,
        children: List<VectorraTileset3DTile> = emptyList(),
        transform: List<Double>? = null
    ): VectorraTileset3DTile {
        return VectorraTileset3DTile(
            boundingVolume = sphere,
            geometricError = geometricError,
            refine = refine,
            transform = transform,
            contents = listOfNotNull(content),
            children = children
        )
    }

    private fun sphere(
        x: Double = 0.0,
        y: Double = 0.0,
        z: Double,
        radius: Double
    ): VectorraTileset3DBoundingVolume.Sphere {
        return VectorraTileset3DBoundingVolume.Sphere(x, y, z, radius)
    }

    private fun content(uri: String): VectorraTileset3DContent {
        return VectorraTileset3DContent(
            uri = uri,
            resolvedUri = "file:///$uri",
            kind = VectorraTileset3DContentKind.GLB
        )
    }
}
