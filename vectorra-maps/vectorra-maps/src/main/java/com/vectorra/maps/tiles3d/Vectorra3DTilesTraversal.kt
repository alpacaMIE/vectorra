package com.vectorra.maps.tiles3d

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

internal data class Vectorra3DTilesRuntimeTile(
    val id: String,
    val tile: VectorraTileset3DTile,
    val depth: Int,
    val parentId: String?,
    val transform: List<Double> = Vectorra3DTilesSpatial.IDENTITY_MATRIX
) {
    val contents: List<VectorraTileset3DContent>
        get() = tile.contents

    val hasRenderableContent: Boolean
        get() = contents.any { it.kind != VectorraTileset3DContentKind.UNKNOWN }
}

internal enum class Vectorra3DTilesRuntimeTileLoadState {
    UNLOADED,
    LOADING,
    LOADED,
    FAILED
}

internal data class Vectorra3DTilesCamera(
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
    val directionX: Double = 0.0,
    val directionY: Double = 0.0,
    val directionZ: Double = -1.0,
    val verticalFovDegrees: Double = 60.0,
    val viewportHeightPixels: Int = 1080,
    val maximumDistanceMeters: Double = Double.POSITIVE_INFINITY
) {
    init {
        require(positionX.isFinite() && positionY.isFinite() && positionZ.isFinite()) {
            "3D Tiles camera position must be finite."
        }
        require(directionX.isFinite() && directionY.isFinite() && directionZ.isFinite()) {
            "3D Tiles camera direction must be finite."
        }
        require(directionLength > 0.0) { "3D Tiles camera direction must not be zero." }
        require(verticalFovDegrees.isFinite() && verticalFovDegrees > 0.0 && verticalFovDegrees < 180.0) {
            "3D Tiles camera verticalFovDegrees must be within 0.0..180.0."
        }
        require(viewportHeightPixels > 0) { "3D Tiles camera viewportHeightPixels must be greater than 0." }
        require(maximumDistanceMeters > 0.0) { "3D Tiles camera maximumDistanceMeters must be greater than 0." }
    }

    val directionLength: Double
        get() = sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ)
}

internal data class Vectorra3DTilesTraversalOptions(
    val maximumScreenSpaceError: Double,
    val maximumLoadedTiles: Int
) {
    init {
        require(maximumScreenSpaceError.isFinite() && maximumScreenSpaceError > 0.0) {
            "3D Tiles traversal maximumScreenSpaceError must be finite and greater than 0."
        }
        require(maximumLoadedTiles > 0) {
            "3D Tiles traversal maximumLoadedTiles must be greater than 0."
        }
    }
}

internal data class Vectorra3DTilesRequest(
    val tileId: String,
    val content: VectorraTileset3DContent,
    val priority: Int,
    val transform: List<Double> = Vectorra3DTilesSpatial.IDENTITY_MATRIX
)

internal data class Vectorra3DTilesTraversalResult(
    val selectedTiles: List<Vectorra3DTilesRuntimeTile>,
    val requests: List<Vectorra3DTilesRequest>,
    val unloadTileIds: Set<String>,
    val overBudgetTileIds: Set<String>
)

internal class Vectorra3DTilesTraversal {
    fun traverse(
        tileset: VectorraTileset3D,
        camera: Vectorra3DTilesCamera,
        options: Vectorra3DTilesTraversalOptions,
        tileStates: Map<String, Vectorra3DTilesRuntimeTileLoadState> = emptyMap()
    ): Vectorra3DTilesTraversalResult {
        val selected = mutableListOf<Vectorra3DTilesRuntimeTile>()
        selectTile(
            runtimeTile = Vectorra3DTilesRuntimeTile(
                id = ROOT_TILE_ID,
                tile = tileset.root,
                depth = 0,
                parentId = null,
                transform = Vectorra3DTilesSpatial.composeTransform(
                    parentTransform = Vectorra3DTilesSpatial.IDENTITY_MATRIX,
                    tileTransform = tileset.root.transform
                )
            ),
            camera = camera,
            options = options,
            selected = selected
        )

        val budgeted = selected
            .filter { it.hasRenderableContent }
            .sortedWith(
                compareByDescending<Vectorra3DTilesRuntimeTile> {
                    requestPriority(it, camera)
                }.thenBy { it.depth }
            )
            .take(options.maximumLoadedTiles)
        val budgetedIds = budgeted.mapTo(linkedSetOf()) { it.id }
        val overBudgetIds = selected
            .filter { it.hasRenderableContent && it.id !in budgetedIds }
            .mapTo(linkedSetOf()) { it.id }
        val selectedIds = selected.mapTo(linkedSetOf()) { it.id }
        val loadedIds = tileStates
            .filterValues { it == Vectorra3DTilesRuntimeTileLoadState.LOADED }
            .keys
        val unloadIds = (loadedIds - selectedIds) + overBudgetIds
        val requests = budgeted.flatMap { runtimeTile ->
            val state = tileStates[runtimeTile.id] ?: Vectorra3DTilesRuntimeTileLoadState.UNLOADED
            if (state == Vectorra3DTilesRuntimeTileLoadState.UNLOADED) {
                runtimeTile.contents
                    .filter { it.kind != VectorraTileset3DContentKind.UNKNOWN }
                    .map { content ->
                        Vectorra3DTilesRequest(
                            tileId = runtimeTile.id,
                            content = content,
                            priority = requestPriority(runtimeTile, camera),
                            transform = runtimeTile.transform
                        )
                    }
            } else {
                emptyList()
            }
        }

        return Vectorra3DTilesTraversalResult(
            selectedTiles = selected.filter { it.id in budgetedIds || !it.hasRenderableContent },
            requests = requests,
            unloadTileIds = unloadIds,
            overBudgetTileIds = overBudgetIds
        )
    }

