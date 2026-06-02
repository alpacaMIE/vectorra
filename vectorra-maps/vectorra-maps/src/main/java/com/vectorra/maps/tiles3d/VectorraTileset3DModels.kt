package com.vectorra.maps.tiles3d

import com.vectorra.maps.VectorraBetaApi
import java.util.Locale

@VectorraBetaApi("0.5.0-beta.1")
data class VectorraTileset3D(
    val asset: VectorraTileset3DAsset,
    val geometricError: Double,
    val root: VectorraTileset3DTile
) {
    init {
        require(geometricError.isFinite() && geometricError >= 0.0) {
            "3D Tiles tileset geometricError must be finite and greater than or equal to 0."
        }
    }

    fun statistics(): VectorraTileset3DStatistics {
        val contentKinds = linkedMapOf<VectorraTileset3DContentKind, Int>()
        var totalTiles = 0
        var contentTileCount = 0
        var maxDepth = 0

        root.walk { tile, depth ->
            totalTiles += 1
            if (tile.contents.isNotEmpty()) {
                contentTileCount += 1
                tile.contents.forEach { content ->
                    contentKinds[content.kind] = (contentKinds[content.kind] ?: 0) + 1
                }
            }
            maxDepth = maxOf(maxDepth, depth)
        }

        return VectorraTileset3DStatistics(
            totalTiles = totalTiles,
            contentTileCount = contentTileCount,
            maxDepth = maxDepth,
            contentKinds = contentKinds
        )
    }
}

@VectorraBetaApi("0.5.0-beta.1")
data class VectorraTileset3DAsset(
    val version: String,
    val tilesetVersion: String? = null,
    val generator: String? = null
) {
    init {
        require(version.isNotBlank()) { "3D Tiles asset.version must not be blank." }
    }
}

@VectorraBetaApi("0.5.0-beta.1")
data class VectorraTileset3DTile(
    val boundingVolume: VectorraTileset3DBoundingVolume,
    val geometricError: Double,
    val refine: VectorraTileset3DRefine? = null,
    val transform: List<Double>? = null,
    val contents: List<VectorraTileset3DContent> = emptyList(),
    val children: List<VectorraTileset3DTile> = emptyList()
) {
    init {
        require(geometricError.isFinite() && geometricError >= 0.0) {
            "3D Tiles tile geometricError must be finite and greater than or equal to 0."
        }
        require(transform == null || transform.size == MATRIX_4_SIZE) {
            "3D Tiles tile transform must contain 16 numbers."
        }
    }

    val content: VectorraTileset3DContent?
        get() = contents.firstOrNull()

    fun walk(visitor: (tile: VectorraTileset3DTile, depth: Int) -> Unit) {
        walk(depth = 0, visitor = visitor)
    }

    private fun walk(depth: Int, visitor: (tile: VectorraTileset3DTile, depth: Int) -> Unit) {
        visitor(this, depth)
        children.forEach { child -> child.walk(depth + 1, visitor) }
    }

    private companion object {
        const val MATRIX_4_SIZE = 16
    }
}

@VectorraBetaApi("0.5.0-beta.1")
data class VectorraTileset3DContent(
    val uri: String,
    val resolvedUri: String,
    val kind: VectorraTileset3DContentKind
) {
    init {
        require(uri.isNotBlank()) { "3D Tiles content uri must not be blank." }
        require(resolvedUri.isNotBlank()) { "3D Tiles content resolvedUri must not be blank." }
    }
}

@VectorraBetaApi("0.5.0-beta.1")
enum class VectorraTileset3DContentKind {
    B3DM,
    GLB,
    GLTF,
    UNKNOWN;

    companion object {
        fun fromUri(uri: String): VectorraTileset3DContentKind {
            val normalized = uri.substringBefore('?')
                .substringBefore('#')
                .lowercase(Locale.US)
            return when {
                normalized.endsWith(".b3dm") -> B3DM
                normalized.endsWith(".glb") -> GLB
                normalized.endsWith(".gltf") -> GLTF
                else -> UNKNOWN
            }
        }
    }
}

@VectorraBetaApi("0.5.0-beta.1")
enum class VectorraTileset3DRefine {
    ADD,
    REPLACE
}

@VectorraBetaApi("0.5.0-beta.1")
sealed class VectorraTileset3DBoundingVolume {
    data class Region(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
        val minimumHeight: Double,
        val maximumHeight: Double
    ) : VectorraTileset3DBoundingVolume()

    data class Box(
        val values: List<Double>
    ) : VectorraTileset3DBoundingVolume() {
        init {
            require(values.size == BOX_VALUE_COUNT) { "3D Tiles box bounding volume must contain 12 numbers." }
        }

        private companion object {
            const val BOX_VALUE_COUNT = 12
        }
    }

    data class Sphere(
        val centerX: Double,
        val centerY: Double,
        val centerZ: Double,
        val radius: Double
    ) : VectorraTileset3DBoundingVolume() {
        init {
            require(radius >= 0.0) { "3D Tiles sphere radius must be greater than or equal to 0." }
        }
    }
}

@VectorraBetaApi("0.5.0-beta.1")
data class VectorraTileset3DStatistics(
    val totalTiles: Int,
    val contentTileCount: Int,
    val maxDepth: Int,
    val contentKinds: Map<VectorraTileset3DContentKind, Int>
)
