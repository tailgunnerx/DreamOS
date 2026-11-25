package com.dreamos.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.dreamos.app.vault.Edits
import com.dreamos.app.vault.LassoMode
import com.dreamos.app.vault.MaskOp
import com.dreamos.app.vault.NRect
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** All pixel work happens in memory; nothing here ever touches the disk in plaintext. */
object ImageOps {
    const val ORIGINAL_MAX = 4096
    const val RENDER_SOURCE_MAX = 2048
    const val DISPLAY_MAX = 1600
    const val THUMB_MAX = 640
    const val EDITOR_MAX = 1400

    class Normalized(val bytes: ByteArray, val bitmap: Bitmap, val hasAlpha: Boolean)

    fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val argb = if (decoded.config != Bitmap.Config.ARGB_8888) {
            decoded.copy(Bitmap.Config.ARGB_8888, false) ?: decoded
        } else decoded
        return scaleDown(argb, maxDim)
    }

    /** Scales down while strictly preserving ARGB_8888 alpha channel without black fringes. */
    fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxDim) return src
        val s = maxDim.toFloat() / m
        val nw = (src.width * s).roundToInt().coerceAtLeast(1)
        val nh = (src.height * s).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(src, null, Rect(0, 0, nw, nh), p)
        return out
    }

    /** Detects whether [bmp] actually contains transparent / semi-transparent pixels. */
    fun hasTransparentPixels(bmp: Bitmap): Boolean {
        if (!bmp.hasAlpha() && bmp.config != Bitmap.Config.ARGB_8888) return false
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false
        // Fast check corners & perimeter first (catches 99.9% of transparent-background PNGs in <1µs)
        val checkPts = intArrayOf(
            0, 0, w - 1, 0, 0, h - 1, w - 1, h - 1,
            w / 2, 0, w / 2, h - 1, 0, h / 2, w - 1, h / 2,
        )
        var i = 0
        while (i + 1 < checkPts.size) {
            if (Color.alpha(bmp.getPixel(checkPts[i], checkPts[i + 1])) < 250) return true
            i += 2
        }
        // Sampled interior scan in case transparency is inside the frame
        val stepX = max(1, w / 48)
        val stepY = max(1, h / 48)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (Color.alpha(bmp.getPixel(x, y)) < 250) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    /**
     * Finds the tight non-transparent bounding rectangle of [src], ignoring faint segmentation
     * ghost haze (`alpha < 38`) and isolated 1-2px brush crumbs so rough cutouts trim 100% flush.
     */
    fun findOpaqueBounds(src: Bitmap): Rect? {
        val w = src.width
        val h = src.height
        if (w <= 2 || h <= 2) return null
        val rowBuf = IntArray(w)
        val rowCounts = IntArray(h)
        val colCounts = IntArray(w)

        var rawTop = h
        var rawBottom = -1
        var rawLeft = w
        var rawRight = -1

        for (y in 0 until h) {
            src.getPixels(rowBuf, 0, w, 0, y, w, 1)
            var rCount = 0
            for (x in 0 until w) {
                val a = (rowBuf[x] ushr 24) and 0xFF
                if (a >= 38) {
                    rCount++
                    colCounts[x]++
                    if (y < rawTop) rawTop = y
                    if (y > rawBottom) rawBottom = y
                    if (x < rawLeft) rawLeft = x
                    if (x > rawRight) rawRight = x
                }
            }
            rowCounts[y] = rCount
        }
        if (rawBottom < rawTop || rawRight < rawLeft) return null

        // Require at least a tiny coherent cluster per boundary line so stray 1-2px lasso/brush crumbs are trimmed away
        val minRowCluster = max(2, min(8, (rawRight - rawLeft + 1) / 90))
        val minColCluster = max(2, min(8, (rawBottom - rawTop + 1) / 90))

        var top = rawTop
        while (top < rawBottom && rowCounts[top] < minRowCluster) top++
        var bottom = rawBottom
        while (bottom > top && rowCounts[bottom] < minRowCluster) bottom--

        var left = rawLeft
        while (left < rawRight && colCounts[left] < minColCluster) left++
        var right = rawRight
        while (right > left && colCounts[right] < minColCluster) right--

        val pad = 1
        val cl = (left - pad).coerceAtLeast(0)
        val ct = (top - pad).coerceAtLeast(0)
        val cr = (right + pad).coerceAtMost(w - 1)
        val cb = (bottom + pad).coerceAtMost(h - 1)
        val cw = (cr - cl + 1).coerceAtLeast(1)
        val ch = (cb - ct + 1).coerceAtLeast(1)
        if (cl == 0 && ct == 0 && cw == w && ch == h) return null
        return Rect(cl, ct, cl + cw, ct + ch)
    }

    /**
     * Crops away purely-transparent outer dead space around an isolated cutout so the rendered
     * PNG hugs the subject tightly (preventing giant invisible bounding boxes on the wheel).
     */
    fun trimTransparentBounds(src: Bitmap): Bitmap {
        val b = findOpaqueBounds(src) ?: return src
        return Bitmap.createBitmap(src, b.left, b.top, b.width(), b.height()).apply { setHasAlpha(true) }
    }

    /**
     * Computes an 8x8 bitmask (`Long`, bit = gy * 8 + gx) indicating which cells of [bmp]
     * contain pixels with `alpha >= alphaThreshold`.
     */
    fun computeGridBitmask8x8(
        bmp: Bitmap,
        alphaThreshold: Int = 40,
        minCoverageRatio: Float = 0.10f,
    ): Long {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0L
        var mask = 0L
        for (gy in 0 until 8) {
            val y0 = (gy * h) / 8
            val y1 = (((gy + 1) * h) / 8).coerceAtLeast(y0 + 1).coerceAtMost(h)
            val spanY = (y1 - y0).coerceAtLeast(1)
            val samplesY = min(8, spanY)
            for (gx in 0 until 8) {
                val x0 = (gx * w) / 8
                val x1 = (((gx + 1) * w) / 8).coerceAtLeast(x0 + 1).coerceAtMost(w)
                val spanX = (x1 - x0).coerceAtLeast(1)
                val samplesX = min(8, spanX)
                var hits = 0
                val total = samplesX * samplesY
                for (sy in 0 until samplesY) {
                    val py = (y0 + ((sy + 0.5f) * spanY / samplesY).toInt()).coerceIn(0, h - 1)
                    for (sx in 0 until samplesX) {
                        val px = (x0 + ((sx + 0.5f) * spanX / samplesX).toInt()).coerceIn(0, w - 1)
                        if (Color.alpha(bmp.getPixel(px, py)) >= alphaThreshold) hits++
                    }
                }
                val needed = (total * minCoverageRatio).roundToInt().coerceAtLeast(1)
                if (hits >= needed) {
                    mask = mask or (1L shl (gy * 8 + gx))
                }
            }
        }
        return mask
    }

    /**
     * Builds an ARGB_8888 mask for "Sweet Spot" protection (starts transparent; strokes/loops with
     * erase=false or KEEP/RESTORE add protected white pixels, erase=true or ERASE clears them).
     */
    fun buildSweetMask(w: Int, h: Int, sweetOps: List<MaskOp>): Bitmap? {
        if (sweetOps.isEmpty() || w <= 0 || h <= 0) return null
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.setHasAlpha(true)
        val c = Canvas(mask)
        val addFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        val clearFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val maxSide = max(w, h).toFloat()
        for (op in sweetOps) {
            when (op) {
                is MaskOp.Lasso -> {
                    if (op.points.size < 6) continue
                    val path = lassoPath(op.points, w, h)
                    if (op.mode == LassoMode.ERASE) c.drawPath(path, clearFill) else c.drawPath(path, addFill)
                }
                is MaskOp.Brush -> {
                    if (op.points.size < 2) continue
                    val path = brushPath(op.points, w, h)
                    val strokePx = (op.radiusNorm * maxSide * 2f).coerceAtLeast(4f)
                    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = strokePx
                        if (op.erase) {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                        } else {
                            color = Color.WHITE
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                        }
                    }
                    c.drawPath(path, sp)
                }
                is MaskOp.AiCutout -> Unit
            }
        }
        return mask
    }

    /**
     * Applies EXIF rotation, caps the size and re-encodes. Re-encoding also strips EXIF
     * metadata (GPS, camera serial, timestamps) from the stored master copy.
     */
    fun normalizeImport(raw: ByteArray): Normalized? {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val decoded = decode(raw, ORIGINAL_MAX) ?: return null
        val bmp = applyOrientation(decoded, orientation)
        val alpha = hasTransparentPixels(bmp)
        if (alpha) bmp.setHasAlpha(true)
        return Normalized(encode(bmp, transparent = alpha, quality = 95), bmp, alpha)
    }

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).apply {
            if (src.hasAlpha()) setHasAlpha(true)
        }
    }

    fun encode(bmp: Bitmap, transparent: Boolean, quality: Int = 90): ByteArray {
        val bos = ByteArrayOutputStream()
        if (transparent) {
            bmp.setHasAlpha(true)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        } else {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        }
        return bos.toByteArray()
    }

    fun lassoPath(points: FloatArray, w: Int, h: Int): Path {
        val p = Path().apply { fillType = Path.FillType.WINDING }
        if (points.size < 6) return p
        p.moveTo(points[0] * w, points[1] * h)
        var i = 2
        while (i + 1 < points.size) {
            p.lineTo(points[i] * w, points[i + 1] * h); i += 2
        }
        p.close()
        return p
    }

    fun brushPath(points: FloatArray, w: Int, h: Int): Path {
        val p = Path()
        if (points.size < 2) return p
        val x0 = points[0] * w
        val y0 = points[1] * h
        p.moveTo(x0, y0)
        if (points.size < 4) {
            // Single-dot tap
            p.lineTo(x0 + 0.5f, y0 + 0.5f)
            return p
        }
        var i = 2
        while (i + 1 < points.size) {
            p.lineTo(points[i] * w, points[i + 1] * h)
            i += 2
        }
        return p
    }

    /**
     * Builds an ARGB_8888 alpha mask at [w] x [h] using reliable PorterDuff modes (no ALPHA_8 or
     * BlurMaskFilter Skia bugs). White (alpha=255) = keep pixel; Transparent (alpha=0) = cut out.
     */
    fun buildMask(w: Int, h: Int, ops: List<MaskOp>, loadMask: (String) -> Bitmap?): Bitmap? {
        if (ops.isEmpty()) return null
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.setHasAlpha(true)
        val c = Canvas(mask)
        c.drawColor(Color.WHITE) // alpha = 255 everywhere initially

        val clearFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val restoreFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        val keepFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val dstIn = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        val maxSide = max(w, h).toFloat()

        for (op in ops) {
            when (op) {
                is MaskOp.Lasso -> {
                    if (op.points.size < 6) continue
                    val path = lassoPath(op.points, w, h)
                    when (op.mode) {
                        LassoMode.ERASE -> c.drawPath(path, clearFill)
                        LassoMode.RESTORE -> c.drawPath(path, restoreFill)
                        LassoMode.KEEP -> {
                            val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            tmp.setHasAlpha(true)
                            Canvas(tmp).drawPath(path, keepFill)
                            c.drawBitmap(tmp, 0f, 0f, dstIn)
                            tmp.recycle()
                        }
                    }
                }
                is MaskOp.Brush -> {
                    if (op.points.size < 2) continue
                    val path = brushPath(op.points, w, h)
                    val strokePx = (op.radiusNorm * maxSide * 2f).coerceAtLeast(4f)
                    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = strokePx
                        if (op.erase) {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                        } else {
                            color = Color.WHITE
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                        }
                    }
                    c.drawPath(path, strokePaint)
                }
                is MaskOp.AiCutout -> {
                    val m = loadMask(op.maskId) ?: continue
                    c.drawBitmap(m, null, Rect(0, 0, w, h), dstIn)
                }
            }
        }
        return mask
    }

    /** Original + masks (no crop). Preserves any native alpha already in [src]! */
    fun applyMasks(src: Bitmap, ops: List<MaskOp>, loadMask: (String) -> Bitmap?): Bitmap {
        val mask = buildMask(src.width, src.height, ops, loadMask) ?: return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)
        c.drawBitmap(mask, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        mask.recycle()
        return out
    }

    fun crop(src: Bitmap, r: NRect?): Bitmap {
        if (r == null) return src
        val x = (r.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val y = (r.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = (r.width * src.width).roundToInt().coerceIn(1, src.width - x)
        val h = (r.height * src.height).roundToInt().coerceIn(1, src.height - y)
        if (x == 0 && y == 0 && w == src.width && h == src.height) return src
        return Bitmap.createBitmap(src, x, y, w, h).apply {
            if (src.hasAlpha()) setHasAlpha(true)
        }
    }

    fun render(src: Bitmap, edits: Edits, loadMask: (String) -> Bitmap?): Bitmap =
        crop(applyMasks(src, edits.ops, loadMask), edits.crop)
}