    private fun selectTile(
        runtimeTile: Vectorra3DTilesRuntimeTile,
        camera: Vectorra3DTilesCamera,
        options: Vectorra3DTilesTraversalOptions,
        selected: MutableList<Vectorra3DTilesRuntimeTile>
    ) {
        if (!isVisible(runtimeTile.tile.boundingVolume, runtimeTile.transform, camera)) {
            return
        }

        val children = runtimeTile.tile.children.mapIndexed { index, child ->
            Vectorra3DTilesRuntimeTile(
                id = "${runtimeTile.id}/$index",
                tile = child,
                depth = runtimeTile.depth + 1,
                parentId = runtimeTile.id,
                transform = Vectorra3DTilesSpatial.composeTransform(
                    parentTransform = runtimeTile.transform,
                    tileTransform = child.transform
                )
            )
        }
        val needsRefinement = runtimeTile.tile.geometricError > 0.0 &&
            screenSpaceError(runtimeTile, camera) > options.maximumScreenSpaceError &&
            children.isNotEmpty()
        if (!needsRefinement) {
            selected += runtimeTile
            return
        }

        when (runtimeTile.tile.refine ?: VectorraTileset3DRefine.REPLACE) {
            VectorraTileset3DRefine.ADD -> {
                selected += runtimeTile
                children.forEach { child ->
                    selectTile(child, camera, options, selected)
                }
            }
            VectorraTileset3DRefine.REPLACE -> {
                val before = selected.size
                children.forEach { child ->
                    selectTile(child, camera, options, selected)
                }
                if (selected.size == before) {
                    selected += runtimeTile
                }
            }
        }
    }

    private fun screenSpaceError(tile: Vectorra3DTilesRuntimeTile, camera: Vectorra3DTilesCamera): Double {
        val distance = max(EPSILON_DISTANCE, distanceTo(tile.tile.boundingVolume, tile.transform, camera))
        val fovRadians = Math.toRadians(camera.verticalFovDegrees)
        return tile.tile.geometricError * camera.viewportHeightPixels / (2.0 * distance * tan(fovRadians / 2.0))
    }

    private fun requestPriority(tile: Vectorra3DTilesRuntimeTile, camera: Vectorra3DTilesCamera): Int {
        val sse = screenSpaceError(tile, camera)
        val distanceScore = max(
            0.0,
            1_000_000.0 - distanceTo(tile.tile.boundingVolume, tile.transform, camera)
        ) / 1_000.0
        return (sse * 100.0 + distanceScore + tile.depth).toInt()
    }

    private fun isVisible(
        volume: VectorraTileset3DBoundingVolume,
        transform: List<Double>,
        camera: Vectorra3DTilesCamera
    ): Boolean {
        val sphere = Vectorra3DTilesSpatial.boundingSphere(volume, transform)
        val center = sphere.center
        val radius = sphere.radius
        val toTileX = center.x - camera.positionX
        val toTileY = center.y - camera.positionY
        val toTileZ = center.z - camera.positionZ
        val distance = sqrt(toTileX * toTileX + toTileY * toTileY + toTileZ * toTileZ)
        if (distance - radius > camera.maximumDistanceMeters) {
            return false
        }
        if (distance <= radius) {
            return true
        }

        val dot = toTileX * camera.directionX + toTileY * camera.directionY + toTileZ * camera.directionZ
        val denominator = distance * camera.directionLength
        if (denominator <= 0.0) {
            return false
        }
        val angle = acos((dot / denominator).coerceIn(-1.0, 1.0))
        val angularRadius = acos((distance / (distance + radius)).coerceIn(-1.0, 1.0))
        return angle <= Math.toRadians(camera.verticalFovDegrees) / 2.0 + angularRadius
    }

    private fun distanceTo(
        volume: VectorraTileset3DBoundingVolume,
        transform: List<Double>,
        camera: Vectorra3DTilesCamera
    ): Double {
        val sphere = Vectorra3DTilesSpatial.boundingSphere(volume, transform)
        val center = sphere.center
        val dx = center.x - camera.positionX
        val dy = center.y - camera.positionY
        val dz = center.z - camera.positionZ
        return max(0.0, sqrt(dx * dx + dy * dy + dz * dz) - sphere.radius)
    }

    private companion object {
        const val ROOT_TILE_ID = "root"
        const val EPSILON_DISTANCE = 0.001
    }
}
