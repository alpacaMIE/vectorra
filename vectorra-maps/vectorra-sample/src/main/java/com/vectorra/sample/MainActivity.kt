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
import com.vectorra.maps.VectorraSdk
import com.vectorra.maps.VectorraSurfaceLifecycleState
import com.vectorra.maps.offline.VectorraMbTilesRasterSource
import com.vectorra.maps.terrain.VectorraTerrainOptions
import com.vectorra.maps.terrain.VectorraTerrainSource
import java.io.File
import java.io.Closeable

class MainActivity : Activity() {
    private lateinit var mapView: VectorraMapView
    private lateinit var statusText: TextView
    private var terrainExaggeration = 1.0
    private var layersInstalled = false
    private var mapLoadErrorSubscription: Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            }

            override fun onMapLoadError(view: VectorraMapView, error: VectorraMapLoadError) {
                statusText.text = "Vectorra load error: ${error.message}"
            }
        }
        mapLoadErrorSubscription = mapView.addMapLoadErrorListener { _, error ->
            statusText.text = "Vectorra load error: ${error.message}"
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
                }
            ))
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
        map.addTerrain(
            source = sampleTerrainSource("sample-base-dem"),
            options = VectorraTerrainOptions(exaggeration = terrainExaggeration)
        )
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
}
