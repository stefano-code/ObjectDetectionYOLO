package com.android.test.objectdetection.yolo.ml

import android.graphics.RectF

    data class Recognition(
    // A unique identifier for what has been recognized.
    // Specific to the class, not the instance of the object.
    val id: String,

    // Display name for the recognition.
    val title: String,

    // A sortable score for how good the recognition is relative to others.
    // Higher should be better.
    val confidence: Float,

    // Optional location within the source image for the location
    // of the recognized object.

    private var location: RectF,

    var detectedClass: Int = 0
) {

    fun getLocation(): RectF = RectF(location)
    fun setLocation(rect: RectF) {
        location = rect
    }

    override fun toString(): String {
        return buildString {
            id?.let { append("[$it] ") }
            title?.let { append("$it ") }
            confidence?.let {
                append(String.format("(%.1f%%) ", it * 100f))
            }
            location?.let { append("$it ") }
        }.trim()
    }
}