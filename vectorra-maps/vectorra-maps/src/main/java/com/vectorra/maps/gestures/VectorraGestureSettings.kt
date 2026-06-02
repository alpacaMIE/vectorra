package com.vectorra.maps.gestures

data class VectorraGestureSettings(
    val scrollEnabled: Boolean = true,
    val rotateEnabled: Boolean = true,
    val pinchToZoomEnabled: Boolean = true,
    val pitchEnabled: Boolean = true,
    val doubleTapToZoomInEnabled: Boolean = true,
    val doubleTouchToZoomOutEnabled: Boolean = true,
    val flingEnabled: Boolean = true
)
