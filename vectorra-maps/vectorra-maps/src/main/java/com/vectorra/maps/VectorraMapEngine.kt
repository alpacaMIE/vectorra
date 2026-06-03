package com.vectorra.maps

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.vectorra.maps.model.VectorraGlbModelLayerOptions
import com.vectorra.maps.model.VectorraGlbModelSource
import com.vectorra.maps.mvt.VectorraMvtJniRenderer
import com.vectorra.maps.mvt.VectorraMvtTileId
import com.vectorra.maps.mvt.VectorraMvtTileLoadResult
import com.vectorra.maps.mvt.asMvtTileLoader
import com.vectorra.maps.mvt.VectorraMvtRuntimeTileStore
import com.vectorra.maps.network.TileCacheStoreStatus
import com.vectorra.maps.network.TileNetworkConfig
import com.vectorra.maps.network.TileProxyServer
import com.vectorra.maps.network.TileRequest
import com.vectorra.maps.network.TileResponse
import com.vectorra.maps.network.TileResourceFetcher
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
import com.vectorra.maps.offline.VectorraCacheBucketStatus
import com.vectorra.maps.offline.VectorraCacheStatus
import com.vectorra.maps.offline.VectorraMbTilesRasterSource
import com.vectorra.maps.offline.VectorraMbTilesVectorSource
import com.vectorra.maps.offline.VectorraOfflineManager
import com.vectorra.maps.offline.VectorraPrefetchResult
import com.vectorra.maps.offline.VectorraPrefetchTileResult
import com.vectorra.maps.terrain.VectorraTerrainOptions
import com.vectorra.maps.terrain.VectorraTerrainSource
import com.vectorra.maps.tiles3d.Vectorra3DTilesLayer
import com.vectorra.maps.tiles3d.Vectorra3DTilesOptions
import com.vectorra.maps.tiles3d.Vectorra3DTilesSource
import com.vectorra.maps.tiles3d.Vectorra3DTilesCamera
import com.vectorra.maps.tiles3d.Vectorra3DTilesContentLifecycle
import com.vectorra.maps.tiles3d.Vectorra3DTilesContentLoadCompletion
import com.vectorra.maps.tiles3d.Vectorra3DTilesContentLoadTask
import com.vectorra.maps.tiles3d.Vectorra3DTilesContentRenderer
import com.vectorra.maps.tiles3d.Vectorra3DTilesRendererContentInput
import com.vectorra.maps.tiles3d.Vectorra3DTilesSpatial
import com.vectorra.maps.tiles3d.Vectorra3DTilesTilesetLoader
import com.vectorra.maps.tiles3d.Vectorra3DTilesTraversal
import com.vectorra.maps.tiles3d.Vectorra3DTilesTraversalOptions
import com.vectorra.maps.tiles3d.VectorraTileset3D
import com.vectorra.maps.vector.VectorraVectorTileLayer
import com.vectorra.maps.vector.VectorraVectorTileSource
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.sinh

internal class VectorraMapEngine(cacheDirectory: File) : VectorraMap {
    private val closed = AtomicBoolean(false)
    private val nativeHandle: Long = VectorraNative.create()
    private val tileProxyServer = TileProxyServer(File(cacheDirectory, "rocky-tile-cache"))
    private val tileResourceFetcher = TileResourceFetcher(File(cacheDirectory, "vectorra-resource-cache"))
    private val tiles3DContentCacheDirectory = File(cacheDirectory, "vectorra-3dtiles-content-cache")
    private val tiles3DTilesetLoader = Vectorra3DTilesTilesetLoader { request ->
        tileResourceFetcher.fetch(request)
    }
    private val tiles3DTraversal = Vectorra3DTilesTraversal()
    private val tiles3DContentRenderer = Native3DTilesContentRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nativeResourceStatusCallback = object : VectorraNative.ResourceStatusCallback {
        override fun onNativeResourceStatus(
            kind: String,
            layerId: String,
            state: String,
            errorType: String?,
            errorMessage: String?
        ) {
            handleNativeResourceStatus(kind, layerId, state, errorType, errorMessage)
        }
    }
    override val location: VectorraLocationComponent = VectorraLocationComponentImpl(this)
    override val offline: VectorraOfflineManager = EngineOfflineManager()
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
    private val resourceStatusListeners = CopyOnWriteArrayList<(VectorraResourceStatus) -> Unit>()
    private val resourceStatusLock = Any()
    private val resourceGenerationByKey = linkedMapOf<String, Long>()
    private val resourceStatusByLayerId = linkedMapOf<String, VectorraResourceStatus>()
    private val resourceStatusBySourceId = linkedMapOf<String, VectorraResourceStatus>()
    private var nativeCameraApplyScheduled = false
    private var cameraNotificationScheduled = false
    private var cameraAnimator: ValueAnimator? = null
    private val hitTester = VectorraAnnotationHitTester()
    private val geoJsonIndex = VectorraGeoJsonIndex(
        project = { coordinate -> hitTester.pixelForCoordinate(coordinate) },
        camera = { cameraState }
    )
    private val mapClickListeners = CopyOnWriteArrayList<VectorraMapClickListener>()
    private val mbTilesSources = CopyOnWriteArrayList<Closeable>()
    private val modelLayerIds = linkedSetOf<String>()
    private val tiles3DRuntimeLock = Any()
    private val tiles3DLayers = linkedMapOf<String, Vectorra3DTilesRuntimeLayer>()
    private val tiles3DLoadGenerationByLayerId = linkedMapOf<String, Long>()
    private var tiles3DTraversalScheduled = false
    private var viewportHeightPixels = DEFAULT_3D_TILES_VIEWPORT_HEIGHT_PIXELS
    private val vectorTileRuntimeLock = Any()
    private val vectorTileLayers = linkedMapOf<String, VectorraVectorTileRuntimeLayer>()
    private val vectorTileLoadGenerationByLayerId = linkedMapOf<String, Long>()
    private var vectorTileLoadScheduled = false
    private val pointHitAnnotationIds = linkedSetOf<String>()
    private val labelHitAnnotationIds = linkedSetOf<String>()
    private val drawHitAnnotationIds = linkedSetOf<String>()
    private var tileNetworkConfig = TileNetworkConfig()

