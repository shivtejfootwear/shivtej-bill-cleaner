package com.shivtej.billcleaner

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** User ke chune hue saaf karne ke options. */
data class CleanOptions(
    val removeShadow: Boolean = true,
    val enhance: Boolean = true,
    val sharpen: Boolean = true,
    val blackWhite: Boolean = false,
)

/**
 * Bill photo ko scanned kagaz jaisa banata hai. Text kabhi dobara type nahi hota,
 * sirf photo ki roshni theek hoti hai, isliye har number original hi rehta hai.
 *
 * Shadow kaise hat-ta hai:
 *  1. Har rang ke liye kagaz ki "roshni" ka naksha banta hai: chhote dabbon ka sabse
 *     ujla hissa lete hain (kaala text gayab), phir use smooth karte hain.
 *  2. Har pixel ko us roshni se divide karte hain. Shadow wali jagah ki roshni kam thi,
 *     to woh bhi utni hi ujli ho jaati hai jitni baaki kagaz. Text kaala hi rehta hai.
 *  3. Levels: halka grey safed ho jata hai, text gehra.
 */
object BillCleaner {
    private const val BLOCK = 2          // roshni ka naksha 2x chhota
    private const val CLOSE_RADIUS = 6   // text hatane ke liye (≈26 px window)
    private const val BLUR_RADIUS = 1
    private const val WHITE_AFTER_SHADOW = 210f
    private const val GAMMA = 1.6f
    private const val SHARP_K = 0.5f

    fun clean(src: Bitmap, o: CleanOptions): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        if (o.removeShadow) {
            for (ch in 0..2) flattenChannel(px, w, h, ch)
        }
        if (o.enhance) {
            val white = if (o.removeShadow) WHITE_AFTER_SHADOW else autoWhite(px)
            applyLevels(px, white)
        }
        if (o.blackWhite) toGray(px)
        val out = if (o.sharpen) sharpen(px, w, h) else px

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    // ---------- Shadow hatana ----------

    private fun flattenChannel(px: IntArray, w: Int, h: Int, ch: Int) {
        val shift = 16 - 8 * ch
        val sw = (w + BLOCK - 1) / BLOCK
        val sh = (h + BLOCK - 1) / BLOCK

        // 1) Har 4x4 dabbe ka sabse ujla pixel
        var small = FloatArray(sw * sh)
        for (y in 0 until h) {
            val row = y * w
            val sRow = (y / BLOCK) * sw
            for (x in 0 until w) {
                val v = ((px[row + x] shr shift) and 0xFF).toFloat()
                val i = sRow + x / BLOCK
                if (v > small[i]) small[i] = v
            }
        }
        // 2) "Closing": pehle aas-paas ka sabse ujla (text gayab), phir sabse dhundla
        //    (shadow ka kinara wapas apni sahi jagah). Isse shadow ke kinare par
        //    grey line / daag nahi bachta. Phir halka smooth.
        small = rankFilter(small, sw, sh, CLOSE_RADIUS, takeMax = true)
        small = rankFilter(small, sw, sh, CLOSE_RADIUS, takeMax = false)
        small = boxBlur(small, sw, sh, BLUR_RADIUS)
        small = boxBlur(small, sw, sh, BLUR_RADIUS)

        // 3) Pura size par wapas (bilinear) aur divide
        val inv = 1f / BLOCK
        val maxSx = (sw - 1).toFloat()
        val maxSy = (sh - 1).toFloat()
        val x0s = IntArray(w)
        val x1s = IntArray(w)
        val fxs = FloatArray(w)
        for (x in 0 until w) {
            val fx = ((x + 0.5f) * inv - 0.5f).coerceIn(0f, maxSx)
            val x0 = fx.toInt()
            x0s[x] = x0
            x1s[x] = min(x0 + 1, sw - 1)
            fxs[x] = fx - x0
        }
        val clearMask = (0xFF shl shift).inv()
        for (y in 0 until h) {
            val fyAll = ((y + 0.5f) * inv - 0.5f).coerceIn(0f, maxSy)
            val y0 = fyAll.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val fy = fyAll - y0
            val r0 = y0 * sw
            val r1 = y1 * sw
            val row = y * w
            for (x in 0 until w) {
                val fx = fxs[x]
                val a = x0s[x]
                val b = x1s[x]
                val top = small[r0 + a] * (1f - fx) + small[r0 + b] * fx
                val bot = small[r1 + a] * (1f - fx) + small[r1 + b] * fx
                val bg = max(1f, top * (1f - fy) + bot * fy)
                val p = px[row + x]
                val v = (p shr shift) and 0xFF
                val nv = min(255, (v * 255f / bg + 0.5f).toInt())
                px[row + x] = (p and clearMask) or (nv shl shift)
            }
        }
    }

