package be.scancounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Icone « scanner » : quatre coins et des barres de code-barres. */
class ScanIconView(ctx: Context, color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * ctx.resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val m = w * 0.12f; val L = w * 0.28f
        // coins
        for ((x, sx) in listOf(m to 1f, w - m to -1f)) for ((y, sy) in listOf(m to 1f, h - m to -1f)) {
            c.drawLine(x, y, x + sx * L, y, p)
            c.drawLine(x, y, x, y + sy * L, p)
        }
        // barres
        val thin = p.strokeWidth; p.strokeWidth = thin * 0.8f
        val top = h * 0.34f; val bottom = h * 0.66f
        for (k in 0..4) { val x = w * (0.30f + 0.10f * k); c.drawLine(x, top, x, bottom, p) }
        p.strokeWidth = thin
    }
}
