package com.vectorra.maps

import android.animation.ValueAnimator
import android.view.Surface
import android.view.Choreographer
import android.view.animation.DecelerateInterpolator
import com.vectorra.maps.annotation.VectorraDrawLineAnnotation
import com.vectorra.maps.annotation.VectorraDrawPointAnnotation
import com.vectorra.maps.annotation.VectorraDrawPolygonAnnotation
import com.vectorra.maps.annotation.VectorraLabelAnnotation
import com.vectorra.maps.annotation.toHitFeature
import com.vectorra.maps.annotation.toHitFeatures
import com.vectorra.maps.internal.VectorraNative
import com.vectorra.maps.layer.VectorraRasterLayer
import com.vectorra.maps.location.VectorraLocation
import com.vectorra.maps.location.VectorraLocationComponent
import com.vectorra.maps.location.VectorraLocationComponentImpl
import com.vectorra.maps.network.TileNetworkConfig
import com.vectorra.maps.network.TileProxyServer
import com.vectorra.maps.network.TileResourceType
import com.vectorra.maps.network.TileScheme
import com.vectorra.maps.query.VectorraAnnotationFeature
import com.vectorra.maps.query.VectorraAnnotationGeometry
import com.vectorra.maps.query.VectorraAnnotationHitTester
import com.vectorra.maps.query.VectorraCoordinate
import com.vectorra.maps.query.VectorraGeoJsonFeature
import com.vectorra.maps.query.VectorraGeoJsonIndex
import com.vectorra.maps.query.VectorraGeoJsonLayer
import com.vectorra.maps.query.VectorraGeoJsonSource
import com.vectorra.maps.query.VectorraMapClickEvent
import com.vectorra.maps.query.VectorraMapClickListener
import com.vectorra.maps.query.VectorraQueriedFeature
import com.vectorra.maps.query.VectorraQueryOptions
import com.vectorra.maps.query.VectorraScreenPoint
import com.vectorra.maps.offline.VectorraMbTilesRasterSource
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

internal class VectorraMapEngine(cacheDirectory: File) : VectorraMap {
    private val closed = AtomicBoolean(false)
    private val nativeHandle: Long = VectorraNative.create()
    private val tileProxyServer = TileProxyServer(File(cacheDirectory, "rocky-tile-cache"))
    override val location: VectorraLocationComponent = VectorraLocationComponentImpl(this)
    var loadState: VectorraMapLoadState = VectorraMapLoadState.IDLE
        private set
    var lastLoadError: VectorraMapLoadError? = null
        private set

    override var cameraState: CameraState = CameraState(
        longitude = 104.293174,
        latitude = 32.2857965,
        zoom = 2.0,
        pitch = 0.0,
        bearing = 0.0
    )
        private set

    override var onCameraChanged: ((CameraState) -> Unit)? = null
    private val cameraChangedListeners = CopyOnWriteArrayList<(CameraState) -> Unit>()
    private var nativeCameraApplyScheduled = false
    private var cameraNotificationScheduled = false
    private var cameraAnimator: ValueAnimator? = null
    private val hitTester = VectorraAnnotationHitTester()
    private val geoJsonIndex = VectorraGeoJsonIndex(
        project = { coordinate -> hitTester.pixelForCoordinate(coordinate) },
        camera = { cameraState }
    )
    private val mapClickListeners = CopyOnWriteArrayList<VectorraMapClickListener>()
    private val mbTilesSources = CopyOnWriteArrayList<VectorraMbTilesRasterSource>()
    private val pointHitAnnotationIds = linkedSetOf<String>()
    private val labelHitAnnotationIds = linkedSetOf<String>()
    private val drawHitAnnotationIds = linkedSetOf<String>()
    private var tileNetworkConfig = TileNetworkConfig()

    init {
        hitTester.setCamera(cameraState)
    }

