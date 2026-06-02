package com.vectorra.maps

enum class VectorraMapLoadState {
    IDLE,
    LOADING,
    READY,
    ERROR
}

enum class VectorraSurfaceLifecycleState {
    NOT_CREATED,
    CREATED,
    CHANGED,
    DESTROYED
}

sealed class VectorraMapLoadError(
    val message: String,
    val cause: Throwable? = null
) {
    class UnsupportedDevice(
        message: String,
        cause: Throwable? = null
    ) : VectorraMapLoadError(message, cause)

    class NativeRenderer(
        message: String,
        cause: Throwable? = null
    ) : VectorraMapLoadError(message, cause)

    class Resource(
        message: String,
        cause: Throwable? = null
    ) : VectorraMapLoadError(message, cause)
}

class VectorraUnsupportedDeviceException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

interface VectorraMapLifecycleCallback {
    fun onSurfaceLifecycleChanged(
        view: VectorraMapView,
        state: VectorraSurfaceLifecycleState,
        width: Int,
        height: Int
    ) = Unit

    fun onMapReady(view: VectorraMapView, map: VectorraMap) = Unit

    fun onMapLoadError(view: VectorraMapView, error: VectorraMapLoadError) = Unit
}
