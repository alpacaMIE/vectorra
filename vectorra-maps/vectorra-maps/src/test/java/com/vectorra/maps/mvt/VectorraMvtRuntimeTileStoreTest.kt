package com.vectorra.maps.mvt

import com.vectorra.maps.vector.VectorraVectorTileLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMvtRuntimeTileStoreTest {
    @Test
    fun putDecodedTileOwnsDecodedTileNativeHandleAndQueryFeatures() {
        val renderer = RecordingMvtRenderer()
        val store = VectorraMvtRuntimeTileStore(
            sourceId = "vector",
            layer = VectorraVectorTileLayer.Line(
                id = "roads-line",
                sourceId = "vector",
                sourceLayer = "roads",
                widthPixels = 2.0
            ),
            nativeRenderer = renderer
        )
        val tileId = VectorraMvtTileId(z = 0, x = 0, y = 0)

        val runtimeTile = store.putDecodedTile(tileId, vectorTile())

        assertEquals(setOf(tileId), store.loadedTileIds())
        assertEquals(setOf("roads-line:0/0/0"), store.nativeTileHandles())
        assertEquals("roads-line:0/0/0", runtimeTile.nativeTileHandle)
        assertEquals(listOf("1"), store.queryFeatures().map { it.id })
        val hitFeature = store.queryHitFeatures().single()
        assertEquals("1", hitFeature.id)
        assertEquals("roads-line", hitFeature.layerId)
        assertEquals("vector", hitFeature.sourceId)
        assertEquals("roads", hitFeature.properties["source-layer"])
        assertEquals("Main", hitFeature.properties["name"])
        assertEquals(listOf("roads-line:0/0/0"), renderer.renderedHandles)
        assertEquals(VectorraMvtRenderLayerKind.LINE, renderer.renderedInputs.single().style.kind)
        assertEquals(listOf("1"), renderer.renderedInputs.single().features.map { it.featureId })
        assertEquals(2.0f, renderer.renderedInputs.single().style.widthPixels)
    }

    @Test
    fun replacingTileRemovesPreviousNativeHandleAndQueryState() {
        val renderer = RecordingMvtRenderer()
        val store = VectorraMvtRuntimeTileStore(
            sourceId = "vector",
            layer = VectorraVectorTileLayer.Circle(
                id = "poi-circle",
                sourceId = "vector",
                sourceLayer = "poi"
            ),
            nativeRenderer = renderer
        )
        val tileId = VectorraMvtTileId(z = 1, x = 1, y = 0)

        store.putDecodedTile(tileId, vectorTile(poiId = 7L))
        store.putDecodedTile(tileId, vectorTile(poiId = 8L))

        assertEquals(setOf(tileId), store.loadedTileIds())
        assertEquals(listOf("poi-circle:1/1/0"), renderer.removedTileHandles)
        assertEquals(
            listOf(
                "render:poi-circle:1/1/0",
                "remove:poi-circle:1/1/0",
                "render:poi-circle:1/1/0"
            ),
            renderer.events
        )
        assertEquals(listOf("8"), store.queryFeatures().map { it.id })
        assertEquals(2, renderer.renderedInputs.size)
    }

    @Test
    fun removeTileClearsRenderAndQueryEntriesTogether() {
        val renderer = RecordingMvtRenderer()
        val store = VectorraMvtRuntimeTileStore(
            sourceId = "vector",
            layer = VectorraVectorTileLayer.Fill(
                id = "land-fill",
                sourceId = "vector",
                sourceLayer = "land"
            ),
            nativeRenderer = renderer
        )
        val tileId = VectorraMvtTileId(z = 0, x = 0, y = 0)
        store.putDecodedTile(tileId, vectorTile())

        val removed = store.removeTile(tileId)

        assertEquals(tileId, removed?.tileId)
        assertTrue(store.loadedTileIds().isEmpty())
        assertTrue(store.queryFeatures().isEmpty())
        assertTrue(store.queryHitFeatures().isEmpty())
        assertEquals(listOf("land-fill:0/0/0"), renderer.removedTileHandles)
    }

    @Test
    fun queryFeaturesComeOnlyFromCurrentlyLoadedTilesAcrossTileChanges() {
        val renderer = RecordingMvtRenderer()
        val store = VectorraMvtRuntimeTileStore(
            sourceId = "vector",
            layer = VectorraVectorTileLayer.Line(
                id = "roads-line",
                sourceId = "vector",
                sourceLayer = "roads"
            ),
            nativeRenderer = renderer
        )
        val westTile = VectorraMvtTileId(z = 1, x = 0, y = 0)
        val eastTile = VectorraMvtTileId(z = 1, x = 1, y = 0)
        store.putDecodedTile(westTile, vectorTile(roadId = 10L, roadName = "West"))
        store.putDecodedTile(eastTile, vectorTile(roadId = 20L, roadName = "East"))

        assertEquals(setOf(westTile, eastTile), store.loadedTileIds())
        assertEquals(listOf("10", "20"), store.queryFeatures().map { it.id })
        assertEquals(listOf("West", "East"), store.queryHitFeatures().map { it.properties["name"] })

        store.removeTile(westTile)

        assertEquals(setOf(eastTile), store.loadedTileIds())
        assertEquals(listOf("20"), store.queryFeatures().map { it.id })
        assertEquals(listOf("East"), store.queryHitFeatures().map { it.properties["name"] })
        assertEquals(listOf("roads-line:1/0/0"), renderer.removedTileHandles)
    }

    @Test
    fun renderInputContainsEveryLineStringPart() {
        val renderer = RecordingMvtRenderer()
        val store = VectorraMvtRuntimeTileStore(
            sourceId = "vector",
            layer = VectorraVectorTileLayer.Line(
                id = "roads-line",
                sourceId = "vector",
                sourceLayer = "roads"
            ),
            nativeRenderer = renderer
        )

        store.putDecodedTile(VectorraMvtTileId(z = 0, x = 0, y = 0), multiPartLineVectorTile())

        val renderedFeatures = renderer.renderedInputs.single().features
        assertEquals(listOf("42:0", "42:1"), renderedFeatures.map { it.featureId })
        assertEquals(2, renderedFeatures.size)
        assertEquals(listOf("42:0", "42:1"), store.queryFeatures().map { it.id })
    }

    @Test
    fun clearRemovesNativeLayerAndAllOwnedState() {
        val renderer = RecordingMvtRenderer()
        val store = VectorraMvtRuntimeTileStore(
            sourceId = "vector",
            layer = VectorraVectorTileLayer.Symbol(
                id = "place-labels",
                sourceId = "vector",
                sourceLayer = "poi",
                textField = "name"
            ),
            nativeRenderer = renderer
        )
        store.putDecodedTile(VectorraMvtTileId(z = 0, x = 0, y = 0), vectorTile())

        store.clear()

        assertTrue(store.loadedTileIds().isEmpty())
        assertTrue(store.nativeTileHandles().isEmpty())
        assertTrue(store.queryFeatures().isEmpty())
        assertTrue(store.queryHitFeatures().isEmpty())
        assertEquals(listOf("place-labels"), renderer.removedLayers)
    }

    @Test
    fun resubmitLoadedTilesRendersExistingDecodedTilesAgain() {
        val renderer = RecordingMvtRenderer()
        val store = VectorraMvtRuntimeTileStore(
            sourceId = "vector",
            layer = VectorraVectorTileLayer.Line(
                id = "roads-line",
                sourceId = "vector",
                sourceLayer = "roads"
            ),
            nativeRenderer = renderer
        )
        val tileId = VectorraMvtTileId(z = 0, x = 0, y = 0)
        store.putDecodedTile(tileId, vectorTile())
        renderer.events.clear()

        store.resubmitLoadedTiles()

        assertEquals(setOf(tileId), store.loadedTileIds())
        assertEquals(listOf("render:roads-line:0/0/0"), renderer.events)
        assertEquals(listOf("1"), store.queryFeatures().map { it.id })
    }

    private fun vectorTile(
        roadId: Long = 1L,
        roadName: String = "Main",
        poiId: Long = 7L
    ): VectorraMvtTile {
        return VectorraMvtTile(
            layers = listOf(
                VectorraMvtLayer(
                    name = "roads",
                    version = 2,
                    extent = 4096,
                    features = listOf(
                        VectorraMvtFeature(
                            id = roadId,
                            layerName = "roads",
                            geometry = VectorraMvtGeometry.LineString(
                                listOf(
                                    listOf(
                                        VectorraMvtPoint(0, 2048),
                                        VectorraMvtPoint(4096, 2048)
                                    )
                                )
                            ),
                            properties = mapOf("name" to VectorraMvtValue.StringValue(roadName))
                        )
                    )
                ),
                VectorraMvtLayer(
                    name = "land",
                    version = 2,
                    extent = 4096,
                    features = listOf(
                        VectorraMvtFeature(
                            id = 2L,
                            layerName = "land",
                            geometry = VectorraMvtGeometry.Polygon(
                                listOf(
                                    listOf(
                                        VectorraMvtPoint(0, 0),
                                        VectorraMvtPoint(4096, 0),
                                        VectorraMvtPoint(4096, 4096),
                                        VectorraMvtPoint(0, 0)
                                    )
                                )
                            ),
                            properties = emptyMap()
                        )
                    )
                ),
                VectorraMvtLayer(
                    name = "poi",
                    version = 2,
                    extent = 4096,
                    features = listOf(
                        VectorraMvtFeature(
                            id = poiId,
                            layerName = "poi",
                            geometry = VectorraMvtGeometry.Point(listOf(VectorraMvtPoint(2048, 2048))),
                            properties = mapOf("name" to VectorraMvtValue.StringValue("Center"))
                        )
                    )
                )
            )
        )
    }

    private fun multiPartLineVectorTile(): VectorraMvtTile {
        return VectorraMvtTile(
            layers = listOf(
                VectorraMvtLayer(
                    name = "roads",
                    version = 2,
                    extent = 4096,
                    features = listOf(
                        VectorraMvtFeature(
                            id = 42L,
                            layerName = "roads",
                            geometry = VectorraMvtGeometry.LineString(
                                listOf(
                                    listOf(
                                        VectorraMvtPoint(0, 2048),
                                        VectorraMvtPoint(1024, 2048)
                                    ),
                                    listOf(
                                        VectorraMvtPoint(2048, 2048),
                                        VectorraMvtPoint(4096, 2048)
                                    )
                                )
                            ),
                            properties = mapOf("name" to VectorraMvtValue.StringValue("Split Road"))
                        )
                    )
                )
            )
        )
    }

    private class RecordingMvtRenderer : VectorraMvtNativeRenderer {
        val renderedInputs = mutableListOf<VectorraMvtRenderTileInput>()
        val renderedHandles = mutableListOf<String>()
        val removedTileHandles = mutableListOf<String>()
        val removedLayers = mutableListOf<String>()
        val events = mutableListOf<String>()

        override fun renderTile(input: VectorraMvtRenderTileInput): String {
            renderedInputs += input
            renderedHandles += input.nativeTileHandle
            events += "render:${input.nativeTileHandle}"
            return input.nativeTileHandle
        }

        override fun removeTile(nativeTileHandle: String) {
            removedTileHandles += nativeTileHandle
            events += "remove:$nativeTileHandle"
        }

        override fun removeLayer(layerId: String) {
            removedLayers += layerId
            events += "remove-layer:$layerId"
        }
    }
}