    fun attachSurface(surface: Surface, width: Int, height: Int): VectorraMapLoadError? {
        if (closed.get()) {
            return null
        }

        hitTester.setViewport(width, height)
        loadState = VectorraMapLoadState.LOADING
        val failure = try {
            VectorraNative.setSurface(nativeHandle, surface, width, height)
        } catch (error: RuntimeException) {
            loadState = VectorraMapLoadState.ERROR
            return classifyLoadError(error.message ?: "Vectorra renderer initialization failed", error)
        } catch (error: LinkageError) {
            loadState = VectorraMapLoadState.ERROR
            return VectorraMapLoadError.NativeRenderer(
                message = error.message ?: "Vectorra native library is not available",
                cause = error
            ).also { lastLoadError = it }
        }

        return if (failure.isNullOrBlank()) {
            lastLoadError = null
            loadState = VectorraMapLoadState.READY
            applyCamera(cameraState)
            null
        } else {
            loadState = VectorraMapLoadState.ERROR
            classifyLoadError(failure, null)
        }
    }

    fun setResourcePath(path: String) {
        ifOpen {
            VectorraNative.setResourcePath(nativeHandle, path)
        }
    }

    fun detachSurface() {
        ifOpen {
            VectorraNative.setSurface(nativeHandle, null, 0, 0)
            loadState = VectorraMapLoadState.IDLE
        }
    }

    fun resize(width: Int, height: Int) {
        hitTester.setViewport(width, height)
        ifOpen {
            VectorraNative.resize(nativeHandle, width, height)
        }
    }

    override fun setCamera(
        longitude: Double,
        latitude: Double,
        zoom: Double,
        pitch: Double,
        bearing: Double
    ) {
        cameraAnimator?.cancel()
        setCameraInternal(longitude, latitude, zoom, pitch, bearing)
    }