    /** Har pixel ke (2r+1)x(2r+1) aas-paas ka sabse bada (takeMax) ya sabse chhota maan. */
    private fun rankFilter(a: FloatArray, w: Int, h: Int, r: Int, takeMax: Boolean): FloatArray {
        val tmp = FloatArray(a.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = a[row + x]
                for (xx in max(0, x - r)..min(w - 1, x + r)) {
                    val v = a[row + xx]
                    if (if (takeMax) v > m else v < m) m = v
                }
                tmp[row + x] = m
            }
        }
        val out = FloatArray(a.size)
        for (y in 0 until h) {
            val yA = max(0, y - r)
            val yB = min(h - 1, y + r)
            for (x in 0 until w) {
                var m = tmp[y * w + x]
                for (yy in yA..yB) {
                    val v = tmp[yy * w + x]
                    if (if (takeMax) v > m else v < m) m = v
                }
                out[y * w + x] = m
            }
        }
        return out
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val k = (2 * r + 1).toFloat()
        val tmp = FloatArray(a.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0f
                for (d in -r..r) s += a[row + (x + d).coerceIn(0, w - 1)]
                tmp[row + x] = s / k
            }
        }
        val out = FloatArray(a.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (d in -r..r) s += tmp[(y + d).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = s / k
            }
        }
        return out
    }

    // ---------- Brightness / contrast ----------

    private fun lum(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    private fun histogram(px: IntArray): IntArray {
        val hist = IntArray(256)
        for (p in px) hist[lum(p)]++
        return hist
    }

    private fun percentile(hist: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toLong()
        var acc = 0L
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }

    /** Shadow hataye bina: kagaz ka rang (90% wala ujla hissa) safed maana jata hai. */
    private fun autoWhite(px: IntArray): Float =
        percentile(histogram(px), px.size, 0.90f).toFloat().coerceIn(150f, 245f)

    private fun applyLevels(px: IntArray, white: Float) {
        val black = min(percentile(histogram(px), px.size, 0.01f).toFloat(), 110f)
        if (white - black < 20f) return
        val lut = IntArray(256)
        for (v in 0..255) {
            val t = ((v - black) / (white - black)).coerceIn(0f, 1f)
            lut[v] = (t.pow(GAMMA) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        for (i in px.indices) {
            val p = px[i]
            px[i] = (0xFF shl 24) or
                (lut[(p shr 16) and 0xFF] shl 16) or
                (lut[(p shr 8) and 0xFF] shl 8) or
                lut[p and 0xFF]
        }
    }

    private fun toGray(px: IntArray) {
        for (i in px.indices) {
            val l = lum(px[i])
            px[i] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
    }

    // ---------- PDF jaisi sharp lines ----------

    private fun sharpen(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(px.size)
        val center = 1f + 4f * SHARP_K
        for (y in 0 until h) {
            val up = max(0, y - 1) * w
            val dn = min(h - 1, y + 1) * w
            val row = y * w
            for (x in 0 until w) {
                val lf = max(0, x - 1)
                val rt = min(w - 1, x + 1)
                val c = px[row + x]
                val n1 = px[up + x]
                val n2 = px[dn + x]
                val n3 = px[row + lf]
                val n4 = px[row + rt]
                var res = 0xFF shl 24
                var shift = 16
                while (shift >= 0) {
                    val sum = ((n1 shr shift) and 0xFF) + ((n2 shr shift) and 0xFF) +
                        ((n3 shr shift) and 0xFF) + ((n4 shr shift) and 0xFF)
                    val v = (((c shr shift) and 0xFF) * center - SHARP_K * sum + 0.5f)
                        .toInt().coerceIn(0, 255)
                    res = res or (v shl shift)
                    shift -= 8
                }
                out[row + x] = res
            }
        }
        return out
    }
}
