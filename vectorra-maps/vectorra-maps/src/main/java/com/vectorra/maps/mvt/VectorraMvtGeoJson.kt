package com.vectorra.maps.mvt

import com.vectorra.maps.VectorraBetaApi
import com.vectorra.maps.query.VectorraAnnotationGeometry
import com.vectorra.maps.query.VectorraCoordinate
import com.vectorra.maps.query.VectorraGeoJsonFeature
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh

@VectorraBetaApi("0.3.0-beta.1")
fun VectorraMvtTile.toGeoJsonFeatures(
    tileId: VectorraMvtTileId,
    layerNames: Set<String> = emptySet()
): List<VectorraGeoJsonFeature> {
    return layers
        .asSequence()
        .filter { layerNames.isEmpty() || it.name in layerNames }
        .flatMap { layer -> layer.features.asSequence().mapNotNull { it.toGeoJsonFeature(tileId, layer.extent) } }
        .toList()
}

@VectorraBetaApi("0.3.0-beta.1")
fun VectorraMvtFeature.toDecodedFeature(
    tileId: VectorraMvtTileId,
    extent: Int
): VectorraMvtDecodedFeature? {
    val geometry = geometry.toAnnotationGeometry(tileId, extent) ?: return null
    return VectorraMvtDecodedFeature(
        id = id?.toString() ?: "$layerName:${geometry.hashCode()}",
        layerName = layerName,
        geometry = geometry,
        properties = stringProperties()
    )
}

private fun VectorraMvtFeature.toGeoJsonFeature(tileId: VectorraMvtTileId, extent: Int): VectorraGeoJsonFeature? {
    val decoded = toDecodedFeature(tileId, extent) ?: return null
    return VectorraGeoJsonFeature(
        id = decoded.id,
        geometry = decoded.geometry,
        properties = decoded.properties + mapOf("mvt_layer" to decoded.layerName)
    )
}

private fun VectorraMvtGeometry.toAnnotationGeometry(
    tileId: VectorraMvtTileId,
    extent: Int
): VectorraAnnotationGeometry? {
    require(extent > 0) { "MVT layer extent must be greater than 0." }
    return when (this) {
        is VectorraMvtGeometry.Point -> {
            val first = points.firstOrNull() ?: return null
            VectorraAnnotationGeometry.Point(first.toCoordinate(tileId, extent))
        }
        is VectorraMvtGeometry.LineString -> {
            val line = lines.firstOrNull { it.size >= 2 } ?: return null
            VectorraAnnotationGeometry.LineString(line.map { it.toCoordinate(tileId, extent) })
        }
        is VectorraMvtGeometry.Polygon -> {
            val validRings = rings.filter { it.size >= 4 }
            if (validRings.isEmpty()) return null
            VectorraAnnotationGeometry.Polygon(
                validRings.map { ring -> ring.map { it.toCoordinate(tileId, extent) } }
            )
        }
    }
}

private fun VectorraMvtPoint.toCoordinate(tileId: VectorraMvtTileId, extent: Int): VectorraCoordinate {
    val worldTiles = 2.0.pow(tileId.z)
    val worldSize = extent.toDouble() * worldTiles
    val globalX = tileId.x.toDouble() * extent.toDouble() + x.toDouble()
    val globalY = tileId.y.toDouble() * extent.toDouble() + y.toDouble()
    val longitude = globalX / worldSize * 360.0 - 180.0
    val mercatorY = PI * (1.0 - 2.0 * globalY / worldSize)
    val latitude = Math.toDegrees(atan(sinh(mercatorY)))
    return VectorraCoordinate(longitude = longitude, latitude = latitude)
}

private fun VectorraMvtFeature.stringProperties(): Map<String, String> {
    return properties.mapValues { it.value.asString() }
}
