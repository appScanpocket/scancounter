package be.scancounter

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Bloc photo d'un article (maquette 01-v009) : zone « Choisir la photo », puis apercu avec
 * « Supprimer? » (Oui / Non) et « Rogner » (zoom, cadre, formats, rotation, Valider / Annuler).
 * Ne touche pas aux fichiers : l'appelant lit [bitmap] / [changed] a l'enregistrement.
 */
class PhotoBox(private val act: Activity, private val onPick: (PhotoBox) -> Unit) {
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    var bitmap: Bitmap? = null; private set
    var changed = false; private set
    var onChange: (() -> Unit)? = null

    private var state = 0                      // 0 normal, 1 confirmer la suppression, 2 rognage
    private var crop: CropView? = null
    private val zoomBtns = mutableListOf<Pair<Float, Button>>()
    private val ratioBtns = mutableListOf<Pair<Float, Button>>()

    private fun dp(v: Int) = Ui.dp(act, v)

    fun load(f: File?) {
        bitmap = f?.takeIf { it.exists() }?.let { Photos.decode(it, 1280) }
        changed = false; state = 0; show()
    }

    fun setPicked(b: Bitmap) { bitmap = Photos.scaleDown(b); changed = true; state = 0; show(); onChange?.invoke() }

    fun clear() { bitmap = null; changed = false; state = 0; show() }

    // ------------------------------------------------------------------ affichage

    private fun pill(text: String, color: Int, onClick: () -> Unit) = Button(act).apply {
        this.text = text; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
        background = Ui.round(act, color, 22); stateListAnimator = null
        setPadding(dp(20), dp(10), dp(20), dp(10)); minHeight = dp(44); minimumHeight = dp(44)
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = Button(act).apply {
        this.text = text; isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; stateListAnimator = null
        setPadding(dp(10), 0, dp(10), 0); minHeight = dp(34); minimumHeight = dp(34); minWidth = 0; minimumWidth = 0
        styleChip(this, on)
        setOnClickListener { onClick() }
    }

    private fun styleChip(b: Button, on: Boolean) {
        b.background = Ui.round(act, if (on) Ui.NAVY else 0xFFFFFFFF.toInt(), 16, 0xFF8A93A6.toInt())
        b.setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.NAVY)
    }

    private fun centered(vararg v: View): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        v.forEachIndexed { i, x -> addView(x, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(12) }) }
    }

    private fun show() {
        view.removeAllViews()
        zoomBtns.clear(); ratioBtns.clear(); crop = null
        val b = bitmap
        if (b == null) { showDrop(); return }
        when (state) {
            2 -> showCrop(b)
            else -> showPreview(b)
        }
    }

    private fun showDrop() {
        val zone = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(2), 0xFF7FB5B5.toInt(), dp(6).toFloat(), dp(4).toFloat())
            }
            setPadding(dp(10), dp(18), dp(10), dp(18))
        }
        zone.addView(TextView(act).apply { text = "☁⬆"; textSize = 40f; setTextColor(Ui.NAVY); gravity = Gravity.CENTER })
        zone.addView(pill("Choisir la photo", Ui.NAVY) { onPick(this) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        view.addView(zone, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun showPreview(b: Bitmap) {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.round(act, 0xFFFFFFFF.toInt(), 16); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val iv = ImageView(act).apply {
            setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.round(act, 0xFF222222.toInt(), 12); clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        card.addView(iv, LinearLayout.LayoutParams(-1, dp(170)))
        val buttons = if (state == 1) {
            centered(
                TextView(act).apply { text = "Supprimer la photo ?"; textSize = 14f; setTextColor(Ui.INK) },
                pill("Oui", Ui.RED) { bitmap = null; changed = true; state = 0; show(); onChange?.invoke() },
                pill("Non", Ui.SLATE) { state = 0; show() }
            )
        } else {
            centered(
                pill("🗑 Supprimer?", 0xFFFF5A5F.toInt()) { state = 1; show() },
                pill("✂ Rogner", Ui.ORANGE) { state = 2; show() }
            )
        }
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        view.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun showCrop(b: Bitmap) {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.round(act, 0xFFFFFFFF.toInt(), 16); setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val cv = CropView(act)
        crop = cv
        val holder = FrameLayout(act).apply {
            background = Ui.round(act, 0xFF111111.toInt(), 12); clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        holder.addView(cv, FrameLayout.LayoutParams(-1, -1))
        card.addView(holder, LinearLayout.LayoutParams(-1, dp(260)))
        cv.setBitmap(b)

        val tools = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tools.addView(label("Zoom"))
        for (z in listOf(1f, 2f, 3f, 4f)) {
            val c = chip("x${z.toInt()}", z == 1f) { cv.setZoomLevel(z) }
            zoomBtns.add(z to c); tools.addView(c, gap())
        }
        card.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val fmt = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fmt.addView(label("Format"))
        for ((name, r) in listOf("Libre" to 0f, "1:1" to 1f, "4:3" to 4f / 3f)) {
            val c = chip(name, r == 0f) { cv.setRatio(r); refreshRatio(r) }
            ratioBtns.add(r to c); fmt.addView(c, gap())
        }
        card.addView(fmt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val more = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        more.addView(chip("↻ 90°", false) { cv.rotate() })
        more.addView(chip("Réinitialiser", false) { cv.reset(); refreshRatio(0f) }, gap())
        card.addView(more, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        cv.onZoom = { z -> zoomBtns.forEach { (v, btn) -> styleChip(btn, Math.abs(v - z) < 0.2f) } }

        card.addView(centered(
            pill("Valider", Ui.GREEN) {
                cv.result()?.let { bitmap = Photos.scaleDown(it); changed = true }
                state = 0; show(); onChange?.invoke()
            },
            pill("Annuler", Ui.SLATE) { state = 0; show() }
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        view.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun label(t: String) = TextView(act).apply { text = t; textSize = 12f; setTextColor(Ui.GREY); setPadding(0, 0, dp(6), 0); minWidth = dp(46) }
    private fun gap() = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
    private fun refreshRatio(r: Float) { ratioBtns.forEach { (v, btn) -> styleChip(btn, v == r) } }
}