    init {
        hitTester.setCamera(cameraState)
        VectorraNative.setResourceStatusCallback(nativeHandle, nativeResourceStatusCallback)
    }

    fun attachSurface(surface: Surface, width: Int, height: Int): VectorraMapLoadError? {
        if (closed.get()) {
            return null
        }

        hitTester.setViewport(width, height)
        viewportHeightPixels = height.coerceAtLeast(1)
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
            resubmitLoaded3DTilesContent()
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
        viewportHeightPixels = height.coerceAtLeast(1)
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
        schedule3DTilesTraversal()
        scheduleVectorTileLoads()
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

    override fun addResourceStatusListener(listener: (VectorraResourceStatus) -> Unit): Closeable {
        resourceStatusListeners.add(listener)
        return Closeable {
            resourceStatusListeners.remove(listener)
        }
    }

    override fun getLayerResourceStatus(layerId: String): VectorraResourceStatus? {
        require(layerId.isNotBlank()) { "Layer id must not be blank." }
        return synchronized(resourceStatusLock) {
            resourceStatusByLayerId[layerId]
        }
    }

    override fun getSourceResourceStatus(sourceId: String): VectorraResourceStatus? {
        require(sourceId.isNotBlank()) { "Source id must not be blank." }
        return synchronized(resourceStatusLock) {
            resourceStatusBySourceId[sourceId]
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
        return (
            hitTester.query(screenPoint, options) +
                geoJsonIndex.query(screenPoint, options) +
                queryVectorTileFeatures(screenPoint, options)
            )
            .sortedWith(compareByDescending<VectorraQueriedFeature> { it.zIndex }.thenBy { it.distancePixels })
    }

    private fun queryVectorTileFeatures(
        screenPoint: VectorraScreenPoint,
        options: VectorraQueryOptions
    ): List<VectorraQueriedFeature> {
        val candidates = synchronized(vectorTileRuntimeLock) {
            vectorTileLayers.values.flatMap { runtimeLayer ->
                runtimeLayer.store.queryHitFeatures()
            }
        }
        return hitTester.queryFeatures(candidates, screenPoint, options)
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
            emitResourceStatus(
                kind = VectorraResourceKind.RASTER,
                sourceId = layer.sourceId,
                layerId = layer.id,
                state = VectorraResourceLoadState.LOADING,
                eventSource = VectorraResourceEventSource.ENGINE
            )
            val headers = tileNetworkConfig.headersFor(layer.sourceId) + layer.headers
            val proxiedTemplateUrl = tileProxyServer.proxyTemplateFor(
                sourceId = layer.sourceId,
                layerId = layer.id,
                templateUrl = layer.templateUrl,
                headers = headers,
                resourceType = TileResourceType.RASTER
            )
            val nativeHeaders = if (proxiedTemplateUrl == layer.templateUrl) headers else emptyMap()
            runResourceNativeCall(VectorraResourceKind.RASTER, layer.sourceId, layer.id) {
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
    }

    override fun addMbTilesRasterLayer(source: VectorraMbTilesRasterSource, layerId: String) {
        require(layerId.isNotBlank()) { "MBTiles raster layer id must not be blank." }
        ifOpen {
            emitResourceStatus(
                kind = VectorraResourceKind.MBTILES,
                sourceId = source.id,
                layerId = layerId,
                state = VectorraResourceLoadState.LOADING,
                eventSource = VectorraResourceEventSource.ENGINE
            )
            val proxiedTemplateUrl = tileProxyServer.proxyTemplateForLocalProvider(
                sourceId = source.id,
                layerId = layerId,
                resourceType = TileResourceType.RASTER,
                provider = source::loadTile
            )
            runResourceNativeCall(VectorraResourceKind.MBTILES, source.id, layerId) {
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
            }
            mbTilesSources.addIfAbsent(source)
        }
    }

    override fun addMbTilesVectorLayer(
        source: VectorraMbTilesVectorSource,
        layer: VectorraVectorTileLayer
    ) {
        require(layer.sourceId == source.id) {
            "MBTiles vector layer sourceId must match source id."
        }
        ifOpen {
            val proxiedTemplateUrl = tileProxyServer.proxyTemplateForLocalProvider(
                sourceId = source.id,
                layerId = layer.id,
                resourceType = TileResourceType.VECTOR,
                provider = source::loadTile
            )
            val localSource = VectorraVectorTileSource.xyz(
                id = source.id,
                templateUrl = proxiedTemplateUrl,
                tileSize = source.tileSize,
                minZoom = source.minZoom,
                maxZoom = source.maxZoom
            )
            addVectorTileLayer(localSource, layer)
            mbTilesSources.addIfAbsent(source)
        }
    }

    override fun setTileNetworkConfig(config: TileNetworkConfig) {
        tileNetworkConfig = config
        tileProxyServer.updateConfig(config)
        tileResourceFetcher.updateConfig(config)
    }

    override fun removeLayer(id: String) {
        ifOpen {
            VectorraNative.removeLayer(nativeHandle, id)
            emitRemovedStatusForLayer(id)
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
        addElevationLayerInternal(
            id = id,
            templateUrl = templateUrl,
            minZoom = minZoom,
            maxZoom = maxZoom,
            sourceHeaders = emptyMap()
        )
    }

    private fun addElevationLayerInternal(
        id: String,
        templateUrl: String,
        minZoom: Int,
        maxZoom: Int,
        sourceHeaders: Map<String, String>
    ) {
        ifOpen {
            emitResourceStatus(
                kind = VectorraResourceKind.DEM,
                sourceId = id,
                layerId = id,
                state = VectorraResourceLoadState.LOADING,
                eventSource = VectorraResourceEventSource.ENGINE
            )
            val headers = tileNetworkConfig.headersFor(id) + sourceHeaders
            val proxiedTemplateUrl = tileProxyServer.proxyTemplateFor(
                sourceId = id,
                layerId = id,
                templateUrl = templateUrl,
                headers = headers,
                resourceType = TileResourceType.DEM
            )
            val nativeHeaders = if (proxiedTemplateUrl == templateUrl) headers else emptyMap()
            runResourceNativeCall(VectorraResourceKind.DEM, id, id) {
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
    }

    override fun setTerrainExaggeration(value: Double) {
        ifOpen {
            VectorraNative.setTerrainExaggeration(nativeHandle, value.coerceIn(0.0, 10.0))
        }
    }

    override fun addTerrain(source: VectorraTerrainSource, options: VectorraTerrainOptions) {
        addElevationLayerInternal(
            id = source.id,
            templateUrl = source.templateUrl,
            minZoom = source.minZoom,
            maxZoom = source.maxZoom,
            sourceHeaders = source.headers
        )
        setTerrainExaggeration(options.clampedExaggeration)
        setTerrainVisible(source.id, options.visible)
    }

    override fun removeTerrain(id: String) {
        removeLayer(id)
    }

    override fun setTerrainVisible(id: String, visible: Boolean) {
        setLayerVisible(id, visible)
    }

    override fun addModelLayer(source: VectorraGlbModelSource, options: VectorraGlbModelLayerOptions) {
        ifOpen {
            if (modelLayerIds.contains(source.id)) {
                VectorraNative.removeModelLayer(nativeHandle, source.id)
            }
            emitResourceStatus(
                kind = VectorraResourceKind.MODEL,
                sourceId = source.id,
                layerId = source.id,
                state = VectorraResourceLoadState.LOADING,
                eventSource = VectorraResourceEventSource.ENGINE
            )
            runResourceNativeCall(VectorraResourceKind.MODEL, source.id, source.id) {
                VectorraNative.addModelLayer(
                    nativeHandle,
                    source.id,
                    source.uri,
                    source.longitude,
                    source.latitude,
                    source.heightMeters,
                    source.scale * options.effectiveScaleMultiplier,
                    source.yawDegrees,
                    options.visible
                )
            }
            modelLayerIds.add(source.id)
        }
    }

    override fun removeModelLayer(id: String) {
        require(id.isNotBlank()) { "Model layer id must not be blank." }
        ifOpen {
            VectorraNative.removeModelLayer(nativeHandle, id)
            modelLayerIds.remove(id)
            emitRemovedStatusForLayer(id)
        }
    }

    override fun setModelLayerVisible(id: String, visible: Boolean) {
        require(id.isNotBlank()) { "Model layer id must not be blank." }
        ifOpen {
            VectorraNative.setModelLayerVisible(nativeHandle, id, visible)
        }
    }

    override fun add3DTilesLayer(
        source: Vectorra3DTilesSource,
        layer: Vectorra3DTilesLayer,
        options: Vectorra3DTilesOptions
    ) {
        require(layer.sourceId == source.id) {
            "3D Tiles layer sourceId must match source id."
        }
        ifOpen {
            emitResourceStatus(
                kind = VectorraResourceKind.TILES3D,
                sourceId = source.id,
                layerId = layer.id,
                state = VectorraResourceLoadState.LOADING,
                eventSource = VectorraResourceEventSource.ENGINE
            )
            val loadGeneration = synchronized(tiles3DRuntimeLock) {
                val nextGeneration = (tiles3DLoadGenerationByLayerId[layer.id] ?: 0L) + 1L
                tiles3DLoadGenerationByLayerId[layer.id] = nextGeneration
                tiles3DLayers.remove(layer.id)
                nextGeneration
            }
            Thread({
                load3DTilesLayer(source, layer.copy(options = options), loadGeneration)
            }, "Vectorra3DTiles-${layer.id}").start()
        }
    }

    override fun remove3DTilesLayer(id: String) {
        require(id.isNotBlank()) { "3D Tiles layer id must not be blank." }
        ifOpen {
            val runtimeLayer = synchronized(tiles3DRuntimeLock) {
                tiles3DLoadGenerationByLayerId[id] = (tiles3DLoadGenerationByLayerId[id] ?: 0L) + 1L
                tiles3DLayers.remove(id)
            }
            runtimeLayer?.contentLifecycle?.cancelAll()
            emitRemovedStatusForLayer(id)
        }
    }

    override fun addVectorTileLayer(
        source: VectorraVectorTileSource,
        layer: VectorraVectorTileLayer
    ) {
        require(layer.sourceId == source.id) {
            "Vector tile layer sourceId must match source id."
        }
        ifOpen {
            emitResourceStatus(
                kind = VectorraResourceKind.VECTOR,
                sourceId = source.id,
                layerId = layer.id,
                state = VectorraResourceLoadState.LOADING,
                eventSource = VectorraResourceEventSource.ENGINE
            )
            synchronized(vectorTileRuntimeLock) {
                val nextGeneration = (vectorTileLoadGenerationByLayerId[layer.id] ?: 0L) + 1L
                vectorTileLoadGenerationByLayerId[layer.id] = nextGeneration
                vectorTileLayers.remove(layer.id)?.store?.clear()
                vectorTileLayers[layer.id] = VectorraVectorTileRuntimeLayer(
                    source = source,
                    layer = layer,
                    loadGeneration = nextGeneration,
                    store = VectorraMvtRuntimeTileStore(
                        sourceId = source.id,
                        layer = layer,
                        nativeRenderer = VectorraMvtJniRenderer(nativeHandle)
                    ),
                    tileLoader = tileResourceFetcher.asMvtTileLoader()
                )
            }
            emitResourceStatus(
                kind = VectorraResourceKind.VECTOR,
                sourceId = source.id,
                layerId = layer.id,
                state = VectorraResourceLoadState.LOADED,
                eventSource = VectorraResourceEventSource.ENGINE
            )
            scheduleVectorTileLoads()
        }
    }

    override fun removeVectorTileLayer(id: String) {
        require(id.isNotBlank()) { "Vector tile layer id must not be blank." }
        ifOpen {
            val removed = synchronized(vectorTileRuntimeLock) {
                vectorTileLoadGenerationByLayerId[id] = (vectorTileLoadGenerationByLayerId[id] ?: 0L) + 1L
                vectorTileLayers.remove(id)
            }
            if (removed != null) {
                removed.store.clear()
                emitRemovedStatusForLayer(id)
            }
        }
    }

    @VectorraExperimentalApi("0.7.0-beta.1")
    @Deprecated(
        message = "Use the Vectorra3DTiles source/layer API when it is available. This method is only a model-rendering smoke entry.",
        replaceWith = ReplaceWith("addModelLayer(source, options)")
    )
    override fun add3DTilesModelLayer(source: VectorraGlbModelSource, options: VectorraGlbModelLayerOptions) {
        addModelLayer(source, options)
    }

    override fun setLayerVisible(id: String, visible: Boolean) {
        ifOpen {
            VectorraNative.setLayerVisible(nativeHandle, id, visible)
        }
    }

    private fun scheduleVectorTileLoads() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { scheduleVectorTileLoads() }
            return
        }
        if (vectorTileLoadScheduled || closed.get()) {
            return
        }
        vectorTileLoadScheduled = true
        Choreographer.getInstance().postFrameCallback {
            vectorTileLoadScheduled = false
            if (closed.get()) {
                return@postFrameCallback
            }
            updateVectorTileLayers(cameraState)
        }
    }

    private fun updateVectorTileLayers(camera: CameraState) {
        val tasks = mutableListOf<VectorraVectorTileRuntimeTask>()
        synchronized(vectorTileRuntimeLock) {
            vectorTileLayers.values.forEach { runtimeLayer ->
                val targetTileId = runtimeLayer.targetTileId(camera)
                val desiredTiles = targetTileId?.let { setOf(it) } ?: emptySet()
                (runtimeLayer.store.loadedTileIds() - desiredTiles).forEach(runtimeLayer.store::removeTile)
                if (targetTileId != null && targetTileId !in runtimeLayer.store.loadedTileIds()) {
                    tasks += VectorraVectorTileRuntimeTask(
                        layerId = runtimeLayer.layer.id,
                        loadGeneration = runtimeLayer.loadGeneration,
                        tileId = targetTileId
                    )
                }
            }
        }
        tasks.forEach { task ->
            Thread({
                loadVectorTileTask(task)
            }, "VectorraMvt-${task.layerId}-${task.tileId.z}-${task.tileId.x}-${task.tileId.y}").start()
        }
    }

    private fun loadVectorTileTask(task: VectorraVectorTileRuntimeTask) {
        val runtimeLayer = synchronized(vectorTileRuntimeLock) {
            vectorTileLayers[task.layerId]
        } ?: return
        val result = runtimeLayer.tileLoader.loadTile(
            source = runtimeLayer.source,
            layerId = runtimeLayer.layer.id,
            tileId = task.tileId
        )
        when (result) {
            is VectorraMvtTileLoadResult.Loaded -> {
                synchronized(vectorTileRuntimeLock) {
                    val current = vectorTileLayers[task.layerId] ?: return
                    if (current.loadGeneration == task.loadGeneration &&
                        current.targetTileId(cameraState) == task.tileId
                    ) {
                        current.store.putDecodedTile(task.tileId, result.decodedTile)
                    }
                }
            }
            is VectorraMvtTileLoadResult.Failed -> {
                val isCurrent = synchronized(vectorTileRuntimeLock) {
                    vectorTileLayers[task.layerId]?.loadGeneration == task.loadGeneration
                }
                if (!isCurrent) {
                    return
                }
                emitResourceStatus(
                    kind = VectorraResourceKind.VECTOR,
                    sourceId = runtimeLayer.source.id,
                    layerId = runtimeLayer.layer.id,
                    state = VectorraResourceLoadState.FAILED,
                    eventSource = VectorraResourceEventSource.ENGINE,
                    error = VectorraResourceLoadError(
                        type = result.errorType,
                        message = result.message
                    )
                )
            }
        }
    }

    internal fun loadVectorTileIntoStore(layerId: String, tileId: VectorraMvtTileId): VectorraMvtTileLoadResult? {
        val runtimeLayer = synchronized(vectorTileRuntimeLock) {
            vectorTileLayers[layerId]
        } ?: return null
        val result = runtimeLayer.tileLoader.loadTile(
            source = runtimeLayer.source,
            layerId = runtimeLayer.layer.id,
            tileId = tileId
        )
        when (result) {
            is VectorraMvtTileLoadResult.Loaded -> {
                synchronized(vectorTileRuntimeLock) {
                    vectorTileLayers[layerId]?.store?.putDecodedTile(tileId, result.decodedTile)
                }
            }
            is VectorraMvtTileLoadResult.Failed -> {
                emitResourceStatus(
                    kind = VectorraResourceKind.VECTOR,
                    sourceId = runtimeLayer.source.id,
                    layerId = runtimeLayer.layer.id,
                    state = VectorraResourceLoadState.FAILED,
                    eventSource = VectorraResourceEventSource.ENGINE,
                    error = VectorraResourceLoadError(
                        type = result.errorType,
                        message = result.message
                    )
                )
            }
        }
        return result
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
            val targetHeightMeters = tiles3DCameraTargetHeightMeters()
            VectorraNative.setCamera(
                nativeHandle,
                camera.longitude,
                camera.latitude,
                camera.zoom,
                camera.pitch,
                camera.bearing,
                targetHeightMeters
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

    private fun schedule3DTilesTraversal() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { schedule3DTilesTraversal() }
            return
        }
        if (tiles3DTraversalScheduled || closed.get()) {
            return
        }
        tiles3DTraversalScheduled = true
        Choreographer.getInstance().postFrameCallback {
            tiles3DTraversalScheduled = false
            if (closed.get()) {
                return@postFrameCallback
            }
            traverse3DTilesLayers(cameraState)
        }
    }

    private fun traverse3DTilesLayers(camera: CameraState) {
        val tasks = mutableListOf<Vectorra3DTilesRuntimeContentTask>()
        val failures = linkedMapOf<String, Pair<Vectorra3DTilesRuntimeLayer, String>>()
        synchronized(tiles3DRuntimeLock) {
            tiles3DLayers.values.forEach { runtimeLayer ->
                if (!runtimeLayer.layer.options.visible) {
                    runtimeLayer.contentLifecycle.cancelAll()
                    return@forEach
                }
                val traversalResult = tiles3DTraversal.traverse(
                    tileset = runtimeLayer.tileset,
                    camera = camera.to3DTilesCamera(runtimeLayer.cameraTargetHeightMeters),
                    options = Vectorra3DTilesTraversalOptions(
                        maximumScreenSpaceError = runtimeLayer.layer.options.maximumScreenSpaceError,
                        maximumLoadedTiles = runtimeLayer.layer.options.maximumLoadedTiles
                    ),
                    tileStates = runtimeLayer.contentLifecycle.tileLoadStates()
                )
                val lifecycleResult = runtimeLayer.contentLifecycle.applyTraversal(traversalResult)
                if (traversalResult.requests.isNotEmpty() ||
                    traversalResult.unloadTileIds.isNotEmpty() ||
                    lifecycleResult.failedTileIds.isNotEmpty()
                ) {
                    Log.i(
                        LOG_TAG,
                        "3D Tiles traversal layer=${runtimeLayer.layer.id} " +
                            "requests=${traversalResult.requests.size} " +
                            "unloads=${traversalResult.unloadTileIds.size} " +
                            "failures=${lifecycleResult.failedTileIds.size}"
                    )
                }
                lifecycleResult.failedTileIds.forEach { (tileId, reason) ->
                    failures[tileId] = runtimeLayer to reason
                }
                lifecycleResult.loadTasks.forEach { task ->
                    tasks += Vectorra3DTilesRuntimeContentTask(runtimeLayer.layer.id, runtimeLayer.loadGeneration, task)
                }
            }
        }
        failures.values.forEach { (runtimeLayer, reason) ->
            emit3DTilesLayerFailure(runtimeLayer, reason, null)
        }
        tasks.forEach { task ->
            Thread({
                load3DTilesContent(task)
            }, "Vectorra3DTilesContent-${task.layerId}-${task.task.tileId}").start()
        }
    }

    private fun load3DTilesContent(runtimeTask: Vectorra3DTilesRuntimeContentTask) {
        if (closed.get()) {
            return
        }
        val response = tileResourceFetcher.fetch(
            request = runtimeTask.task.request,
            priority = runtimeTask.task.priority
        )
        var failure: Pair<Vectorra3DTilesRuntimeLayer, String>? = null
        synchronized(tiles3DRuntimeLock) {
            val runtimeLayer = tiles3DLayers[runtimeTask.layerId] ?: return
            if (runtimeLayer.loadGeneration != runtimeTask.loadGeneration ||
                tiles3DLoadGenerationByLayerId[runtimeTask.layerId] != runtimeTask.loadGeneration
            ) {
                return
            }
            val completion = runtimeLayer.contentLifecycle.completeRemoteLoad(runtimeTask.task, response)
            if (completion == Vectorra3DTilesContentLoadCompletion.FAILED) {
                failure = runtimeLayer to (
                    response.errorMessage ?: "3D Tiles content request or preparation failed."
                )
            }
        }
        failure?.let { (runtimeLayer, reason) ->
            emit3DTilesLayerFailure(runtimeLayer, reason, null)
        }
    }

    private fun emit3DTilesLayerFailure(
        runtimeLayer: Vectorra3DTilesRuntimeLayer,
        message: String,
        cause: Throwable?
    ) {
        emitResourceStatus(
            kind = VectorraResourceKind.TILES3D,
            sourceId = runtimeLayer.source.id,
            layerId = runtimeLayer.layer.id,
            state = VectorraResourceLoadState.FAILED,
            eventSource = VectorraResourceEventSource.ENGINE,
            error = VectorraResourceLoadError(
                type = VectorraResourceErrorType.RESOURCE,
                message = message,
                cause = cause
            )
        )
    }

    private fun CameraState.to3DTilesCamera(targetHeightMeters: Double = 0.0): Vectorra3DTilesCamera {
        val viewportHeight = viewportHeightPixels.coerceAtLeast(1)
        val target = Vectorra3DTilesSpatial.wgs84DegreesToEcef(
            longitude,
            latitude,
            targetHeightMeters
        )
        val position = Vectorra3DTilesSpatial.wgs84DegreesToEcef(
            longitude,
            latitude,
            targetHeightMeters + cameraRangeMetersForZoom(zoom, latitude, viewportHeight)
        )
        val directionLength = sqrt(
            (target.x - position.x) * (target.x - position.x) +
                (target.y - position.y) * (target.y - position.y) +
                (target.z - position.z) * (target.z - position.z)
        ).coerceAtLeast(1.0)
        return Vectorra3DTilesCamera(
            positionX = position.x,
            positionY = position.y,
            positionZ = position.z,
            directionX = (target.x - position.x) / directionLength,
            directionY = (target.y - position.y) / directionLength,
            directionZ = (target.z - position.z) / directionLength,
            verticalFovDegrees = 60.0,
            viewportHeightPixels = viewportHeight
        )
    }

    private fun tiles3DCameraTargetHeightMeters(): Double {
        return synchronized(tiles3DRuntimeLock) {
            tiles3DLayers.values
                .firstOrNull { it.layer.options.visible }
                ?.cameraTargetHeightMeters
                ?: 0.0
        }
    }

    private inner class Native3DTilesContentRenderer : Vectorra3DTilesContentRenderer {
        override fun addContent(input: Vectorra3DTilesRendererContentInput) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { addContent(input) }
                return
            }
            ifOpen {
                VectorraNative.add3DTilesRendererContent(
                    handle = nativeHandle,
                    id = input.nativeContentId,
                    renderUri = input.renderUri,
                    transformKind = input.nativeTransformKind(),
                    transformMatrix = input.nativeMatrix(),
                    ecefX = input.nativeEcefOrigin()[0],
                    ecefY = input.nativeEcefOrigin()[1],
                    ecefZ = input.nativeEcefOrigin()[2],
                    visible = input.visible
                )
            }
        }

        override fun removeContent(nativeContentId: String) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { removeContent(nativeContentId) }
                return
            }
            ifOpen {
                VectorraNative.remove3DTilesRendererContent(nativeHandle, nativeContentId)
            }
        }
    }

