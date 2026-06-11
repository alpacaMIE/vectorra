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

    interface CameraCallback {
        fun onNativeCameraChanged(
            longitude: Double,
            latitude: Double,
            zoom: Double,
            pitch: Double,
            bearing: Double
        )
    }

    init {
        System.loadLibrary("vectorra_jni")
    }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun setResourceStatusCallback(handle: Long, callback: ResourceStatusCallback?)
    external fun setCameraCallback(handle: Long, callback: CameraCallback?)
    external fun setResourcePath(handle: Long, path: String)
    external fun setCachePath(handle: Long, path: String)
    external fun setSurface(handle: Long, surface: Surface?, width: Int, height: Int): String?
    external fun resize(handle: Long, width: Int, height: Int)
    external fun setCamera(
        handle: Long,
        longitude: Double,
        latitude: Double,
        zoom: Double,
        pitch: Double,
        bearing: Double,
        targetHeightMeters: Double,
        durationMillis: Long
    )
    external fun panByPixels(handle: Long, deltaX: Float, deltaY: Float, viewWidth: Int, viewHeight: Int)
    external fun zoomByScale(handle: Long, scale: Float)
    external fun zoomByScaleAt(
        handle: Long,
        scale: Float,
        focusX: Float,
        focusY: Float,
        viewWidth: Int,
        viewHeight: Int
    )
    external fun rotateByDegrees(handle: Long, deltaDegrees: Double)
    external fun pitchByDegrees(handle: Long, deltaDegrees: Double)
    external fun flingByVelocity(handle: Long, velocityX: Float, velocityY: Float, viewWidth: Int, viewHeight: Int)
    external fun cancelCameraMotion(handle: Long)
    external fun projectCoordinates(handle: Long, lonLatHeight: DoubleArray): DoubleArray
    external fun screenToCoordinate(handle: Long, x: Double, y: Double): DoubleArray

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
    external fun add3DTilesRendererContent(
        handle: Long,
        id: String,
        renderUri: String,
        transformKind: String,
        transformMatrix: DoubleArray,
        ecefX: Double,
        ecefY: Double,
        ecefZ: Double,
        visible: Boolean
    )
    external fun remove3DTilesRendererContent(handle: Long, id: String)

    // Native 3D Tiles layer (C++ pipeline)
    external fun addTileset3DLayer(
        handle: Long,
        layerId: String,
        tilesetUri: String,
        headerKeys: Array<String>,
        headerValues: Array<String>,
        maxSSE: Float,
        maxTiles: Int
    )
    external fun removeTileset3DLayer(handle: Long, layerId: String)
    external fun setTileset3DLayerViewportHeight(handle: Long, height: Float)

    external fun renderMvtTile(
        handle: Long,
        sourceId: String,
        layerId: String,
        tileZ: Int,
        tileX: Int,
        tileY: Int,
        styleKind: String,
        visible: Boolean,
        color: Int,
        opacity: Float,
        widthPixels: Float,
        radiusPixels: Float,
        textSizeSp: Float,
        featureIds: Array<String>,
        sourceLayers: Array<String>,
        geometryTypes: IntArray,
        coordinateOffsets: IntArray,
        coordinates: DoubleArray,
        ringOffsets: IntArray,
        ringEnds: IntArray
    ): String
    external fun removeMvtTile(handle: Long, nativeTileHandle: String)
    external fun removeMvtLayer(handle: Long, layerId: String)
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
}
