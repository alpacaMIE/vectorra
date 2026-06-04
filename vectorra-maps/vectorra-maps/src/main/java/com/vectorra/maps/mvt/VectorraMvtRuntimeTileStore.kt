package com.vectorra.maps.mvt

import com.vectorra.maps.query.VectorraAnnotationFeature
import com.vectorra.maps.query.VectorraAnnotationGeometry
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
                mvtLayer.features.asSequence().mapIndexedNotNull { index, feature ->
                    feature.toRenderFeature(tileId, mvtLayer.extent, index)
                }
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
                mvtLayer.features.asSequence().mapNotNull { feature ->
                    feature.toDecodedFeature(tileId, mvtLayer.extent)
                }
            }
            .toList()
    }

    private fun VectorraMvtFeature.toRenderFeature(
        tileId: VectorraMvtTileId,
        extent: Int,
        featureIndex: Int
    ): VectorraMvtRenderFeature? {
        val decoded = toDecodedFeature(tileId, extent) ?: return null
        val geometryType = layer.expectedGeometryType()
        val coordinates = decoded.geometry.coordinatesForGeometry(geometryType) ?: return null
        val ringEnds = if (geometryType == VectorraMvtRenderGeometryType.POLYGON) {
            decoded.geometry.polygonRingEnds()
        } else {
            emptyList()
        }
        if (coordinates.isEmpty()) {
            return null
        }
        return VectorraMvtRenderFeature(
            featureId = decoded.id.ifBlank { "${layer.sourceLayer}:$featureIndex" },
            sourceLayer = layer.sourceLayer,
            geometryType = geometryType,
            coordinates = coordinates,
            ringEnds = ringEnds
        )
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

    private fun VectorraAnnotationGeometry.coordinatesForGeometry(
        geometryType: VectorraMvtRenderGeometryType
    ): List<Double>? {
        return when (geometryType) {
            VectorraMvtRenderGeometryType.POINT -> {
                val point = this as? VectorraAnnotationGeometry.Point ?: return null
                listOf(point.coordinate.longitude, point.coordinate.latitude)
            }
            VectorraMvtRenderGeometryType.LINE -> {
                val line = this as? VectorraAnnotationGeometry.LineString ?: return null
                line.coordinates.flatMap { coordinate ->
                    listOf(coordinate.longitude, coordinate.latitude)
                }
            }
            VectorraMvtRenderGeometryType.POLYGON -> {
                val polygon = this as? VectorraAnnotationGeometry.Polygon ?: return null
                polygon.rings.flatten().flatMap { coordinate ->
                    listOf(coordinate.longitude, coordinate.latitude)
                }
            }
        }
    }

    private fun VectorraAnnotationGeometry.polygonRingEnds(): List<Int> {
        val polygon = this as? VectorraAnnotationGeometry.Polygon ?: return emptyList()
        var cursor = 0
        return polygon.rings.map { ring ->
            cursor += ring.size
            cursor
        }
    }
}
