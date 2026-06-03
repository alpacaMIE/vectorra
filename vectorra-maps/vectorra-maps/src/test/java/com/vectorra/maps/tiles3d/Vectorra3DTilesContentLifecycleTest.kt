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
        assertEquals(Vectorra3DTilesRendererTransformKind.ECEF, renderer.added.single().transform.kind)
        assertEquals(Vectorra3DTilesSpatial.IDENTITY_MATRIX, renderer.added.single().nativeMatrix().toList())
        assertEquals(listOf(1.0, 2.0, 3.0), renderer.added.single().nativeEcefOrigin().toList())
        assertFalse(renderer.added.single().renderUri.startsWith("file:"))
        assertTrue(File(renderer.added.single().renderUri).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(
            mapOf("root" to Vectorra3DTilesRuntimeTileLoadState.LOADED),
            lifecycle.tileLoadStates()
        )
    }

    @Test
    fun rendererTransformSplitsEcefOriginAndPreservesLocalMatrix() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val traversal = traversalResult(
            request(
                tileId = "root",
                content = remoteContent("model.glb"),
                transform = listOf(
                    2.0, 0.0, 0.0, 0.0,
                    0.0, 3.0, 0.0, 0.0,
                    0.0, 0.0, 4.0, 0.0,
                    1_215_107.761, -4_736_682.902, 4_081_926.095, 1.0
                )
            )
        )
        val task = lifecycle.applyTraversal(traversal).loadTasks.single()

        lifecycle.completeRemoteLoad(
            task,
            TileResponse(request = task.request, statusCode = 200, body = byteArrayOf(1, 2, 3))
        )

        val added = renderer.added.single()
        assertEquals(Vectorra3DTilesRendererTransformKind.ECEF, added.transform.kind)
        assertEquals(
            listOf(
                2.0, 0.0, 0.0, 0.0,
                0.0, 3.0, 0.0, 0.0,
                0.0, 0.0, 4.0, 0.0,
                0.0, 0.0, 0.0, 1.0
            ),
            added.nativeMatrix().toList()
        )
        assertEquals(
            listOf(1_215_107.761, -4_736_682.902, 4_081_926.095),
            added.nativeEcefOrigin().toList()
        )
    }

    @Test
    fun loadedRemoteContentCanBeResubmittedAfterRendererRecreation() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val traversal = traversalResult(request("root", remoteContent("model.glb")))
        val task = lifecycle.applyTraversal(traversal).loadTasks.single()
        lifecycle.completeRemoteLoad(
            task,
            TileResponse(request = task.request, statusCode = 200, body = byteArrayOf(1, 2, 3))
        )

        val resubmitted = lifecycle.resubmitLoadedContent()
        val second = lifecycle.applyTraversal(traversal)

        assertEquals(listOf("3d-layer:root"), resubmitted)
        assertEquals(listOf("3d-layer:root", "3d-layer:root"), renderer.added.map { it.nativeContentId })
        assertTrue(second.loadTasks.isEmpty())
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
        assertEquals(Vectorra3DTilesSpatial.IDENTITY_MATRIX, renderer.added.single().nativeMatrix().toList())
        assertEquals(listOf(1.0, 2.0, 3.0), renderer.added.single().nativeEcefOrigin().toList())
        assertEquals(localFile.absolutePath, renderer.added.single().renderUri)
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
    fun remoteB3dmCompletionExtractsInnerGlbAndAppliesRtcCenter() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val traversal = traversalResult(
            request(
                "root",
                VectorraTileset3DContent(
                    uri = "tile.b3dm",
                    resolvedUri = "https://example.test/tile.b3dm",
                    kind = VectorraTileset3DContentKind.B3DM
                )
            )
        )
        val task = lifecycle.applyTraversal(traversal).loadTasks.single()

        val completion = lifecycle.completeRemoteLoad(
            task,
            TileResponse(
                request = task.request,
                statusCode = 200,
                body = Vectorra3DTilesB3dmParserTest.b3dmBytes(
                    featureJson = """{"BATCH_LENGTH":1,"RTC_CENTER":[10.0,20.0,30.0]}""",
                    batchJson = "{}"
                )
            )
        )

        assertEquals(Vectorra3DTilesContentLoadCompletion.ADDED, completion)
        assertEquals(listOf("3d-layer:root"), renderer.added.map { it.nativeContentId })
        assertEquals(VectorraTileset3DContentKind.B3DM, renderer.added.single().contentKind)
        assertEquals(
            Vectorra3DTilesRendererPayloadSource.B3DM_INNER_GLB_CACHE_URI,
            renderer.added.single().payloadSource
        )
        assertTrue(renderer.added.single().renderUri.endsWith(".glb"))
        assertFalse(renderer.added.single().renderUri.startsWith("file:"))
        assertTrue(
            File(renderer.added.single().renderUri)
                .readBytes()
                .contentEquals(Vectorra3DTilesB3dmParserTest.glbBytes())
        )
        assertEquals(
            Vectorra3DTilesSpatial.IDENTITY_MATRIX,
            renderer.added.single().nativeMatrix().toList()
        )
        assertEquals(listOf(11.0, 22.0, 33.0), renderer.added.single().nativeEcefOrigin().toList())
    }

    @Test
    fun localB3dmExtractsInnerGlbAndBypassesRemoteLoad() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val b3dmFile = temporaryFolder.newFile("tile.b3dm")
        b3dmFile.writeBytes(
            Vectorra3DTilesB3dmParserTest.b3dmBytes(
                featureJson = """{"RTC_CENTER":[10.0,20.0,30.0]}""",
                batchJson = ""
            )
        )

        val result = lifecycle.applyTraversal(
            traversalResult(
                request(
                    "root",
                    VectorraTileset3DContent(
                        uri = b3dmFile.name,
                        resolvedUri = b3dmFile.toURI().toString(),
                        kind = VectorraTileset3DContentKind.B3DM
                    )
                )
            )
        )

        assertTrue(result.loadTasks.isEmpty())
        assertTrue(result.failedTileIds.isEmpty())
        assertEquals(listOf("3d-layer:root"), result.addedContentIds)
        assertEquals(VectorraTileset3DContentKind.B3DM, renderer.added.single().contentKind)
        assertEquals(
            Vectorra3DTilesRendererPayloadSource.B3DM_INNER_GLB_CACHE_URI,
            renderer.added.single().payloadSource
        )
        assertTrue(renderer.added.single().renderUri.endsWith(".glb"))
        assertEquals(
            mapOf("root" to Vectorra3DTilesRuntimeTileLoadState.LOADED),
            lifecycle.tileLoadStates()
        )
    }

    @Test
    fun invalidRemoteB3dmFailsWithoutRendererContent() {
        val renderer = RecordingRenderer()
        val lifecycle = lifecycle(renderer)
        val task = lifecycle.applyTraversal(
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
        ).loadTasks.single()

        val completion = lifecycle.completeRemoteLoad(
            task,
            TileResponse(request = task.request, statusCode = 200, body = byteArrayOf(1, 2, 3))
        )

        assertEquals(Vectorra3DTilesContentLoadCompletion.FAILED, completion)
        assertTrue(renderer.added.isEmpty())
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
        transform: List<Double> = Vectorra3DTilesSpatial.translation(1.0, 2.0, 3.0),
        priority: Int = 42
    ): Vectorra3DTilesRequest {
        return Vectorra3DTilesRequest(
            tileId = tileId,
            content = content,
            priority = priority,
            transform = transform
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
