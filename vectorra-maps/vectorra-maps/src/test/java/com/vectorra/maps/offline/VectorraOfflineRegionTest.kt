package com.vectorra.maps.offline

import com.vectorra.maps.layer.VectorraRasterLayer
import com.vectorra.maps.network.TileResourceType
import com.vectorra.maps.network.TileScheme
import com.vectorra.maps.source.VectorraRasterTileSource
import com.vectorra.maps.terrain.VectorraTerrainSource
import com.vectorra.maps.vector.VectorraVectorTileSource
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorraOfflineRegionTest {
    @Test
    fun regionEnumeratesXyzTemplateRequestsForBoundsAndZoom() {
        val region = VectorraOfflineRegion(
            bounds = VectorraOfflineBounds(west = -80.0, south = 10.0, east = -70.0, north = 20.0),
            minZoom = 2,
            maxZoom = 2
        )
        val source = VectorraOfflineTileSource(
            sourceId = "raster",
            layerId = "raster-layer",
            templateUrl = "https://tiles.example.com/{z}/{x}/{y}.png",
            minZoom = 0,
            maxZoom = 4,
            resourceType = TileResourceType.RASTER
        )

        val requests = region.toTileRequests(listOf(source))

        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("raster", request.sourceId)
        assertEquals("raster-layer", request.layerId)
        assertEquals("https://tiles.example.com/2/1/1.png", request.url)
        assertEquals(2, request.tileId?.z)
        assertEquals(1, request.tileId?.x)
        assertEquals(1, request.tileId?.y)
        assertEquals(TileScheme.XYZ, request.tileId?.scheme)
        assertEquals(TileResourceType.RASTER, request.resourceType)
    }

    @Test
    fun regionEnumeratesTmsUrlWhileKeepingCanonicalTileIdY() {
        val region = VectorraOfflineRegion(
            bounds = VectorraOfflineBounds(west = -80.0, south = 10.0, east = -70.0, north = 20.0),
            minZoom = 2,
            maxZoom = 2
        )
        val vector = VectorraVectorTileSource.tms(
            id = "vector",
            templateUrl = "https://tiles.example.com/\${z}/\${x}/\${y}.pbf",
            minZoom = 0,
            maxZoom = 4,
            headers = mapOf("Authorization" to "Bearer token")
        )

        val request = region.toTileRequests(
            listOf(VectorraOfflineTileSource.vector(vector, layerId = "roads"))
        ).single()

        assertEquals("https://tiles.example.com/2/1/2.pbf", request.url)
        assertEquals(1, request.tileId?.y)
        assertEquals(TileScheme.TMS, request.tileId?.scheme)
        assertEquals("Bearer token", request.headers["Authorization"])
        assertEquals(TileResourceType.VECTOR, request.resourceType)
        assertEquals("mvt", request.metadata["kind"])
    }

    @Test
    fun regionUsesZoomIntersectionWithSource() {
        val region = VectorraOfflineRegion(
            bounds = VectorraOfflineBounds(west = -80.0, south = 10.0, east = -70.0, north = 20.0),
            minZoom = 1,
            maxZoom = 3
        )
        val source = VectorraOfflineTileSource(
            sourceId = "restricted",
            templateUrl = "https://tiles.example.com/{z}/{x}/{y}.png",
            minZoom = 2,
            maxZoom = 2
        )

        val requests = region.toTileRequests(listOf(source))

        assertEquals(listOf(2), requests.mapNotNull { it.tileId?.z }.distinct())
    }

    @Test
    fun sourceFactoriesPreserveResourceTypeAndHeaders() {
        val rasterLayer = VectorraRasterLayer(
            id = "imagery-layer",
            sourceId = "imagery",
            templateUrl = "https://raster.example.com/{z}/{x}/{y}.jpg",
            headers = mapOf("X-Raster" to "1")
        )
        val rasterSource = VectorraRasterTileSource.xyz(
            id = "raster-source",
            templateUrl = "https://raster.example.com/{z}/{x}/{y}.png"
        )
        val terrainSource = VectorraTerrainSource(
            id = "terrain",
            templateUrl = "https://terrain.example.com/{z}/{x}/{y}.png",
            headers = mapOf("X-Dem" to "1")
        )

        val fromLayer = VectorraOfflineTileSource.raster(rasterLayer)
        val fromSource = VectorraOfflineTileSource.raster(rasterSource, layerId = "raster-layer")
        val terrain = VectorraOfflineTileSource.terrain(terrainSource)

        assertEquals("imagery-layer", fromLayer.layerId)
        assertEquals("1", fromLayer.headers["X-Raster"])
        assertEquals(TileResourceType.RASTER, fromSource.resourceType)
        assertEquals("raster-layer", fromSource.layerId)
        assertEquals(TileResourceType.DEM, terrain.resourceType)
        assertEquals("terrarium", terrain.metadata["encoding"])
        assertEquals("1", terrain.headers["X-Dem"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAntimeridianBoundsUntilSplitRegionsAreExplicit() {
        VectorraOfflineBounds(west = 170.0, south = -10.0, east = -170.0, north = 10.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptySources() {
        VectorraOfflineRegion(
            bounds = VectorraOfflineBounds(west = -80.0, south = 10.0, east = -70.0, north = 20.0),
            minZoom = 2,
            maxZoom = 2
        ).toTileRequests(emptyList())
    }
}
