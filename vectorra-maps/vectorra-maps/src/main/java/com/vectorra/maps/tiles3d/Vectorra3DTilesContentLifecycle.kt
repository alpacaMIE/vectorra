package com.vectorra.maps.tiles3d

import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResourceType
import com.vectorra.maps.network.TileResponse
import java.io.File
import java.io.IOException
import java.net.URI

internal interface Vectorra3DTilesContentRenderer {
    fun addContent(input: Vectorra3DTilesRendererContentInput)
    fun removeContent(nativeContentId: String)
}

internal enum class Vectorra3DTilesContentLifecycleState {
    LOADING,
    LOADED,
    FAILED
}

internal data class Vectorra3DTilesContentLoadTask(
    val layerId: String,
    val sourceId: String,
    val tileId: String,
    val content: VectorraTileset3DContent,
    val request: TileRequest,
    val priority: Int,
    val generation: Long,
    val transform: List<Double>
)

internal data class Vectorra3DTilesContentLifecycleResult(
    val loadTasks: List<Vectorra3DTilesContentLoadTask> = emptyList(),
    val addedContentIds: List<String> = emptyList(),
    val removedContentIds: List<String> = emptyList(),
    val failedTileIds: Map<String, String> = emptyMap()
)

internal enum class Vectorra3DTilesContentLoadCompletion {
    ADDED,
    FAILED,
    STALE
}

