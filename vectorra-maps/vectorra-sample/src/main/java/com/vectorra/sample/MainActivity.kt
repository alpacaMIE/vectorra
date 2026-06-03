package com.vectorra.sample

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import com.vectorra.maps.VectorraResourceLoadState
import com.vectorra.maps.VectorraSdk
import com.vectorra.maps.VectorraSurfaceLifecycleState
import com.vectorra.maps.model.VectorraGlbModelLayerOptions
import com.vectorra.maps.model.VectorraGlbModelSource
import com.vectorra.maps.offline.VectorraMbTilesRasterSource
import com.vectorra.maps.terrain.VectorraTerrainOptions
import com.vectorra.maps.terrain.VectorraTerrainSource
import com.vectorra.maps.tiles3d.Vectorra3DTilesLayer
import com.vectorra.maps.tiles3d.Vectorra3DTilesOptions
import com.vectorra.maps.tiles3d.Vectorra3DTilesSource
import java.io.File
import java.io.Closeable

class MainActivity : Activity() {
    private lateinit var mapView: VectorraMapView
    private lateinit var statusText: TextView
    private var terrainExaggeration = 1.0
    private var layersInstalled = false
    private var modelInstalled = false
    private var modelVisible = true
    private var mapLoadErrorSubscription: Closeable? = null
    private var resourceStatusSubscription: Closeable? = null
    private var pendingSmokeAction: String? = null

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
                statusText.text = "Vectorra map ready"
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
            statusText.text = when (status.state) {
                VectorraResourceLoadState.FAILED ->
                    "${status.kind.name.lowercase()} ${status.layerId} failed: ${status.error?.message}"
                else ->
                    "${status.kind.name.lowercase()} ${status.layerId} ${status.state.name.lowercase()}"
            }
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

    private fun runPendingSmokeAction() {
        val action = pendingSmokeAction ?: return
        pendingSmokeAction = null
        statusText.post {
            when (action) {
                SAMPLE_ACTION_3D_TILES -> loadSample3DTiles()
                SAMPLE_ACTION_BAD_3D_TILES -> loadBrokenSample3DTiles()
                SAMPLE_ACTION_REMOVE_3D_TILES -> removeSample3DTiles()
                SAMPLE_ACTION_READD_3D_TILES -> reloadSample3DTiles()
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

    private companion object {
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
        const val SAMPLE_3D_TILES_LATITUDE = 39.853105846881554
        const val SAMPLE_BROKEN_3D_TILES_URI = "https://raw.githubusercontent.com/CesiumGS/3d-tiles-samples/main/missing-vectorra-smoke/tileset.json"
        const val SAMPLE_3D_TILES_REMOVE_DELAY_MS = 16_000L
        const val SAMPLE_3D_TILES_READD_DELAY_MS = 4_000L
        const val EXTRA_SAMPLE_ACTION = "vectorra.sample.action"
        const val SAMPLE_ACTION_3D_TILES = "3dtiles"
        const val SAMPLE_ACTION_BAD_3D_TILES = "bad-3dtiles"
        const val SAMPLE_ACTION_REMOVE_3D_TILES = "remove-3dtiles"
        const val SAMPLE_ACTION_READD_3D_TILES = "readd-3dtiles"
    }
}
