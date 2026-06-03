package com.vectorra.maps.mvt

import com.vectorra.maps.query.VectorraAnnotationGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraMvtGeoJsonTest {
    @Test
    fun convertsTileLocalPointToLonLatAtZoomZero() {
        val tile = VectorraMvtTile(
            layers = listOf(
                VectorraMvtLayer(
                    name = "places",
                    version = 2,
                    extent = 4096,
                    features = listOf(
                        VectorraMvtFeature(
                            id = 1,
                            layerName = "places",
                            geometry = VectorraMvtGeometry.Point(listOf(VectorraMvtPoint(2048, 2048))),
                            properties = mapOf("name" to VectorraMvtValue.StringValue("Center"))
                        )
                    )
                )
            )
        )

        val feature = tile.toGeoJsonFeatures(VectorraMvtTileId(0, 0, 0)).single()
        val point = feature.geometry as VectorraAnnotationGeometry.Point
        assertEquals(0.0, point.coordinate.longitude, 1e-9)
        assertEquals(0.0, point.coordinate.latitude, 1e-9)
        assertEquals("Center", feature.properties["name"])
        assertEquals("places", feature.properties["mvt_layer"])
    }

    @Test
    fun filtersLayersDuringConversion() {
        val tile = VectorraMvtTile(
            layers = listOf(
                layer("roads"),
                layer("buildings")
            )
        )

        val features = tile.toGeoJsonFeatures(
            tileId = VectorraMvtTileId(1, 0, 0),
            layerNames = setOf("buildings")
        )

        assertEquals(1, features.size)
        assertEquals("buildings", features.single().properties["mvt_layer"])
    }

    @Test
    fun convertsLineStringGeometry() {
        val decoded = VectorraMvtFeature(
            id = null,
            layerName = "roads",
            geometry = VectorraMvtGeometry.LineString(
                listOf(listOf(VectorraMvtPoint(0, 0), VectorraMvtPoint(4096, 4096)))
            ),
            properties = emptyMap()
        ).toDecodedFeature(VectorraMvtTileId(0, 0, 0), 4096)

        assertTrue(decoded?.geometry is VectorraAnnotationGeometry.LineString)
    }

    @Test
    fun convertsTileCornersWithYDownWebMercator() {
        val tile = VectorraMvtTileId(z = 1, x = 1, y = 0)

        val northWest = pointFeature(VectorraMvtPoint(0, 0))
            .toDecodedFeature(tile, 4096)
            ?.geometry as VectorraAnnotationGeometry.Point
        val southEast = pointFeature(VectorraMvtPoint(4096, 4096))
            .toDecodedFeature(tile, 4096)
            ?.geometry as VectorraAnnotationGeometry.Point

        assertEquals(0.0, northWest.coordinate.longitude, 1e-9)
        assertEquals(MAX_WEB_MERCATOR_LATITUDE, northWest.coordinate.latitude, 1e-9)
        assertEquals(180.0, southEast.coordinate.longitude, 1e-9)
        assertEquals(0.0, southEast.coordinate.latitude, 1e-9)
    }

    @Test
    fun respectsLayerExtentWhenConvertingCoordinates() {
        val decoded = pointFeature(VectorraMvtPoint(128, 128))
            .toDecodedFeature(VectorraMvtTileId(z = 0, x = 0, y = 0), 256)
            ?.geometry as VectorraAnnotationGeometry.Point

        assertEquals(0.0, decoded.coordinate.longitude, 1e-9)
        assertEquals(0.0, decoded.coordinate.latitude, 1e-9)
    }

    @Test
    fun preservesPolygonRingsAndTileBoundaryCoordinates() {
        val decoded = VectorraMvtFeature(
            id = 9,
            layerName = "land",
            geometry = VectorraMvtGeometry.Polygon(
                listOf(
                    listOf(
                        VectorraMvtPoint(0, 0),
                        VectorraMvtPoint(4096, 0),
                        VectorraMvtPoint(4096, 4096),
                        VectorraMvtPoint(0, 4096),
                        VectorraMvtPoint(0, 0)
                    ),
                    listOf(
                        VectorraMvtPoint(1024, 1024),
                        VectorraMvtPoint(2048, 1024),
                        VectorraMvtPoint(2048, 2048),
                        VectorraMvtPoint(1024, 1024)
                    )
                )
            ),
            properties = mapOf("kind" to VectorraMvtValue.StringValue("park"))
        ).toDecodedFeature(VectorraMvtTileId(z = 0, x = 0, y = 0), 4096)

        val polygon = decoded?.geometry as VectorraAnnotationGeometry.Polygon
        assertEquals(2, polygon.rings.size)
        assertEquals(5, polygon.rings[0].size)
        assertEquals(4, polygon.rings[1].size)
        assertEquals(-180.0, polygon.rings[0].first().longitude, 1e-9)
        assertEquals(MAX_WEB_MERCATOR_LATITUDE, polygon.rings[0].first().latitude, 1e-9)
        assertEquals(180.0, polygon.rings[0][2].longitude, 1e-9)
        assertEquals(-MAX_WEB_MERCATOR_LATITUDE, polygon.rings[0][2].latitude, 1e-9)
        assertEquals("park", decoded.properties["kind"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveExtent() {
        pointFeature(VectorraMvtPoint(0, 0)).toDecodedFeature(VectorraMvtTileId(0, 0, 0), 0)
    }

    private fun layer(name: String): VectorraMvtLayer {
        return VectorraMvtLayer(
            name = name,
            version = 2,
            extent = 4096,
            features = listOf(
                VectorraMvtFeature(
                    id = null,
                    layerName = name,
                    geometry = VectorraMvtGeometry.Point(listOf(VectorraMvtPoint(0, 0))),
                    properties = emptyMap()
                )
            )
        )
    }

    private fun pointFeature(point: VectorraMvtPoint): VectorraMvtFeature {
        return VectorraMvtFeature(
            id = null,
            layerName = "places",
            geometry = VectorraMvtGeometry.Point(listOf(point)),
            properties = emptyMap()
        )
    }

    private companion object {
        const val MAX_WEB_MERCATOR_LATITUDE = 85.0511287798066
    }
}
