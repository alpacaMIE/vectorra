package com.vectorra.maps.mvt

import com.vectorra.maps.internal.VectorraNative

internal enum class VectorraMvtRenderGeometryType {
    POINT,
    LINE,
    POLYGON
}

internal enum class VectorraMvtRenderLayerKind {
    LINE,
    FILL,
    CIRCLE,
    SYMBOL
}

internal data class VectorraMvtRenderStyle(
    val kind: VectorraMvtRenderLayerKind,
    val visible: Boolean = true,
    val color: Int = 0xffffffff.toInt(),
    val opacity: Float = 1.0f,
    val widthPixels: Float = 1.0f,
    val radiusPixels: Float = 4.0f,
    val textSizeSp: Float = 12.0f
) {
    init {
        require(opacity.isFinite() && opacity in 0.0f..1.0f) {
            "MVT render style opacity must be finite and in 0..1."
        }
        require(widthPixels.isFinite() && widthPixels >= 0.0f) {
            "MVT render style widthPixels must be finite and non-negative."
        }
        require(radiusPixels.isFinite() && radiusPixels >= 0.0f) {
            "MVT render style radiusPixels must be finite and non-negative."
        }
        require(textSizeSp.isFinite() && textSizeSp >= 0.0f) {
            "MVT render style textSizeSp must be finite and non-negative."
        }
    }
}

internal data class VectorraMvtRenderFeature(
    val featureId: String,
    val sourceLayer: String,
    val geometryType: VectorraMvtRenderGeometryType,
    val coordinates: List<Double>,
    val ringEnds: List<Int> = emptyList()
) {
    init {
        require(featureId.isNotBlank()) { "MVT render feature id must not be blank." }
        require(sourceLayer.isNotBlank()) { "MVT render feature sourceLayer must not be blank." }
        require(coordinates.isNotEmpty()) { "MVT render feature coordinates must not be empty." }
        require(coordinates.size % 2 == 0) {
            "MVT render feature coordinates must be longitude/latitude pairs."
        }
        require(coordinates.all { it.isFinite() }) {
            "MVT render feature coordinates must be finite."
        }
        require(ringEnds.all { it > 0 && it <= coordinates.size / 2 }) {
            "MVT render feature ring ends must address coordinate pairs."
        }
        require(ringEnds == ringEnds.sorted()) {
            "MVT render feature ring ends must be sorted."
        }
        require(geometryType == VectorraMvtRenderGeometryType.POLYGON || ringEnds.isEmpty()) {
            "MVT render feature ring ends are only valid for polygons."
        }
    }
}

internal data class VectorraMvtRenderTileInput(
    val sourceId: String,
    val layerId: String,
    val tileId: VectorraMvtTileId,
    val style: VectorraMvtRenderStyle,
    val features: List<VectorraMvtRenderFeature>
) {
    init {
        require(sourceId.isNotBlank()) { "MVT render sourceId must not be blank." }
        require(layerId.isNotBlank()) { "MVT render layerId must not be blank." }
    }

    val nativeTileHandle: String = "$layerId:${tileId.z}/${tileId.x}/${tileId.y}"

    fun nativeFeatureIds(): Array<String> = features.map { it.featureId }.toTypedArray()

    fun nativeSourceLayers(): Array<String> = features.map { it.sourceLayer }.toTypedArray()

    fun nativeGeometryTypes(): IntArray = features.map { it.geometryType.ordinal }.toIntArray()

    fun nativeCoordinateOffsets(): IntArray {
        val offsets = IntArray(features.size + 1)
        var cursor = 0
        features.forEachIndexed { index, feature ->
            offsets[index] = cursor
            cursor += feature.coordinates.size
        }
        offsets[features.size] = cursor
        return offsets
    }

    fun nativeCoordinates(): DoubleArray = features
        .flatMap { it.coordinates }
        .toDoubleArray()

    fun nativeRingOffsets(): IntArray {
        val offsets = IntArray(features.size + 1)
        var cursor = 0
        features.forEachIndexed { index, feature ->
            offsets[index] = cursor
            cursor += feature.ringEnds.size
        }
        offsets[features.size] = cursor
        return offsets
    }

    fun nativeRingEnds(): IntArray = features
        .flatMap { it.ringEnds }
        .toIntArray()
}

internal interface VectorraMvtNativeRenderer {
    fun renderTile(input: VectorraMvtRenderTileInput): String
    fun removeTile(nativeTileHandle: String)
    fun removeLayer(layerId: String)
}

internal class VectorraMvtJniRenderer(
    private val nativeHandle: Long
) : VectorraMvtNativeRenderer {
    override fun renderTile(input: VectorraMvtRenderTileInput): String {
        val nativeTileHandle = VectorraNative.renderMvtTile(
            handle = nativeHandle,
            sourceId = input.sourceId,
            layerId = input.layerId,
            tileZ = input.tileId.z,
            tileX = input.tileId.x,
            tileY = input.tileId.y,
            styleKind = input.style.kind.name,
            visible = input.style.visible,
            color = input.style.color,
            opacity = input.style.opacity,
            widthPixels = input.style.widthPixels,
            radiusPixels = input.style.radiusPixels,
            textSizeSp = input.style.textSizeSp,
            featureIds = input.nativeFeatureIds(),
            sourceLayers = input.nativeSourceLayers(),
            geometryTypes = input.nativeGeometryTypes(),
            coordinateOffsets = input.nativeCoordinateOffsets(),
            coordinates = input.nativeCoordinates(),
            ringOffsets = input.nativeRingOffsets(),
            ringEnds = input.nativeRingEnds()
        )
        require(nativeTileHandle.isNotBlank()) { "MVT native renderer rejected tile input." }
        return nativeTileHandle
    }

    override fun removeTile(nativeTileHandle: String) {
        require(nativeTileHandle.isNotBlank()) { "MVT native tile handle must not be blank." }
        VectorraNative.removeMvtTile(nativeHandle, nativeTileHandle)
    }

    override fun removeLayer(layerId: String) {
        require(layerId.isNotBlank()) { "MVT render layerId must not be blank." }
        VectorraNative.removeMvtLayer(nativeHandle, layerId)
    }
}
