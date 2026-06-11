package com.vectorra.maps.mvt

import com.vectorra.maps.internal.VectorraNative
import com.vectorra.maps.query.VectorraAnnotationGeometry
import com.vectorra.maps.query.VectorraCoordinate

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

internal data class VectorraMvtNativeTileRequest(
    val sourceId: String,
    val layerId: String,
    val sourceLayer: String,
    val tileId: VectorraMvtTileId,
    val style: VectorraMvtRenderStyle,
    val tileBytes: ByteArray,
    val renderNow: Boolean
) {
    init {
        require(sourceId.isNotBlank()) { "MVT native tile sourceId must not be blank." }
        require(layerId.isNotBlank()) { "MVT native tile layerId must not be blank." }
        require(sourceLayer.isNotBlank()) { "MVT native tile sourceLayer must not be blank." }
        require(tileBytes.isNotEmpty()) { "MVT native tile bytes must not be empty." }
    }

    val nativeTileHandle: String = "$layerId:${tileId.z}/${tileId.x}/${tileId.y}"
}

internal data class VectorraMvtSubmittedTile(
    val nativeTileHandle: String,
    val queryFeatures: List<VectorraMvtDecodedFeature>
)

internal interface VectorraMvtNativeRenderer {
    fun submitTile(request: VectorraMvtNativeTileRequest): VectorraMvtSubmittedTile
    fun setTileRendered(nativeTileHandle: String, rendered: Boolean)
    fun removeTile(nativeTileHandle: String)
    fun removeLayer(layerId: String)
}

internal class VectorraMvtJniRenderer(
    private val nativeHandle: Long
) : VectorraMvtNativeRenderer {
    override fun submitTile(request: VectorraMvtNativeTileRequest): VectorraMvtSubmittedTile {
        val result = VectorraNative.submitMvtTileBytes(
            handle = nativeHandle,
            sourceId = request.sourceId,
            layerId = request.layerId,
            sourceLayer = request.sourceLayer,
            tileZ = request.tileId.z,
            tileX = request.tileId.x,
            tileY = request.tileId.y,
            styleKind = request.style.kind.name,
            visible = request.style.visible,
            color = request.style.color,
            opacity = request.style.opacity,
            widthPixels = request.style.widthPixels,
            radiusPixels = request.style.radiusPixels,
            textSizeSp = request.style.textSizeSp,
            tileBytes = request.tileBytes,
            renderNow = request.renderNow
        )
        require(result.errorMessage == null) { result.errorMessage ?: "MVT native renderer rejected tile bytes." }
        require(result.nativeTileHandle.isNotBlank()) { "MVT native renderer returned a blank tile handle." }
        return VectorraMvtSubmittedTile(
            nativeTileHandle = result.nativeTileHandle,
            queryFeatures = result.toDecodedFeatures()
        )
    }

    override fun setTileRendered(nativeTileHandle: String, rendered: Boolean) {
        require(nativeTileHandle.isNotBlank()) { "MVT native tile handle must not be blank." }
        VectorraNative.setMvtTileRendered(nativeHandle, nativeTileHandle, rendered)
    }

    override fun removeTile(nativeTileHandle: String) {
        require(nativeTileHandle.isNotBlank()) { "MVT native tile handle must not be blank." }
        VectorraNative.removeMvtTile(nativeHandle, nativeTileHandle)
    }

    override fun removeLayer(layerId: String) {
        require(layerId.isNotBlank()) { "MVT render layerId must not be blank." }
        VectorraNative.removeMvtLayer(nativeHandle, layerId)
    }

    private fun VectorraNative.MvtTileResult.toDecodedFeatures(): List<VectorraMvtDecodedFeature> {
        val featureCount = featureIds.size
        require(sourceLayers.size == featureCount) { "MVT native query source layer count mismatch." }
        require(geometryTypes.size == featureCount) { "MVT native query geometry type count mismatch." }
        require(coordinateOffsets.size == featureCount + 1) { "MVT native query coordinate offsets mismatch." }
        require(ringOffsets.size == featureCount + 1) { "MVT native query ring offsets mismatch." }
        require(propertyOffsets.size == featureCount + 1) { "MVT native query property offsets mismatch." }

        return List(featureCount) { index ->
            VectorraMvtDecodedFeature(
                id = featureIds[index],
                layerName = sourceLayers[index],
                geometry = geometryAt(index),
                properties = propertiesAt(index)
            )
        }
    }

    private fun VectorraNative.MvtTileResult.geometryAt(index: Int): VectorraAnnotationGeometry {
        val coordinateStart = coordinateOffsets[index]
        val coordinateEnd = coordinateOffsets[index + 1]
        require(coordinateStart >= 0 && coordinateEnd >= coordinateStart && coordinateEnd <= coordinates.size) {
            "MVT native query coordinate range is invalid."
        }
        require((coordinateEnd - coordinateStart) % 2 == 0) {
            "MVT native query coordinates must be longitude/latitude pairs."
        }
        val coordinatePairs = coordinates
            .asList()
            .subList(coordinateStart, coordinateEnd)
            .chunked(2)
            .map { (longitude, latitude) -> VectorraCoordinate(longitude, latitude) }
        return when (geometryTypes[index]) {
            VectorraMvtRenderGeometryType.POINT.ordinal -> {
                require(coordinatePairs.isNotEmpty()) { "MVT native point query geometry is empty." }
                VectorraAnnotationGeometry.Point(coordinatePairs.first())
            }
            VectorraMvtRenderGeometryType.LINE.ordinal -> {
                VectorraAnnotationGeometry.LineString(coordinatePairs)
            }
            VectorraMvtRenderGeometryType.POLYGON.ordinal -> {
                val ringStart = ringOffsets[index]
                val ringEnd = ringOffsets[index + 1]
                require(ringStart >= 0 && ringEnd >= ringStart && ringEnd <= ringEnds.size) {
                    "MVT native query ring range is invalid."
                }
                var previous = 0
                val rings = ringEnds
                    .asList()
                    .subList(ringStart, ringEnd)
                    .map { next ->
                        require(next > previous && next <= coordinatePairs.size) {
                            "MVT native query ring end is invalid."
                        }
                        coordinatePairs.subList(previous, next).also {
                            previous = next
                        }
                    }
                VectorraAnnotationGeometry.Polygon(rings)
            }
            else -> error("Unsupported MVT native query geometry type: ${geometryTypes[index]}")
        }
    }

    private fun VectorraNative.MvtTileResult.propertiesAt(index: Int): Map<String, String> {
        val propertyStart = propertyOffsets[index]
        val propertyEnd = propertyOffsets[index + 1]
        require(propertyStart >= 0 && propertyEnd >= propertyStart && propertyEnd <= propertyKeys.size) {
            "MVT native query property range is invalid."
        }
        require(propertyValues.size == propertyKeys.size) { "MVT native query property value count mismatch." }
        return (propertyStart until propertyEnd).associate { propertyIndex ->
            propertyKeys[propertyIndex] to propertyValues[propertyIndex]
        }
    }
}
