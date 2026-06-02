package com.vectorra.maps.annotation

import android.graphics.Color

data class VectorraLabelAnnotation(
    val id: String,
    val longitude: Double,
    val latitude: Double,
    val text: String,
    val textSizeSp: Float = 12f,
    val textColor: Int = Color.WHITE,
    val textHaloColor: Int = Color.BLACK,
    val textHaloWidthPx: Float = 2f,
    val textOffsetXPx: Float = 0f,
    val textOffsetYPx: Float = 22f,
    val iconColor: Int? = Color.rgb(253, 173, 36),
    val iconRadiusPx: Float = 7f,
    val allowOverlap: Boolean = false,
    val layerId: String = "label",
    val properties: Map<String, String> = emptyMap(),
    val hitRadiusPixels: Double = 24.0,
    val zIndex: Int = 0
)