internal class Vectorra3DTilesContentLifecycle(
    private val layerId: String,
    private val sourceId: String,
    private val headers: Map<String, String>,
    private val contentCacheDirectory: File,
    private val renderer: Vectorra3DTilesContentRenderer
) {
    private val entries = linkedMapOf<String, Entry>()

    init {
        require(layerId.isNotBlank()) { "3D Tiles content lifecycle layerId must not be blank." }
        require(sourceId.isNotBlank()) { "3D Tiles content lifecycle sourceId must not be blank." }
    }

    fun tileLoadStates(): Map<String, Vectorra3DTilesRuntimeTileLoadState> {
        return entries.mapNotNull { (tileId, entry) ->
            when (entry.state) {
                Vectorra3DTilesContentLifecycleState.LOADING ->
                    tileId to Vectorra3DTilesRuntimeTileLoadState.LOADING
                Vectorra3DTilesContentLifecycleState.LOADED ->
                    tileId to Vectorra3DTilesRuntimeTileLoadState.LOADED
                Vectorra3DTilesContentLifecycleState.FAILED ->
                    tileId to Vectorra3DTilesRuntimeTileLoadState.FAILED
            }
        }.toMap()
    }

    fun applyTraversal(result: Vectorra3DTilesTraversalResult): Vectorra3DTilesContentLifecycleResult {
        val removed = mutableListOf<String>()
        result.unloadTileIds.forEach { tileId ->
            unload(tileId)?.let { removed += it }
        }

        val loadTasks = mutableListOf<Vectorra3DTilesContentLoadTask>()
        val added = mutableListOf<String>()
        val failures = linkedMapOf<String, String>()
        result.requests.forEach { request ->
            val current = entries[request.tileId]
            if (current?.state == Vectorra3DTilesContentLifecycleState.LOADING ||
                current?.state == Vectorra3DTilesContentLifecycleState.LOADED
            ) {
                return@forEach
            }

            val unsupportedReason = unsupportedReason(request.content)
            if (unsupportedReason != null) {
                val generation = nextGeneration(request.tileId)
                entries[request.tileId] = Entry(
                    state = Vectorra3DTilesContentLifecycleState.FAILED,
                    generation = generation,
                    nativeContentId = null,
                    failureReason = unsupportedReason
                )
                failures[request.tileId] = unsupportedReason
                return@forEach
            }

            val generation = nextGeneration(request.tileId)
            val uri = parseUri(request.content.resolvedUri)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    entries[request.tileId] = Entry(
                        state = Vectorra3DTilesContentLifecycleState.LOADING,
                        generation = generation
                    )
                    loadTasks += Vectorra3DTilesContentLoadTask(
                        layerId = layerId,
                        sourceId = sourceId,
                        tileId = request.tileId,
                        content = request.content,
                        request = TileRequest(
                            sourceId = sourceId,
                            layerId = layerId,
                            url = uri.toString(),
                            headers = headers,
                            resourceType = TileResourceType.TILES3D,
                            metadata = mapOf(
                                "kind" to "content",
                                "tileId" to request.tileId,
                                "contentUri" to request.content.uri
                            )
                        ),
                        priority = request.priority,
                        generation = generation,
                        transform = request.transform
                    )
                }
                "file" -> {
                    val input = rendererInput(
                        tileId = request.tileId,
                        content = request.content,
                        renderUri = uri.toString(),
                        transform = request.transform
                    )
                    renderer.addContent(input)
                    entries[request.tileId] = Entry(
                        state = Vectorra3DTilesContentLifecycleState.LOADED,
                        generation = generation,
                        nativeContentId = input.nativeContentId
                    )
                    added += input.nativeContentId
                }
                else -> {
                    val reason = "Unsupported 3D Tiles content URI scheme: ${uri.scheme}"
                    entries[request.tileId] = Entry(
                        state = Vectorra3DTilesContentLifecycleState.FAILED,
                        generation = generation,
                        failureReason = reason
                    )
                    failures[request.tileId] = reason
                }
            }
        }

        return Vectorra3DTilesContentLifecycleResult(
            loadTasks = loadTasks,
            addedContentIds = added,
            removedContentIds = removed,
            failedTileIds = failures
        )
    }

    fun completeRemoteLoad(
        task: Vectorra3DTilesContentLoadTask,
        response: TileResponse
    ): Vectorra3DTilesContentLoadCompletion {
        val current = entries[task.tileId]
        if (current == null ||
            current.generation != task.generation ||
            current.state != Vectorra3DTilesContentLifecycleState.LOADING
        ) {
            return Vectorra3DTilesContentLoadCompletion.STALE
        }
        if (response.statusCode !in 200..299) {
            entries[task.tileId] = current.copy(
                state = Vectorra3DTilesContentLifecycleState.FAILED,
                failureReason = response.errorMessage
                    ?: "3D Tiles content request failed with HTTP ${response.statusCode}."
            )
            return Vectorra3DTilesContentLoadCompletion.FAILED
        }

        val renderFile = writeRendererContent(task, response.body)
        val input = rendererInput(
            tileId = task.tileId,
            content = task.content,
            renderUri = renderFile.toURI().toString(),
            transform = task.transform
        )
        renderer.addContent(input)
        entries[task.tileId] = current.copy(
            state = Vectorra3DTilesContentLifecycleState.LOADED,
            nativeContentId = input.nativeContentId,
            failureReason = null
        )
        return Vectorra3DTilesContentLoadCompletion.ADDED
    }

    fun cancelAll(): List<String> {
        val removed = entries.keys.toList().mapNotNull { unload(it) }
        entries.clear()
        return removed
    }

    private fun unload(tileId: String): String? {
        val current = entries.remove(tileId) ?: return null
        current.nativeContentId?.let { renderer.removeContent(it) }
        return current.nativeContentId
    }

    private fun rendererInput(
        tileId: String,
        content: VectorraTileset3DContent,
        renderUri: String,
        transform: List<Double>
    ): Vectorra3DTilesRendererContentInput {
        return Vectorra3DTilesRendererContentInput(
            layerId = layerId,
            tileId = tileId,
            contentUri = content.resolvedUri,
            contentKind = content.kind,
            renderUri = renderUri,
            payloadSource = Vectorra3DTilesRendererContentInput.expectedPayloadSource(content.kind),
            transform = Vectorra3DTilesRendererTransform.Matrix(transform)
        )
    }

    private fun writeRendererContent(
        task: Vectorra3DTilesContentLoadTask,
        body: ByteArray
    ): File {
        if (!contentCacheDirectory.exists() && !contentCacheDirectory.mkdirs()) {
            throw IOException("Unable to create 3D Tiles content cache directory: $contentCacheDirectory")
        }
        val file = File(
            contentCacheDirectory,
            "${safeFilePart(task.layerId)}-${safeFilePart(task.tileId)}-${safeFilePart(task.content.uri)}" +
                extension(task.content.kind)
        )
        file.writeBytes(body)
        return file
    }

    private fun nextGeneration(tileId: String): Long {
        return (entries[tileId]?.generation ?: 0L) + 1L
    }

    private fun unsupportedReason(content: VectorraTileset3DContent): String? {
        return when (content.kind) {
            VectorraTileset3DContentKind.GLB,
            VectorraTileset3DContentKind.GLTF -> null
            VectorraTileset3DContentKind.B3DM ->
                "b3dm 3D Tiles content is implemented in P1.T6, not P1.T4."
            VectorraTileset3DContentKind.UNKNOWN ->
                "Unknown 3D Tiles content cannot be rendered."
        }
    }

    private fun parseUri(value: String): URI {
        val parsed = URI(value.trim())
        return if (parsed.scheme == null) {
            File(value).toURI()
        } else {
            parsed
        }
    }

    private fun extension(kind: VectorraTileset3DContentKind): String {
        return when (kind) {
            VectorraTileset3DContentKind.GLB -> ".glb"
            VectorraTileset3DContentKind.GLTF -> ".gltf"
            VectorraTileset3DContentKind.B3DM,
            VectorraTileset3DContentKind.UNKNOWN -> error("Unsupported renderer content kind: $kind")
        }
    }

    private fun safeFilePart(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "content" }
    }

    private data class Entry(
        val state: Vectorra3DTilesContentLifecycleState,
        val generation: Long,
        val nativeContentId: String? = null,
        val failureReason: String? = null
    )
}
