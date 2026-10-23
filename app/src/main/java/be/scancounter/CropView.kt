package be.scancounter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * Editeur de recadrage : photo zoomable (x1 a x4, pincement ou boutons) et deplacable,
 * cadre a 4 poignees (deplacer / redimensionner), formats Libre / 1:1 / 4:3, rotation 90 degres.
 */
class CropView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    var zoom = 1f; private set
    private var tx = 0f
    private var ty = 0f
    private val frame = RectF()
    var ratio = 0f; private set          // 0 = libre, sinon largeur / hauteur
    var onZoom: ((Float) -> Unit)? = null

    private val d = ctx.resources.displayMetrics.density
    private val minSide = 60f * d
    private val hit = 30f * d
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    private var mode = 0                  // 0 rien, 1 deplacer la photo, 2 deplacer le cadre, 3 redimensionner
    private var corner = 0                // 0 nw, 1 ne, 2 sw, 3 se
    private var lx = 0f
    private var ly = 0f
    private var needInit = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean { zoomAt(zoom * det.scaleFactor, det.focusX, det.focusY); return true }
    })

    fun setBitmap(b: Bitmap) { bmp = b; zoom = 1f; tx = 0f; ty = 0f; ratio = 0f; needInit = true; initFrame(); onZoom?.invoke(zoom); invalidate() }

    fun reset() { bmp?.let { setBitmap(it) } }

    fun rotate() {
        val b = bmp ?: return
        bmp = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)
        zoom = 1f; tx = 0f; ty = 0f; needInit = true; initFrame(); onZoom?.invoke(zoom); invalidate()
    }

    fun setZoomLevel(z: Float) { zoomAt(z, width / 2f, height / 2f) }

    fun setRatio(r: Float) {
        ratio = r
        if (r > 0f && width > 0) {
            var w = frame.width(); var h = w / r
            if (h > height) { h = height.toFloat(); w = h * r }
            if (w > width) { w = width.toFloat(); h = w / r }
            val cx = frame.centerX().coerceIn(w / 2, width - w / 2); val cy = frame.centerY().coerceIn(h / 2, height - h / 2)
            frame.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }
        invalidate()
    }

    /** Image rognee selon le cadre. */
    fun result(): Bitmap? {
        val b = bmp ?: return null
        val s = scale(); val l = imgLeft(); val t = imgTop()
        val x0 = ((frame.left - l) / s).toInt().coerceIn(0, b.width - 1)
        val y0 = ((frame.top - t) / s).toInt().coerceIn(0, b.height - 1)
        val x1 = ((frame.right - l) / s).toInt().coerceIn(x0 + 1, b.width)
        val y1 = ((frame.bottom - t) / s).toInt().coerceIn(y0 + 1, b.height)
        return Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0)
    }

    // ---- geometrie
    private fun base(): Float { val b = bmp ?: return 1f; return minOf(width.toFloat() / b.width, height.toFloat() / b.height) }
    private fun scale() = base() * zoom
    private fun imgLeft() = width / 2f + tx - (bmp?.width ?: 0) * scale() / 2f
    private fun imgTop() = height / 2f + ty - (bmp?.height ?: 0) * scale() / 2f

    private fun clampPan() {
        val b = bmp ?: return
        val hw = b.width * scale() / 2f; val hh = b.height * scale() / 2f
        tx = if (hw * 2 <= width) 0f else tx.coerceIn(-(hw - width / 2f), hw - width / 2f)
        ty = if (hh * 2 <= height) 0f else ty.coerceIn(-(hh - height / 2f), hh - height / 2f)
    }

    private fun zoomAt(z: Float, fx: Float, fy: Float) {
        val nz = z.coerceIn(1f, 4f)
        val k = nz / zoom
        tx = fx + (width / 2f + tx - fx) * k - width / 2f
        ty = fy + (height / 2f + ty - fy) * k - height / 2f
        zoom = nz
        clampPan(); onZoom?.invoke(zoom); invalidate()
    }

    private fun initFrame() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val s = base()
        val iw = b.width * s; val ih = b.height * s
        val l = (width - iw) / 2f; val t = (height - ih) / 2f
        frame.set(l + iw * 0.06f, t + ih * 0.06f, l + iw * 0.94f, t + ih * 0.94f)
        needInit = false
        if (ratio > 0f) setRatio(ratio)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); if (needInit) initFrame() }

    // ---- dessin
    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val s = scale()
        c.drawBitmap(b, Matrix().apply { postScale(s, s); postTranslate(imgLeft(), imgTop()) }, paint)
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, frame.top, dim)
        c.drawRect(0f, frame.bottom, w, h, dim)
        c.drawRect(0f, frame.top, frame.left, frame.bottom, dim)
        c.drawRect(frame.right, frame.top, w, frame.bottom, dim)
        val fw = frame.width() / 3f; val fh = frame.height() / 3f
        for (k in 1..2) {
            c.drawLine(frame.left + fw * k, frame.top, frame.left + fw * k, frame.bottom, grid)
            c.drawLine(frame.left, frame.top + fh * k, frame.right, frame.top + fh * k, grid)
        }
        c.drawRect(frame, line)
        val r = 8f * d
        for (p in corners()) c.drawCircle(p[0], p[1], r, dot)
    }

    private fun corners() = listOf(
        floatArrayOf(frame.left, frame.top), floatArrayOf(frame.right, frame.top),
        floatArrayOf(frame.left, frame.bottom), floatArrayOf(frame.right, frame.bottom)
    )

    // ---- gestes
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (scaler.isInProgress) { mode = 0; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lx = e.x; ly = e.y
                corner = corners().indexOfFirst { abs(it[0] - e.x) < hit && abs(it[1] - e.y) < hit }
                mode = when { corner >= 0 -> 3; frame.contains(e.x, e.y) -> 2; else -> 1 }
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && mode != 0) {
                val dx = e.x - lx; val dy = e.y - ly
                lx = e.x; ly = e.y
                when (mode) {
                    1 -> { tx += dx; ty += dy; clampPan() }
                    2 -> {
                        val nx = (frame.left + dx).coerceIn(0f, width - frame.width())
                        val ny = (frame.top + dy).coerceIn(0f, height - frame.height())
                        frame.offsetTo(nx, ny)
                    }
                    3 -> resize(dx, dy)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    private fun resize(dx: Float, dy: Float) {
        val r = RectF(frame)
        val west = corner == 0 || corner == 2
        val north = corner == 0 || corner == 1
        if (west) r.left += dx else r.right += dx
        if (north) r.top += dy else r.bottom += dy
        if (ratio > 0f) {
            val h = r.width() / ratio
            if (north) r.top = r.bottom - h else r.bottom = r.top + h
        }
        if (r.width() < minSide || r.height() < minSide) return
        if (r.left < 0f || r.top < 0f || r.right > width || r.bottom > height) return
        frame.set(r)
    }
}
