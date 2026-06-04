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
        .flatMap { layer ->
            layer.features.asSequence().flatMap { it.toGeoJsonFeatures(tileId, layer.extent).asSequence() }
        }
        .toList()
}

@VectorraBetaApi("0.3.0-beta.1")
fun VectorraMvtFeature.toDecodedFeature(
    tileId: VectorraMvtTileId,
    extent: Int
): VectorraMvtDecodedFeature? {
    return toDecodedFeatures(tileId, extent).firstOrNull()
}

internal fun VectorraMvtFeature.toDecodedFeatures(
    tileId: VectorraMvtTileId,
    extent: Int
): List<VectorraMvtDecodedFeature> {
    val geometries = geometry.toAnnotationGeometries(tileId, extent)
    return geometries.mapIndexed { index, geometry ->
        VectorraMvtDecodedFeature(
            id = decodedFeatureId(geometry, index, geometries.size),
            layerName = layerName,
            geometry = geometry,
            properties = stringProperties()
        )
    }
}

private fun VectorraMvtFeature.toGeoJsonFeatures(tileId: VectorraMvtTileId, extent: Int): List<VectorraGeoJsonFeature> {
    return toDecodedFeatures(tileId, extent).map { decoded ->
        VectorraGeoJsonFeature(
            id = decoded.id,
            geometry = decoded.geometry,
            properties = decoded.properties + mapOf("mvt_layer" to decoded.layerName)
        )
    }
}

private fun VectorraMvtFeature.decodedFeatureId(
    geometry: VectorraAnnotationGeometry,
    index: Int,
    geometryCount: Int
): String {
    val baseId = id?.toString() ?: "$layerName:${geometry.hashCode()}"
    return if (geometryCount == 1) baseId else "$baseId:$index"
}

private fun VectorraMvtGeometry.toAnnotationGeometries(
    tileId: VectorraMvtTileId,
    extent: Int
): List<VectorraAnnotationGeometry> {
    require(extent > 0) { "MVT layer extent must be greater than 0." }
    return when (this) {
        is VectorraMvtGeometry.Point -> {
            points.map { point ->
                VectorraAnnotationGeometry.Point(point.toCoordinate(tileId, extent))
            }
        }
        is VectorraMvtGeometry.LineString -> {
            lines
                .filter { it.size >= 2 }
                .map { line ->
                    VectorraAnnotationGeometry.LineString(line.map { it.toCoordinate(tileId, extent) })
                }
        }
        is VectorraMvtGeometry.Polygon -> {
            val validRings = rings.filter { it.size >= 4 }
            if (validRings.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    VectorraAnnotationGeometry.Polygon(
                        validRings.map { ring -> ring.map { it.toCoordinate(tileId, extent) } }
                    )
                )
            }
        }
    }
}

internal fun VectorraMvtGeometry.toRenderGeometries(
    tileId: VectorraMvtTileId,
    extent: Int
): List<VectorraAnnotationGeometry> {
    return toAnnotationGeometries(tileId, extent)
}

internal fun VectorraMvtFeature.renderFeatureIds(
    geometries: List<VectorraAnnotationGeometry>
): List<String> {
    return geometries.mapIndexed { index, geometry ->
        decodedFeatureId(
            geometry = geometry,
            index = index,
            geometryCount = geometries.size
        )
    }
}

internal fun VectorraMvtGeometry.coordinatesForGeometry(
    tileId: VectorraMvtTileId,
    extent: Int,
    geometryType: VectorraMvtRenderGeometryType
): List<List<VectorraCoordinate>> {
    return when (geometryType) {
        VectorraMvtRenderGeometryType.POINT -> {
            val points = this as? VectorraMvtGeometry.Point ?: return emptyList()
            points.points.map { point -> listOf(point.toCoordinate(tileId, extent)) }
        }
        VectorraMvtRenderGeometryType.LINE -> {
            val lineString = this as? VectorraMvtGeometry.LineString ?: return emptyList()
            lineString.lines
                .filter { it.size >= 2 }
                .map { line -> line.map { it.toCoordinate(tileId, extent) } }
        }
        VectorraMvtRenderGeometryType.POLYGON -> {
            val polygon = this as? VectorraMvtGeometry.Polygon ?: return emptyList()
            val validRings = polygon.rings.filter { it.size >= 4 }
            if (validRings.isEmpty()) {
                emptyList()
            } else {
                listOf(validRings.flatten().map { it.toCoordinate(tileId, extent) })
            }
        }
    }
}

internal fun VectorraMvtGeometry.ringEndsForGeometry(geometryType: VectorraMvtRenderGeometryType): List<List<Int>> {
    if (geometryType != VectorraMvtRenderGeometryType.POLYGON) {
        return emptyList()
    }
    val polygon = this as? VectorraMvtGeometry.Polygon ?: return emptyList()
    val validRings = polygon.rings.filter { it.size >= 4 }
    if (validRings.isEmpty()) {
        return emptyList()
    }
    var cursor = 0
    return listOf(
        validRings.map { ring ->
            cursor += ring.size
            cursor
        }
    )
}

internal fun List<VectorraCoordinate>.toFlatCoordinateList(): List<Double> {
    return flatMap { coordinate ->
        listOf(coordinate.longitude, coordinate.latitude)
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
