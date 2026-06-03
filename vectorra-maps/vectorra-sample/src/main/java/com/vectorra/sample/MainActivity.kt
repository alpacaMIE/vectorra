package com.vectorra.sample

import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vectorra.maps.CameraOptions
import com.vectorra.maps.VectorraMap
import com.vectorra.maps.VectorraMapLifecycleCallback
import com.vectorra.maps.VectorraMapLoadError
import com.vectorra.maps.VectorraMapView
import com.vectorra.maps.VectorraResourceKind
import com.vectorra.maps.VectorraResourceLoadState
import com.vectorra.maps.VectorraSdk
import com.vectorra.maps.VectorraSurfaceLifecycleState
import com.vectorra.maps.annotation.VectorraDrawLineAnnotation
import com.vectorra.maps.annotation.VectorraDrawPointAnnotation
import com.vectorra.maps.annotation.VectorraDrawPolygonAnnotation
import com.vectorra.maps.location.VectorraFollowMode
import com.vectorra.maps.location.VectorraLocation
import com.vectorra.maps.model.VectorraGlbModelLayerOptions
import com.vectorra.maps.model.VectorraGlbModelSource
import com.vectorra.maps.offline.VectorraCacheStatus
import com.vectorra.maps.offline.VectorraOfflineBounds
import com.vectorra.maps.offline.VectorraMbTilesRasterSource
import com.vectorra.maps.offline.VectorraMbTilesVectorSource
import com.vectorra.maps.offline.VectorraOfflineRegion
import com.vectorra.maps.offline.VectorraOfflineTileSource
import com.vectorra.maps.offline.VectorraPrefetchOptions
import com.vectorra.maps.offline.VectorraPrefetchTask
import com.vectorra.maps.offline.VectorraPrefetchTaskState
import com.vectorra.maps.query.VectorraAnnotationGeometry
import com.vectorra.maps.query.VectorraCoordinate
import com.vectorra.maps.query.VectorraGeoJsonFeature
import com.vectorra.maps.query.VectorraGeoJsonLayer
import com.vectorra.maps.query.VectorraGeoJsonSource
import com.vectorra.maps.query.VectorraQueriedFeature
import com.vectorra.maps.query.VectorraQueryOptions
import com.vectorra.maps.query.VectorraScreenPoint
import com.vectorra.maps.terrain.VectorraTerrainOptions
import com.vectorra.maps.terrain.VectorraTerrainSource
import com.vectorra.maps.tiles3d.Vectorra3DTilesLayer
import com.vectorra.maps.tiles3d.Vectorra3DTilesOptions
import com.vectorra.maps.tiles3d.Vectorra3DTilesSource
import com.vectorra.maps.vector.VectorraVectorTileLayer
import com.vectorra.maps.vector.VectorraVectorTileSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity() {
    private lateinit var mapView: VectorraMapView
    private lateinit var statusText: TextView
    private var terrainExaggeration = 1.0
    private var layersInstalled = false
    private var modelInstalled = false
    private var modelVisible = true
    private var mapLoadErrorSubscription: Closeable? = null
    private var resourceStatusSubscription: Closeable? = null
    private var mapClickSubscription: Closeable? = null
    private var pendingSmokeAction: String? = null
    private var lastResourceStatusText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSmokeAction = intent.getStringExtra(EXTRA_SAMPLE_ACTION)

        mapView = VectorraMapView(this)
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(0xCC101418.toInt(), dp(6).toFloat())
            text = "Vectorra ${VectorraSdk.VERSION} sample loading"
        }

        mapView.lifecycleCallback = object : VectorraMapLifecycleCallback {
            override fun onSurfaceLifecycleChanged(
                view: VectorraMapView,
                state: VectorraSurfaceLifecycleState,
                width: Int,
                height: Int
            ) {
                statusText.text = "Surface ${state.name.lowercase()} ${width}x$height"
            }

            override fun onMapReady(view: VectorraMapView, map: VectorraMap) {
                installBaseLayers(map)
                statusText.text = lastResourceStatusText ?: "Vectorra map ready"
                runPendingSmokeAction()
            }

            override fun onMapLoadError(view: VectorraMapView, error: VectorraMapLoadError) {
                statusText.text = "Vectorra load error: ${error.message}"
            }
        }
        mapLoadErrorSubscription = mapView.addMapLoadErrorListener { _, error ->
            statusText.text = "Vectorra load error: ${error.message}"
        }
        resourceStatusSubscription = mapView.map.addResourceStatusListener { status ->
            val text = when (status.state) {
                VectorraResourceLoadState.FAILED ->
                    "${status.kind.name.lowercase()} ${status.layerId} failed: ${status.error?.message}"
                else ->
                    "${status.kind.name.lowercase()} ${status.layerId} ${status.state.name.lowercase()}"
            }
            if (shouldShowResourceStatus(status.kind, status.state)) {
                lastResourceStatusText = text
                statusText.text = text
            }
        }
        mapClickSubscription = mapView.map.addOnMapClickListener { event ->
            val text = clickStatusText(event.features)
            Log.i(LOG_TAG, text)
            statusText.text = text
            event.features.isNotEmpty()
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                mapView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(statusText, statusLayoutParams())
            addView(createControls(), controlsLayoutParams())
        }

        setContentView(root)
    }

    override fun onDestroy() {
        mapLoadErrorSubscription?.close()
        resourceStatusSubscription?.close()
        mapClickSubscription?.close()
        if (::mapView.isInitialized) {
            mapView.map.close()
        }
        super.onDestroy()
    }

    private fun createControls(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(0xDD101418.toInt(), dp(8).toFloat())

            addView(controlRow(
                sampleButton("Globe") {
                    mapView.map.setCamera(CameraOptions(longitude = 0.0, latitude = 20.0, zoom = 2.0))
                    statusText.text = "Camera: globe"
                },
                sampleButton("China") {
                    mapView.map.setCamera(CameraOptions(longitude = 104.293174, latitude = 32.2857965, zoom = 4.2))
                    statusText.text = "Camera: China"
                },
                sampleButton("Tibet") {
                    mapView.map.setCamera(CameraOptions(longitude = 91.117, latitude = 29.647, zoom = 6.2, pitch = 45.0))
                    statusText.text = "Camera: terrain view"
                }
            ))

            addView(controlRow(
                sampleButton("Imagery") {
                    mapView.map.addRasterLayer(
                        id = "sample-readymap",
                        templateUrl = "http://readymap.org/readymap/tiles/1.0.0/7/",
                        minZoom = 0,
                        maxZoom = 18
                    )
                    statusText.text = "Imagery layer requested"
                },
                sampleButton("DEM") {
                    mapView.map.addTerrain(
                        source = sampleTerrainSource("sample-terrarium-dem"),
                        options = VectorraTerrainOptions(exaggeration = terrainExaggeration)
                    )
                    statusText.text = "DEM layer requested"
                },
                sampleButton("Reset") {
                    terrainExaggeration = 1.0
                    mapView.map.setTerrainExaggeration(terrainExaggeration)
                    mapView.map.setCamera(CameraOptions(longitude = 0.0, latitude = 20.0, zoom = 2.0, pitch = 0.0))
                    statusText.text = "Terrain reset"
                }
            ))

            addView(controlRow(
                sampleButton("Terrain -") {
                    terrainExaggeration = (terrainExaggeration - 0.5).coerceAtLeast(0.0)
                    mapView.map.setTerrainExaggeration(terrainExaggeration)
                    statusText.text = "Terrain x${"%.1f".format(terrainExaggeration)}"
                },
                sampleButton("Terrain +") {
                    terrainExaggeration = (terrainExaggeration + 0.5).coerceAtMost(5.0)
                    mapView.map.setTerrainExaggeration(terrainExaggeration)
                    statusText.text = "Terrain x${"%.1f".format(terrainExaggeration)}"
                }
            ))

            addView(controlRow(
                sampleButton("MBTiles") {
                    loadSampleMbTiles()
                },
                sampleButton("3D Tiles") {
                    loadSample3DTiles()
                },
                sampleButton("3D Zoom") {
                    zoomSample3DTiles()
                },
                sampleButton("MVT") {
                    loadSampleMvt()
                },
                sampleButton("MVT MBTiles") {
                    loadSampleMvtMbTiles()
                }
            ))

            addView(controlRow(
                sampleButton("MVT Pan") {
                    panSampleMvt()
                },
                sampleButton("MVT Readd") {
                    reloadSampleMvt()
                },
                sampleButton("MVT Hide") {
                    hiddenSampleMvt()
                }
            ))

            addView(controlRow(
                sampleButton("Offline PF") {
                    runOfflinePrefetchSmoke(cancelAfterFirst = false)
                },
                sampleButton("Cancel PF") {
                    runOfflinePrefetchSmoke(cancelAfterFirst = true)
                },
                sampleButton("Snapshot") {
                    runSnapshotSmoke()
                }
            ))

            addView(controlRow(
                sampleButton("GeoJSON") {
                    loadSampleGeoJson()
                },
                sampleButton("Draw") {
                    loadSampleDrawAnnotations()
                },
                sampleButton("Clear Draw") {
                    clearSampleDrawAnnotations()
                }
            ))

            addView(controlRow(
                sampleButton("Location") {
                    showSampleLocation()
                },
                sampleButton("Follow") {
                    followSampleLocation()
                },
                sampleButton("Clear Loc") {
                    clearSampleLocation()
                }
            ))

            if (ENABLE_MODEL_SMOKE) {
                addView(controlRow(
                    sampleButton("Model") {
                        loadSampleModel()
                    },
                    sampleButton("Toggle") {
                        toggleSampleModel()
                    },
                    sampleButton("Remove") {
                        removeSampleModel()
                    },
                    sampleButton("Bad GLB") {
                        loadBrokenSampleModel()
                    }
                ))
            }
        }
    }

    private fun installBaseLayers(map: VectorraMap) {
        if (layersInstalled) {
            return
        }
        layersInstalled = true
        map.addRasterLayer(
            id = "sample-base-imagery",
            templateUrl = "http://readymap.org/readymap/tiles/1.0.0/7/",
            minZoom = 0,
            maxZoom = 18
        )
        if (ENABLE_STARTUP_TERRAIN) {
            map.addTerrain(
                source = sampleTerrainSource("sample-base-dem"),
                options = VectorraTerrainOptions(exaggeration = terrainExaggeration)
            )
        }
    }

    private fun sampleTerrainSource(id: String): VectorraTerrainSource {
        return VectorraTerrainSource(
            id = id,
            templateUrl = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png",
            minZoom = 0,
            maxZoom = 14
        )
    }

    private fun loadSampleMbTiles() {
        val file = File(filesDir, "sample.mbtiles")
        if (!file.exists()) {
            statusText.text = "Place sample.mbtiles in app filesDir"
            return
        }

        runCatching {
            val source = VectorraMbTilesRasterSource.open(file, id = "sample-mbtiles")
            mapView.map.addMbTilesRasterLayer(source, layerId = "sample-mbtiles")
            val center = source.metadata.center
            if (center != null) {
                mapView.map.setCamera(
                    CameraOptions(
                        longitude = center.longitude,
                        latitude = center.latitude,
                        zoom = center.zoom ?: source.minZoom.toDouble()
                    )
                )
            }
            statusText.text = "MBTiles layer loaded: ${source.metadata.name ?: file.name}"
        }.onFailure { error ->
            statusText.text = "MBTiles error: ${error.message}"
        }
    }

    private fun loadSampleModel() {
        runCatching {
            mapView.map.addModelLayer(
                source = sampleModelSource(SAMPLE_MODEL_LAYER_ID, SAMPLE_MODEL_URI),
                options = VectorraGlbModelLayerOptions(visible = true)
            )
            modelInstalled = true
            modelVisible = true
            mapView.map.setCamera(
                CameraOptions(
                    longitude = SAMPLE_MODEL_LONGITUDE,
                    latitude = SAMPLE_MODEL_LATITUDE,
                    zoom = 10.5,
                    pitch = 62.0,
                    bearing = 0.0
                )
            )
            statusText.text = "Model requested: C130 GLB"
        }.onFailure { error ->
            statusText.text = "Model error: ${error.message}"
        }
    }

    private fun toggleSampleModel() {
        if (!modelInstalled) {
            statusText.text = "Load model first"
            return
        }

        modelVisible = !modelVisible
        mapView.map.setModelLayerVisible(SAMPLE_MODEL_LAYER_ID, modelVisible)
        statusText.text = if (modelVisible) "Model visible" else "Model hidden"
    }

    private fun removeSampleModel() {
        if (!modelInstalled) {
            statusText.text = "No model layer to remove"
            return
        }

        mapView.map.removeModelLayer(SAMPLE_MODEL_LAYER_ID)
        modelInstalled = false
        modelVisible = true
        statusText.text = "Model removed"
    }

    private fun loadBrokenSampleModel() {
        runCatching {
            mapView.map.addModelLayer(
                source = sampleModelSource("sample-broken-model", SAMPLE_BROKEN_MODEL_URI),
                options = VectorraGlbModelLayerOptions(visible = true)
            )
            statusText.text = "Broken model requested"
        }.onFailure { error ->
            statusText.text = "Model error: ${error.message}"
        }
    }

    private fun loadSample3DTiles() {
        runCatching {
            val source = Vectorra3DTilesSource(
                id = SAMPLE_3D_TILES_SOURCE_ID,
                tilesetUri = SAMPLE_3D_TILES_URI
            )
            mapView.map.add3DTilesLayer(
                source = source,
                layer = Vectorra3DTilesLayer(
                    id = SAMPLE_3D_TILES_LAYER_ID,
                    sourceId = source.id
                ),
                options = Vectorra3DTilesOptions(
                    maximumScreenSpaceError = 16.0,
                    maximumLoadedTiles = 128
                )
            )
            mapView.map.setCamera(
                CameraOptions(
                    longitude = SAMPLE_3D_TILES_LONGITUDE,
                    latitude = SAMPLE_3D_TILES_LATITUDE,
                    zoom = 16.0,
                    pitch = 0.0,
                    bearing = 0.0
                )
            )
            statusText.text = "3D Tiles requested"
        }.onFailure { error ->
            statusText.text = "3D Tiles error: ${error.message}"
        }
    }

    private fun loadSampleMvt(visible: Boolean = true) {
        runCatching {
            val source = VectorraVectorTileSource.xyz(
                id = SAMPLE_MVT_SOURCE_ID,
                templateUrl = SAMPLE_MVT_TEMPLATE_URL,
                minZoom = 0,
                maxZoom = 14
            )
            mapView.map.setCamera(
                CameraOptions(
                    longitude = SAMPLE_MVT_LONGITUDE,
                    latitude = SAMPLE_MVT_LATITUDE,
                    zoom = 12.0,
                    pitch = 0.0,
                    bearing = 0.0
                )
            )
            mapView.map.addVectorTileLayer(
                source = source,
                layer = VectorraVectorTileLayer.Line(
                    id = SAMPLE_MVT_LAYER_ID,
                    sourceId = source.id,
                    sourceLayer = "transportation",
                    visible = visible,
                    minZoom = 4,
                    maxZoom = 14,
                    color = 0xffffd43b.toInt(),
                    opacity = 0.95,
                    widthPixels = 2.5
                )
            )
            statusText.text = if (visible) "MVT layer requested" else "MVT hidden layer requested"
        }.onFailure { error ->
            statusText.text = "MVT error: ${error.message}"
        }
    }

    private fun loadSampleMvtMbTiles() {
        runCatching {
            val source = VectorraMbTilesVectorSource.open(
                file = createSampleMvtMbTiles(),
                id = SAMPLE_MVT_MBTILES_SOURCE_ID
            )
            mapView.map.setCamera(
                CameraOptions(
                    longitude = SAMPLE_MVT_LONGITUDE,
                    latitude = SAMPLE_MVT_LATITUDE,
                    zoom = 12.0,
                    pitch = 0.0,
                    bearing = 0.0
                )
            )
            mapView.map.addMbTilesVectorLayer(
                source = source,
                layer = VectorraVectorTileLayer.Line(
                    id = SAMPLE_MVT_LAYER_ID,
                    sourceId = source.id,
                    sourceLayer = "transportation",
                    minZoom = 12,
                    maxZoom = 12,
                    color = 0xff6ee7b7.toInt(),
                    opacity = 0.95,
                    widthPixels = 4.0
                )
            )
            statusText.text = "MVT MBTiles layer requested"
            Log.i(LOG_TAG, "MVT MBTiles smoke: requested file=${source.metadata.name}")
            statusText.postDelayed({
                logCenterMvtQuery("MVT MBTiles center query")
            }, SAMPLE_MVT_QUERY_DELAY_MS)
        }.onFailure { error ->
            statusText.text = "MVT MBTiles error: ${error.message}"
        }
    }

    private fun createSampleMvtMbTiles(): File {
        val file = File(cacheDir, SAMPLE_MVT_MBTILES_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.use { db ->
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL(
                "CREATE TABLE tiles (" +
                    "zoom_level INTEGER, " +
                    "tile_column INTEGER, " +
                    "tile_row INTEGER, " +
                    "tile_data BLOB)"
            )
            insertMbTilesMetadata(db, "name", "Vectorra Sample MVT")
            insertMbTilesMetadata(db, "format", "pbf")
            insertMbTilesMetadata(db, "minzoom", "12")
            insertMbTilesMetadata(db, "maxzoom", "12")
            insertMbTilesMetadata(db, "scheme", "tms")
            db.compileStatement(
                "INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)"
            ).use { statement ->
                statement.bindLong(1, SAMPLE_MVT_TILE_Z.toLong())
                statement.bindLong(2, SAMPLE_MVT_TILE_X.toLong())
                statement.bindLong(3, tmsTileRow(SAMPLE_MVT_TILE_Z, SAMPLE_MVT_TILE_Y).toLong())
                statement.bindBlob(4, sampleMvtTile())
                statement.executeInsert()
            }
        }
        return file
    }

    private fun insertMbTilesMetadata(database: SQLiteDatabase, name: String, value: String) {
        database.compileStatement("INSERT INTO metadata (name, value) VALUES (?, ?)").use { statement ->
            statement.bindString(1, name)
            statement.bindString(2, value)
            statement.executeInsert()
        }
    }

    private fun tmsTileRow(z: Int, y: Int): Int = ((1 shl z) - 1) - y

    private fun removeSampleMvt() {
        runCatching {
            mapView.map.removeVectorTileLayer(SAMPLE_MVT_LAYER_ID)
            statusText.text = "MVT removed"
            Log.i(LOG_TAG, "MVT smoke: removed layer")
        }.onFailure { error ->
            statusText.text = "MVT remove error: ${error.message}"
        }
    }

    private fun reloadSampleMvt() {
        loadSampleMvt()
        statusText.postDelayed({
            removeSampleMvt()
            statusText.postDelayed({
                loadSampleMvt()
                statusText.postDelayed({
                    logCenterMvtQuery("MVT readd center query")
                }, SAMPLE_MVT_QUERY_DELAY_MS)
            }, SAMPLE_MVT_READD_DELAY_MS)
        }, SAMPLE_MVT_REMOVE_DELAY_MS)
    }

    private fun panSampleMvt() {
        loadSampleMvt()
        statusText.postDelayed({
            mapView.map.setCamera(
                CameraOptions(
                    longitude = SAMPLE_MVT_PAN_LONGITUDE,
                    latitude = SAMPLE_MVT_LATITUDE,
                    zoom = 12.0,
                    pitch = 0.0,
                    bearing = 0.0
                )
            )
            statusText.text = "MVT pan smoke"
            Log.i(LOG_TAG, "MVT smoke: camera pan lon=$SAMPLE_MVT_PAN_LONGITUDE")
            statusText.postDelayed({
                logCenterMvtQuery("MVT pan center query")
            }, SAMPLE_MVT_QUERY_DELAY_MS)
        }, SAMPLE_MVT_PAN_DELAY_MS)
    }

    private fun hiddenSampleMvt() {
        loadSampleMvt(visible = false)
        statusText.postDelayed({
            logCenterMvtQuery("MVT hidden center query")
        }, SAMPLE_MVT_QUERY_DELAY_MS)
    }

    private fun runOfflinePrefetchSmoke(cancelAfterFirst: Boolean) {
        runCatching {
            val offline = mapView.map.offline
            offline.clearCache()
            val before = offline.cacheStatus()
            Log.i(LOG_TAG, "Offline prefetch smoke: cache before ${before.toSmokeText()}")

            val taskRef = AtomicReference<VectorraPrefetchTask>()
            val task = offline.prefetchRegionAsync(
                region = sampleOfflineRegion(),
                sources = listOf(VectorraOfflineTileSource.vector(sampleOfflineMvtSource())),
                options = VectorraPrefetchOptions(maxAttempts = 2)
            ) { progress ->
                val text = "Offline prefetch progress state=${progress.state} " +
                    "finished=${progress.finishedCount}/${progress.totalCount} " +
                    "failed=${progress.failedCount} bytes=${progress.totalBytes}"
                Log.i(LOG_TAG, text)
                statusText.post { statusText.text = text }
                if (cancelAfterFirst && progress.finishedCount >= 1 && progress.state == VectorraPrefetchTaskState.RUNNING) {
                    val canceled = taskRef.get()?.cancel() ?: false
                    Log.i(LOG_TAG, "Offline prefetch smoke: cancel requested=$canceled")
                }
            }
            taskRef.set(task)
            statusText.text = if (cancelAfterFirst) "Offline prefetch cancel smoke" else "Offline prefetch smoke"
            Thread({
                val result = task.await()
                val after = offline.cacheStatus()
                val resultText = "Offline prefetch result status=${result.status} " +
                    "completed=${result.completedCount} failed=${result.failedCount} " +
                    "bytes=${result.totalBytes} state=${task.state}"
                Log.i(LOG_TAG, resultText)
                Log.i(LOG_TAG, "Offline prefetch smoke: cache after ${after.toSmokeText()}")
                offline.clearCache()
                val cleared = offline.cacheStatus()
                Log.i(LOG_TAG, "Offline prefetch smoke: cache cleared ${cleared.toSmokeText()}")
                statusText.post {
                    statusText.text = "$resultText cache=${after.totalEntryCount}->${cleared.totalEntryCount}"
                }
            }, "VectorraSampleOfflinePrefetch").start()
        }.onFailure { error ->
            statusText.text = "Offline prefetch error: ${error.message}"
            Log.e(LOG_TAG, "Offline prefetch smoke error", error)
        }
    }

    private fun runSnapshotSmoke() {
        statusText.text = "Snapshot requested"
        logSnapshotSmoke(label = "Snapshot", updateStatus = true)
    }

    private fun runPostRecreateSnapshotSmoke() {
        statusText.text = "Post-recreate snapshot requested"
        logSnapshotSmoke(label = "Post-recreate snapshot", updateStatus = true)
    }

    private fun logSnapshotSmoke(label: String, updateStatus: Boolean) {
        mapView.snapshot { bitmap, error ->
            if (error != null || bitmap == null) {
                val text = "$label error: ${error?.message ?: "empty bitmap"}"
                if (updateStatus) {
                    statusText.text = text
                }
                Log.e(LOG_TAG, text, error?.cause)
                return@snapshot
            }
            val text = "$label ${bitmap.width}x${bitmap.height} nonblank=${bitmap.hasVisiblePixel()}"
            if (updateStatus) {
                statusText.text = text
            }
            Log.i(LOG_TAG, text)
            bitmap.recycle()
        }
    }

    private fun Bitmap.hasVisiblePixel(): Boolean {
        if (width <= 0 || height <= 0) {
            return false
        }
        val samplePoints = listOf(
            0 to 0,
            width / 2 to height / 2,
            (width - 1) to (height - 1)
        )
        return samplePoints.any { (x, y) -> getPixel(x, y) != Color.TRANSPARENT }
    }

    private fun loadSampleGeoJson() {
        runCatching {
            mapView.map.setCamera(
                CameraOptions(
                    longitude = SAMPLE_GEOJSON_LONGITUDE,
                    latitude = SAMPLE_GEOJSON_LATITUDE,
                    zoom = 13.0,
                    pitch = 0.0,
                    bearing = 0.0
                )
            )
            val source = VectorraGeoJsonSource(
                id = SAMPLE_GEOJSON_SOURCE_ID,
                features = listOf(
                    VectorraGeoJsonFeature(
                        id = "geojson-point",
                        geometry = VectorraAnnotationGeometry.Point(
                            VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE, SAMPLE_GEOJSON_LATITUDE)
                        ),
                        properties = mapOf("name" to "GeoJSON point")
                    ),
                    VectorraGeoJsonFeature(
                        id = "geojson-line",
                        geometry = VectorraAnnotationGeometry.LineString(
                            listOf(
                                VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE - 0.01, SAMPLE_GEOJSON_LATITUDE - 0.006),
                                VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE + 0.01, SAMPLE_GEOJSON_LATITUDE + 0.006)
                            )
                        ),
                        properties = mapOf("name" to "GeoJSON line")
                    )
                )
            )
            mapView.map.setGeoJsonSource(source)
            mapView.map.setGeoJsonLayer(
                VectorraGeoJsonLayer(
                    id = SAMPLE_GEOJSON_LAYER_ID,
                    sourceId = source.id,
                    hitRadiusPixels = 36.0,
                    zIndex = 320
                )
            )
            statusText.text = "GeoJSON query layer requested"
            Log.i(LOG_TAG, "GeoJSON smoke: source=${source.id} features=${source.features.size}")
            statusText.postDelayed({
                logCenterQuery("GeoJSON center query", layerId = SAMPLE_GEOJSON_LAYER_ID)
            }, SAMPLE_QUERY_DELAY_MS)
        }.onFailure { error ->
            statusText.text = "GeoJSON error: ${error.message}"
            Log.e(LOG_TAG, "GeoJSON smoke error", error)
        }
    }

    private fun loadSampleDrawAnnotations() {
        runCatching {
            mapView.map.setCamera(
                CameraOptions(
                    longitude = SAMPLE_GEOJSON_LONGITUDE,
                    latitude = SAMPLE_GEOJSON_LATITUDE,
                    zoom = 13.0,
                    pitch = 0.0,
                    bearing = 0.0
                )
            )
            mapView.map.clearDrawAnnotations()
            mapView.map.addDrawPointAnnotation(
                VectorraDrawPointAnnotation(
                    id = "sample-draw-point",
                    coordinate = VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE, SAMPLE_GEOJSON_LATITUDE),
                    text = "Draw"
                )
            )
            mapView.map.addDrawLineAnnotation(
                VectorraDrawLineAnnotation(
                    id = "sample-draw-line",
                    coordinates = listOf(
                        VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE - 0.012, SAMPLE_GEOJSON_LATITUDE - 0.008),
                        VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE + 0.012, SAMPLE_GEOJSON_LATITUDE + 0.008)
                    )
                )
            )
            mapView.map.addDrawPolygonAnnotation(
                VectorraDrawPolygonAnnotation(
                    id = "sample-draw-polygon",
                    rings = listOf(
                        listOf(
                            VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE - 0.008, SAMPLE_GEOJSON_LATITUDE + 0.004),
                            VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE, SAMPLE_GEOJSON_LATITUDE + 0.012),
                            VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE + 0.008, SAMPLE_GEOJSON_LATITUDE + 0.004),
                            VectorraCoordinate(SAMPLE_GEOJSON_LONGITUDE - 0.008, SAMPLE_GEOJSON_LATITUDE + 0.004)
                        )
                    )
                )
            )
            statusText.text = "Draw annotations requested"
            Log.i(LOG_TAG, "Draw smoke: point line polygon requested")
            statusText.postDelayed({
                logCenterQuery("Draw center query", layerId = "draw-point")
            }, SAMPLE_QUERY_DELAY_MS)
        }.onFailure { error ->
            statusText.text = "Draw error: ${error.message}"
            Log.e(LOG_TAG, "Draw smoke error", error)
        }
    }

    private fun clearSampleDrawAnnotations() {
        runCatching {
            mapView.map.clearDrawAnnotations()
            statusText.text = "Draw annotations cleared"
            Log.i(LOG_TAG, "Draw smoke: cleared")
        }.onFailure { error ->
            statusText.text = "Draw clear error: ${error.message}"
        }
    }

    private fun showSampleLocation() {
        runCatching {
            mapView.map.location.enabled = true
            mapView.map.location.showAccuracyRing = true
            mapView.map.location.followMode = VectorraFollowMode.NONE
            mapView.map.location.updateLocation(sampleLocation())
            mapView.map.setCamera(
                CameraOptions(
                    longitude = SAMPLE_LOCATION_LONGITUDE,
                    latitude = SAMPLE_LOCATION_LATITUDE,
                    zoom = 15.0,
                    pitch = 0.0,
                    bearing = 0.0
                )
            )
            statusText.text = "Location indicator requested"
            Log.i(LOG_TAG, "Location smoke: indicator requested")
        }.onFailure { error ->
            statusText.text = "Location error: ${error.message}"
            Log.e(LOG_TAG, "Location smoke error", error)
        }
    }

    private fun followSampleLocation() {
        runCatching {
            mapView.map.location.enabled = true
            mapView.map.location.showAccuracyRing = true
            mapView.map.location.updateLocation(sampleLocation())
            mapView.map.location.followMode = VectorraFollowMode.HEADING
            statusText.text = "Location follow requested"
            Log.i(LOG_TAG, "Location smoke: follow heading requested")
        }.onFailure { error ->
            statusText.text = "Location follow error: ${error.message}"
            Log.e(LOG_TAG, "Location follow smoke error", error)
        }
    }

    private fun clearSampleLocation() {
        runCatching {
            mapView.map.location.followMode = VectorraFollowMode.NONE
            mapView.map.location.clearLocation()
            mapView.map.location.enabled = false
            statusText.text = "Location cleared"
            Log.i(LOG_TAG, "Location smoke: cleared")
        }.onFailure { error ->
            statusText.text = "Location clear error: ${error.message}"
        }
    }

    private fun sampleLocation(): VectorraLocation {
        return VectorraLocation(
            longitude = SAMPLE_LOCATION_LONGITUDE,
            latitude = SAMPLE_LOCATION_LATITUDE,
            accuracyMeters = 42.0,
            bearingDegrees = 35.0,
            provider = "sample"
        )
    }

    private fun sampleOfflineMvtSource(): VectorraVectorTileSource {
        return VectorraVectorTileSource.xyz(
            id = "$SAMPLE_MVT_SOURCE_ID-offline-prefetch",
            templateUrl = SAMPLE_MVT_TEMPLATE_URL,
            minZoom = 12,
            maxZoom = 12
        )
    }

    private fun sampleOfflineRegion(): VectorraOfflineRegion {
        return VectorraOfflineRegion(
            bounds = VectorraOfflineBounds(
                west = -122.45,
                south = 37.75,
                east = -122.25,
                north = 37.80
            ),
            minZoom = 12,
            maxZoom = 12
        )
    }

    private fun VectorraCacheStatus.toSmokeText(): String {
        return "entries=$totalEntryCount bytes=$totalBytes " +
            "proxy=${proxy.totalEntryCount}/${proxy.totalBytes} " +
            "resources=${resources.totalEntryCount}/${resources.totalBytes}"
    }

    private fun loadBrokenSample3DTiles() {
        runCatching {
            val source = Vectorra3DTilesSource(
                id = "$SAMPLE_3D_TILES_SOURCE_ID-bad",
                tilesetUri = SAMPLE_BROKEN_3D_TILES_URI
            )
            mapView.map.add3DTilesLayer(
                source = source,
                layer = Vectorra3DTilesLayer(
                    id = "$SAMPLE_3D_TILES_LAYER_ID-bad",
                    sourceId = source.id
                ),
                options = Vectorra3DTilesOptions(
                    maximumScreenSpaceError = 16.0,
                    maximumLoadedTiles = 16
                )
            )
            statusText.text = "Bad 3D Tiles requested"
        }.onFailure { error ->
            statusText.text = "Bad 3D Tiles error: ${error.message}"
        }
    }

    private fun removeSample3DTiles() {
        runCatching {
            mapView.map.remove3DTilesLayer(SAMPLE_3D_TILES_LAYER_ID)
            statusText.text = "3D Tiles removed"
        }.onFailure { error ->
            statusText.text = "3D Tiles remove error: ${error.message}"
        }
    }

    private fun reloadSample3DTiles() {
        loadSample3DTiles()
        statusText.postDelayed({
            removeSample3DTiles()
            statusText.postDelayed({
                loadSample3DTiles()
            }, SAMPLE_3D_TILES_READD_DELAY_MS)
        }, SAMPLE_3D_TILES_REMOVE_DELAY_MS)
    }

    private fun zoomSample3DTiles() {
        runCatching {
            mapView.map.remove3DTilesLayer(SAMPLE_3D_TILES_LAYER_ID)
            loadSample3DTiles()
            mapView.map.setCamera(
                CameraOptions(
                    longitude = SAMPLE_3D_TILES_LONGITUDE,
                    latitude = SAMPLE_3D_TILES_LATITUDE,
                    zoom = 14.0,
                    pitch = 0.0,
                    bearing = 0.0
                )
            )
            statusText.text = "3D Tiles zoom smoke requested"
            Log.i(LOG_TAG, "3D Tiles zoom smoke: loaded at zoom=14.0")
            statusText.postDelayed({
                mapView.map.setCamera(
                    CameraOptions(
                        longitude = SAMPLE_3D_TILES_LONGITUDE,
                        latitude = SAMPLE_3D_TILES_LATITUDE,
                        zoom = SAMPLE_3D_TILES_ZOOM_CLOSE,
                        pitch = 0.0,
                        bearing = 0.0
                    )
                )
                statusText.text = "3D Tiles zoom smoke close"
                Log.i(LOG_TAG, "3D Tiles zoom smoke: camera zoom=$SAMPLE_3D_TILES_ZOOM_CLOSE")
            }, SAMPLE_3D_TILES_ZOOM_IN_DELAY_MS)
            statusText.postDelayed({
                mapView.map.setCamera(
                    CameraOptions(
                        longitude = SAMPLE_3D_TILES_LONGITUDE,
                        latitude = SAMPLE_3D_TILES_LATITUDE,
                        zoom = SAMPLE_3D_TILES_ZOOM_CLOSEST,
                        pitch = 0.0,
                        bearing = 0.0
                    )
                )
                statusText.text = "3D Tiles zoom smoke closest"
                Log.i(LOG_TAG, "3D Tiles zoom smoke: camera zoom=$SAMPLE_3D_TILES_ZOOM_CLOSEST")
            }, SAMPLE_3D_TILES_ZOOM_CLOSE_DELAY_MS)
            statusText.postDelayed({
                logSnapshotSmoke(label = "3D Tiles zoom snapshot", updateStatus = false)
            }, SAMPLE_3D_TILES_ZOOM_SNAPSHOT_DELAY_MS)
        }.onFailure { error ->
            statusText.text = "3D Tiles zoom smoke error: ${error.message}"
        }
    }

    private fun runPendingSmokeAction() {
        val action = pendingSmokeAction ?: return
        pendingSmokeAction = null
        statusText.post {
            when (action) {
                SAMPLE_ACTION_3D_TILES -> loadSample3DTiles()
                SAMPLE_ACTION_MVT -> loadSampleMvt()
                SAMPLE_ACTION_MVT_MBTILES -> loadSampleMvtMbTiles()
                SAMPLE_ACTION_REMOVE_MVT -> removeSampleMvt()
                SAMPLE_ACTION_READD_MVT -> reloadSampleMvt()
                SAMPLE_ACTION_PAN_MVT -> panSampleMvt()
                SAMPLE_ACTION_HIDDEN_MVT -> hiddenSampleMvt()
                SAMPLE_ACTION_BAD_3D_TILES -> loadBrokenSample3DTiles()
                SAMPLE_ACTION_REMOVE_3D_TILES -> removeSample3DTiles()
                SAMPLE_ACTION_READD_3D_TILES -> reloadSample3DTiles()
                SAMPLE_ACTION_ZOOM_3D_TILES -> zoomSample3DTiles()
                SAMPLE_ACTION_OFFLINE_PREFETCH -> runOfflinePrefetchSmoke(cancelAfterFirst = false)
                SAMPLE_ACTION_CANCEL_PREFETCH -> runOfflinePrefetchSmoke(cancelAfterFirst = true)
                SAMPLE_ACTION_SNAPSHOT -> runSnapshotSmoke()
                SAMPLE_ACTION_POST_RECREATE_SNAPSHOT -> runPostRecreateSnapshotSmoke()
                SAMPLE_ACTION_GEOJSON -> loadSampleGeoJson()
                SAMPLE_ACTION_DRAW -> loadSampleDrawAnnotations()
                SAMPLE_ACTION_CLEAR_DRAW -> clearSampleDrawAnnotations()
                SAMPLE_ACTION_LOCATION -> showSampleLocation()
                SAMPLE_ACTION_LOCATION_FOLLOW -> followSampleLocation()
                SAMPLE_ACTION_CLEAR_LOCATION -> clearSampleLocation()
                else -> statusText.text = "Unknown sample action: $action"
            }
        }
    }

    private fun sampleModelSource(id: String, uri: String): VectorraGlbModelSource {
        return VectorraGlbModelSource(
            id = id,
            uri = uri,
            longitude = SAMPLE_MODEL_LONGITUDE,
            latitude = SAMPLE_MODEL_LATITUDE,
            heightMeters = 18000.0,
            scale = 20000.0,
            yawDegrees = 0.0
        )
    }

    private fun controlRow(vararg buttons: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEach { button ->
                addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                })
            }
        }
    }

    private fun sampleButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            background = roundedBackground(0xFF2F6F8F.toInt(), dp(6).toFloat())
            setOnClickListener { onClick() }
        }
    }

    private fun statusLayoutParams(): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            topMargin = dp(16)
            marginStart = dp(16)
            marginEnd = dp(16)
        }
    }

    private fun controlsLayoutParams(): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(16)
        }
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun shouldShowResourceStatus(
        kind: VectorraResourceKind,
        state: VectorraResourceLoadState
    ): Boolean {
        if (state == VectorraResourceLoadState.FAILED || kind == VectorraResourceKind.TILES3D) {
            return true
        }
        return lastResourceStatusText?.startsWith("tiles3d ") != true
    }

    private fun clickStatusText(features: List<VectorraQueriedFeature>): String {
        val first = features.firstOrNull()
            ?: return "Click: no features"
        val sourceLayer = first.properties["source-layer"]?.let { " source-layer=$it" }.orEmpty()
        val name = first.properties["name"]?.let { " name=$it" }.orEmpty()
        val source = first.sourceId?.let { " source=$it" }.orEmpty()
        return "Click: ${features.size} feature(s) layer=${first.layerId}$source$sourceLayer$name"
    }

    private fun logCenterMvtQuery(prefix: String) {
        logCenterQuery(
            prefix = prefix,
            layerId = SAMPLE_MVT_LAYER_ID,
            sourceLayerIds = setOf("transportation"),
            radiusPixels = 48.0
        )
    }

    private fun logCenterQuery(
        prefix: String,
        layerId: String,
        sourceLayerIds: Set<String> = emptySet(),
        radiusPixels: Double = 48.0
    ) {
        val width = mapView.width
        val height = mapView.height
        Log.i(LOG_TAG, "$prefix: start viewport=${width}x$height")
        if (width <= 0 || height <= 0) {
            Log.i(LOG_TAG, "$prefix: skipped empty viewport ${width}x$height")
            return
        }
        val features = mapView.map.queryRenderedFeatures(
            screenPoint = VectorraScreenPoint(width / 2.0, height / 2.0),
            options = VectorraQueryOptions(
                layerIds = setOf(layerId),
                sourceLayerIds = sourceLayerIds,
                radiusPixels = radiusPixels
            )
        )
        val text = "$prefix: ${clickStatusText(features)}"
        Log.i(LOG_TAG, text)
        statusText.text = text
    }

    private fun sampleMvtTile(): ByteArray {
        return pbfMessage {
            bytes(
                MVT_TILE_LAYERS_FIELD,
                pbfMessage {
                    string(MVT_LAYER_NAME_FIELD, "transportation")
                    bytes(
                        MVT_LAYER_FEATURES_FIELD,
                        pbfMessage {
                            uint(MVT_FEATURE_ID_FIELD, 1)
                            packed(MVT_FEATURE_TAGS_FIELD, listOf(0, 0))
                            uint(MVT_FEATURE_TYPE_FIELD, MVT_GEOMETRY_LINE_STRING.toLong())
                            packed(
                                MVT_FEATURE_GEOMETRY_FIELD,
                                listOf(
                                    mvtCommand(MVT_COMMAND_MOVE_TO, 1),
                                    zigZag(1900),
                                    zigZag(2048),
                                    mvtCommand(MVT_COMMAND_LINE_TO, 2),
                                    zigZag(350),
                                    zigZag(0),
                                    zigZag(350),
                                    zigZag(0)
                                )
                            )
                        }
                    )
                    string(MVT_LAYER_KEYS_FIELD, "name")
                    bytes(
                        MVT_LAYER_VALUES_FIELD,
                        pbfMessage {
                            string(MVT_VALUE_STRING_FIELD, "Offline MBTiles")
                        }
                    )
                    uint(MVT_LAYER_EXTENT_FIELD, MVT_TILE_EXTENT.toLong())
                    uint(MVT_LAYER_VERSION_FIELD, 2)
                }
            )
        }
    }

    private fun pbfMessage(block: PbfEncoder.() -> Unit): ByteArray {
        return PbfEncoder().apply(block).toByteArray()
    }

    private fun mvtCommand(id: Int, count: Int): Int = (count shl MVT_COMMAND_COUNT_SHIFT) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private class PbfEncoder {
        private val output = ByteArrayOutputStream()

        fun uint(field: Int, value: Long) {
            tag(field, PBF_WIRE_VARINT)
            varint(value)
        }

        fun string(field: Int, value: String) {
            bytes(field, value.toByteArray(Charsets.UTF_8))
        }

        fun bytes(field: Int, value: ByteArray) {
            tag(field, PBF_WIRE_LENGTH_DELIMITED)
            varint(value.size.toLong())
            output.write(value)
        }

        fun packed(field: Int, values: List<Int>) {
            if (values.isEmpty()) return
            val packed = PbfEncoder()
            values.forEach { packed.varint(it.toLong()) }
            bytes(field, packed.toByteArray())
        }

        fun toByteArray(): ByteArray = output.toByteArray()

        private fun tag(field: Int, wireType: Int) {
            varint(((field shl 3) or wireType).toLong())
        }

        fun varint(value: Long) {
            var remaining = value
            while (remaining >= 0x80L) {
                output.write(((remaining and 0x7fL) or 0x80L).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
    }

    private companion object {
        const val LOG_TAG = "VectorraSample"
        const val ENABLE_MODEL_SMOKE = true
        const val ENABLE_STARTUP_TERRAIN = false
        const val SAMPLE_MODEL_LAYER_ID = "sample-c130-model"
        const val SAMPLE_MODEL_URI = "https://readymap.org/readymap/filemanager/download/public/models/C130_WFF_AIR_0824.glb"
        const val SAMPLE_BROKEN_MODEL_URI = "https://readymap.org/readymap/filemanager/download/public/models/missing-vectorra-smoke.glb"
        const val SAMPLE_MODEL_LONGITUDE = 41.8
        const val SAMPLE_MODEL_LATITUDE = 1.0
        const val SAMPLE_3D_TILES_SOURCE_ID = "sample-3d-tiles"
        const val SAMPLE_3D_TILES_LAYER_ID = "sample-3d-tiles-layer"
        const val SAMPLE_3D_TILES_URI = "https://raw.githubusercontent.com/CesiumGS/3d-tiles-samples/main/1.0/TilesetWithDiscreteLOD/tileset.json"
        const val SAMPLE_3D_TILES_LONGITUDE = -75.61209430782448
        const val SAMPLE_3D_TILES_LATITUDE = 40.04253061142592
        const val SAMPLE_3D_TILES_ZOOM_CLOSE = 16.25
        const val SAMPLE_3D_TILES_ZOOM_CLOSEST = 16.5
        const val SAMPLE_BROKEN_3D_TILES_URI = "https://raw.githubusercontent.com/CesiumGS/3d-tiles-samples/main/missing-vectorra-smoke/tileset.json"
        const val SAMPLE_MVT_SOURCE_ID = "sample-mvt"
        const val SAMPLE_MVT_LAYER_ID = "sample-mvt-transportation"
        const val SAMPLE_MVT_TEMPLATE_URL = "https://tiles.openfreemap.org/planet/20260520_001001_pt/{z}/{x}/{y}.pbf"
        const val SAMPLE_MVT_LONGITUDE = -122.4194
        const val SAMPLE_MVT_PAN_LONGITUDE = -122.3000
        const val SAMPLE_MVT_LATITUDE = 37.7749
        const val SAMPLE_MVT_MBTILES_SOURCE_ID = "sample-mvt-mbtiles"
        const val SAMPLE_MVT_MBTILES_FILE_NAME = "sample-vector.mbtiles"
        const val SAMPLE_GEOJSON_SOURCE_ID = "sample-geojson"
        const val SAMPLE_GEOJSON_LAYER_ID = "sample-geojson-layer"
        const val SAMPLE_GEOJSON_LONGITUDE = -122.4194
        const val SAMPLE_GEOJSON_LATITUDE = 37.7749
        const val SAMPLE_LOCATION_LONGITUDE = -122.4194
        const val SAMPLE_LOCATION_LATITUDE = 37.7749
        const val SAMPLE_MVT_TILE_Z = 12
        const val SAMPLE_MVT_TILE_X = 655
        const val SAMPLE_MVT_TILE_Y = 1583
        const val MVT_TILE_EXTENT = 4096
        const val SAMPLE_3D_TILES_REMOVE_DELAY_MS = 16_000L
        const val SAMPLE_3D_TILES_READD_DELAY_MS = 4_000L
        const val SAMPLE_3D_TILES_ZOOM_IN_DELAY_MS = 7_000L
        const val SAMPLE_3D_TILES_ZOOM_CLOSE_DELAY_MS = 14_000L
        const val SAMPLE_3D_TILES_ZOOM_SNAPSHOT_DELAY_MS = 20_000L
        const val SAMPLE_MVT_REMOVE_DELAY_MS = 6_000L
        const val SAMPLE_MVT_READD_DELAY_MS = 2_000L
        const val SAMPLE_MVT_PAN_DELAY_MS = 7_000L
        const val SAMPLE_MVT_QUERY_DELAY_MS = 7_000L
        const val SAMPLE_QUERY_DELAY_MS = 2_000L
        const val EXTRA_SAMPLE_ACTION = "vectorra.sample.action"
        const val SAMPLE_ACTION_3D_TILES = "3dtiles"
        const val SAMPLE_ACTION_MVT = "mvt"
        const val SAMPLE_ACTION_MVT_MBTILES = "mvt-mbtiles"
        const val SAMPLE_ACTION_REMOVE_MVT = "remove-mvt"
        const val SAMPLE_ACTION_READD_MVT = "readd-mvt"
        const val SAMPLE_ACTION_PAN_MVT = "pan-mvt"
        const val SAMPLE_ACTION_HIDDEN_MVT = "hidden-mvt"
        const val SAMPLE_ACTION_BAD_3D_TILES = "bad-3dtiles"
        const val SAMPLE_ACTION_REMOVE_3D_TILES = "remove-3dtiles"
        const val SAMPLE_ACTION_READD_3D_TILES = "readd-3dtiles"
        const val SAMPLE_ACTION_ZOOM_3D_TILES = "zoom-3dtiles"
        const val SAMPLE_ACTION_OFFLINE_PREFETCH = "offline-prefetch"
        const val SAMPLE_ACTION_CANCEL_PREFETCH = "cancel-prefetch"
        const val SAMPLE_ACTION_SNAPSHOT = "snapshot"
        const val SAMPLE_ACTION_POST_RECREATE_SNAPSHOT = "post-recreate-snapshot"
        const val SAMPLE_ACTION_GEOJSON = "geojson"
        const val SAMPLE_ACTION_DRAW = "draw"
        const val SAMPLE_ACTION_CLEAR_DRAW = "clear-draw"
        const val SAMPLE_ACTION_LOCATION = "location"
        const val SAMPLE_ACTION_LOCATION_FOLLOW = "location-follow"
        const val SAMPLE_ACTION_CLEAR_LOCATION = "clear-location"
        const val PBF_WIRE_VARINT = 0
        const val PBF_WIRE_LENGTH_DELIMITED = 2
        const val MVT_TILE_LAYERS_FIELD = 3
        const val MVT_LAYER_NAME_FIELD = 1
        const val MVT_LAYER_FEATURES_FIELD = 2
        const val MVT_LAYER_KEYS_FIELD = 3
        const val MVT_LAYER_VALUES_FIELD = 4
        const val MVT_LAYER_EXTENT_FIELD = 5
        const val MVT_LAYER_VERSION_FIELD = 15
        const val MVT_FEATURE_ID_FIELD = 1
        const val MVT_FEATURE_TAGS_FIELD = 2
        const val MVT_FEATURE_TYPE_FIELD = 3
        const val MVT_FEATURE_GEOMETRY_FIELD = 4
        const val MVT_VALUE_STRING_FIELD = 1
        const val MVT_GEOMETRY_LINE_STRING = 2
        const val MVT_COMMAND_MOVE_TO = 1
        const val MVT_COMMAND_LINE_TO = 2
        const val MVT_COMMAND_COUNT_SHIFT = 3
    }
}
