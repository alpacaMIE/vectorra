package com.vectorra.maps.mvt

import com.vectorra.maps.query.VectorraAnnotationGeometry
import com.vectorra.maps.query.VectorraCoordinate
import com.vectorra.maps.vector.VectorraVectorTileLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMvtRuntimeTileStoreTest {
    @Test
    fun putTileOwnsNativeHandleAndQueryFeatures() {
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
        renderer.queryFeaturesByTileId[tileId] = listOf(queryFeature("1", "roads", "Main"))

        val runtimeTile = store.putTile(tileId, tileBytes(1))

        assertEquals(setOf(tileId), store.loadedTileIds())
        assertEquals(setOf("roads-line:0/0/0"), store.nativeTileHandles())
        assertEquals("roads-line:0/0/0", runtimeTile.nativeTileHandle)
        assertEquals(true, runtimeTile.rendered)
        assertEquals(listOf("1"), store.queryFeatures().map { it.id })
        val hitFeature = store.queryHitFeatures().single()
        assertEquals("1", hitFeature.id)
        assertEquals("roads-line", hitFeature.layerId)
        assertEquals("vector", hitFeature.sourceId)
        assertEquals("roads", hitFeature.properties["source-layer"])
        assertEquals("Main", hitFeature.properties["name"])
        assertEquals(listOf("submit:roads-line:0/0/0:true"), renderer.events)
        assertEquals(VectorraMvtRenderLayerKind.LINE, renderer.submittedRequests.single().style.kind)
        assertEquals(2.0f, renderer.submittedRequests.single().style.widthPixels)
    }

    @Test
    fun replacingTileSubmitsReplacementWithoutRemovingSameNativeHandle() {
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

        renderer.queryFeaturesByTileId[tileId] = listOf(queryFeature("7", "poi", "Old"))
        store.putTile(tileId, tileBytes(7))
        renderer.queryFeaturesByTileId[tileId] = listOf(queryFeature("8", "poi", "New"))
        store.putTile(tileId, tileBytes(8))

        assertEquals(setOf(tileId), store.loadedTileIds())
        assertTrue(renderer.removedTileHandles.isEmpty())
        assertEquals(
            listOf(
                "submit:poi-circle:1/1/0:true",
                "submit:poi-circle:1/1/0:true"
            ),
            renderer.events
        )
        assertEquals(listOf("8"), store.queryFeatures().map { it.id })
        assertEquals(2, renderer.submittedRequests.size)
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
        renderer.queryFeaturesByTileId[tileId] = listOf(queryFeature("2", "land", "Park"))
        store.putTile(tileId, tileBytes(2))

        val removed = store.removeTile(tileId)

        assertEquals(tileId, removed?.tileId)
        assertEquals(false, removed?.rendered)
        assertTrue(store.loadedTileIds().isEmpty())
        assertTrue(store.queryFeatures().isEmpty())
        assertTrue(store.queryHitFeatures().isEmpty())
        assertEquals(listOf("land-fill:0/0/0"), renderer.removedTileHandles)
    }

    @Test
    fun queryFeaturesComeOnlyFromCurrentlyRenderedTilesAcrossTileChanges() {
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
        renderer.queryFeaturesByTileId[westTile] = listOf(queryFeature("10", "roads", "West"))
        renderer.queryFeaturesByTileId[eastTile] = listOf(queryFeature("20", "roads", "East"))
        store.putTile(westTile, tileBytes(10))
        store.putTile(eastTile, tileBytes(20), renderNow = false)

        assertEquals(setOf(westTile, eastTile), store.loadedTileIds())
        assertEquals(listOf("10"), store.queryFeatures().map { it.id })

        store.setRenderedTileIds(setOf(eastTile))

        assertEquals(listOf("20"), store.queryFeatures().map { it.id })
        assertEquals(listOf("East"), store.queryHitFeatures().map { it.properties["name"] })
        assertEquals(
            listOf(
                "submit:roads-line:1/0/0:true",
                "submit:roads-line:1/1/0:false",
                "rendered:roads-line:1/1/0:true",
                "rendered:roads-line:1/0/0:false"
            ),
            renderer.events
        )
    }

    @Test
    fun setRenderedTileIdsKeepsInactiveDecodedTilesAvailableForFallbackSwitching() {
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
        val parentTile = VectorraMvtTileId(z = 0, x = 0, y = 0)
        val childTile = VectorraMvtTileId(z = 1, x = 1, y = 0)
        renderer.queryFeaturesByTileId[parentTile] = listOf(queryFeature("100", "roads", "Parent"))
        renderer.queryFeaturesByTileId[childTile] = listOf(queryFeature("200", "roads", "Child"))
        store.putTile(parentTile, tileBytes(100))
        store.putTile(childTile, tileBytes(200))
        renderer.events.clear()

        store.setRenderedTileIds(setOf(parentTile))

        assertEquals(setOf(parentTile, childTile), store.loadedTileIds())
        assertEquals(setOf("roads-line:0/0/0"), store.nativeTileHandles())
        assertEquals(listOf("100"), store.queryFeatures().map { it.id })
        assertEquals(listOf("rendered:roads-line:1/1/0:false"), renderer.events)

        renderer.events.clear()
        store.setRenderedTileIds(setOf(childTile))

        assertEquals(setOf(parentTile, childTile), store.loadedTileIds())
        assertEquals(setOf("roads-line:1/1/0"), store.nativeTileHandles())
        assertEquals(listOf("200"), store.queryFeatures().map { it.id })
        assertEquals(
            listOf(
                "rendered:roads-line:1/1/0:true",
                "rendered:roads-line:0/0/0:false"
            ),
            renderer.events
        )
    }

    @Test
    fun putTileCanCacheWithoutRenderingUntilRenderSetSelectsTile() {
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
        renderer.queryFeaturesByTileId[tileId] = listOf(queryFeature("1", "roads", "Main"))

        store.putTile(tileId, tileBytes(1), renderNow = false)

        assertEquals(setOf(tileId), store.loadedTileIds())
        assertTrue(store.nativeTileHandles().isEmpty())
        assertTrue(store.queryFeatures().isEmpty())
        assertEquals(listOf("submit:roads-line:0/0/0:false"), renderer.events)

        store.setRenderedTileIds(setOf(tileId))

        assertEquals(setOf("roads-line:0/0/0"), store.nativeTileHandles())
        assertEquals(listOf("1"), store.queryFeatures().map { it.id })
        assertEquals(
            listOf(
                "submit:roads-line:0/0/0:false",
                "rendered:roads-line:0/0/0:true"
            ),
            renderer.events
        )
    }

    @Test
    fun trimLoadedTilesEvictsOnlyInactiveUnretainedDecodedTiles() {
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
        val firstTile = VectorraMvtTileId(z = 1, x = 0, y = 0)
        val retainedTile = VectorraMvtTileId(z = 1, x = 1, y = 0)
        val activeTile = VectorraMvtTileId(z = 1, x = 1, y = 1)
        renderer.queryFeaturesByTileId[firstTile] = listOf(queryFeature("10", "roads", "First"))
        renderer.queryFeaturesByTileId[retainedTile] = listOf(queryFeature("20", "roads", "Retained"))
        renderer.queryFeaturesByTileId[activeTile] = listOf(queryFeature("30", "roads", "Active"))
        store.putTile(firstTile, tileBytes(10), renderNow = false)
        store.putTile(retainedTile, tileBytes(20), renderNow = false)
        store.putTile(activeTile, tileBytes(30))
        renderer.events.clear()

        store.trimLoadedTiles(maxLoadedTiles = 2, retainTileIds = setOf(retainedTile))

        assertEquals(setOf(retainedTile, activeTile), store.loadedTileIds())
        assertEquals(setOf("roads-line:1/1/1"), store.nativeTileHandles())
        assertEquals(listOf("30"), store.queryFeatures().map { it.id })
        assertEquals(listOf("roads-line:1/0/0"), renderer.removedTileHandles)
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
        val tileId = VectorraMvtTileId(z = 0, x = 0, y = 0)
        renderer.queryFeaturesByTileId[tileId] = listOf(queryFeature("7", "poi", "Center"))
        store.putTile(tileId, tileBytes(7))

        store.clear()

        assertTrue(store.loadedTileIds().isEmpty())
        assertTrue(store.nativeTileHandles().isEmpty())
        assertTrue(store.queryFeatures().isEmpty())
        assertTrue(store.queryHitFeatures().isEmpty())
        assertEquals(listOf("place-labels"), renderer.removedLayers)
    }

    @Test
    fun resubmitLoadedTilesRendersExistingNativeTilesAgain() {
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
        renderer.queryFeaturesByTileId[tileId] = listOf(queryFeature("1", "roads", "Main"))
        store.putTile(tileId, tileBytes(1))
        renderer.events.clear()

        store.resubmitLoadedTiles()

        assertEquals(setOf(tileId), store.loadedTileIds())
        assertEquals(listOf("rendered:roads-line:0/0/0:true"), renderer.events)
        assertEquals(listOf("1"), store.queryFeatures().map { it.id })
    }

    private fun tileBytes(value: Int): ByteArray = byteArrayOf(value.toByte())

    private fun queryFeature(id: String, layerName: String, name: String): VectorraMvtDecodedFeature {
        return VectorraMvtDecodedFeature(
            id = id,
            layerName = layerName,
            geometry = VectorraAnnotationGeometry.LineString(
                listOf(
                    VectorraCoordinate(-1.0, 0.0),
                    VectorraCoordinate(1.0, 0.0)
                )
            ),
            properties = mapOf("name" to name)
        )
    }

    private class RecordingMvtRenderer : VectorraMvtNativeRenderer {
        val queryFeaturesByTileId = linkedMapOf<VectorraMvtTileId, List<VectorraMvtDecodedFeature>>()
        val submittedRequests = mutableListOf<VectorraMvtNativeTileRequest>()
        val renderedToggles = mutableListOf<Pair<String, Boolean>>()
        val removedTileHandles = mutableListOf<String>()
        val removedLayers = mutableListOf<String>()
        val events = mutableListOf<String>()

        override fun submitTile(request: VectorraMvtNativeTileRequest): VectorraMvtSubmittedTile {
            submittedRequests += request
            events += "submit:${request.nativeTileHandle}:${request.renderNow}"
            return VectorraMvtSubmittedTile(
                nativeTileHandle = request.nativeTileHandle,
                queryFeatures = queryFeaturesByTileId[request.tileId].orEmpty()
            )
        }

        override fun setTileRendered(nativeTileHandle: String, rendered: Boolean) {
            renderedToggles += nativeTileHandle to rendered
            events += "rendered:$nativeTileHandle:$rendered"
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
