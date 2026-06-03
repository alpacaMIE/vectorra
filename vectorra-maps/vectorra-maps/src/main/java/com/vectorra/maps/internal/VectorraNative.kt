package com.vectorra.maps.internal

import android.view.Surface

internal object VectorraNative {
    interface ResourceStatusCallback {
        fun onNativeResourceStatus(
            kind: String,
            layerId: String,
            state: String,
            errorType: String?,
            errorMessage: String?
        )
    }

    init {
        System.loadLibrary("vectorra_jni")
    }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun setResourceStatusCallback(handle: Long, callback: ResourceStatusCallback?)
    external fun setResourcePath(handle: Long, path: String)
    external fun setSurface(handle: Long, surface: Surface?, width: Int, height: Int): String?
    external fun resize(handle: Long, width: Int, height: Int)
    external fun setCamera(
        handle: Long,
        longitude: Double,
        latitude: Double,
        zoom: Double,
        pitch: Double,
        bearing: Double
    )

    external fun addRasterLayer(
        handle: Long,
        id: String,
        templateUrl: String,
        minZoom: Int,
        maxZoom: Int,
        visible: Boolean,
        opacity: Double,
        saturation: Double,
        contrast: Double,
        tileSize: Int,
        scheme: String,
        matrixSet: String,
        headerNames: Array<String>,
        headerValues: Array<String>
    )
    external fun removeLayer(handle: Long, id: String)
    external fun moveLayerToTop(handle: Long, id: String)
    external fun setRasterLayerStyle(
        handle: Long,
        id: String,
        visible: Boolean,
        opacity: Double,
        saturation: Double,
        contrast: Double
    )
    external fun addElevationLayer(
        handle: Long,
        id: String,
        templateUrl: String,
        minZoom: Int,
        maxZoom: Int,
        headerNames: Array<String>,
        headerValues: Array<String>
    )
    external fun setTerrainExaggeration(handle: Long, value: Double)
    external fun setLayerVisible(handle: Long, id: String, visible: Boolean)
    external fun addModelLayer(
        handle: Long,
        id: String,
        uri: String,
        longitude: Double,
        latitude: Double,
        heightMeters: Double,
        scale: Double,
        yawDegrees: Double,
        visible: Boolean
    )
    external fun removeModelLayer(handle: Long, id: String)
    external fun setModelLayerVisible(handle: Long, id: String, visible: Boolean)
    external fun clearAnnotations(handle: Long)
    external fun addPointAnnotation(handle: Long, id: String, longitude: Double, latitude: Double)
    external fun clearDrawAnnotations(handle: Long)
    external fun removeDrawAnnotation(handle: Long, id: String)
    external fun addDrawPointAnnotation(
        handle: Long,
        id: String,
        longitude: Double,
        latitude: Double,
        text: String,
        textSizeSp: Float,
        textColor: Int,
        textHaloColor: Int,
        textHaloWidthPx: Float,
        iconColor: Int,
        iconRadiusPx: Float
    )
    external fun addDrawLineAnnotation(
        handle: Long,
        id: String,
        coordinates: DoubleArray,
        lineColor: Int,
        lineWidthPixels: Float
    )
    external fun addDrawPolygonAnnotation(
        handle: Long,
        id: String,
        coordinates: DoubleArray,
        ringEnds: IntArray,
        fillColor: Int,
        fillOpacity: Float,
        outlineColor: Int,
        outlineWidthPixels: Float
    )
    external fun clearLabelAnnotations(handle: Long)
    external fun addLabelAnnotation(
        handle: Long,
        id: String,
        longitude: Double,
        latitude: Double,
        text: String,
        textSize: Double,
        textColor: Int,
        textHaloColor: Int,
        textHaloWidth: Double,
        textOffsetX: Double,
        textOffsetY: Double,
        hasIcon: Boolean,
        iconColor: Int,
        iconRadius: Double,
        allowOverlap: Boolean
    )
    external fun setLocationIndicator(
        handle: Long,
        enabled: Boolean,
        longitude: Double,
        latitude: Double,
        accuracyMeters: Double,
        bearingDegrees: Double,
        showAccuracyRing: Boolean,
        accuracyRadiusPixels: Double
    )
    external fun clearLocationIndicator(handle: Long)
    external fun onTouch(handle: Long, action: Int, pointerCount: Int, x0: Float, y0: Float, x1: Float, y1: Float)
}
