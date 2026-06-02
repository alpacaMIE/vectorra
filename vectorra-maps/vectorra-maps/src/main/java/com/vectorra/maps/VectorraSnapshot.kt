package com.vectorra.maps

import android.graphics.Bitmap

fun interface VectorraSnapshotCallback {
    fun onSnapshotReady(bitmap: Bitmap?, error: VectorraSnapshotError?)
}

data class VectorraSnapshotError(
    val message: String,
    val cause: Throwable? = null
)