    private fun resubmitLoaded3DTilesContent() {
        val resubmitted = synchronized(tiles3DRuntimeLock) {
            tiles3DLayers.values.flatMap { runtimeLayer ->
                runtimeLayer.contentLifecycle.resubmitLoadedContent()
            }
        }
        if (resubmitted.isNotEmpty()) {
            Log.i(LOG_TAG, "resubmitted 3D Tiles renderer content count=${resubmitted.size}")
        }
    }

    private inline fun ifOpen(block: () -> Unit) {
        if (!closed.get()) {
            block()
        }
    }

    private inline fun runResourceNativeCall(
        kind: VectorraResourceKind,
        sourceId: String,
        layerId: String,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (error: RuntimeException) {
            emitResourceStatus(
                kind = kind,
                sourceId = sourceId,
                layerId = layerId,
                state = VectorraResourceLoadState.FAILED,
                eventSource = VectorraResourceEventSource.ENGINE,
                error = VectorraResourceLoadError(
                    type = VectorraResourceErrorType.NATIVE_RENDERER,
                    message = error.message ?: "Native renderer resource operation failed.",
                    cause = error
                )
            )
            throw error
        }
    }

    private fun load3DTilesLayer(
        source: Vectorra3DTilesSource,
        layer: Vectorra3DTilesLayer,
        loadGeneration: Long
    ) {
        if (closed.get()) {
            return
        }

        try {
            val tileset = tiles3DTilesetLoader.load(
                source = source,
                headers = tileNetworkConfig.headersFor(source.id) + source.headers
            )
            if (closed.get()) {
                return
            }
            synchronized(tiles3DRuntimeLock) {
                if (tiles3DLoadGenerationByLayerId[layer.id] != loadGeneration) {
                    return
                } else {
                    val cameraTargetHeightMeters = tileset.cameraTargetHeightMeters()
                    tiles3DLayers[layer.id] = Vectorra3DTilesRuntimeLayer(
                        source = source,
                        layer = layer,
                        tileset = tileset,
                        loadGeneration = loadGeneration,
                        cameraTargetHeightMeters = cameraTargetHeightMeters,
                        contentLifecycle = Vectorra3DTilesContentLifecycle(
                            layerId = layer.id,
                            sourceId = source.id,
                            headers = tileNetworkConfig.headersFor(source.id) + source.headers,
                            contentCacheDirectory = File(tiles3DContentCacheDirectory, layer.id),
                            renderer = tiles3DContentRenderer
                        )
                    )
                    Log.i(
                        LOG_TAG,
                        "3D Tiles layer=${layer.id} cameraTargetHeightMeters=$cameraTargetHeightMeters"
                    )
                    emitResourceStatus(
                        kind = VectorraResourceKind.TILES3D,
                        sourceId = source.id,
                        layerId = layer.id,
                        state = VectorraResourceLoadState.LOADED,
                        eventSource = VectorraResourceEventSource.ENGINE
                    )
                }
            }
            mainHandler.post { applyCamera(cameraState) }
            schedule3DTilesTraversal()
        } catch (error: Throwable) {
            if (closed.get()) {
                return
            }
            synchronized(tiles3DRuntimeLock) {
                if (tiles3DLoadGenerationByLayerId[layer.id] != loadGeneration) {
                    return
                }
                emitResourceStatus(
                    kind = VectorraResourceKind.TILES3D,
                    sourceId = source.id,
                    layerId = layer.id,
                    state = VectorraResourceLoadState.FAILED,
                    eventSource = VectorraResourceEventSource.ENGINE,
                    error = VectorraResourceLoadError(
                        type = error.to3DTilesResourceErrorType(),
                        message = error.message ?: "3D Tiles tileset load failed.",
                        cause = error
                    )
                )
            }
        }
    }

