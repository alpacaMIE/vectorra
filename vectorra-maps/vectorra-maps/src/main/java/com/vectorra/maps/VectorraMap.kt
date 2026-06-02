package com.vectorra.maps

import com.vectorra.maps.layer.VectorraRasterLayer
import com.vectorra.maps.annotation.VectorraLabelAnnotation
import com.vectorra.maps.annotation.VectorraDrawLineAnnotation
import com.vectorra.maps.annotation.VectorraDrawPointAnnotation
import com.vectorra.maps.annotation.VectorraDrawPolygonAnnotation
import com.vectorra.maps.location.VectorraLocationComponent
import com.vectorra.maps.query.VectorraAnnotationFeature
import com.vectorra.maps.query.VectorraGeoJsonFeature
import com.vectorra.maps.query.VectorraGeoJsonLayer
import com.vectorra.maps.query.VectorraGeoJsonSource
import com.vectorra.maps.query.VectorraMapClickListener
import com.vectorra.maps.query.VectorraQueriedFeature
import com.vectorra.maps.query.VectorraQueryOptions
import com.vectorra.maps.query.VectorraScreenPoint
import com.vectorra.maps.query.VectorraCoordinate
import com.vectorra.maps.network.TileNetworkConfig
import com.vectorra.maps.source.VectorraRasterTileSource
import java.io.Closeable

data class CameraState(
    val longitude: Double,
    val latitude: Double,
    val zoom: Double,
    val pitch: Double,
    val bearing: Double
)

data class CameraOptions(
    val longitude: Double? = null,
    val latitude: Double? = null,
    val zoom: Double? = null,
    val pitch: Double? = null,
    val bearing: Double? = null
)

data class CameraAnimationOptions(
    val durationMillis: Long = 300L
)

interface VectorraMap : Closeable {
    val cameraState: CameraState
    val location: VectorraLocationComponent
    var onCameraChanged: ((CameraState) -> Unit)?

    fun setCamera(
        longitude: Double = cameraState.longitude,
        latitude: Double = cameraState.latitude,
        zoom: Double = cameraState.zoom,
        pitch: Double = cameraState.pitch,
        bearing: Double = cameraState.bearing
    )

    fun setCamera(options: CameraOptions) {
        setCamera(
            longitude = options.longitude ?: cameraState.longitude,
            latitude = options.latitude ?: cameraState.latitude,
            zoom = options.zoom ?: cameraState.zoom,
            pitch = options.pitch ?: cameraState.pitch,
            bearing = options.bearing ?: cameraState.bearing
        )
    }

    fun panByPixels(deltaX: Float, deltaY: Float, viewWidth: Int, viewHeight: Int)

    fun zoomByScale(scale: Float)

    fun rotateBy(deltaDegrees: Double)

    fun pitchBy(deltaDegrees: Double)

    fun easeTo(
        options: CameraOptions,
        animationOptions: CameraAnimationOptions = CameraAnimationOptions()
    )

    fun flyTo(
        options: CameraOptions,
        animationOptions: CameraAnimationOptions = CameraAnimationOptions(durationMillis = 900L)
    )

    fun addCameraChangedListener(listener: (CameraState) -> Unit): Closeable

    fun addOnMapClickListener(listener: VectorraMapClickListener): Closeable

    fun queryRenderedFeatures(
        screenPoint: VectorraScreenPoint,
        options: VectorraQueryOptions = VectorraQueryOptions()
    ): List<VectorraQueriedFeature>

    fun pixelForCoordinate(coordinate: VectorraCoordinate): VectorraScreenPoint

    fun coordinateForPixel(screenPoint: VectorraScreenPoint): VectorraCoordinate

    fun addHitTestAnnotation(feature: VectorraAnnotationFeature)

    fun setHitTestAnnotations(features: List<VectorraAnnotationFeature>)

    fun removeHitTestAnnotation(id: String)

    fun clearHitTestAnnotations()

    fun setGeoJsonSource(source: VectorraGeoJsonSource)

    fun removeGeoJsonSource(sourceId: String)

    fun setGeoJsonLayer(layer: VectorraGeoJsonLayer)

    fun removeGeoJsonLayer(layerId: String)

    fun getGeoJsonClusterLeaves(
        sourceId: String,
        clusterId: Long,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0
    ): List<VectorraGeoJsonFeature>

    fun addRasterLayer(id: String, templateUrl: String, minZoom: Int = 0, maxZoom: Int = 18)

    fun addRasterLayer(layer: VectorraRasterLayer)

    fun addRasterLayer(source: VectorraRasterTileSource, layerId: String = source.id) {
        addRasterLayer(source.toRasterLayer(layerId))
    }

    fun setTileNetworkConfig(config: TileNetworkConfig)

    fun removeLayer(id: String)

    fun moveLayerToTop(id: String)

    fun setRasterLayerStyle(
        id: String,
        visible: Boolean = true,
        opacity: Double = 1.0,
        saturation: Double = 0.0,
        contrast: Double = 0.0
    )

    fun addElevationLayer(id: String, templateUrl: String, minZoom: Int = 0, maxZoom: Int = 14)

    fun setTerrainExaggeration(value: Double)

    fun setLayerVisible(id: String, visible: Boolean)

    fun clearAnnotations()

    fun addPointAnnotation(id: String, longitude: Double, latitude: Double)

    fun clearDrawAnnotations()

    fun addDrawPointAnnotation(annotation: VectorraDrawPointAnnotation)

    fun addDrawLineAnnotation(annotation: VectorraDrawLineAnnotation)

    fun addDrawPolygonAnnotation(annotation: VectorraDrawPolygonAnnotation)

    fun removeDrawAnnotation(id: String)

    fun clearLabelAnnotations()

    fun addLabelAnnotation(annotation: VectorraLabelAnnotation)

    fun setLabelAnnotations(annotations: List<VectorraLabelAnnotation>) {
        clearLabelAnnotations()
        annotations.forEach(::addLabelAnnotation)
    }
}
