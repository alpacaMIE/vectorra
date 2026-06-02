package com.vectorra.maps.location

interface VectorraLocationComponent {
    var enabled: Boolean
    var showAccuracyRing: Boolean
    var followMode: VectorraFollowMode
    val location: VectorraLocation?

    fun updateLocation(location: VectorraLocation)
    fun updateBearing(bearingDegrees: Double)
    fun clearLocation()
}
