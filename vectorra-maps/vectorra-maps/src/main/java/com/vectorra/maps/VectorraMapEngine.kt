package com.vectorra.maps

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.Choreographer
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
import com.vectorra.maps.mvt.VectorraMvtDecodedFeature
import com.vectorra.maps.mvt.hitRadiusPixels
import com.vectorra.maps.mvt.layerOpacity
import com.vectorra.maps.mvt.toDecodedFeatures
import com.vectorra.maps.mvt.toMvtRenderStyle
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
import com.vectorra.maps.query.VectorraCoordinateProjector
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
import com.vectorra.maps.offline.VectorraOfflineRegion
import com.vectorra.maps.offline.VectorraOfflineTileSource
import com.vectorra.maps.offline.VectorraPrefetchOptions
import com.vectorra.maps.offline.VectorraPrefetchResult
import com.vectorra.maps.offline.VectorraPrefetchTileResult
import com.vectorra.maps.offline.VectorraPrefetchProgressListener
import com.vectorra.maps.offline.VectorraPrefetchTask
import com.vectorra.maps.offline.VectorraPrefetchTaskRunner
import com.vectorra.maps.offline.toTileRequests
import com.vectorra.maps.terrain.VectorraTerrainOptions
import com.vectorra.maps.terrain.VectorraTerrainSource
import com.vectorra.maps.tiles3d.Vectorra3DTilesLayer
import com.vectorra.maps.tiles3d.Vectorra3DTilesOptions
import com.vectorra.maps.tiles3d.Vectorra3DTilesSource
import com.vectorra.maps.vector.VectorraVectorTileLayer
import com.vectorra.maps.vector.VectorraVectorTileSource
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal class VectorraMapEngine(cacheDirectory: File) : VectorraMap {
    private val closed = AtomicBoolean(false)
    private val nativeHandle: Long = VectorraNative.create()
    private val tileProxyServer = TileProxyServer(File(cacheDirectory, "rocky-tile-cache"))
    private val tileResourceFetcher = TileResourceFetcher(File(cacheDirectory, "vectorra-resource-cache"))
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
    private val nativeCameraCallback = object : VectorraNative.CameraCallback {
        override fun onNativeCameraChanged(
            longitude: Double,
            latitude: Double,
            zoom: Double,
            pitch: Double,
            bearing: Double
        ) {
            handleNativeCameraChanged(
                CameraState(
                    longitude = longitude,
                    latitude = latitude,
                    zoom = zoom,
                    pitch = pitch,
                    bearing = bearing
                )
            )
        }
    }
    private val nativeProjector = VectorraCoordinateProjector { coordinates ->
        if (coordinates.isEmpty()) {
            emptyList()
        } else {
            val nativeInput = DoubleArray(coordinates.size * COORDINATE_PROJECTION_INPUT_STRIDE)
            coordinates.forEachIndexed { index, coordinate ->
                val offset = index * COORDINATE_PROJECTION_INPUT_STRIDE
                nativeInput[offset] = coordinate.longitude
                nativeInput[offset + 1] = coordinate.latitude
                nativeInput[offset + 2] = 0.0
            }
            val nativeOutput = VectorraNative.projectCoordinates(nativeHandle, nativeInput)
            check(nativeOutput.size == coordinates.size * COORDINATE_PROJECTION_OUTPUT_STRIDE) {
                "Native projection is not ready or returned ${nativeOutput.size} values for " +
                    "${coordinates.size} coordinates."
            }
            coordinates.indices.map { index ->
                val offset = index * COORDINATE_PROJECTION_OUTPUT_STRIDE
                if (nativeOutput[offset + 2] >= 1.0) {
                    VectorraScreenPoint(
                        x = nativeOutput[offset],
                        y = nativeOutput[offset + 1]
                    )
                } else {
                    null
                }
            }
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
    private var cameraNotificationScheduled = false
    private val hitTester = VectorraAnnotationHitTester(
        projector = nativeProjector,
        camera = { cameraState }
    )
    private val geoJsonIndex = VectorraGeoJsonIndex(
        projector = nativeProjector,
        camera = { cameraState }
    )
    private val mapClickListeners = CopyOnWriteArrayList<VectorraMapClickListener>()
    private val mbTilesSources = CopyOnWriteArrayList<Closeable>()
    private val modelLayerIds = linkedSetOf<String>()
    private val vectorTileRuntimeLock = Any()
    private val vectorTileLayers = linkedMapOf<String, VectorraVectorTileRuntimeLayer>()
    private val pointHitAnnotationIds = linkedSetOf<String>()
    private val labelHitAnnotationIds = linkedSetOf<String>()
    private val drawHitAnnotationIds = linkedSetOf<String>()
    private var tileNetworkConfig = TileNetworkConfig()

    init {
        VectorraNative.setResourceStatusCallback(nativeHandle, nativeResourceStatusCallback)
        VectorraNative.setCameraCallback(nativeHandle, nativeCameraCallback)
    }

    fun attachSurface(surface: Surface, width: Int, height: Int): VectorraMapLoadError? {
        if (closed.get()) {
            return null
        }

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

    fun setCachePath(path: String) {
        ifOpen {
            VectorraNative.setCachePath(nativeHandle, path)
        }
    }

    fun detachSurface() {
        ifOpen {
            VectorraNative.setSurface(nativeHandle, null, 0, 0)
            loadState = VectorraMapLoadState.IDLE
        }
    }

    fun resize(width: Int, height: Int) {
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
        setNativeCamera(
            CameraState(
                longitude = longitude,
                latitude = latitude,
                zoom = zoom,
                pitch = pitch,
                bearing = bearing
            ),
            durationMillis = 0L
        )
    }

    override fun panByPixels(deltaX: Float, deltaY: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return
        }
        ifOpen {
            VectorraNative.panByPixels(nativeHandle, deltaX, deltaY, viewWidth, viewHeight)
        }
    }

    override fun zoomByScale(scale: Float) {
        if (scale <= 0f || !scale.isFinite()) {
            return
        }
        ifOpen {
            VectorraNative.zoomByScale(nativeHandle, scale)
        }
    }

    internal fun zoomByScaleAt(scale: Float, focusX: Float, focusY: Float, viewWidth: Int, viewHeight: Int) {
        if (scale <= 0f || !scale.isFinite()) {
            return
        }
        if (viewWidth <= 0 || viewHeight <= 0) {
            zoomByScale(scale)
            return
        }
        ifOpen {
            VectorraNative.zoomByScaleAt(nativeHandle, scale, focusX, focusY, viewWidth, viewHeight)
        }
    }

    override fun rotateBy(deltaDegrees: Double) {
        if (!deltaDegrees.isFinite()) {
            return
        }
        ifOpen {
            VectorraNative.rotateByDegrees(nativeHandle, deltaDegrees)
        }
    }

    override fun pitchBy(deltaDegrees: Double) {
        if (!deltaDegrees.isFinite()) {
            return
        }
        ifOpen {
            VectorraNative.pitchByDegrees(nativeHandle, deltaDegrees)
        }
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
        setNativeCamera(target, durationMillis.coerceAtLeast(0L))
    }

    private fun setNativeCamera(target: CameraState, durationMillis: Long) {
        ifOpen {
            VectorraNative.setCamera(
                nativeHandle,
                target.longitude,
                target.latitude,
                target.zoom,
                target.pitch,
                target.bearing,
                0.0,
                durationMillis
            )
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
        requireProjectionReady("queryRenderedFeatures")
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
        val decodedFeatures = VectorraNative.queryMvtRenderedFeatures(nativeHandle).toDecodedFeatures()
        val layerById = synchronized(vectorTileRuntimeLock) {
            vectorTileLayers.values.associateBy { it.layer.id }
        }
        val candidates = decodedFeatures.mapNotNull { feature ->
            val runtimeLayer = layerById[feature.renderLayerId()] ?: return@mapNotNull null
            feature.toHitFeature(runtimeLayer)
        }
        return hitTester.queryFeatures(candidates, screenPoint, options)
    }

    override fun pixelForCoordinate(coordinate: VectorraCoordinate): VectorraScreenPoint {
        requireProjectionReady("pixelForCoordinate")
        require(coordinate.longitude.isFinite() && coordinate.latitude.isFinite()) {
            "Coordinate must contain finite longitude and latitude."
        }
        return nativeProjector.project(listOf(coordinate)).singleOrNull()
            ?: throw IllegalStateException(
                "Native projection could not project coordinate " +
                    "longitude=${coordinate.longitude}, latitude=${coordinate.latitude}."
            )
    }

    override fun coordinateForPixel(screenPoint: VectorraScreenPoint): VectorraCoordinate {
        requireProjectionReady("coordinateForPixel")
        require(screenPoint.x.isFinite() && screenPoint.y.isFinite()) {
            "Screen point must contain finite x and y values."
        }
        val nativeResult = VectorraNative.screenToCoordinate(nativeHandle, screenPoint.x, screenPoint.y)
        check(nativeResult.size == SCREEN_TO_COORDINATE_OUTPUT_STRIDE) {
            "Native screen-to-coordinate projection is not ready."
        }
        if (nativeResult[2] < 1.0) {
            throw IllegalStateException(
                "Native projection could not resolve a ground coordinate for " +
                    "screen x=${screenPoint.x}, y=${screenPoint.y}."
            )
        }
        return VectorraCoordinate(
            longitude = nativeResult[0],
            latitude = nativeResult[1]
        )
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
            val disableNativeDiskCache = shouldDisableNativeDiskCache(
                tileProxyServer = tileProxyServer,
                templateUrl = proxiedTemplateUrl
            )
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
                    disableNativeDiskCache,
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
                    shouldDisableNativeDiskCache(
                        tileProxyServer = tileProxyServer,
                        templateUrl = proxiedTemplateUrl
                    ),
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
            val disableNativeDiskCache = shouldDisableNativeDiskCache(
                tileProxyServer = tileProxyServer,
                templateUrl = proxiedTemplateUrl
            )
            runResourceNativeCall(VectorraResourceKind.DEM, id, id) {
                VectorraNative.addElevationLayer(
                    nativeHandle,
                    id,
                    proxiedTemplateUrl,
                    minZoom,
                    maxZoom,
                    disableNativeDiskCache,
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
            val effectiveHeaders = tileNetworkConfig.headersFor(source.id) + source.headers
            val headerKeys = effectiveHeaders.keys.toTypedArray()
            val headerValues = effectiveHeaders.values.toTypedArray()
            VectorraNative.addTileset3DLayer(
                nativeHandle,
                layer.id,
                source.tilesetUri,
                headerKeys,
                headerValues,
                options.maximumScreenSpaceError.toFloat(),
                options.maximumLoadedTiles
            )
        }
    }

    override fun remove3DTilesLayer(id: String) {
        require(id.isNotBlank()) { "3D Tiles layer id must not be blank." }
        ifOpen {
            VectorraNative.removeTileset3DLayer(nativeHandle, id)
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
            val headers = tileNetworkConfig.headersFor(source.id) + source.headers
            val proxiedTemplateUrl = tileProxyServer.proxyTemplateFor(
                sourceId = source.id,
                layerId = layer.id,
                templateUrl = source.templateUrl,
                headers = headers,
                resourceType = TileResourceType.VECTOR
            )
            val nativeHeaders = if (proxiedTemplateUrl == source.templateUrl) headers else emptyMap()
            val style = layer.toMvtRenderStyle()
            synchronized(vectorTileRuntimeLock) {
                vectorTileLayers[layer.id] = VectorraVectorTileRuntimeLayer(
                    source = source,
                    layer = layer
                )
            }
            VectorraNative.addMvtLayer(
                nativeHandle,
                source.id,
                layer.id,
                layer.sourceLayer,
                proxiedTemplateUrl,
                source.minZoom,
                source.maxZoom,
                layer.minZoom,
                layer.maxZoom,
                source.tileSize,
                source.scheme.name,
                style.kind.name,
                style.visible,
                style.color,
                style.opacity,
                style.widthPixels,
                style.radiusPixels,
                style.textSizeSp,
                nativeHeaders.toNameArray(),
                nativeHeaders.toValueArray()
            )
        }
    }

    override fun removeVectorTileLayer(id: String) {
        require(id.isNotBlank()) { "Vector tile layer id must not be blank." }
        ifOpen {
            val removed = synchronized(vectorTileRuntimeLock) {
                vectorTileLayers.remove(id)
            }
            if (removed != null) {
                VectorraNative.removeMvtLayer(nativeHandle, id)
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
        if (mapClickListeners.isEmpty() || loadState != VectorraMapLoadState.READY || closed.get()) {
            return false
        }
        val screenPoint = VectorraScreenPoint(screenX.toDouble(), screenY.toDouble())
        val event = VectorraMapClickEvent(
            screenPoint = screenPoint,
            features = queryRenderedFeatures(screenPoint)
        )
        return mapClickListeners.any { listener -> listener(event) }
    }

    internal fun flingByVelocity(velocityX: Float, velocityY: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || !velocityX.isFinite() || !velocityY.isFinite()) {
            return
        }
        ifOpen {
            VectorraNative.flingByVelocity(nativeHandle, velocityX, velocityY, viewWidth, viewHeight)
        }
    }

    internal fun cancelCameraMotion() {
        ifOpen {
            VectorraNative.cancelCameraMotion(nativeHandle)
        }
    }

    internal fun cancelFling() {
        ifOpen {
            VectorraNative.cancelFling(nativeHandle)
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

    private fun handleNativeCameraChanged(camera: CameraState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyNativeCameraState(camera)
        } else {
            mainHandler.post {
                applyNativeCameraState(camera)
            }
        }
    }

    private fun applyNativeCameraState(camera: CameraState) {
        if (closed.get() || camera == cameraState) {
            return
        }
        cameraState = camera
        scheduleCameraNotification()
        (location as? VectorraLocationComponentImpl)?.onCameraChanged()
    }

    private inline fun ifOpen(block: () -> Unit) {
        if (!closed.get()) {
            block()
        }
    }

    private fun requireProjectionReady(operation: String) {
        check(!closed.get()) {
            "Vectorra map is closed; $operation requires an active native renderer."
        }
        check(loadState == VectorraMapLoadState.READY) {
            "Vectorra native projection is not ready; $operation requires an attached, ready map surface."
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

        override fun prefetchTiles(
            requests: List<TileRequest>,
            options: VectorraPrefetchOptions
        ): VectorraPrefetchResult {
            require(requests.isNotEmpty()) { "Prefetch requests must not be empty." }
            return VectorraPrefetchTaskRunner(
                requests = requests,
                options = options
            ) { request ->
                tileResourceFetcher.fetch(request).toPrefetchTileResult()
            }.await()
        }

        override fun prefetchRegion(
            region: VectorraOfflineRegion,
            sources: List<VectorraOfflineTileSource>,
            options: VectorraPrefetchOptions
        ): VectorraPrefetchResult {
            val requests = region.toTileRequests(sources)
            return if (requests.isEmpty()) {
                VectorraPrefetchResult(emptyList())
            } else {
                prefetchTiles(requests, options)
            }
        }

        override fun prefetchTilesAsync(
            requests: List<TileRequest>,
            options: VectorraPrefetchOptions,
            listener: VectorraPrefetchProgressListener?
        ): VectorraPrefetchTask {
            require(requests.isNotEmpty()) { "Prefetch requests must not be empty." }
            return VectorraPrefetchTaskRunner(
                requests = requests,
                options = options,
                listener = listener
            ) { request ->
                tileResourceFetcher.fetch(request).toPrefetchTileResult()
            }
        }

        override fun prefetchRegionAsync(
            region: VectorraOfflineRegion,
            sources: List<VectorraOfflineTileSource>,
            options: VectorraPrefetchOptions,
            listener: VectorraPrefetchProgressListener?
        ): VectorraPrefetchTask {
            val requests = region.toTileRequests(sources)
            return VectorraPrefetchTaskRunner(
                requests = requests,
                options = options,
                listener = listener
            ) { request ->
                tileResourceFetcher.fetch(request).toPrefetchTileResult()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            VectorraNative.setResourceStatusCallback(nativeHandle, null)
            VectorraNative.setCameraCallback(nativeHandle, null)
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
            synchronized(vectorTileRuntimeLock) {
                vectorTileLayers.clear()
            }
            VectorraNative.destroy(nativeHandle)
        }
    }

    private companion object {
        const val COORDINATE_PROJECTION_INPUT_STRIDE = 3
        const val COORDINATE_PROJECTION_OUTPUT_STRIDE = 3
        const val SCREEN_TO_COORDINATE_OUTPUT_STRIDE = 3
        const val LOG_TAG = "VectorraMapEngine"

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

private fun TileCacheStoreStatus.toPublicStatus(): VectorraCacheBucketStatus {
    return VectorraCacheBucketStatus(
        memoryEntryCount = memoryEntryCount,
        memoryBytes = memoryBytes,
        diskEntryCount = diskEntryCount,
        diskBytes = diskBytes
    )
}

internal fun shouldDisableNativeDiskCache(
    tileProxyServer: TileProxyServer,
    templateUrl: String
): Boolean {
    return tileProxyServer.isProxyTemplate(templateUrl)
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

private data class VectorraVectorTileRuntimeLayer(
    val source: VectorraVectorTileSource,
    val layer: VectorraVectorTileLayer
)

private fun VectorraMvtDecodedFeature.renderLayerId(): String? {
    return properties[MVT_RENDER_LAYER_PROPERTY]
}

private fun VectorraMvtDecodedFeature.toHitFeature(
    runtimeLayer: VectorraVectorTileRuntimeLayer
): VectorraAnnotationFeature {
    val layer = runtimeLayer.layer
    return VectorraAnnotationFeature(
        id = id,
        layerId = layer.id,
        geometry = geometry,
        properties = properties - MVT_RENDER_LAYER_PROPERTY + mapOf("source-layer" to layerName),
        sourceId = runtimeLayer.source.id,
        radiusPixels = layer.hitRadiusPixels(),
        visible = layer.visible,
        opacity = layer.layerOpacity(),
        minZoom = layer.minZoom.toDouble(),
        maxZoom = layer.maxZoom.toDouble()
    )
}

private const val MVT_RENDER_LAYER_PROPERTY = "__vectorra-render-layer"
