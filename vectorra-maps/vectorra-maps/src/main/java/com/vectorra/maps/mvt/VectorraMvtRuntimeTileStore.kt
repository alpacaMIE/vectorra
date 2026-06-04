package com.vectorra.maps.mvt

import com.vectorra.maps.query.VectorraAnnotationFeature
import com.vectorra.maps.vector.VectorraVectorTileLayer

internal data class VectorraMvtRuntimeTile(
    val tileId: VectorraMvtTileId,
    val decodedTile: VectorraMvtTile,
    val nativeTileHandle: String?,
    val queryFeatures: List<VectorraMvtDecodedFeature>
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

    fun putDecodedTile(
        tileId: VectorraMvtTileId,
        decodedTile: VectorraMvtTile,
        renderNow: Boolean = true
    ): VectorraMvtRuntimeTile {
        loadedTiles.remove(tileId)
        val runtimeTile = VectorraMvtRuntimeTile(
            tileId = tileId,
            decodedTile = decodedTile,
            nativeTileHandle = null,
            queryFeatures = decodedTile.toQueryFeatures(tileId)
        )
        loadedTiles[tileId] = runtimeTile
        val nativeTileHandle = if (renderNow) {
            renderLoadedTile(tileId, runtimeTile)
        } else {
            null
        }
        return runtimeTile.copy(nativeTileHandle = nativeTileHandle)
    }

    fun setRenderedTileIds(tileIds: Set<VectorraMvtTileId>) {
        require(tileIds.all(loadedTiles::containsKey)) {
            "MVT render set can only include loaded tiles."
        }
        tileIds.forEach { tileId ->
            if (tileId !in activeNativeTileHandles) {
                renderLoadedTile(tileId, loadedTiles.getValue(tileId))
            }
        }
        (activeNativeTileHandles.keys - tileIds).forEach(::removeActiveNativeTile)
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
        removeActiveNativeTile(tileId)
        return removed
    }

    fun resubmitLoadedTiles() {
        val activeTileIds = activeNativeTileHandles.keys.toList()
        activeNativeTileHandles.clear()
        activeTileIds.forEach { tileId ->
            loadedTiles[tileId]?.let { runtimeTile ->
                renderLoadedTile(tileId, runtimeTile)
            }
        }
    }

    fun clear() {
        loadedTiles.clear()
        activeNativeTileHandles.clear()
        nativeRenderer.removeLayer(layer.id)
    }

    private fun renderLoadedTile(
        tileId: VectorraMvtTileId,
        runtimeTile: VectorraMvtRuntimeTile
    ): String {
        val previousNativeTileHandle = activeNativeTileHandles[tileId]
        val nativeTileHandle = nativeRenderer.renderTile(runtimeTile.decodedTile.toRenderInput(tileId))
        activeNativeTileHandles[tileId] = nativeTileHandle
        if (previousNativeTileHandle != null && previousNativeTileHandle != nativeTileHandle) {
            nativeRenderer.removeTile(previousNativeTileHandle)
        }
        return nativeTileHandle
    }

    private fun removeActiveNativeTile(tileId: VectorraMvtTileId) {
        val nativeTileHandle = activeNativeTileHandles.remove(tileId) ?: return
        nativeRenderer.removeTile(nativeTileHandle)
    }

    private fun VectorraMvtTile.toRenderInput(tileId: VectorraMvtTileId): VectorraMvtRenderTileInput {
        val renderFeatures = layers
            .asSequence()
            .filter { it.name == layer.sourceLayer }
            .flatMap { mvtLayer ->
                mvtLayer.features.asSequence().mapIndexed { index, feature ->
                    feature.toRenderFeatures(tileId, mvtLayer.extent, index)
                }
                    .flatten()
            }
            .toList()
        return VectorraMvtRenderTileInput(
            sourceId = sourceId,
            layerId = layer.id,
            tileId = tileId,
            style = layer.toRenderStyle(),
            features = renderFeatures
        )
    }

    private fun VectorraMvtTile.toQueryFeatures(tileId: VectorraMvtTileId): List<VectorraMvtDecodedFeature> {
        return layers
            .asSequence()
            .filter { it.name == layer.sourceLayer }
            .flatMap { mvtLayer ->
                mvtLayer.features.asSequence().flatMap { feature ->
                    feature.toDecodedFeatures(tileId, mvtLayer.extent).asSequence()
                }
            }
            .toList()
    }

    private fun VectorraMvtFeature.toRenderFeatures(
        tileId: VectorraMvtTileId,
        extent: Int,
        featureIndex: Int
    ): List<VectorraMvtRenderFeature> {
        val geometryType = layer.expectedGeometryType()
        val coordinateParts = geometry.coordinatesForGeometry(tileId, extent, geometryType)
        if (coordinateParts.isEmpty()) {
            return emptyList()
        }
        val featureIds = renderFeatureIds(geometry.toRenderGeometries(tileId, extent))
        val ringEndParts = geometry.ringEndsForGeometry(geometryType)
        return coordinateParts.mapIndexedNotNull { partIndex, coordinates ->
            val flatCoordinates = coordinates.toFlatCoordinateList()
            if (flatCoordinates.isEmpty()) {
                return@mapIndexedNotNull null
            }
            VectorraMvtRenderFeature(
                featureId = featureIds.getOrNull(partIndex)
                    ?: "${layer.sourceLayer}:$featureIndex:$partIndex",
                sourceLayer = layer.sourceLayer,
                geometryType = geometryType,
                coordinates = flatCoordinates,
                ringEnds = ringEndParts.getOrElse(partIndex) { emptyList() }
            )
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

    private fun VectorraVectorTileLayer.expectedGeometryType(): VectorraMvtRenderGeometryType {
        return when (this) {
            is VectorraVectorTileLayer.Line -> VectorraMvtRenderGeometryType.LINE
            is VectorraVectorTileLayer.Fill -> VectorraMvtRenderGeometryType.POLYGON
            is VectorraVectorTileLayer.Circle -> VectorraMvtRenderGeometryType.POINT
            is VectorraVectorTileLayer.Symbol -> VectorraMvtRenderGeometryType.POINT
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
