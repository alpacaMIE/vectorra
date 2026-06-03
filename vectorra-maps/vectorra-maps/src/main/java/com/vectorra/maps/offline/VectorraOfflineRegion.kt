package com.vectorra.maps.offline

import com.vectorra.maps.VectorraBetaApi
import com.vectorra.maps.layer.VectorraRasterLayer
import com.vectorra.maps.network.TileId
import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResourceType
import com.vectorra.maps.network.TileScheme
import com.vectorra.maps.source.VectorraRasterTileSource
import com.vectorra.maps.terrain.VectorraTerrainSource
import com.vectorra.maps.vector.VectorraVectorTileSource
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

@VectorraBetaApi("0.8.0-beta.1")
data class VectorraOfflineBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
) {
    init {
        require(listOf(west, south, east, north).all { it.isFinite() }) {
            "Offline bounds values must be finite."
        }
        require(west in MIN_LONGITUDE..MAX_LONGITUDE && east in MIN_LONGITUDE..MAX_LONGITUDE) {
            "Offline bounds longitudes must be within -180.0..180.0."
        }
        require(south in MIN_LATITUDE..MAX_LATITUDE && north in MIN_LATITUDE..MAX_LATITUDE) {
            "Offline bounds latitudes must be within -85.05112878..85.05112878."
        }
        require(west < east) { "Offline bounds west must be less than east." }
        require(south < north) { "Offline bounds south must be less than north." }
    }
}

@VectorraBetaApi("0.8.0-beta.1")
data class VectorraOfflineRegion(
    val bounds: VectorraOfflineBounds,
    val minZoom: Int,
    val maxZoom: Int
) {
    init {
        require(minZoom >= 0) { "Offline region minZoom must be greater than or equal to 0." }
        require(maxZoom >= minZoom) { "Offline region maxZoom must be greater than or equal to minZoom." }
        require(maxZoom <= MAX_ENUMERABLE_ZOOM) { "Offline region maxZoom must be less than or equal to 30." }
    }
}

@VectorraBetaApi("0.8.0-beta.1")
data class VectorraOfflineTileSource(
    val sourceId: String,
    val layerId: String? = null,
    val templateUrl: String,
    val minZoom: Int,
    val maxZoom: Int,
    val scheme: TileScheme = TileScheme.XYZ,
    val headers: Map<String, String> = emptyMap(),
    val resourceType: TileResourceType = TileResourceType.UNKNOWN,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(sourceId.isNotBlank()) { "Offline tile source sourceId must not be blank." }
        require(layerId == null || layerId.isNotBlank()) { "Offline tile source layerId must not be blank." }
        require(templateUrl.isNotBlank()) { "Offline tile source templateUrl must not be blank." }
        require(minZoom >= 0) { "Offline tile source minZoom must be greater than or equal to 0." }
        require(maxZoom >= minZoom) { "Offline tile source maxZoom must be greater than or equal to minZoom." }
        require(maxZoom <= MAX_ENUMERABLE_ZOOM) { "Offline tile source maxZoom must be less than or equal to 30." }
        require(headers.keys.none { it.isBlank() }) { "Offline tile source header names must not be blank." }
        require(metadata.keys.none { it.isBlank() }) { "Offline tile source metadata keys must not be blank." }
    }

    companion object {
        fun raster(source: VectorraRasterTileSource, layerId: String? = null): VectorraOfflineTileSource {
            return VectorraOfflineTileSource(
                sourceId = source.id,
                layerId = layerId,
                templateUrl = source.templateUrl,
                minZoom = source.minZoom,
                maxZoom = source.maxZoom,
                scheme = source.scheme,
                headers = source.headers,
                resourceType = TileResourceType.RASTER
            )
        }

        fun raster(layer: VectorraRasterLayer): VectorraOfflineTileSource {
            return VectorraOfflineTileSource(
                sourceId = layer.sourceId,
                layerId = layer.id,
                templateUrl = layer.templateUrl,
                minZoom = layer.minZoom,
                maxZoom = layer.maxZoom,
                scheme = layer.scheme,
                headers = layer.headers,
                resourceType = TileResourceType.RASTER
            )
        }

        fun vector(source: VectorraVectorTileSource, layerId: String? = null): VectorraOfflineTileSource {
            return VectorraOfflineTileSource(
                sourceId = source.id,
                layerId = layerId,
                templateUrl = source.templateUrl,
                minZoom = source.minZoom,
                maxZoom = source.maxZoom,
                scheme = source.scheme,
                headers = source.headers,
                resourceType = TileResourceType.VECTOR,
                metadata = mapOf("kind" to "mvt")
            )
        }

        fun terrain(source: VectorraTerrainSource): VectorraOfflineTileSource {
            return VectorraOfflineTileSource(
                sourceId = source.id,
                templateUrl = source.templateUrl,
                minZoom = source.minZoom,
                maxZoom = source.maxZoom,
                headers = source.headers,
                resourceType = TileResourceType.DEM,
                metadata = mapOf("encoding" to source.encoding.name.lowercase())
            )
        }
    }
}

