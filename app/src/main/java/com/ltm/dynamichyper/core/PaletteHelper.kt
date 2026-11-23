package com.ltm.dynamichyper.core

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

object PaletteHelper {

    fun extractDominantColorHex(bitmap: Bitmap?): String {
        bitmap ?: return "#0A84FF"
        return try {
            val palette = Palette.from(bitmap).generate()
            val dominantSwatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
            if (dominantSwatch != null) {
                String.format("#%06X", 0xFFFFFF and dominantSwatch.rgb)
            } else {
                "#0A84FF"
            }
        } catch (_: Exception) {
            "#0A84FF"
        }
    }
}

