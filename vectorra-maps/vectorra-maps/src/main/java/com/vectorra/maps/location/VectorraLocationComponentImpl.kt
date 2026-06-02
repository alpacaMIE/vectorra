package com.vectorra.maps.location

import com.vectorra.maps.VectorraMapEngine
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class VectorraLocationComponentImpl(
    private val engine: VectorraMapEngine
) : VectorraLocationComponent {
    override var enabled: Boolean = false
        set(value) {
            field = value
            refreshIndicator()
        }

    override var showAccuracyRing: Boolean = true
        set(value) {
            field = value
            refreshIndicator()
        }

    override var followMode: VectorraFollowMode = VectorraFollowMode.NONE
        set(value) {
            field = value
            applyFollow()
        }

    override var location: VectorraLocation? = null
        private set

    private var currentBearingDegrees: Double = 0.0
    private var lastSubmittedIndicator: NativeIndicatorState? = null

    override fun updateLocation(location: VectorraLocation) {
        this.location = location
        location.bearingDegrees?.let { currentBearingDegrees = normalizeBearing(it) }
        refreshIndicator()
        applyFollow()
    }

    override fun updateBearing(bearingDegrees: Double) {
        currentBearingDegrees = normalizeBearing(bearingDegrees)
        refreshIndicator()
        applyFollow()
    }

    override fun clearLocation() {
        location = null
        lastSubmittedIndicator = null
        engine.clearNativeLocationIndicator()
    }

    internal fun onUserGesture() {
        if (followMode != VectorraFollowMode.NONE) {
            followMode = VectorraFollowMode.NONE
        }
    }

    internal fun onCameraChanged() {
        refreshIndicator()
    }

    private fun applyFollow() {
        val current = location ?: return
        if (followMode == VectorraFollowMode.NONE) {
            return
        }

        val camera = engine.cameraState
        engine.setCamera(
            longitude = current.longitude,
            latitude = current.latitude,
            zoom = camera.zoom,
            pitch = camera.pitch,
            bearing = if (followMode == VectorraFollowMode.HEADING) currentBearingDegrees else camera.bearing
        )
    }

    private fun refreshIndicator() {
        val current = location
        if (!enabled || current == null) {
            lastSubmittedIndicator = null
            engine.clearNativeLocationIndicator()
            return
        }

        val radiusPixels = current.accuracyMeters
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { accuracyMetersToPixels(it, current.latitude, engine.cameraState.zoom) }
            ?: 0.0
        val indicatorState = NativeIndicatorState(
            longitude = (current.longitude * 10_000_000.0).roundToLong(),
            latitude = (current.latitude * 10_000_000.0).roundToLong(),
            accuracyMeters = ((current.accuracyMeters ?: 0.0) * 10.0).roundToInt(),
            bearingDegrees = (currentBearingDegrees * 10.0).roundToInt(),
            showAccuracyRing = showAccuracyRing,
            accuracyRadiusPixels = radiusPixels.roundToInt()
        )
        if (indicatorState == lastSubmittedIndicator) {
            return
        }
        lastSubmittedIndicator = indicatorState

        engine.setNativeLocationIndicator(
            location = current,
            bearingDegrees = currentBearingDegrees,
            showAccuracyRing = showAccuracyRing,
            accuracyRadiusPixels = radiusPixels
        )
    }

    private fun accuracyMetersToPixels(accuracyMeters: Double, latitude: Double, zoom: Double): Double {
        val metersPerPixel = cos(Math.toRadians(latitude)).coerceAtLeast(0.05) *
            EARTH_CIRCUMFERENCE_METERS / (TILE_SIZE * 2.0.pow(zoom))
        return (accuracyMeters / metersPerPixel).coerceIn(MIN_ACCURACY_RADIUS_PX, MAX_ACCURACY_RADIUS_PX)
    }

    private fun normalizeBearing(value: Double): Double {
        var result = value % 360.0
        if (result < 0.0) {
            result += 360.0
        }
        return result
    }

    private companion object {
        const val EARTH_CIRCUMFERENCE_METERS = 40075016.686
        const val TILE_SIZE = 256.0
        const val MIN_ACCURACY_RADIUS_PX = 18.0
        const val MAX_ACCURACY_RADIUS_PX = 160.0
    }

    private data class NativeIndicatorState(
        val longitude: Long,
        val latitude: Long,
        val accuracyMeters: Int,
        val bearingDegrees: Int,
        val showAccuracyRing: Boolean,
        val accuracyRadiusPixels: Int
    )
}
