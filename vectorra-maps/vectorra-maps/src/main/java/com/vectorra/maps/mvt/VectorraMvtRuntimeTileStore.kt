package com.vectorra.maps.mvt

import com.vectorra.maps.query.VectorraAnnotationFeature
import com.vectorra.maps.vector.VectorraVectorTileLayer

internal data class VectorraMvtRuntimeTile(
    val tileId: VectorraMvtTileId,
    val decodedTile: VectorraMvtTile,
    val nativeTileHandle: String,
    val queryFeatures: List<VectorraMvtDecodedFeature>
)

internal class VectorraMvtRuntimeTileStore(
    private val sourceId: String,
    private val layer: VectorraVectorTileLayer,
    private val nativeRenderer: VectorraMvtNativeRenderer
) {
    private val loadedTiles = linkedMapOf<VectorraMvtTileId, VectorraMvtRuntimeTile>()

    init {
        require(sourceId.isNotBlank()) { "MVT runtime sourceId must not be blank." }
        require(layer.sourceId == sourceId) {
            "MVT runtime layer sourceId must match source id."
        }
    }

    fun loadedTileIds(): Set<VectorraMvtTileId> = loadedTiles.keys.toSet()

    fun nativeTileHandles(): Set<String> = loadedTiles.values.mapTo(linkedSetOf()) { it.nativeTileHandle }

    fun queryFeatures(): List<VectorraMvtDecodedFeature> = loadedTiles.values.flatMap { it.queryFeatures }

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

    fun putDecodedTile(tileId: VectorraMvtTileId, decodedTile: VectorraMvtTile): VectorraMvtRuntimeTile {
        loadedTiles.remove(tileId)?.let { previous ->
            nativeRenderer.removeTile(previous.nativeTileHandle)
        }
        val renderInput = decodedTile.toRenderInput(tileId)
        val nativeTileHandle = nativeRenderer.renderTile(renderInput)
        val runtimeTile = VectorraMvtRuntimeTile(
            tileId = tileId,
            decodedTile = decodedTile,
            nativeTileHandle = nativeTileHandle,
            queryFeatures = decodedTile.toQueryFeatures(tileId)
        )
        loadedTiles[tileId] = runtimeTile
        return runtimeTile
    }

    fun removeTile(tileId: VectorraMvtTileId): VectorraMvtRuntimeTile? {
        val removed = loadedTiles.remove(tileId) ?: return null
        nativeRenderer.removeTile(removed.nativeTileHandle)
        return removed
    }

    fun resubmitLoadedTiles() {
        val tiles = loadedTiles.values.map { runtimeTile ->
            runtimeTile.tileId to runtimeTile.decodedTile
        }
        loadedTiles.clear()
        tiles.forEach { (tileId, decodedTile) ->
            putDecodedTile(tileId, decodedTile)
        }
    }

    fun clear() {
        loadedTiles.clear()
        nativeRenderer.removeLayer(layer.id)
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