    private fun setCameraInternal(
        longitude: Double,
        latitude: Double,
        zoom: Double,
        pitch: Double,
        bearing: Double
    ) {
        val next = CameraState(
            longitude = wrapLongitude(longitude),
            latitude = latitude.coerceIn(MIN_LATITUDE, MAX_LATITUDE),
            zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
            pitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH),
            bearing = normalizeBearing(bearing)
        )
        cameraState = next
        hitTester.setCamera(next)
        scheduleNativeCameraApply()
        scheduleCameraNotification()
        (location as? VectorraLocationComponentImpl)?.onCameraChanged()
    }

    override fun panByPixels(deltaX: Float, deltaY: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return
        }

        val bearingRadians = Math.toRadians(gestureVisualBearing(cameraState.bearing))
        val cosBearing = cos(bearingRadians)
        val sinBearing = sin(bearingRadians)
        val worldDeltaX = -deltaX.toDouble() * cosBearing - deltaY.toDouble() * sinBearing
        val worldDeltaY = deltaX.toDouble() * sinBearing - deltaY.toDouble() * cosBearing
        val center = worldPointFor(cameraState.longitude, cameraState.latitude, cameraState.zoom)
        val next = coordinateForWorldPoint(
            WorldPoint(
                x = center.x + worldDeltaX,
                y = center.y + worldDeltaY
            ),
            cameraState.zoom
        )
        setCamera(
            longitude = next.longitude,
            latitude = next.latitude,
            zoom = cameraState.zoom,
            pitch = cameraState.pitch,
            bearing = cameraState.bearing
        )
    }

    override fun zoomByScale(scale: Float) {
        val zoomDelta = zoomDeltaForScale(scale) ?: return
        setCamera(
            longitude = cameraState.longitude,
            latitude = cameraState.latitude,
            zoom = cameraState.zoom + zoomDelta,
            pitch = cameraState.pitch,
            bearing = cameraState.bearing
        )
    }

    internal fun zoomByScaleAt(scale: Float, focusX: Float, focusY: Float, viewWidth: Int, viewHeight: Int) {
        val zoomDelta = zoomDeltaForScale(scale) ?: return
        if (viewWidth <= 0 || viewHeight <= 0) {
            zoomByScale(scale)
            return
        }

        val current = cameraState
        val nextZoom = (current.zoom + zoomDelta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val focusOffsetX = focusX.toDouble() - viewWidth.toDouble() * 0.5
        val focusOffsetY = focusY.toDouble() - viewHeight.toDouble() * 0.5
        val currentCenter = worldPointFor(current.longitude, current.latitude, current.zoom)
        val visualBearing = gestureVisualBearing(current.bearing)
        val currentFocusOffset = screenOffsetToWorldOffset(focusOffsetX, focusOffsetY, visualBearing)
        val focusCoordinate = coordinateForWorldPoint(
            WorldPoint(
                x = currentCenter.x + currentFocusOffset.x,
                y = currentCenter.y + currentFocusOffset.y
            ),
            current.zoom
        )
        val nextFocusWorld = worldPointFor(focusCoordinate.longitude, focusCoordinate.latitude, nextZoom)
        val nextFocusOffset = screenOffsetToWorldOffset(focusOffsetX, focusOffsetY, visualBearing)
        val nextCenter = coordinateForWorldPoint(
            WorldPoint(
                x = nextFocusWorld.x - nextFocusOffset.x,
                y = nextFocusWorld.y - nextFocusOffset.y
            ),
            nextZoom
        )

        setCamera(
            longitude = nextCenter.longitude,
            latitude = nextCenter.latitude,
            zoom = nextZoom,
            pitch = current.pitch,
            bearing = current.bearing
        )
    }

    private fun zoomDeltaForScale(scale: Float): Double? {
        if (scale <= 0f || !scale.isFinite()) {
            return null
        }

        val zoomDelta = ln(scale.toDouble()) / ln(2.0)
        return zoomDelta.takeIf { it.isFinite() }
    }

    override fun rotateBy(deltaDegrees: Double) {
        if (!deltaDegrees.isFinite() || abs(deltaDegrees) < 0.01) {
            return
        }
        setCamera(
            longitude = cameraState.longitude,
            latitude = cameraState.latitude,
            zoom = cameraState.zoom,
            pitch = cameraState.pitch,
            bearing = cameraState.bearing + deltaDegrees
        )
    }

    override fun pitchBy(deltaDegrees: Double) {
        if (!deltaDegrees.isFinite() || abs(deltaDegrees) < 0.01) {
            return
        }
        setCamera(
            longitude = cameraState.longitude,
            latitude = cameraState.latitude,
            zoom = cameraState.zoom,
            pitch = cameraState.pitch + deltaDegrees,
            bearing = cameraState.bearing
        )
    }

    override fun easeTo(options: CameraOptions, animationOptions: CameraAnimationOptions) {
        animateCamera(options, animationOptions.durationMillis)
    }

    override fun flyTo(options: CameraOptions, animationOptions: CameraAnimationOptions) {
        animateCamera(options, animationOptions.durationMillis)
    }

    private fun animateCamera(options: CameraOptions, durationMillis: Long) {
        val start = cameraState
        val target = CameraState(
            longitude = options.longitude ?: start.longitude,
            latitude = options.latitude ?: start.latitude,
            zoom = options.zoom ?: start.zoom,
            pitch = options.pitch ?: start.pitch,
            bearing = options.bearing ?: start.bearing
        )
        val duration = durationMillis.coerceAtLeast(0L)
        cameraAnimator?.cancel()
        if (duration == 0L) {
            setCamera(
                longitude = target.longitude,
                latitude = target.latitude,
                zoom = target.zoom,
                pitch = target.pitch,
                bearing = target.bearing
            )
            return
        }

        val longitudeDelta = shortestLongitudeDelta(start.longitude, target.longitude)
        val bearingDelta = shortestBearingDelta(start.bearing, target.bearing)
        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                setCameraInternal(
                    longitude = start.longitude + longitudeDelta * t,
                    latitude = lerp(start.latitude, target.latitude, t),
                    zoom = lerp(start.zoom, target.zoom, t),
                    pitch = lerp(start.pitch, target.pitch, t),
                    bearing = start.bearing + bearingDelta * t
                )
            }
            start()
        }
    }

    override fun addCameraChangedListener(listener: (CameraState) -> Unit): Closeable {
        cameraChangedListeners.add(listener)
        return Closeable {
            cameraChangedListeners.remove(listener)
        }
    }

    override fun addOnMapClickListener(listener: VectorraMapClickListener): Closeable {
        mapClickListeners.add(listener)
        return Closeable {
            mapClickListeners.remove(listener)
        }
    }

    override fun queryRenderedFeatures(
        screenPoint: VectorraScreenPoint,
        options: VectorraQueryOptions
    ): List<VectorraQueriedFeature> {
        return (hitTester.query(screenPoint, options) + geoJsonIndex.query(screenPoint, options))
            .sortedWith(compareByDescending<VectorraQueriedFeature> { it.zIndex }.thenBy { it.distancePixels })
    }

    override fun pixelForCoordinate(coordinate: VectorraCoordinate): VectorraScreenPoint {
        return hitTester.pixelForCoordinate(coordinate)
    }

    override fun coordinateForPixel(screenPoint: VectorraScreenPoint): VectorraCoordinate {
        return hitTester.coordinateForPixel(screenPoint)
    }

    override fun addHitTestAnnotation(feature: VectorraAnnotationFeature) {
        hitTester.add(feature)
    }

    override fun setHitTestAnnotations(features: List<VectorraAnnotationFeature>) {
        pointHitAnnotationIds.clear()
        labelHitAnnotationIds.clear()
        hitTester.set(features)
    }

    override fun removeHitTestAnnotation(id: String) {
        pointHitAnnotationIds.remove(id)
        labelHitAnnotationIds.remove(id)
        hitTester.remove(id)
    }

    override fun clearHitTestAnnotations() {
        pointHitAnnotationIds.clear()
        labelHitAnnotationIds.clear()
        hitTester.clear()
    }

    override fun setGeoJsonSource(source: VectorraGeoJsonSource) {
        geoJsonIndex.setSource(source)
    }

    override fun removeGeoJsonSource(sourceId: String) {
        geoJsonIndex.removeSource(sourceId)
    }

    override fun setGeoJsonLayer(layer: VectorraGeoJsonLayer) {
        geoJsonIndex.setLayer(layer)
    }

    override fun removeGeoJsonLayer(layerId: String) {
        geoJsonIndex.removeLayer(layerId)
    }

    override fun getGeoJsonClusterLeaves(
        sourceId: String,
        clusterId: Long,
        limit: Int,
        offset: Int
    ): List<VectorraGeoJsonFeature> {
        return geoJsonIndex.getClusterLeaves(sourceId, clusterId, limit, offset)
    }

    override fun addRasterLayer(id: String, templateUrl: String, minZoom: Int, maxZoom: Int) {
        addRasterLayer(
            VectorraRasterLayer(
                id = id,
                templateUrl = templateUrl,
                minZoom = minZoom,
                maxZoom = maxZoom
            )
        )
    }

    override fun addRasterLayer(layer: VectorraRasterLayer) {
        ifOpen {
            val headers = tileNetworkConfig.headersFor(layer.sourceId) + layer.headers
            val proxiedTemplateUrl = tileProxyServer.proxyTemplateFor(
                sourceId = layer.sourceId,
                layerId = layer.id,
                templateUrl = layer.templateUrl,
                headers = headers,
                resourceType = TileResourceType.RASTER
            )
            val nativeHeaders = if (proxiedTemplateUrl == layer.templateUrl) headers else emptyMap()
            VectorraNative.addRasterLayer(
                nativeHandle,
                layer.id,
                proxiedTemplateUrl,
                layer.minZoom,
                layer.maxZoom,
                layer.visible,
                layer.opacity.coerceIn(0.0, 1.0),
                layer.saturation.coerceIn(-1.0, 1.0),
                layer.contrast.coerceIn(-1.0, 1.0),
                layer.tileSize,
                layer.scheme.name,
                layer.matrixSet.orEmpty(),
                nativeHeaders.toNameArray(),
                nativeHeaders.toValueArray()
            )
        }
    }

    override fun addMbTilesRasterLayer(source: VectorraMbTilesRasterSource, layerId: String) {
        require(layerId.isNotBlank()) { "MBTiles raster layer id must not be blank." }
        ifOpen {
            val proxiedTemplateUrl = tileProxyServer.proxyTemplateForLocalProvider(
                sourceId = source.id,
                layerId = layerId,
                resourceType = TileResourceType.RASTER,
                provider = source::loadTile
            )
            VectorraNative.addRasterLayer(
                nativeHandle,
                layerId,
                proxiedTemplateUrl,
                source.minZoom,
                source.maxZoom,
                true,
                1.0,
                0.0,
                0.0,
                source.tileSize,
                TileScheme.XYZ.name,
                "",
                emptyArray(),
                emptyArray()
            )
            mbTilesSources.addIfAbsent(source)
        }
    }

    override fun setTileNetworkConfig(config: TileNetworkConfig) {
        tileNetworkConfig = config
        tileProxyServer.updateConfig(config)
    }

    override fun removeLayer(id: String) {
        ifOpen {
            VectorraNative.removeLayer(nativeHandle, id)
        }
    }

    override fun moveLayerToTop(id: String) {
        ifOpen {
            VectorraNative.moveLayerToTop(nativeHandle, id)
        }
    }

    override fun setRasterLayerStyle(
        id: String,
        visible: Boolean,
        opacity: Double,
        saturation: Double,
        contrast: Double
    ) {
        ifOpen {
            VectorraNative.setRasterLayerStyle(
                nativeHandle,
                id,
                visible,
                opacity.coerceIn(0.0, 1.0),
                saturation.coerceIn(-1.0, 1.0),
                contrast.coerceIn(-1.0, 1.0)
            )
        }
    }

    override fun addElevationLayer(id: String, templateUrl: String, minZoom: Int, maxZoom: Int) {
        ifOpen {
            val headers = tileNetworkConfig.headersFor(id)
            val proxiedTemplateUrl = tileProxyServer.proxyTemplateFor(
                sourceId = id,
                layerId = id,
                templateUrl = templateUrl,
                headers = headers,
                resourceType = TileResourceType.DEM
            )
            val nativeHeaders = if (proxiedTemplateUrl == templateUrl) headers else emptyMap()
            VectorraNative.addElevationLayer(
                nativeHandle,
                id,
                proxiedTemplateUrl,
                minZoom,
                maxZoom,
                nativeHeaders.toNameArray(),
                nativeHeaders.toValueArray()
            )
        }
    }

    override fun setTerrainExaggeration(value: Double) {
        ifOpen {
            VectorraNative.setTerrainExaggeration(nativeHandle, value.coerceIn(0.0, 10.0))
        }
    }

    override fun setLayerVisible(id: String, visible: Boolean) {
        ifOpen {
            VectorraNative.setLayerVisible(nativeHandle, id, visible)
        }
    }

    override fun clearAnnotations() {
        ifOpen {
            VectorraNative.clearAnnotations(nativeHandle)
        }
        pointHitAnnotationIds.forEach(hitTester::remove)
        pointHitAnnotationIds.clear()
    }

    override fun addPointAnnotation(id: String, longitude: Double, latitude: Double) {
        ifOpen {
            VectorraNative.addPointAnnotation(nativeHandle, id, longitude, latitude)
        }
        pointHitAnnotationIds.add(id)
        hitTester.add(
            VectorraAnnotationFeature(
                id = id,
                layerId = "annotation",
                geometry = VectorraAnnotationGeometry.Point(VectorraCoordinate(longitude, latitude)),
                properties = mapOf("id" to id),
                radiusPixels = 16.0
            )
        )
    }

    override fun clearDrawAnnotations() {
        ifOpen {
            VectorraNative.clearDrawAnnotations(nativeHandle)
        }
        drawHitAnnotationIds.forEach(hitTester::remove)
        drawHitAnnotationIds.clear()
    }

    override fun addDrawPointAnnotation(annotation: VectorraDrawPointAnnotation) {
        removeDrawAnnotation(annotation.id)
        if (!annotation.coordinate.isValid()) {
            return
        }
        ifOpen {
            VectorraNative.addDrawPointAnnotation(
                nativeHandle,
                annotation.id,
                annotation.coordinate.longitude,
                annotation.coordinate.latitude,
                annotation.text,
                annotation.textSizeSp,
                annotation.textColor,
                annotation.textHaloColor,
                annotation.textHaloWidthPx,
                annotation.iconColor,
                annotation.iconRadiusPx
            )
        }
        val feature = annotation.toHitFeature()
        hitTester.add(feature)
        drawHitAnnotationIds.add(feature.id)
    }

    override fun addDrawLineAnnotation(annotation: VectorraDrawLineAnnotation) {
        removeDrawAnnotation(annotation.id)
        val coordinates = annotation.coordinates.filter { it.isValid() }
        if (coordinates.size < 2) {
            return
        }
        ifOpen {
            VectorraNative.addDrawLineAnnotation(
                nativeHandle,
                annotation.id,
                coordinates.toNativeCoordinateArray(),
                annotation.lineColor,
                annotation.lineWidthPixels
            )
        }
        val feature = annotation.copy(coordinates = coordinates).toHitFeature()
        hitTester.add(feature)
        drawHitAnnotationIds.add(feature.id)
    }

    override fun addDrawPolygonAnnotation(annotation: VectorraDrawPolygonAnnotation) {
        removeDrawAnnotation(annotation.id)
        val rings = annotation.rings
            .map { ring -> ring.filter { it.isValid() } }
            .filter { it.size >= 3 }
        if (rings.isEmpty()) {
            return
        }
        ifOpen {
            VectorraNative.addDrawPolygonAnnotation(
                nativeHandle,
                annotation.id,
                rings.flatten().toNativeCoordinateArray(),
                rings.toRingEnds(),
                annotation.fillColor,
                annotation.fillOpacity.toFloat(),
                annotation.outlineColor,
                annotation.outlineWidthPixels
            )
        }
        annotation.copy(rings = rings).toHitFeatures().forEach { feature ->
            hitTester.add(feature)
            drawHitAnnotationIds.add(feature.id)
        }
    }

    override fun removeDrawAnnotation(id: String) {
        ifOpen {
            VectorraNative.removeDrawAnnotation(nativeHandle, id)
        }
        listOf(
            "draw-point:$id",
            "draw-polyline:$id",
            "draw-polygon:$id",
            "draw-polygon-outline:$id"
        ).forEach { hitId ->
            hitTester.remove(hitId)
            drawHitAnnotationIds.remove(hitId)
        }
    }

    override fun clearLabelAnnotations() {
        ifOpen {
            VectorraNative.clearLabelAnnotations(nativeHandle)
        }
        labelHitAnnotationIds.forEach(hitTester::remove)
        labelHitAnnotationIds.clear()
    }

    override fun addLabelAnnotation(annotation: VectorraLabelAnnotation) {
        ifOpen {
            VectorraNative.addLabelAnnotation(
                nativeHandle,
                annotation.id,
                annotation.longitude,
                annotation.latitude,
                annotation.text,
                annotation.textSizeSp.toDouble(),
                annotation.textColor,
                annotation.textHaloColor,
                annotation.textHaloWidthPx.toDouble(),
                annotation.textOffsetXPx.toDouble(),
                annotation.textOffsetYPx.toDouble(),
                annotation.iconColor != null,
                annotation.iconColor ?: 0,
                annotation.iconRadiusPx.toDouble(),
                annotation.allowOverlap
            )
        }
        labelHitAnnotationIds.add(annotation.id)
        hitTester.add(
            VectorraAnnotationFeature(
                id = annotation.id,
                layerId = annotation.layerId,
                geometry = VectorraAnnotationGeometry.Point(
                    VectorraCoordinate(annotation.longitude, annotation.latitude)
                ),
                properties = annotation.properties + mapOf(
                    "id" to annotation.id,
                    "text" to annotation.text
                ),
                radiusPixels = annotation.hitRadiusPixels,
                zIndex = annotation.zIndex
            )
        )
    }

    internal fun dispatchMapClick(screenX: Float, screenY: Float): Boolean {
        if (mapClickListeners.isEmpty()) {
            return false
        }
        val screenPoint = VectorraScreenPoint(screenX.toDouble(), screenY.toDouble())
        val event = VectorraMapClickEvent(
            screenPoint = screenPoint,
            features = queryRenderedFeatures(screenPoint)
        )
        return mapClickListeners.any { listener -> listener(event) }
    }

    internal fun onTouch(action: Int, pointerCount: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        ifOpen {
            VectorraNative.onTouch(nativeHandle, action, pointerCount, x0, y0, x1, y1)
        }
    }

    internal fun onUserGesture() {
        (location as? VectorraLocationComponentImpl)?.onUserGesture()
    }

    internal fun setNativeLocationIndicator(
        location: VectorraLocation,
        bearingDegrees: Double,
        showAccuracyRing: Boolean,
        accuracyRadiusPixels: Double
    ) {
        ifOpen {
            VectorraNative.setLocationIndicator(
                nativeHandle,
                true,
                location.longitude,
                location.latitude,
                location.accuracyMeters ?: 0.0,
                bearingDegrees,
                showAccuracyRing,
                accuracyRadiusPixels
            )
        }
    }

    internal fun clearNativeLocationIndicator() {
        ifOpen {
            VectorraNative.clearLocationIndicator(nativeHandle)
        }
    }

    private fun applyCamera(camera: CameraState) {
        ifOpen {
            VectorraNative.setCamera(
                nativeHandle,
                camera.longitude,
                camera.latitude,
                camera.zoom,
                camera.pitch,
                camera.bearing
            )
        }
    }

    private fun scheduleNativeCameraApply() {
        if (nativeCameraApplyScheduled) {
            return
        }
        nativeCameraApplyScheduled = true
        Choreographer.getInstance().postFrameCallback {
            nativeCameraApplyScheduled = false
            applyCamera(cameraState)
        }
    }

    private fun scheduleCameraNotification() {
        if (cameraNotificationScheduled) {
            return
        }
        cameraNotificationScheduled = true
        Choreographer.getInstance().postFrameCallback {
            cameraNotificationScheduled = false
            val camera = cameraState
            onCameraChanged?.invoke(camera)
            cameraChangedListeners.forEach { it.invoke(camera) }
        }
    }

    private inline fun ifOpen(block: () -> Unit) {
        if (!closed.get()) {
            block()
        }
    }

    private fun classifyLoadError(message: String, cause: Throwable?): VectorraMapLoadError {
        val normalized = message.lowercase()
        return if (
            "vulkan" in normalized ||
            "physical device" in normalized ||
            "surface" in normalized ||
            "vk_" in normalized ||
            "vkcreate" in normalized
        ) {
            VectorraMapLoadError.UnsupportedDevice(message, cause)
        } else if ("resource" in normalized || "asset" in normalized || "file" in normalized) {
            VectorraMapLoadError.Resource(message, cause)
        } else {
            VectorraMapLoadError.NativeRenderer(message, cause)
        }.also {
            lastLoadError = it
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            cameraAnimator?.cancel()
            cameraAnimator = null
            nativeCameraApplyScheduled = false
            cameraNotificationScheduled = false
            loadState = VectorraMapLoadState.IDLE
            mapClickListeners.clear()
            cameraChangedListeners.clear()
            clearHitTestAnnotations()
            clearDrawAnnotations()
            geoJsonIndex.clear()
            tileProxyServer.close()
            mbTilesSources.forEach { it.close() }
            mbTilesSources.clear()
            VectorraNative.destroy(nativeHandle)
        }
    }

    private companion object {
        const val MIN_LATITUDE = -85.0
        const val MAX_LATITUDE = 85.0
        const val MIN_ZOOM = 0.0
        const val MAX_ZOOM = 22.0
        const val MIN_PITCH = 0.0
        const val MAX_PITCH = 80.0
        const val WEB_MERCATOR_TILE_SIZE = 256.0

        data class WorldPoint(val x: Double, val y: Double)

        fun wrapLongitude(longitude: Double): Double {
            var result = longitude
            while (result < -180.0) result += 360.0
            while (result > 180.0) result -= 360.0
            return result
        }

        fun worldSizeFor(zoom: Double): Double = WEB_MERCATOR_TILE_SIZE * 2.0.pow(zoom)

        fun worldPointFor(longitude: Double, latitude: Double, zoom: Double): WorldPoint {
            val worldSize = worldSizeFor(zoom)
            val clampedLatitude = latitude.coerceIn(MIN_LATITUDE, MAX_LATITUDE)
            val sinLatitude = sin(Math.toRadians(clampedLatitude))
            return WorldPoint(
                x = (wrapLongitude(longitude) + 180.0) / 360.0 * worldSize,
                y = (0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI)) * worldSize
            )
        }

        fun coordinateForWorldPoint(point: WorldPoint, zoom: Double): VectorraCoordinate {
            val worldSize = worldSizeFor(zoom)
            val normalizedX = ((point.x % worldSize) + worldSize) % worldSize
            val longitude = normalizedX / worldSize * 360.0 - 180.0
            val mercatorY = PI * (1.0 - 2.0 * point.y / worldSize)
            val latitude = Math.toDegrees(atan(sinh(mercatorY))).coerceIn(MIN_LATITUDE, MAX_LATITUDE)
            return VectorraCoordinate(longitude, latitude)
        }

        fun gestureVisualBearing(bearing: Double): Double = -bearing

        fun screenOffsetToWorldOffset(offsetX: Double, offsetY: Double, bearing: Double): WorldPoint {
            val bearingRadians = Math.toRadians(bearing)
            val cosBearing = cos(bearingRadians)
            val sinBearing = sin(bearingRadians)
            return WorldPoint(
                x = offsetX * cosBearing + offsetY * sinBearing,
                y = -offsetX * sinBearing + offsetY * cosBearing
            )
        }

        fun normalizeBearing(bearing: Double): Double {
            var result = bearing % 360.0
            if (result < 0.0) {
                result += 360.0
            }
            return result
        }

        fun shortestBearingDelta(from: Double, to: Double): Double {
            var delta = normalizeBearing(to) - normalizeBearing(from)
            if (delta > 180.0) delta -= 360.0
            if (delta < -180.0) delta += 360.0
            return delta
        }

        fun shortestLongitudeDelta(from: Double, to: Double): Double {
            var delta = wrapLongitude(to) - wrapLongitude(from)
            if (delta > 180.0) delta -= 360.0
            if (delta < -180.0) delta += 360.0
            return delta
        }

        fun lerp(from: Double, to: Double, fraction: Float): Double {
            return from + (to - from) * fraction
        }

        fun VectorraCoordinate.isValid(): Boolean {
            return longitude.isFinite() && latitude.isFinite() &&
                longitude in -180.0..180.0 && latitude in -90.0..90.0
        }

        fun List<VectorraCoordinate>.toNativeCoordinateArray(): DoubleArray {
            val values = DoubleArray(size * 2)
            forEachIndexed { index, coordinate ->
                values[index * 2] = coordinate.longitude
                values[index * 2 + 1] = coordinate.latitude
            }
            return values
        }

        fun List<List<VectorraCoordinate>>.toRingEnds(): IntArray {
            var end = 0
            return IntArray(size) { index ->
                end += this[index].size
                end
            }
        }

        fun Map<String, String>.toNameArray(): Array<String> = keys.toTypedArray()

        fun Map<String, String>.toValueArray(): Array<String> = values.toTypedArray()
    }
}