    private fun Throwable.to3DTilesResourceErrorType(): VectorraResourceErrorType {
        return when (this) {
            is IOException -> VectorraResourceErrorType.NETWORK
            is IllegalArgumentException -> VectorraResourceErrorType.RESOURCE
            else -> VectorraResourceErrorType.UNKNOWN
        }
    }

    private fun emitRemovedStatusForLayer(layerId: String) {
        val previous = synchronized(resourceStatusLock) {
            resourceStatusByLayerId[layerId]
        } ?: return
        emitResourceStatus(
            kind = previous.kind,
            sourceId = previous.sourceId,
            layerId = previous.layerId,
            state = VectorraResourceLoadState.REMOVED,
            eventSource = VectorraResourceEventSource.ENGINE
        )
    }

    private fun handleNativeResourceStatus(
        rawKind: String,
        layerId: String,
        rawState: String,
        rawErrorType: String?,
        rawErrorMessage: String?
    ) {
        if (closed.get() || layerId.isBlank()) {
            return
        }
        val previous = synchronized(resourceStatusLock) {
            resourceStatusByLayerId[layerId]
        } ?: return
        val update = VectorraNativeResourceStatusMapper.map(
            previous = previous,
            rawKind = rawKind,
            rawState = rawState,
            rawErrorType = rawErrorType,
            rawErrorMessage = rawErrorMessage
        ) ?: return
        emitResourceStatus(
            kind = update.kind,
            sourceId = update.sourceId,
            layerId = update.layerId,
            state = update.state,
            eventSource = VectorraResourceEventSource.NATIVE,
            error = update.error
        )
    }

