package com.shivtej.billcleaner

import android.graphics.Bitmap

/** Ek page ki vector shapes (pixel naap mein), PDF banane ke liye. */
class VectorPage(val width: Int, val height: Int, val shapes: List<FloatArray>) {
    companion object {
        private const val LEVEL = 128f       // kaala/safed ki seema
        private const val TOLERANCE = 0.35f  // shape simple karne ki had (pixel)
        private const val MIN_AREA = 3f      // isse chhote dhool ke kan hata do

        /** Saaf Black & White bitmap se outlines banata hai. */
        fun from(bmp: Bitmap): VectorPage {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) px[i] = px[i] and 0xFF  // grey maan (R=G=B)
            val shapes = Vectorizer.trace(px, w, h, LEVEL, TOLERANCE, MIN_AREA)
            return VectorPage(w, h, shapes)
        }
    }
}
