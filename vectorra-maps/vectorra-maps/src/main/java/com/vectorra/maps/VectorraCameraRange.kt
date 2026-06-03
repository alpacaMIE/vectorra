package com.vectorra.maps

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.tan

internal fun vectorraNativeCameraRangeMetersForZoom(
    zoom: Double,
    latitude: Double,
    minZoom: Double = 0.0,
    maxZoom: Double = 22.0,
    viewportHeightPixels: Int = 1080
): Double {
    val clampedZoom = zoom.coerceIn(minZoom, maxZoom)
    val latitudeScale = cos(Math.toRadians(latitude)).coerceAtLeast(0.05)
    val metersPerPixel = EARTH_CIRCUMFERENCE_METERS * latitudeScale /
        (NATIVE_CAMERA_TILE_SIZE * 2.0.pow(clampedZoom))
    val visibleGroundMeters = metersPerPixel * viewportHeightPixels.coerceAtLeast(1)
    val halfFovyRadians = Math.toRadians(VECTORRA_NATIVE_CAMERA_FOVY_DEGREES) * 0.5
    return (visibleGroundMeters / (2.0 * tan(halfFovyRadians)))
        .coerceIn(MIN_NATIVE_CAMERA_RANGE_METERS, MAX_NATIVE_CAMERA_RANGE_METERS)
}

internal const val VECTORRA_NATIVE_CAMERA_FOVY_DEGREES = 30.0
private const val NATIVE_CAMERA_TILE_SIZE = 512.0
private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.68557849
private const val MIN_NATIVE_CAMERA_RANGE_METERS = 100.0
private const val MAX_NATIVE_CAMERA_RANGE_METERS = 30_000_000.0
