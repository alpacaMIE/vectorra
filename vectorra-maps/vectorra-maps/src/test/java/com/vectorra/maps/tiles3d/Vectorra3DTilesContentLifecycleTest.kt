package com.vectorra.maps.tiles3d

import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResourceType
import com.vectorra.maps.network.TileResponse
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Vectorra3DTilesContentLifecycleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun remoteGlbLoadUsesTiles3dRequestAndDeduplicatesWhileLoading() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val traversal = traversalResult(request("root", remoteContent("model.glb")))

        val first = lifecycle.applyTraversal(traversal)
        val second = lifecycle.applyTraversal(traversal)

        assertEquals(1, first.loadTasks.size)
        assertTrue(second.loadTasks.isEmpty())
        assertTrue(renderer.added.isEmpty())
        assertEquals(TileResourceType.TILES3D, first.loadTasks.single().request.resourceType)
        assertEquals("3d-source", first.loadTasks.single().request.sourceId)
        assertEquals("3d-layer", first.loadTasks.single().request.layerId)
        assertEquals("content", first.loadTasks.single().request.metadata["kind"])
        assertEquals("root", first.loadTasks.single().request.metadata["tileId"])
        assertEquals(mapOf("Authorization" to "Bearer token"), first.loadTasks.single().request.headers)
        assertEquals(
            mapOf("root" to Vectorra3DTilesRuntimeTileLoadState.LOADING),
            lifecycle.tileLoadStates()
        )
    }

    @Test
    fun remoteGlbCompletionWritesRendererCacheUriAndSuppressesLoadedRequests() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val traversal = traversalResult(request("root", remoteContent("model.glb")))
        val task = lifecycle.applyTraversal(traversal).loadTasks.single()

        val completion = lifecycle.completeRemoteLoad(
            task,
            TileResponse(request = task.request, statusCode = 200, body = byteArrayOf(1, 2, 3))
        )
        val second = lifecycle.applyTraversal(traversal)

        assertEquals(Vectorra3DTilesContentLoadCompletion.ADDED, completion)
        assertTrue(second.loadTasks.isEmpty())
        assertEquals(listOf("3d-layer:root"), renderer.added.map { it.nativeContentId })
        assertEquals(VectorraTileset3DContentKind.GLB, renderer.added.single().contentKind)
        assertEquals(Vectorra3DTilesRendererPayloadSource.GLB_URI, renderer.added.single().payloadSource)
        assertEquals(Vectorra3DTilesSpatial.translation(1.0, 2.0, 3.0), renderer.added.single().nativeMatrix().toList())
        assertTrue(renderer.added.single().renderUri.startsWith("file:/"))
        assertTrue(File(java.net.URI(renderer.added.single().renderUri)).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(
            mapOf("root" to Vectorra3DTilesRuntimeTileLoadState.LOADED),
            lifecycle.tileLoadStates()
        )
    }

    @Test
    fun localGltfContentBypassesRemoteLoadAndAddsRendererContent() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val localFile = temporaryFolder.newFile("model.gltf")
        val traversal = traversalResult(request("root", localContent(localFile, VectorraTileset3DContentKind.GLTF)))

        val result = lifecycle.applyTraversal(traversal)

        assertTrue(result.loadTasks.isEmpty())
        assertEquals(listOf("3d-layer:root"), result.addedContentIds)
        assertEquals(listOf("3d-layer:root"), renderer.added.map { it.nativeContentId })
        assertEquals(VectorraTileset3DContentKind.GLTF, renderer.added.single().contentKind)
        assertEquals(Vectorra3DTilesRendererPayloadSource.GLTF_URI, renderer.added.single().payloadSource)
        assertEquals(Vectorra3DTilesSpatial.translation(1.0, 2.0, 3.0), renderer.added.single().nativeMatrix().toList())
        assertEquals(localFile.toURI().toString(), renderer.added.single().renderUri)
    }

    @Test
    fun unloadRemovesLoadedRendererContentAndClearsTraversalState() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val localFile = temporaryFolder.newFile("model.glb")
        lifecycle.applyTraversal(
            traversalResult(request("root", localContent(localFile, VectorraTileset3DContentKind.GLB)))
        )

        val result = lifecycle.applyTraversal(
            Vectorra3DTilesTraversalResult(
                selectedTiles = emptyList(),
                requests = emptyList(),
                unloadTileIds = setOf("root"),
                overBudgetTileIds = emptySet()
            )
        )

        assertEquals(listOf("3d-layer:root"), result.removedContentIds)
        assertEquals(listOf("3d-layer:root"), renderer.removed)
        assertTrue(lifecycle.tileLoadStates().isEmpty())
    }

    @Test
    fun remoteCompletionAfterUnloadIsStaleAndDoesNotRender() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val traversal = traversalResult(request("root", remoteContent("model.glb")))
        val task = lifecycle.applyTraversal(traversal).loadTasks.single()
        lifecycle.applyTraversal(
            Vectorra3DTilesTraversalResult(
                selectedTiles = emptyList(),
                requests = emptyList(),
                unloadTileIds = setOf("root"),
                overBudgetTileIds = emptySet()
            )
        )

        val completion = lifecycle.completeRemoteLoad(
            task,
            TileResponse(request = task.request, statusCode = 200, body = byteArrayOf(1))
        )

        assertEquals(Vectorra3DTilesContentLoadCompletion.STALE, completion)
        assertTrue(renderer.added.isEmpty())
        assertTrue(lifecycle.tileLoadStates().isEmpty())
    }

    @Test
    fun b3dmContentFailsUntilDedicatedB3dmTask() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)

        val result = lifecycle.applyTraversal(
            traversalResult(
                request(
                    "root",
                    VectorraTileset3DContent(
                        uri = "tile.b3dm",
                        resolvedUri = "https://example.test/tile.b3dm",
                        kind = VectorraTileset3DContentKind.B3DM
                    )
                )
            )
        )

        assertTrue(result.loadTasks.isEmpty())
        assertTrue(renderer.added.isEmpty())
        assertEquals("b3dm 3D Tiles content is implemented in P1.T6, not P1.T4.", result.failedTileIds["root"])
        assertEquals(
            mapOf("root" to Vectorra3DTilesRuntimeTileLoadState.FAILED),
            lifecycle.tileLoadStates()
        )
    }

    private fun lifecycle(renderer: RecordingRenderer): Vectorra3DTilesContentLifecycle {
        return Vectorra3DTilesContentLifecycle(
            layerId = "3d-layer",
            sourceId = "3d-source",
            headers = mapOf("Authorization" to "Bearer token"),
            contentCacheDirectory = temporaryFolder.newFolder("renderer-content"),
            renderer = renderer
        )
    }

    private fun traversalResult(
        vararg requests: Vectorra3DTilesRequest,
        unloadTileIds: Set<String> = emptySet()
    ): Vectorra3DTilesTraversalResult {
        return Vectorra3DTilesTraversalResult(
            selectedTiles = emptyList(),
            requests = requests.toList(),
            unloadTileIds = unloadTileIds,
            overBudgetTileIds = emptySet()
        )
    }

    private fun request(
        tileId: String,
        content: VectorraTileset3DContent,
        priority: Int = 42
    ): Vectorra3DTilesRequest {
        return Vectorra3DTilesRequest(
            tileId = tileId,
            content = content,
            priority = priority,
            transform = Vectorra3DTilesSpatial.translation(1.0, 2.0, 3.0)
        )
    }

    private fun remoteContent(fileName: String): VectorraTileset3DContent {
        return VectorraTileset3DContent(
            uri = fileName,
            resolvedUri = "https://example.test/tiles/$fileName",
            kind = VectorraTileset3DContentKind.fromUri(fileName)
        )
    }

    private fun localContent(
        file: File,
        kind: VectorraTileset3DContentKind
    ): VectorraTileset3DContent {
        return VectorraTileset3DContent(
            uri = file.name,
            resolvedUri = file.toURI().toString(),
            kind = kind
        )
    }

    private class RecordingRenderer : Vectorra3DTilesContentRenderer {
        val added = mutableListOf<Vectorra3DTilesRendererContentInput>()
        val removed = mutableListOf<String>()

        override fun addContent(input: Vectorra3DTilesRendererContentInput) {
            added += input
        }

        override fun removeContent(nativeContentId: String) {
            removed += nativeContentId
        }
    }
}
