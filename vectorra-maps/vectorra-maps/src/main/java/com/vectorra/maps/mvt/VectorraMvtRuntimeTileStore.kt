package com.vectorra.maps.mvt

import com.vectorra.maps.query.VectorraAnnotationFeature
import com.vectorra.maps.vector.VectorraVectorTileLayer

internal data class VectorraMvtRuntimeTile(
    val tileId: VectorraMvtTileId,
    val nativeTileHandle: String,
    val queryFeatures: List<VectorraMvtDecodedFeature>,
    val rendered: Boolean
)

internal class VectorraMvtRuntimeTileStore(
    private val sourceId: String,
    private val layer: VectorraVectorTileLayer,
    private val nativeRenderer: VectorraMvtNativeRenderer
) {
    private val loadedTiles = linkedMapOf<VectorraMvtTileId, VectorraMvtRuntimeTile>()
    private val activeNativeTileHandles = linkedMapOf<VectorraMvtTileId, String>()

    init {
        require(sourceId.isNotBlank()) { "MVT runtime sourceId must not be blank." }
        require(layer.sourceId == sourceId) {
            "MVT runtime layer sourceId must match source id."
        }
    }

    fun loadedTileIds(): Set<VectorraMvtTileId> = loadedTiles.keys.toSet()

    fun nativeTileHandles(): Set<String> = activeNativeTileHandles.values.toSet()

    fun queryFeatures(): List<VectorraMvtDecodedFeature> {
        return activeNativeTileHandles.keys.flatMap { tileId ->
            loadedTiles[tileId]?.queryFeatures ?: emptyList()
        }
    }

    fun queryHitFeatures(): List<VectorraAnnotationFeature> {
        return queryFeatures().map { feature ->
            VectorraAnnotationFeature(
                id = feature.id,
                layerId = layer.id,
                geometry = feature.geometry,
                properties = feature.properties + mapOf("source-layer" to feature.layerName),
                sourceId = sourceId,
                radiusPixels = layer.hitRadiusPixels(),
                visible = layer.visible,
                opacity = layer.layerOpacity(),
                minZoom = layer.minZoom.toDouble(),
                maxZoom = layer.maxZoom.toDouble()
            )
        }
    }

    fun putTile(
        tileId: VectorraMvtTileId,
        tileBytes: ByteArray,
        renderNow: Boolean = true
    ): VectorraMvtRuntimeTile {
        val previousTile = loadedTiles.remove(tileId)
        val submittedTile = nativeRenderer.submitTile(
            VectorraMvtNativeTileRequest(
                sourceId = sourceId,
                layerId = layer.id,
                sourceLayer = layer.sourceLayer,
                tileId = tileId,
                style = layer.toRenderStyle(),
                tileBytes = tileBytes,
                renderNow = renderNow
            )
        )
        if (previousTile != null && previousTile.nativeTileHandle != submittedTile.nativeTileHandle) {
            activeNativeTileHandles.remove(tileId)
            nativeRenderer.removeTile(previousTile.nativeTileHandle)
        }
        val runtimeTile = VectorraMvtRuntimeTile(
            tileId = tileId,
            nativeTileHandle = submittedTile.nativeTileHandle,
            queryFeatures = submittedTile.queryFeatures,
            rendered = renderNow
        )
        loadedTiles[tileId] = runtimeTile
        if (renderNow) {
            activeNativeTileHandles[tileId] = submittedTile.nativeTileHandle
        } else {
            activeNativeTileHandles.remove(tileId)
        }
        return runtimeTile
    }

    fun setRenderedTileIds(tileIds: Set<VectorraMvtTileId>) {
        require(tileIds.all(loadedTiles::containsKey)) {
            "MVT render set can only include loaded tiles."
        }
        tileIds.forEach { tileId ->
            val runtimeTile = loadedTiles.getValue(tileId)
            if (tileId !in activeNativeTileHandles) {
                nativeRenderer.setTileRendered(runtimeTile.nativeTileHandle, true)
                activeNativeTileHandles[tileId] = runtimeTile.nativeTileHandle
                loadedTiles[tileId] = runtimeTile.copy(rendered = true)
            }
        }
        (activeNativeTileHandles.keys - tileIds).forEach(::deactivateTile)
    }

    fun trimLoadedTiles(maxLoadedTiles: Int, retainTileIds: Set<VectorraMvtTileId> = emptySet()) {
        require(maxLoadedTiles > 0) { "MVT decoded tile cache size must be greater than 0." }
        while (loadedTiles.size > maxLoadedTiles) {
            val tileId = loadedTiles.keys.firstOrNull {
                it !in retainTileIds && it !in activeNativeTileHandles
            } ?: return
            removeTile(tileId)
        }
    }

    fun removeTile(tileId: VectorraMvtTileId): VectorraMvtRuntimeTile? {
        val removed = loadedTiles.remove(tileId) ?: return null
        activeNativeTileHandles.remove(tileId)
        nativeRenderer.removeTile(removed.nativeTileHandle)
        return removed.copy(rendered = false)
    }

    fun resubmitLoadedTiles() {
        val activeTileIds = activeNativeTileHandles.keys.toList()
        activeNativeTileHandles.clear()
        activeTileIds.forEach { tileId ->
            loadedTiles[tileId]?.let { runtimeTile ->
                nativeRenderer.setTileRendered(runtimeTile.nativeTileHandle, true)
                activeNativeTileHandles[tileId] = runtimeTile.nativeTileHandle
                loadedTiles[tileId] = runtimeTile.copy(rendered = true)
            }
        }
    }

    fun clear() {
        loadedTiles.clear()
        activeNativeTileHandles.clear()
        nativeRenderer.removeLayer(layer.id)
    }

    private fun deactivateTile(tileId: VectorraMvtTileId) {
        val nativeTileHandle = activeNativeTileHandles.remove(tileId) ?: return
        nativeRenderer.setTileRendered(nativeTileHandle, false)
        loadedTiles[tileId]?.let { runtimeTile ->
            loadedTiles[tileId] = runtimeTile.copy(rendered = false)
        }
    }

    private fun VectorraVectorTileLayer.toRenderStyle(): VectorraMvtRenderStyle {
        return when (this) {
            is VectorraVectorTileLayer.Line -> VectorraMvtRenderStyle(
                kind = VectorraMvtRenderLayerKind.LINE,
                visible = visible,
                color = color,
                opacity = opacity.toFloat(),
                widthPixels = widthPixels.toFloat()
            )
            is VectorraVectorTileLayer.Fill -> VectorraMvtRenderStyle(
                kind = VectorraMvtRenderLayerKind.FILL,
                visible = visible,
                color = color,
                opacity = opacity.toFloat()
            )
            is VectorraVectorTileLayer.Circle -> VectorraMvtRenderStyle(
                kind = VectorraMvtRenderLayerKind.CIRCLE,
                visible = visible,
                color = color,
                opacity = opacity.toFloat(),
                radiusPixels = radiusPixels.toFloat()
            )
            is VectorraVectorTileLayer.Symbol -> VectorraMvtRenderStyle(
                kind = VectorraMvtRenderLayerKind.SYMBOL,
                visible = visible,
                color = textColor,
                opacity = textOpacity.toFloat(),
                textSizeSp = textSizeSp.toFloat()
            )
        }
    }

    private fun VectorraVectorTileLayer.hitRadiusPixels(): Double {
        return when (this) {
            is VectorraVectorTileLayer.Circle -> radiusPixels
            is VectorraVectorTileLayer.Line -> maxOf(16.0, widthPixels)
            is VectorraVectorTileLayer.Fill -> 16.0
            is VectorraVectorTileLayer.Symbol -> 16.0
        }
    }

    private fun VectorraVectorTileLayer.layerOpacity(): Double {
        return when (this) {
            is VectorraVectorTileLayer.Circle -> opacity
            is VectorraVectorTileLayer.Line -> opacity
            is VectorraVectorTileLayer.Fill -> opacity
            is VectorraVectorTileLayer.Symbol -> textOpacity
        }
    }
}
