package com.vectorra.maps.mvt

internal object VectorraMvtTilePyramid {
    fun renderTileIds(
        idealTileIds: Set<VectorraMvtTileId>,
        loadedTileIds: Set<VectorraMvtTileId>,
        minZoom: Int,
        maxZoom: Int
    ): Set<VectorraMvtTileId> {
        require(minZoom in 0..30) { "MVT tile pyramid minZoom must be in 0..30." }
        require(maxZoom in minZoom..30) { "MVT tile pyramid maxZoom must be in minZoom..30." }
        if (idealTileIds.isEmpty() || loadedTileIds.isEmpty()) {
            return emptySet()
        }

        val renderTileIds = linkedSetOf<VectorraMvtTileId>()
        val maxLoadedZoom = loadedTileIds.maxOfOrNull { it.z } ?: minZoom
        val childCoverMaxZoom = minOf(maxZoom, maxLoadedZoom)
        idealTileIds.forEach { idealTileId ->
            when {
                idealTileId in loadedTileIds -> renderTileIds += idealTileId
                else -> {
                    val childCover = loadedDescendantCover(
                        tileId = idealTileId,
                        loadedTileIds = loadedTileIds,
                        maxZoom = childCoverMaxZoom
                    )
                    if (childCover != null) {
                        renderTileIds += childCover
                    } else {
                        loadedParentTile(
                            tileId = idealTileId,
                            loadedTileIds = loadedTileIds,
                            minZoom = minZoom
                        )?.let(renderTileIds::add)
                    }
                }
            }
        }
        return renderTileIds
    }

    private fun loadedDescendantCover(
        tileId: VectorraMvtTileId,
        loadedTileIds: Set<VectorraMvtTileId>,
        maxZoom: Int
    ): Set<VectorraMvtTileId>? {
        if (tileId in loadedTileIds) {
            return linkedSetOf(tileId)
        }
        if (tileId.z >= maxZoom || !loadedTileIds.any { it.isInside(tileId) }) {
            return null
        }

        val cover = linkedSetOf<VectorraMvtTileId>()
        childTileIds(tileId).forEach { childTileId ->
            val childCover = loadedDescendantCover(childTileId, loadedTileIds, maxZoom) ?: return null
            cover += childCover
        }
        return cover
    }

    private fun loadedParentTile(
        tileId: VectorraMvtTileId,
        loadedTileIds: Set<VectorraMvtTileId>,
        minZoom: Int
    ): VectorraMvtTileId? {
        var current = tileId
        while (current.z > minZoom) {
            current = parentTileId(current)
            if (current in loadedTileIds) {
                return current
            }
        }
        return null
    }

    private fun parentTileId(tileId: VectorraMvtTileId): VectorraMvtTileId {
        return VectorraMvtTileId(
            z = tileId.z - 1,
            x = tileId.x / 2,
            y = tileId.y / 2
        )
    }

    private fun childTileIds(tileId: VectorraMvtTileId): List<VectorraMvtTileId> {
        val childZ = tileId.z + 1
        val childX = tileId.x * 2
        val childY = tileId.y * 2
        return listOf(
            VectorraMvtTileId(z = childZ, x = childX, y = childY),
            VectorraMvtTileId(z = childZ, x = childX + 1, y = childY),
            VectorraMvtTileId(z = childZ, x = childX, y = childY + 1),
            VectorraMvtTileId(z = childZ, x = childX + 1, y = childY + 1)
        )
    }

    private fun VectorraMvtTileId.isInside(ancestor: VectorraMvtTileId): Boolean {
        if (z < ancestor.z) {
            return false
        }
        val scale = 1 shl (z - ancestor.z)
        return x / scale == ancestor.x && y / scale == ancestor.y
    }
}
