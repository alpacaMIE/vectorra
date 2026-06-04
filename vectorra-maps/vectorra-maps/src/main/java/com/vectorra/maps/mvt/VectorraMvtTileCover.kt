package com.vectorra.maps.mvt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

internal data class VectorraMvtTileCoverRequest(
    val longitude: Double,
    val latitude: Double,
    val zoom: Double,
    val bearing: Double,
    val viewportWidthPixels: Int,
    val viewportHeightPixels: Int,
    val tileSizePixels: Int,
    val tilePadding: Int = 0,
    val visible: Boolean,
    val visibleMinZoom: Int,
    val visibleMaxZoom: Int,
    val tileMinZoom: Int,
    val tileMaxZoom: Int
) {
    init {
        require(viewportWidthPixels > 0) { "MVT viewport width must be greater than 0." }
        require(viewportHeightPixels > 0) { "MVT viewport height must be greater than 0." }
        require(tileSizePixels > 0) { "MVT tile size must be greater than 0." }
        require(tilePadding >= 0) { "MVT tile padding must be greater than or equal to 0." }
        require(visibleMinZoom >= 0) { "MVT visible minZoom must be greater than or equal to 0." }
        require(visibleMaxZoom >= 0) { "MVT visible maxZoom must be greater than or equal to 0." }
        require(tileMinZoom >= 0) { "MVT tile minZoom must be greater than or equal to 0." }
        require(tileMaxZoom >= tileMinZoom) { "MVT tile maxZoom must be greater than or equal to tile minZoom." }
    }
}

internal object VectorraMvtTileCover {
    fun visibleTiles(request: VectorraMvtTileCoverRequest): Set<VectorraMvtTileId> {
        if (!request.visible ||
            request.visibleMinZoom > request.visibleMaxZoom ||
            request.zoom < request.visibleMinZoom ||
            request.zoom > request.visibleMaxZoom
        ) {
            return emptySet()
        }

        val z = floor(request.zoom)
            .toInt()
            .coerceIn(request.tileMinZoom, request.tileMaxZoom)
            .coerceIn(0, MAX_MVT_COVER_ZOOM)
        val tileCount = 1 shl z
        val worldSize = worldSizeFor(request.zoom, request.tileSizePixels)
        val tileWorldSize = worldSize / tileCount.toDouble()
        val center = worldPointFor(request.longitude, request.latitude, request.zoom, request.tileSizePixels)
        val halfWidth = request.viewportWidthPixels.toDouble() * 0.5
        val halfHeight = request.viewportHeightPixels.toDouble() * 0.5
        val visualBearing = -request.bearing
        val corners = listOf(
            screenCorner(center, -halfWidth, -halfHeight, visualBearing),
            screenCorner(center, halfWidth, -halfHeight, visualBearing),
            screenCorner(center, halfWidth, halfHeight, visualBearing),
            screenCorner(center, -halfWidth, halfHeight, visualBearing)
        )
        val minWorldX = corners.minOf { it.x }
        val maxWorldX = corners.maxOf { it.x }
        val minWorldY = corners.minOf { it.y }
        val maxWorldY = corners.maxOf { it.y }

        val minTileY = (lowerTileIndex(minWorldY, tileWorldSize) - request.tilePadding).coerceAtLeast(0)
        val maxTileY = (upperTileIndex(maxWorldY, tileWorldSize) + request.tilePadding).coerceAtMost(tileCount - 1)
        if (minTileY > maxTileY) {
            return emptySet()
        }

        val minTileX = lowerTileIndex(minWorldX, tileWorldSize) - request.tilePadding
        val maxTileX = upperTileIndex(maxWorldX, tileWorldSize) + request.tilePadding
        val tileIds = linkedSetOf<VectorraMvtTileId>()
        val xSpan = maxTileX.toLong() - minTileX.toLong() + 1L
        val xValues = if (xSpan >= tileCount.toLong()) {
            0 until tileCount
        } else {
            minTileX..maxTileX
        }
        for (x in xValues) {
            val wrappedX = x.floorMod(tileCount)
            for (y in minTileY..maxTileY) {
                tileIds += VectorraMvtTileId(z = z, x = wrappedX, y = y)
            }
        }
        return tileIds
    }

    private fun worldSizeFor(zoom: Double, tileSizePixels: Int): Double {
        return tileSizePixels.toDouble() * 2.0.pow(zoom)
    }

    private fun worldPointFor(longitude: Double, latitude: Double, zoom: Double, tileSizePixels: Int): WorldPoint {
        val worldSize = worldSizeFor(zoom, tileSizePixels)
        val clampedLatitude = latitude.coerceIn(MIN_LATITUDE_FOR_MVT_TILE, MAX_LATITUDE_FOR_MVT_TILE)
        val sinLatitude = sin(Math.toRadians(clampedLatitude))
        return WorldPoint(
            x = (wrapLongitude(longitude) + 180.0) / 360.0 * worldSize,
            y = (0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI)) * worldSize
        )
    }

    private fun screenCorner(center: WorldPoint, offsetX: Double, offsetY: Double, bearing: Double): WorldPoint {
        val bearingRadians = Math.toRadians(bearing)
        val cosBearing = cos(bearingRadians)
        val sinBearing = sin(bearingRadians)
        return WorldPoint(
            x = center.x + offsetX * cosBearing + offsetY * sinBearing,
            y = center.y - offsetX * sinBearing + offsetY * cosBearing
        )
    }

    private fun lowerTileIndex(value: Double, tileWorldSize: Double): Int {
        return floor(value / tileWorldSize).toInt()
    }

    private fun upperTileIndex(value: Double, tileWorldSize: Double): Int {
        return floor((value - TILE_EDGE_EPSILON) / tileWorldSize).toInt()
    }

    private fun wrapLongitude(longitude: Double): Double {
        var result = longitude
        while (result < -180.0) result += 360.0
        while (result > 180.0) result -= 360.0
        return result
    }

    private fun Int.floorMod(modulus: Int): Int {
        return ((this % modulus) + modulus) % modulus
    }

    private data class WorldPoint(val x: Double, val y: Double)

    private const val MAX_MVT_COVER_ZOOM = 30
    private const val TILE_EDGE_EPSILON = 1e-9
}

private const val MIN_LATITUDE_FOR_MVT_TILE = -85.0511287798066
private const val MAX_LATITUDE_FOR_MVT_TILE = 85.0511287798066