    internal fun emitResourceStatus(
        kind: VectorraResourceKind,
        sourceId: String,
        layerId: String,
        state: VectorraResourceLoadState,
        eventSource: VectorraResourceEventSource,
        error: VectorraResourceLoadError? = null
    ) {
        val status = synchronized(resourceStatusLock) {
            val key = "$kind:$sourceId:$layerId"
            val generation = (resourceGenerationByKey[key] ?: 0L) + 1L
            resourceGenerationByKey[key] = generation
            VectorraResourceStatus(
                kind = kind,
                sourceId = sourceId,
                layerId = layerId,
                generation = generation,
                state = state,
                eventSource = eventSource,
                error = error
            ).also {
                resourceStatusByLayerId[layerId] = it
                resourceStatusBySourceId[sourceId] = it
            }
        }
        dispatchResourceStatus(status)
    }

    private fun dispatchResourceStatus(status: VectorraResourceStatus) {
        if (resourceStatusListeners.isEmpty()) {
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            resourceStatusListeners.forEach { it.invoke(status) }
        } else {
            mainHandler.post {
                resourceStatusListeners.forEach { it.invoke(status) }
            }
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

    private inner class EngineOfflineManager : VectorraOfflineManager {
        override fun cacheStatus(): VectorraCacheStatus {
            return VectorraCacheStatus(
                proxy = tileProxyServer.cacheStatus().toPublicStatus(),
                resources = tileResourceFetcher.cacheStatus().toPublicStatus()
            )
        }

        override fun clearCache() {
            tileProxyServer.clearCache()
            tileResourceFetcher.clearCache()
        }

        override fun prefetchTiles(requests: List<TileRequest>): VectorraPrefetchResult {
            return VectorraPrefetchResult(
                tiles = tileResourceFetcher.prefetch(requests).map { it.toPrefetchTileResult() }
            )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            VectorraNative.setResourceStatusCallback(nativeHandle, null)
            cameraAnimator?.cancel()
            cameraAnimator = null
            nativeCameraApplyScheduled = false
            cameraNotificationScheduled = false
            loadState = VectorraMapLoadState.IDLE
            mapClickListeners.clear()
            cameraChangedListeners.clear()
            resourceStatusListeners.clear()
            synchronized(resourceStatusLock) {
                resourceGenerationByKey.clear()
                resourceStatusByLayerId.clear()
                resourceStatusBySourceId.clear()
            }
            clearHitTestAnnotations()
            clearDrawAnnotations()
            geoJsonIndex.clear()
            tileProxyServer.close()
            tileResourceFetcher.close()
            mbTilesSources.forEach { it.close() }
            mbTilesSources.clear()
            modelLayerIds.clear()
            synchronized(tiles3DRuntimeLock) {
                tiles3DLayers.clear()
                tiles3DLoadGenerationByLayerId.clear()
                tiles3DTraversalScheduled = false
            }
            synchronized(vectorTileRuntimeLock) {
                vectorTileLayers.values.forEach { it.store.clear() }
                vectorTileLayers.clear()
                vectorTileLoadGenerationByLayerId.clear()
                vectorTileLoadScheduled = false
            }
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
        const val LOG_TAG = "VectorraMapEngine"

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

        fun cameraRangeMetersForZoom(
            zoom: Double,
            latitude: Double,
            viewportHeightPixels: Int = DEFAULT_3D_TILES_VIEWPORT_HEIGHT_PIXELS
        ): Double {
            return vectorraNativeCameraRangeMetersForZoom(
                zoom = zoom,
                latitude = latitude,
                minZoom = MIN_ZOOM,
                maxZoom = MAX_ZOOM,
                viewportHeightPixels = viewportHeightPixels
            )
        }

        private const val DEFAULT_3D_TILES_VIEWPORT_HEIGHT_PIXELS = 1080
    }
}

internal fun VectorraTileset3D.cameraTargetHeightMeters(): Double {
    val sphere = Vectorra3DTilesSpatial.boundingSphere(
        root.boundingVolume,
        root.transform ?: Vectorra3DTilesSpatial.IDENTITY_MATRIX
    )
    return Vectorra3DTilesSpatial.ecefToWgs84(sphere.center).heightMeters.takeIf(Double::isFinite) ?: 0.0
}

private fun TileCacheStoreStatus.toPublicStatus(): VectorraCacheBucketStatus {
    return VectorraCacheBucketStatus(
        memoryEntryCount = memoryEntryCount,
        memoryBytes = memoryBytes,
        diskEntryCount = diskEntryCount,
        diskBytes = diskBytes
    )
}

private fun TileResponse.toPrefetchTileResult(): VectorraPrefetchTileResult {
    return VectorraPrefetchTileResult(
        request = request,
        statusCode = statusCode,
        cacheStatus = cacheStatus,
        byteCount = body.size,
        errorMessage = errorMessage
    )
}

private data class Vectorra3DTilesRuntimeLayer(
    val source: Vectorra3DTilesSource,
    val layer: Vectorra3DTilesLayer,
    val tileset: VectorraTileset3D,
    val loadGeneration: Long,
    val cameraTargetHeightMeters: Double,
    val contentLifecycle: Vectorra3DTilesContentLifecycle
)

private data class Vectorra3DTilesRuntimeContentTask(
    val layerId: String,
    val loadGeneration: Long,
    val task: Vectorra3DTilesContentLoadTask
)

private data class VectorraVectorTileRuntimeLayer(
    val source: VectorraVectorTileSource,
    val layer: VectorraVectorTileLayer,
    val loadGeneration: Long,
    val store: VectorraMvtRuntimeTileStore,
    val tileLoader: com.vectorra.maps.mvt.VectorraMvtTileLoader
) {
    fun targetTileId(camera: CameraState): VectorraMvtTileId? {
        if (!layer.visible) {
            return null
        }
        val minZoom = maxOf(source.minZoom, layer.minZoom)
        val maxZoom = minOf(source.maxZoom, layer.maxZoom)
        if (camera.zoom < minZoom || camera.zoom > maxZoom) {
            return null
        }

        val z = floor(camera.zoom).toInt().coerceIn(minZoom, maxZoom)
        val tileCount = 1 shl z
        val x = floor(((camera.longitude + 180.0) / 360.0) * tileCount)
            .toInt()
            .floorMod(tileCount)
        val latitude = camera.latitude.coerceIn(
            MIN_LATITUDE_FOR_MVT_TILE,
            MAX_LATITUDE_FOR_MVT_TILE
        )
        val sinLatitude = sin(Math.toRadians(latitude))
        val y = floor(
            (0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI)) * tileCount
        ).toInt().coerceIn(0, tileCount - 1)
        return VectorraMvtTileId(z = z, x = x, y = y)
    }

}

private data class VectorraVectorTileRuntimeTask(
    val layerId: String,
    val loadGeneration: Long,
    val tileId: VectorraMvtTileId
)

private fun Int.floorMod(modulus: Int): Int {
    return ((this % modulus) + modulus) % modulus
}

private const val MIN_LATITUDE_FOR_MVT_TILE = -85.0511287798066
private const val MAX_LATITUDE_FOR_MVT_TILE = 85.0511287798066