@VectorraBetaApi("0.8.0-beta.1")
fun VectorraOfflineRegion.toTileRequests(sources: List<VectorraOfflineTileSource>): List<TileRequest> {
    require(sources.isNotEmpty()) { "Offline region sources must not be empty." }
    return sources.flatMap { source ->
        val minZ = maxOf(minZoom, source.minZoom)
        val maxZ = minOf(maxZoom, source.maxZoom)
        if (minZ > maxZ) {
            emptyList()
        } else {
            (minZ..maxZ).flatMap { z -> tileRequestsForZoom(source, z) }
        }
    }
}

private fun VectorraOfflineRegion.tileRequestsForZoom(
    source: VectorraOfflineTileSource,
    z: Int
): List<TileRequest> {
    val tileCount = 1L shl z
    val minX = longitudeToTileX(bounds.west, tileCount)
    val maxX = longitudeToTileX(bounds.east, tileCount)
    val minY = latitudeToTileY(bounds.north, tileCount)
    val maxY = latitudeToTileY(bounds.south, tileCount)
    val requests = ArrayList<TileRequest>((maxX - minX + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    for (x in minX..maxX) {
        for (y in minY..maxY) {
            requests += source.toTileRequest(z = z, x = x.toInt(), y = y.toInt())
        }
    }
    return requests
}

private fun VectorraOfflineTileSource.toTileRequest(z: Int, x: Int, y: Int): TileRequest {
    val requestY = when (scheme) {
        TileScheme.TMS -> ((1L shl z) - 1L - y).coerceAtLeast(0L).toInt()
        TileScheme.XYZ,
        TileScheme.WMTS -> y
    }
    return TileRequest(
        sourceId = sourceId,
        layerId = layerId,
        url = templateUrl
            .replace("\${z}", z.toString())
            .replace("\${x}", x.toString())
            .replace("\${y}", requestY.toString())
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", requestY.toString()),
        headers = headers,
        tileId = TileId(z = z, x = x, y = y, scheme = scheme),
        resourceType = resourceType,
        metadata = metadata
    )
}

private fun longitudeToTileX(longitude: Double, tileCount: Long): Long {
    val x = floor(((longitude + 180.0) / 360.0) * tileCount).toLong()
    return x.coerceIn(0L, tileCount - 1L)
}

private fun latitudeToTileY(latitude: Double, tileCount: Long): Long {
    val radians = Math.toRadians(latitude)
    val y = floor((1.0 - ln(tan(radians) + 1.0 / kotlin.math.cos(radians)) / PI) / 2.0 * tileCount).toLong()
    return y.coerceIn(0L, tileCount - 1L)
}

private const val MIN_LONGITUDE = -180.0
private const val MAX_LONGITUDE = 180.0
private const val MIN_LATITUDE = -85.05112878
private const val MAX_LATITUDE = 85.05112878
private const val MAX_ENUMERABLE_ZOOM = 30
