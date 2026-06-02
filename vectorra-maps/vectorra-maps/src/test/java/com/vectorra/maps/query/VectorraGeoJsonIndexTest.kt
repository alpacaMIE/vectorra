package com.vectorra.maps.query

import com.vectorra.maps.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorraGeoJsonIndexTest {

    private fun newIndex(zoom: Double = 10.0): VectorraGeoJsonIndex {
        return VectorraGeoJsonIndex(
            project = { coordinate -> VectorraScreenPoint(coordinate.longitude, coordinate.latitude) },
            camera = {
                CameraState(
                    longitude = 0.0,
                    latitude = 0.0,
                    zoom = zoom,
                    pitch = 0.0,
                    bearing = 0.0
                )
            }
        )
    }

    @Test
    fun pointLineAndPolygonAreQueryable() {
        val index = newIndex()
        index.setSource(
            VectorraGeoJsonSource(
                id = "source",
                features = listOf(
                    VectorraGeoJsonFeature(
                        id = "point",
                        geometry = VectorraAnnotationGeometry.Point(VectorraCoordinate(10.0, 10.0)),
                        properties = mapOf("name" to "p")
                    ),
                    VectorraGeoJsonFeature(
                        id = "line",
                        geometry = VectorraAnnotationGeometry.LineString(
                            listOf(VectorraCoordinate(20.0, 20.0), VectorraCoordinate(40.0, 20.0))
                        )
                    ),
                    VectorraGeoJsonFeature(
                        id = "polygon",
                        geometry = VectorraAnnotationGeometry.Polygon(
                            listOf(
                                listOf(
                                    VectorraCoordinate(60.0, 60.0),
                                    VectorraCoordinate(80.0, 60.0),
                                    VectorraCoordinate(80.0, 80.0),
                                    VectorraCoordinate(60.0, 80.0)
                                )
                            )
                        )
                    )
                )
            )
        )
        index.setLayer(VectorraGeoJsonLayer(id = "points", sourceId = "source", geometryTypes = setOf(VectorraGeometryType.POINT)))
        index.setLayer(VectorraGeoJsonLayer(id = "lines", sourceId = "source", geometryTypes = setOf(VectorraGeometryType.LINE_STRING)))
        index.setLayer(VectorraGeoJsonLayer(id = "polygons", sourceId = "source", geometryTypes = setOf(VectorraGeometryType.POLYGON)))

        assertEquals("point", index.query(VectorraScreenPoint(10.0, 10.0), VectorraQueryOptions(layerIds = setOf("points"))).single().id)
        assertEquals("line", index.query(VectorraScreenPoint(30.0, 20.0), VectorraQueryOptions(layerIds = setOf("lines"))).single().id)
        assertEquals("polygon", index.query(VectorraScreenPoint(70.0, 70.0), VectorraQueryOptions(layerIds = setOf("polygons"))).single().id)
    }

    @Test
    fun layerFilterAndPropertiesAreReturned() {
        val index = newIndex()
        index.setSource(
            VectorraGeoJsonSource(
                id = "source",
                features = listOf(
                    VectorraGeoJsonFeature(
                        id = "point",
                        geometry = VectorraAnnotationGeometry.Point(VectorraCoordinate(10.0, 10.0)),
                        properties = mapOf("businessId" to "b1")
                    )
                )
            )
        )
        index.setLayer(VectorraGeoJsonLayer(id = "visible", sourceId = "source"))
        index.setLayer(VectorraGeoJsonLayer(id = "other", sourceId = "source"))

        assertTrue(index.query(VectorraScreenPoint(10.0, 10.0), VectorraQueryOptions(layerIds = setOf("missing"))).isEmpty())
        val result = index.query(VectorraScreenPoint(10.0, 10.0), VectorraQueryOptions(layerIds = setOf("visible"))).single()
        assertEquals("b1", result.properties["businessId"])
        assertEquals("source", result.sourceId)
    }

    @Test
    fun clusteredPointQueryReturnsClusterAndLeaves() {
        val index = newIndex()
        index.setSource(
            VectorraGeoJsonSource(
                id = "source",
                cluster = true,
                clusterRadiusPixels = 30.0,
                features = listOf(
                    VectorraGeoJsonFeature("a", VectorraAnnotationGeometry.Point(VectorraCoordinate(100.0, 100.0))),
                    VectorraGeoJsonFeature("b", VectorraAnnotationGeometry.Point(VectorraCoordinate(110.0, 100.0))),
                    VectorraGeoJsonFeature("c", VectorraAnnotationGeometry.Point(VectorraCoordinate(300.0, 300.0)))
                )
            )
        )
        index.setLayer(
            VectorraGeoJsonLayer(
                id = "cluster",
                sourceId = "source",
                geometryTypes = setOf(VectorraGeometryType.POINT),
                filter = VectorraGeoJsonLayerFilter.CLUSTER
            )
        )
        index.setLayer(
            VectorraGeoJsonLayer(
                id = "unclustered",
                sourceId = "source",
                geometryTypes = setOf(VectorraGeometryType.POINT),
                filter = VectorraGeoJsonLayerFilter.UNCLUSTERED
            )
        )

        val cluster = index.query(VectorraScreenPoint(105.0, 100.0), VectorraQueryOptions(layerIds = setOf("cluster"))).single()
        assertEquals("true", cluster.properties["cluster"])
        assertEquals("2", cluster.properties["point_count"])
        val leaves = index.getClusterLeaves("source", cluster.clusterId ?: -1L)
        assertEquals(listOf("a", "b"), leaves.map { it.id })

        val single = index.query(VectorraScreenPoint(300.0, 300.0), VectorraQueryOptions(layerIds = setOf("unclustered"))).single()
        assertEquals("c", single.id)
        assertEquals("false", single.properties["cluster"])
    }

    @Test
    fun visibilityOpacityZoomAndZIndexAreApplied() {
        val index = newIndex(zoom = 10.0)
        index.setSource(
            VectorraGeoJsonSource(
                id = "source",
                features = listOf(
                    VectorraGeoJsonFeature("feature", VectorraAnnotationGeometry.Point(VectorraCoordinate(10.0, 10.0)))
                )
            )
        )
        index.setLayer(VectorraGeoJsonLayer(id = "hidden", sourceId = "source", visible = false, zIndex = 100))
        index.setLayer(VectorraGeoJsonLayer(id = "transparent", sourceId = "source", opacity = 0.0, zIndex = 90))
        index.setLayer(VectorraGeoJsonLayer(id = "out-of-zoom", sourceId = "source", minZoom = 11.0, zIndex = 80))
        index.setLayer(VectorraGeoJsonLayer(id = "low", sourceId = "source", zIndex = 1))
        index.setLayer(VectorraGeoJsonLayer(id = "high", sourceId = "source", zIndex = 2))

        val results = index.query(VectorraScreenPoint(10.0, 10.0), VectorraQueryOptions())
        assertEquals(listOf("high", "low"), results.map { it.layerId })
    }
}
