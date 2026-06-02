package com.vectorra.maps.location

data class VectorraLocation(
    val longitude: Double,
    val latitude: Double,
    val accuracyMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val provider: String? = null
)
