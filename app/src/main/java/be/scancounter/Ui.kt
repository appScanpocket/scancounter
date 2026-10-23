package be.scancounter

import android.content.Context
import android.graphics.drawable.GradientDrawable

/** Couleurs et petits utilitaires d'interface partages (maquette 01-v009 : bleu nuit + jaune). */
object Ui {
    val NAVY = 0xFF14213D.toInt()
    val YELLOW = 0xFFFFC300.toInt()
    val BG = 0xFFF2F4F8.toInt()
    val INK = 0xFF14213D.toInt()
    val GREY = 0xFF6B7280.toInt()
    val RED = 0xFFC62828.toInt()
    val GREEN = 0xFF43A047.toInt()
    val SLATE = 0xFF607D8B.toInt()
    val ORANGE = 0xFFF5A63A.toInt()
    val DIALOG_BG = 0xFFE9ECF2.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun round(ctx: Context, color: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (stroke != 0) setStroke(maxOf(1, dp(ctx, strokeDp)), stroke)
    }
}
