package be.scancounter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Date

/** Scancounter natif : port de la PWA v0.18 (onglets Saisie / Liste / Outils), memes regles metier (Logic.kt). */
class MainActivity : Activity() {
    private companion object {
        const val RC_PHOTO_CODE = 101
        const val RC_PHOTO_LABEL = 102
        const val RC_EXPORT_XLSX = 103
        const val RC_IMPORT = 104
        const val PAGE = 10
        const val LAST_N = 5
        const val VERSION = "v2.0"
        val BLUE = 0xFF1B6EF3.toInt()
        val BG = 0xFFF2F4F7.toInt()
        val INK = 0xFF1B1F24.toInt()
        val GREY = 0xFF555555.toInt()
    }

    private lateinit var store: Store
    private var lines = mutableListOf<Line>()
    private var lastExport = ""
    private var editIdx = -1
    private var pendingDup: Triple<String, String, Details>? = null
    private val handler = Handler(Looper.getMainLooper())

    // etat de la liste
    private var shown = PAGE
    private val sorts = mutableListOf(Sort("date", -1))
    private var query = ""
    private var searchIdx: List<String>? = null
    private var order: List<Int>? = null
    private var listDirty = true
    private var searchRunnable: Runnable? = null

    // photo / import / export en attente
    private var photoFile: File? = null
    private var pendingBytes: ByteArray? = null
    private var pendingImport: ImportData? = null
    private var pendingName = ""
    private var ocrBusy = false

    // vues
    private lateinit var msgBox: LinearLayout
    private lateinit var msgText: TextView
    private lateinit var msgLink: TextView
    private lateinit var content: FrameLayout
    private val tabNames = listOf("saisie", "liste", "outils")
    private val tabViews = mutableMapOf<String, View>()
    private val tabButtons = mutableMapOf<String, TextView>()
    private var currentTab = "saisie"

    private lateinit var etCode: EditText
    private lateinit var etCodeRo: EditText
    private val etDet = mutableListOf<EditText>()
    private lateinit var etQty: EditText
    private lateinit var btnAdd: Button
    private lateinit var btnCancel: Button
    private lateinit var addLbl: TextView
    private lateinit var detailsBox: LinearLayout
    private lateinit var detailsToggle: TextView
    private lateinit var dupBox: LinearLayout
    private lateinit var dupText: TextView
    private lateinit var lastListBox: LinearLayout
    private lateinit var lastEmpty: TextView

    private lateinit var etSearch: EditText
    private lateinit var searchClear: TextView
    private lateinit var chipsBox: LinearLayout
    private lateinit var countTv: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var emptyTv: TextView
    private lateinit var btnMore: Button
    private lateinit var totalTv: TextView
    private lateinit var listScroll: ScrollView

    private lateinit var lastExportTv: TextView
    private lateinit var impBox: LinearLayout
    private lateinit var impText: TextView
    private lateinit var cbUpdate: CheckBox
    private lateinit var rbComplete: RadioButton
    private lateinit var rbReplace: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        lines = store.loadLines()
        lastExport = store.lastExport
        buildUi()
        switchTab("saisie")
        render()
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    // ------------------------------------------------------------------ construction de l'ecran

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        title.addView(tv("Scancounter", 20f, INK, true))
        title.addView(tv("  $VERSION", 14f, INK, true))
        root.addView(title)

        msgBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        msgText = tv("", 15f, INK, false).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        msgLink = tv("", 15f, INK, true).apply { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; setPadding(dp(8), dp(4), dp(4), dp(4)); visibility = View.GONE }
        msgBox.addView(msgText); msgBox.addView(msgLink)
        root.addView(msgBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), 0, dp(10), dp(8)) })

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        tabViews["saisie"] = buildSaisie()
        tabViews["liste"] = buildListe()
        tabViews["outils"] = buildOutils()
        tabViews.values.forEach { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFFFFFFFF.toInt()); elevation = dp(8).toFloat() }
        val titles = mapOf("saisie" to "✎\nSaisie", "liste" to "☰\nListe", "outils" to "⚙\nOutils")
        for (n in tabNames) {
            val b = TextView(this).apply {
                text = titles[n]; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, dp(8)); minHeight = dp(56)
                setOnClickListener { switchTab(n) }
            }
            tabButtons[n] = b
            bar.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        // Android 15+ : l'app passe sous les barres systeme, on decale le contenu (et le clavier)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
    }

    private fun buildSaisie(): View {
        val scroll = ScrollView(this)
        val col = vbox().apply { setPadding(dp(10), 0, dp(10), dp(16)) }
        scroll.addView(col)

        val card = card()
        card.addView(row(
            btn("📷 Photo", "pri") { openCamera(RC_PHOTO_CODE) },
            btn("⌖ Scanner", "pri") { onLiveScan() },
            btn("🏷 Étiquette", "pri") { onEtiquette() }
        ))
        etCode = edit()
        card.addView(labeled("Code article", etCode), topMargin(12))

        detailsToggle = tv("▸  Détails de l'étiquette (facultatif)", 15f, BLUE, true).apply { setPadding(0, dp(12), 0, dp(4)); setOnClickListener { setDetailsOpen(detailsBox.visibility != View.VISIBLE) } }
        card.addView(detailsToggle)
        detailsBox = vbox().apply { visibility = View.GONE }
        etCodeRo = edit().apply { isEnabled = false; isFocusable = false }
        detailsBox.addView(labeled("Code article (lecture seule)", etCodeRo))
        for (i in 0..4) {
            val e = edit()
            etDet.add(e)
            detailsBox.addView(labeled(Logic.FIELD_TITLES[i], e), topMargin(8))
        }
        card.addView(detailsBox)

        etQty = edit().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 26f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        btnAdd = btn("+", "pri") { onAdd() }.apply { textSize = 26f }
        btnCancel = btn("✕", "sec") { cancelEdit(); msg("", "") }.apply { visibility = View.GONE }
        addLbl = tv("Ajouter", 13f, GREY, false)
        val qrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qrow.addView(labeled("Quantité comptée", etQty), LinearLayout.LayoutParams(0, -2, 1.3f))
        qrow.addView(btnAdd, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(8) })
        qrow.addView(btnCancel, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(6) })
        qrow.addView(addLbl.apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(qrow, topMargin(14))

        dupBox = vbox().apply { background = round(0xFFFFF3CD.toInt(), 10); setPadding(dp(10), dp(10), dp(10), dp(10)); visibility = View.GONE }
        dupText = tv("", 15f, INK, false)
        dupBox.addView(dupText)
        dupBox.addView(row(btn("Additionner", "pri") { onDup("add") }, btn("Remplacer", "sec") { onDup("replace") }), topMargin(8))
        dupBox.addView(btn("Annuler", "sec") { dupBox.visibility = View.GONE; pendingDup = null }, topMargin(6))
        card.addView(dupBox, topMargin(10))
        col.addView(card, cardParams())

        val last = card()
        last.addView(tv("Derniers encodés / modifiés", 13f, GREY, true))
        lastListBox = vbox()
        last.addView(lastListBox)
        lastEmpty = tv("Aucune ligne.", 12f, GREY, false)
        last.addView(lastEmpty)
        col.addView(last, cardParams())

        etCode.addTextChangedListener(simpleWatcher { syncCodeRo() })
        etCode.setOnFocusChangeListener { _, has -> if (!has) { syncCodeRo(); prefillFor(Logic.normCode(etCode.text.toString())) } }
        return scroll
    }

    private fun buildListe(): View {
        val root = vbox().apply { setPadding(dp(10), 0, dp(10), 0) }
        etSearch = edit().apply { hint = "🔍  Rechercher dans la liste"; inputType = InputType.TYPE_CLASS_TEXT; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        val sbox = FrameLayout(this)
        sbox.addView(etSearch, FrameLayout.LayoutParams(-1, -2))
        searchClear = tv("✕", 18f, GREY, true).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); visibility = View.GONE; setOnClickListener {
            etSearch.setText(""); searchRunnable?.let { handler.removeCallbacks(it) }; query = ""; shown = PAGE; renderList()
        } }
        sbox.addView(searchClear, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(sbox, topMargin(6))
        etSearch.addTextChangedListener(simpleWatcher {
            searchClear.visibility = if (etSearch.text.isNotEmpty()) View.VISIBLE else View.GONE
            searchRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable { query = etSearch.text.toString(); shown = PAGE; renderList() }
            searchRunnable = r
            handler.postDelayed(r, 150)
        })

        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hs.addView(chipsBox)
        root.addView(hs, topMargin(6))
        countTv = tv("", 13f, GREY, false)
        root.addView(countTv, topMargin(4))

        listScroll = ScrollView(this)
        val inner = vbox().apply { setPadding(0, dp(6), 0, dp(16)) }
        val card = card()
        listBox = vbox()
        card.addView(listBox)
        emptyTv = tv("Aucune ligne.", 15f, GREY, false)
        card.addView(emptyTv)
        btnMore = btn("Afficher 10 de plus", "sec") { shown += PAGE; renderList() }
        card.addView(btnMore, topMargin(10))
        totalTv = tv("", 15f, INK, true)
        card.addView(totalTv, topMargin(10))
        inner.addView(card, cardParams())
        listScroll.addView(inner)
        root.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderSortChips()
        return root
    }

    private fun buildOutils(): View {
        val scroll = ScrollView(this)
        val col = vbox().apply { setPadding(dp(10), 0, dp(10), dp(16)) }
        scroll.addView(col)
        val card = card()
        lastExportTv = tv("", 14f, 0xFF145A1F.toInt(), false)
        card.addView(lastExportTv)
        card.addView(row(btn("⬆ Exporter", "pri") { onExportXlsx() }, btn("⬇ Importer", "sec") { onPickImport() }), topMargin(8))
        card.addView(row(btn("➦ Partager (csv)", "pri") { onShareCsv() }, btn("↺ Quantités à 0", "sec") { onZero() }), topMargin(8))
        card.addView(btn("🗑 Tout effacer", "danger") { onClear() }, topMargin(8))
        card.addView(btn("🕘 Dernières actions", "sec") { showLog() }, topMargin(8))
        card.addView(tv("Exporter : enregistre le .xlsx où vous le choisissez (Téléchargements...), puis joignez-le à un message. Partager (csv) : envoie le CSV par e-mail ou messagerie.", 12f, GREY, false), topMargin(10))
        card.addView(tv("Importer : fichier .xlsx ou .csv ; ou « Partager » un fichier reçu par e-mail vers Scancounter.", 12f, GREY, false), topMargin(6))

        impBox = vbox().apply { background = round(0xFFFFF3CD.toInt(), 10); setPadding(dp(10), dp(10), dp(10), dp(10)); visibility = View.GONE }
        impText = tv("", 14f, INK, false)
        impBox.addView(impText)
        cbUpdate = CheckBox(this).apply { text = "Mettre à jour les compteurs des articles déjà présents (la quantité du fichier remplace l'actuelle ; sinon l'actuelle est gardée)"; textSize = 14f }
        impBox.addView(cbUpdate, topMargin(6))
        impBox.addView(tv("Informations de l'article :", 14f, INK, true), topMargin(6))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        rbComplete = RadioButton(this).apply { id = View.generateViewId(); text = "Compléter les infos manquantes"; textSize = 14f; isChecked = true }
        rbReplace = RadioButton(this).apply { id = View.generateViewId(); text = "Remplacer les libellés (les champs vides du fichier effacent aussi)"; textSize = 14f }
        rg.addView(rbComplete); rg.addView(rbReplace)
        impBox.addView(rg)
        cbUpdate.setOnCheckedChangeListener { _, _ -> refreshImpPreview() }
        rg.setOnCheckedChangeListener { _, _ -> refreshImpPreview() }
        impBox.addView(row(btn("Ajouter à la liste", "pri") { applyImport(false) }, btn("Remplacer la liste", "sec") { applyImport(true) }), topMargin(8))
        impBox.addView(btn("Annuler", "sec") { pendingImport = null; impBox.visibility = View.GONE }, topMargin(6))
        card.addView(impBox, topMargin(10))
        card.addView(tv("Scancounter $VERSION (natif)", 12f, 0xFF777777.toInt(), false).apply { gravity = Gravity.CENTER }, topMargin(14))
        col.addView(card, cardParams())
        return scroll
    }

    // ------------------------------------------------------------------ outils d'interface

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun tv(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun round(color: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != 0) setStroke(dp(2), stroke)
    }
    private fun card() = vbox().apply { background = round(0xFFFFFFFF.toInt(), 12); setPadding(dp(12), dp(12), dp(12), dp(12)); elevation = dp(2).toFloat() }
    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    private fun topMargin(dpv: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(dpv) }
    private fun edit() = EditText(this).apply {
        setSingleLine(true); textSize = 17f; setTextColor(INK)
        background = round(0xFFFFFFFF.toInt(), 10, 0xFFB8C0CC.toInt()); setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private fun labeled(label: String, e: View): LinearLayout = vbox().apply {
        addView(tv(label, 12f, GREY, false).apply { setPadding(dp(4), 0, 0, dp(2)) })
        addView(e, LinearLayout.LayoutParams(-1, -2))
    }
    private fun btn(text: String, kind: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8)); textSize = 15f
        val (bg, fg) = when (kind) { "sec" -> 0xFFE3E8EF.toInt() to INK; "danger" -> 0xFFC62828.toInt() to 0xFFFFFFFF.toInt(); else -> BLUE to 0xFFFFFFFF.toInt() }
        background = round(bg, 10); setTextColor(fg)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }
    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) }) }
    }
    private fun simpleWatcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { f() }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(content.windowToken, 0) }

    /** Bandeau court ; actionLabel + onAction : lien d'action dans le bandeau (ex. « Voir dans la liste »). */
    private fun msg(text: String, kind: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        if (text.isEmpty()) { msgBox.visibility = View.GONE; return }
        val (bg, fg) = when (kind) {
            "ok" -> 0xFFE1F5E4.toInt() to 0xFF145A1F.toInt()
            "err" -> 0xFFFDE3E3.toInt() to 0xFF8A1111.toInt()
            else -> 0xFFE3EFFD.toInt() to 0xFF0F3F8A.toInt()
        }
        msgBox.background = round(bg, 12)
        msgText.text = text; msgText.setTextColor(fg)
        if (actionLabel != null && onAction != null) {
            msgLink.text = actionLabel; msgLink.setTextColor(fg); msgLink.visibility = View.VISIBLE
            msgLink.setOnClickListener { onAction(); msgBox.visibility = View.GONE }
        } else msgLink.visibility = View.GONE
        msgBox.visibility = View.VISIBLE
    }

    private fun confirm(heading: String, message: String, okLabel: String, cancelLabel: String, onOk: () -> Unit) {
        AlertDialog.Builder(this).setTitle(heading).setMessage(message)
            .setPositiveButton(okLabel) { _, _ -> onOk() }.setNegativeButton(cancelLabel, null).show()
    }
    private fun alert(heading: String, message: String) {
        AlertDialog.Builder(this).setTitle(heading).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun switchTab(name: String) {
        currentTab = name
        for (n in tabNames) {
            tabViews[n]?.visibility = if (n == name) View.VISIBLE else View.GONE
            tabButtons[n]?.setTextColor(if (n == name) BLUE else 0xFF5B6573.toInt())
        }
        hideKeyboard()
    }

    // ------------------------------------------------------------------ affichage

    private fun save() { listDirty = true; store.saveLines(lines) }
    private fun plural(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

    private fun prepareList() {
        if (!listDirty && searchIdx != null && order != null) return
        searchIdx = Logic.buildSearchIndex(lines)
        order = Logic.sortedIndexes(lines, sorts)
        listDirty = false
    }

    private fun itemView(idx: Int): View {
        val ln = lines[idx]
        val item = vbox().apply { setPadding(0, dp(8), 0, dp(8)); setOnClickListener { startEdit(idx) } }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(tv(ln.code, Logic.codeFontSp(ln.code), INK, true).apply { setSingleLine(true) })
        r1.addView(hs, LinearLayout.LayoutParams(0, -2, 1f))
        r1.addView(tv(Logic.fmtQty(ln.qty), 20f, INK, true).apply { gravity = Gravity.END; setPadding(dp(8), 0, dp(8), 0) })
        r1.addView(iconBtn("✎", false) { startEdit(idx) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        r1.addView(iconBtn("🗑", true) { onDelete(idx) }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) })
        item.addView(r1)
        if (ln.d.nom.isNotEmpty()) item.addView(tv(ln.d.nom, 15f, INK, false))
        val extra = listOf(ln.d.marque, ln.d.emplacement, if (ln.d.ref.isNotEmpty()) "ref ${ln.d.ref}" else "", ln.d.affectation).filter { it.isNotEmpty() }.joinToString(" - ")
        if (extra.isNotEmpty()) item.addView(tv(extra, 12f, 0xFF666666.toInt(), false))
        item.addView(tv(Logic.formatDateTime(ln.time), 12f, 0xFF8A919A.toInt(), false))
        val wrap = vbox()
        wrap.addView(item)
        wrap.addView(View(this).apply { setBackgroundColor(0xFFE3E6EA.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrap
    }

    private fun iconBtn(label: String, danger: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 17f
        setTextColor(if (danger) 0xFFC62828.toInt() else INK)
        background = round(if (danger) 0xFFFDE3E3.toInt() else 0xFFE3E8EF.toInt(), 8)
        setOnClickListener { onClick() }
    }

    private fun onDelete(idx: Int) {
        val ln = lines.getOrNull(idx) ?: return
        confirm("Supprimer", "Supprimer la ligne ${ln.code} ?\nCette action est définitive.", "Supprimer la ligne", "Garder la ligne") {
            lines.removeAt(idx)
            if (editIdx == idx) cancelEdit() else if (editIdx > idx) editIdx--
            save(); render()
        }
    }

    private fun renderList() {
        prepareList()
        val view = Logic.searchLines(lines, query, searchIdx!!, order!!)
        listBox.removeAllViews()
        val n = minOf(shown, view.size)
        for (k in 0 until n) listBox.addView(itemView(view[k]))
        val filtering = Logic.searchTokens(query).isNotEmpty()
        emptyTv.visibility = if (view.isEmpty()) View.VISIBLE else View.GONE
        emptyTv.text = if (lines.isEmpty()) "Aucune ligne." else "Aucun résultat"
        btnMore.visibility = if (view.size > n) View.VISIBLE else View.GONE
        btnMore.text = "Afficher ${minOf(PAGE, view.size - n)} de plus (reste ${view.size - n})"
        countTv.text = if (filtering) "${view.size} sur ${lines.size}" else if (lines.isNotEmpty()) plural(lines.size, "article", "articles") else ""
    }

    private fun renderLast() {
        lastListBox.removeAllViews()
        Logic.sortedIndexes(lines).take(LAST_N).forEach { lastListBox.addView(itemView(it)) }
        lastEmpty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun render() {
        renderList()
        renderLast()
        totalTv.text = plural(lines.size, "ligne", "lignes") + " - total " + Logic.fmtQty(Logic.total(lines))
        lastExportTv.text = if (lastExport.isNotEmpty()) "Dernier export : " + Logic.fmtFull(lastExport) else "Jamais exporté"
        lastExportTv.setTextColor(if (lastExport.isNotEmpty()) 0xFF145A1F.toInt() else 0xFF8A1111.toInt())
    }

    // ---- tri : puces (toucher = inverser asc/desc, x = retirer, + = ajouter un critere)
    private fun renderSortChips() {
        chipsBox.removeAllViews()
        chipsBox.addView(tv("Tri :  ", 12f, GREY, false))
        sorts.forEachIndexed { i, s ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = round(0xFFE3EFFD.toInt(), 16); setPadding(dp(11), dp(6), dp(8), dp(6)); minimumHeight = dp(36)
            }
            val label = (if (sorts.size > 1) "${i + 1}. " else "") + Logic.SORT_LABELS[s.k] + " " + (if (s.d > 0) "▲" else "▼")
            chip.addView(tv(label, 13f, 0xFF0F3F8A.toInt(), true).apply { setOnClickListener { sorts[i] = s.copy(d = -s.d); applySort() } })
            if (sorts.size > 1) chip.addView(tv("  ✕ ", 12f, GREY, true).apply { setPadding(dp(4), dp(4), dp(4), dp(4)); setOnClickListener { sorts.removeAt(i); applySort() } })
            chipsBox.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        if (sorts.size < Logic.SORT_ORDER.size) {
            val plus = tv("+", 18f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER; background = round(BLUE, 16); setPadding(dp(16), dp(4), dp(16), dp(4)); minimumHeight = dp(36)
                setOnClickListener {
                    val free = Logic.SORT_ORDER.filter { k -> sorts.none { it.k == k } }
                    AlertDialog.Builder(this@MainActivity).setTitle("Ajouter un critère de tri")
                        .setItems(free.map { Logic.SORT_LABELS.getValue(it) }.toTypedArray()) { _, which ->
                            val k = free[which]
                            sorts.add(Sort(k, if (k == "date") -1 else 1)); applySort()
                        }.show()
                }
            }
            chipsBox.addView(plus)
        }
    }

    private fun applySort() { order = null; shown = PAGE; renderSortChips(); renderList() }

    // ------------------------------------------------------------------ saisie

    private fun setDetailsOpen(open: Boolean) {
        detailsBox.visibility = if (open) View.VISIBLE else View.GONE
        detailsToggle.text = (if (open) "▾  " else "▸  ") + "Détails de l'étiquette (facultatif)"
    }

    private fun getDetails() = Details(etDet[0].text.toString(), etDet[1].text.toString(), etDet[2].text.toString(), etDet[3].text.toString(), etDet[4].text.toString())
    private fun setDetails(d: Details?) {
        for (i in 0..4) etDet[i].setText(d?.get(i) ?: "")
        if (d != null && d.any()) setDetailsOpen(true)
        syncCodeRo()
    }
    private fun syncCodeRo() { etCodeRo.setText(etCode.text.toString()) }
    private fun setCode(t: String) { etCode.setText(t); syncCodeRo(); prefillFor(t) }

    /** Memoire par code : complete les champs VIDES depuis la ligne deja connue. */
    private fun prefillFor(code: String) {
        if (editIdx >= 0 || code.isEmpty()) return
        val idx = Logic.findIndex(lines, Logic.normCode(code))
        if (idx < 0) return
        val d = lines[idx].d.clean()
        var filled = false
        for (i in 0..4) if (Logic.normCode(etDet[i].text.toString()).isEmpty() && d[i].isNotEmpty()) { etDet[i].setText(d[i]); filled = true }
        if (filled) setDetailsOpen(true)
        msg("Code déjà dans la liste (quantité ${Logic.fmtQty(lines[idx].qty)})." + (if (filled) " Détails repris." else "") + " À la validation : additionner ou remplacer.", "info")
    }

    private fun setAddMode(edit: Boolean) {
        btnAdd.text = if (edit) "✓" else "+"
        addLbl.text = if (edit) "Enregistrer la modification" else "Ajouter"
    }

    private fun clearForm() {
        etCode.setText(""); etQty.setText(""); setDetails(null)
        dupBox.visibility = View.GONE; pendingDup = null
    }

    private fun startEdit(idx: Int) {
        val ln = lines.getOrNull(idx) ?: return
        editIdx = idx
        etCode.setText(ln.code)
        setDetails(ln.d)
        etQty.setText(Logic.fmtQty(ln.qty))
        setAddMode(true)
        btnCancel.visibility = View.VISIBLE
        dupBox.visibility = View.GONE; pendingDup = null
        switchTab("saisie")
        msg("Modification de la ligne ${ln.code}", "info")
        etQty.requestFocus()
    }

    private fun cancelEdit() {
        editIdx = -1
        setAddMode(false)
        btnCancel.visibility = View.GONE
        clearForm()
    }

    private fun afterAdd(res: AddResult) {
        when {
            res.ok -> {
                save(); render()
                clearForm()
                val done = when (res.action) { "added" -> "Quantité additionnée."; "replaced" -> "Quantité remplacée."; else -> "Ajouté." }
                msg(done, "ok", "Voir dans la liste") { switchTab("liste") }
            }
            res.exists -> {
                pendingDup = Triple(Logic.normCode(etCode.text.toString()), etQty.text.toString(), getDetails())
                dupText.text = "Le code ${pendingDup!!.first} existe déjà (quantité ${Logic.fmtQty(res.current)}). Que faire ?"
                dupBox.visibility = View.VISIBLE
                msg("", "")
            }
            else -> msg(res.error, "err")
        }
    }

    private fun onAdd() {
        dupBox.visibility = View.GONE
        if (editIdx >= 0) {
            val r = Logic.updateLine(lines, editIdx, etCode.text.toString(), etQty.text.toString(), Logic.nowIso(), getDetails())
            if (r.ok) { save(); cancelEdit(); render(); msg("Ligne modifiée.", "ok") } else msg(r.error, "err")
            return
        }
        afterAdd(Logic.addLine(lines, etCode.text.toString(), etQty.text.toString(), "new", Logic.nowIso(), getDetails()))
    }

    private fun onDup(mode: String) {
        val p = pendingDup ?: return
        afterAdd(Logic.addLine(lines, p.first, p.second, mode, Logic.nowIso(), p.third))
    }

    // ------------------------------------------------------------------ photo, code-barres, etiquette

    private fun openCamera(code: Int) {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "photo_" + System.currentTimeMillis() + ".jpg")
        photoFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
        } catch (e: Exception) {
            msg("Aucune application appareil photo disponible.", "err")
        }
    }

    private fun onEtiquette() {
        if (ocrBusy) { msg("Lecture déjà en cours.", "info"); return }
        openCamera(RC_PHOTO_LABEL)
    }

    /** Scanner en direct : lecteur de codes Google (ML Kit), sans autorisation camera propre a l'application. */
    private fun onLiveScan() {
        msg("Ouverture du scanner...", "info")
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { b ->
                val t = Logic.normCode(b.rawValue)
                if (t.isEmpty()) { msg("Code vide : réessayez ou saisissez-le.", "err"); return@addOnSuccessListener }
                setCode(t)
                msg("Code lu : $t. Saisissez la quantité.", "ok")
                etQty.requestFocus()
            }
            .addOnCanceledListener { msg("", "") }
            .addOnFailureListener { e -> msg("Scanner indisponible (${e.localizedMessage ?: "erreur"}) : utilisez Photo ou la saisie.", "err") }
    }

    private fun readBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val big = maxOf(info.size.width, info.size.height)
                if (big > 2400) decoder.setTargetSampleSize((big + 2399) / 2400)
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    private fun readBarcodeFromPhoto(bitmap: Bitmap) {
        msg("Lecture de la photo...", "info")
        val scanner = BarcodeScanning.getClient()
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { list ->
                val t = Logic.normCode(list.firstOrNull()?.rawValue)
                if (t.isNotEmpty()) {
                    setCode(t)
                    msg("Code lu : $t. Saisissez la quantité.", "ok")
                    etQty.requestFocus()
                } else msg("Aucun code lu sur la photo. Reprenez la photo (plus près, bien nette) ou saisissez le code à la main.", "err")
                scanner.close()
            }
            .addOnFailureListener { e ->
                msg("Lecture impossible (${e.localizedMessage ?: "erreur"}). Saisissez le code à la main.", "err")
                scanner.close()
            }
    }

    private fun readLabel(bitmap: Bitmap) {
        ocrBusy = true
        msg("Lecture de l'étiquette...", "info")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val parsed = LabelParser.parse(
                    result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.right, it.bottom) }
                    }
                )
                val ap = Logic.applyEtiquette(mapOf(
                    "code" to parsed.code, "ref" to parsed.reference, "nom" to parsed.nom,
                    "affectation" to parsed.affectation, "marque" to parsed.marque, "emplacement" to parsed.emplacement
                ))
                val keys = listOf("ref", "nom", "affectation", "marque", "emplacement")
                ap.fields.forEach { (k, v) -> if (k == "code") etCode.setText(v) else etDet[keys.indexOf(k)].setText(v) }
                setDetailsOpen(true)
                syncCodeRo()
                ap.fields["code"]?.let { prefillFor(it) }
                msg(ap.message, ap.kind)
                (if (ap.focus == "code") etCode else etQty).requestFocus()
                ocrBusy = false
                recognizer.close()
            }
            .addOnFailureListener { e ->
                msg("Lecture de l'étiquette impossible (${e.localizedMessage ?: "erreur"}). Code non lu, saisissez-le à la main.", "err")
                etCode.requestFocus()
                ocrBusy = false
                recognizer.close()
            }
    }

    // ------------------------------------------------------------------ export

    private fun markExport() { lastExport = Logic.nowIso(); store.lastExport = lastExport; render() }
    private fun plurLines() = plural(lines.size, "ligne", "lignes")

    /** Exporter : .xlsx a l'emplacement choisi par l'utilisateur (Telechargements...). */
    private fun onExportXlsx() {
        if (lines.isEmpty()) { msg("Rien à exporter.", "err"); return }
        val name = Logic.exportFileName(Date(), "xlsx")
        pendingBytes = try { Xlsx.build(lines) } catch (e: Exception) { msg("Export impossible : ${e.localizedMessage}", "err"); return }
        pendingName = name
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = Xlsx.MIME; putExtra(Intent.EXTRA_TITLE, name)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, RC_EXPORT_XLSX)
    }

    private fun writeXlsx(uri: Uri) {
        val bytes = pendingBytes ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("fichier inaccessible")
            markExport(); msg("", "")
            store.logAction("Export (.xlsx)", "$pendingName, ${plurLines()}")
            alert("Export terminé", "Fichier : $pendingName\nLignes : ${lines.size}\n\nJoignez-le maintenant à un message (trombone).")
        } catch (e: Exception) {
            store.logAction("Export (.xlsx)", "échec : ${e.localizedMessage}")
            msg("Export impossible : ${e.localizedMessage}", "err")
        }
        pendingBytes = null
    }

    /** Partager (csv) : feuille de partage Android (e-mail, messagerie...). */
    private fun onShareCsv() {
        if (lines.isEmpty()) { msg("Rien à exporter.", "err"); return }
        val name = Logic.exportFileName(Date(), "csv")
        try {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, name)
            file.writeBytes(Logic.toCsv(lines).toByteArray(Charsets.UTF_8))
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, Logic.exportSubject(name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Partager le CSV"))
            markExport()
            store.logAction("Partage (csv)", "ouvert : $name, ${plurLines()}")
            msg("Partage ouvert : $name", "ok")
        } catch (e: Exception) {
            store.logAction("Partage (csv)", "échec : ${e.localizedMessage}")
            msg("Partage impossible : ${e.localizedMessage}", "err")
        }
    }

    private fun showLog() {
        val list = store.journal()
        alert("Dernières actions", if (list.isEmpty()) "Aucune action enregistrée pour le moment." else list.joinToString("\n\n") { Logic.journalLine(it) })
    }

    private fun onZero() {
        if (lines.isEmpty()) { msg("Aucune ligne.", "info"); return }
        val warn = if (lastExport.isEmpty()) "\n\nAttention : aucun export n'a été fait." else ""
        confirm("Quantités à zéro", "Remettre toutes les quantités à zéro ?\nLes lignes sont conservées.$warn", "Remettre à zéro", "Annuler") {
            store.logAction("Quantités à 0", plurLines() + " conservées")
            Logic.resetQuantities(lines); cancelEdit(); save(); render()
            msg("Quantités remises à zéro (${plurLines()} conservées).", "ok")
        }
    }

    private fun onClear() {
        if (lines.isEmpty()) { msg("Rien à effacer.", "info"); return }
        val warn = if (lastExport.isEmpty()) "\n\nAttention : aucun export n'a été fait." else ""
        confirm("Tout effacer", "Effacer TOUTES les lignes (${lines.size}) ?\nCette action est définitive.$warn", "Tout effacer", "Annuler") {
            store.logAction("Tout effacer", plurLines() + " supprimées")
            lines = mutableListOf(); cancelEdit(); save(); render(); msg("Tout est effacé.", "ok")
        }
    }

    // ------------------------------------------------------------------ import

    private fun onPickImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(Xlsx.MIME, "text/csv", "text/comma-separated-values", "application/csv", "application/octet-stream", "text/plain"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, RC_IMPORT)
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "fichier"
    }

    private fun readUri(uri: Uri): ByteArray =
        contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("fichier illisible")

    private fun analyze(bytes: ByteArray): ImportData {
        val r = Xlsx.rowsToLines(Xlsx.readTable(bytes), Logic.nowIso())
        if (r.count == 0) throw IllegalArgumentException("Aucune ligne exploitable dans le fichier.")
        return r
    }

    private fun processImport(name: String, bytes: ByteArray) {
        impBox.visibility = View.GONE; pendingImport = null
        msg("Lecture du fichier $name...", "info")
        try {
            val r = analyze(bytes)
            showImportPreview(name, r)
            store.logAction("Import", "aperçu de $name (${plural(r.count, "ligne", "lignes")})")
        } catch (e: Exception) {
            store.logAction("Import", "erreur : ${e.localizedMessage ?: "fichier illisible"}")
            msg("Import impossible : ${e.localizedMessage ?: "fichier illisible"}", "err")
        }
    }

    private fun showImportPreview(name: String, r: ImportData) {
        pendingImport = r; pendingName = name
        cbUpdate.isChecked = false; rbComplete.isChecked = true
        refreshImpPreview()
        impBox.visibility = View.VISIBLE
        switchTab("outils")
        msg("", "")
    }

    private fun infoMode() = if (rbReplace.isChecked) "replace" else "complete"

    private fun dryRun(r: ImportData): MergeCounts =
        Logic.mergeImport(lines.map { it.copyOf() }.toMutableList(), r.lines, cbUpdate.isChecked, infoMode(), r.cols)

    private fun summaryText(c: MergeCounts, skipped: Int, mode: String): String =
        plural(c.created, "nouveau", "nouveaux") + ", " + plural(c.updated, "mis à jour (quantité remplacée)", "mis à jour (quantité remplacée)") +
            ", " + plural(c.kept, "conservé (quantité gardée)", "conservés (quantité gardée)") + ", " + plural(skipped, "ignoré (invalide)", "ignorés (invalides)") +
            ". Infos " + (if (mode == "replace") "remplacées" else "complétées") + " pour " + plural(c.infoChanged, "article", "articles") +
            (if (mode == "replace") " (" + plural(c.cleared, "champ effacé", "champs effacés") + ")" else "") + "."

    private fun refreshImpPreview() {
        val r = pendingImport ?: return
        val first = r.lines.take(3).joinToString(" ; ") { l ->
            l.code + " x " + Logic.fmtQty(l.qty) + (if (l.d.nom.isNotEmpty()) " (" + (if (l.d.nom.length > 30) l.d.nom.take(30) + "..." else l.d.nom) + ")" else "")
        }
        val dry = dryRun(r)
        impText.text = pendingName + " : " + plural(r.count, "ligne", "lignes") + " à importer" +
            (if (r.skipped.isNotEmpty()) ", " + plural(r.skipped.size, "ignorée", "ignorées") + " (ex. ligne ${r.skipped[0].row} : ${r.skipped[0].why})" else "") +
            ". Début : $first. Si vous choisissez « Ajouter à la liste » : " + summaryText(dry, r.skipped.size, infoMode()) +
            " « Remplacer la liste » efface les " + plural(lines.size, "ligne actuelle", "lignes actuelles") + "."
    }

    private fun applyImport(replace: Boolean) {
        val r = pendingImport ?: return
        if (replace && lines.isNotEmpty()) {
            confirm("Remplacer la liste", "Remplacer la liste ?\nLes ${plural(lines.size, "ligne actuelle sera effacée", "lignes actuelles seront effacées")} et remplacée(s) par les ${plural(r.count, "ligne", "lignes")} du fichier.", "Remplacer la liste", "Annuler") { doImport(true) }
            return
        }
        if (!replace && infoMode() == "replace") {
            val dry = dryRun(r)
            if (dry.cleared > 0) {
                confirm("Effacer des champs", plural(dry.cleared, "champ sera effacé", "champs seront effacés") + " : les cellules vides du fichier effacent les informations existantes (nom, ref fournisseur, affectation, marque, emplacement).\nContinuer ?", "Ajouter et effacer", "Annuler") { doImport(false) }
                return
            }
        }
        doImport(replace)
    }

    private fun doImport(replace: Boolean) {
        val r = pendingImport ?: return
        val mode = infoMode()
        val c: MergeCounts
        if (replace) { lines = mutableListOf(); c = Logic.mergeImport(lines, r.lines, true, "complete", null) }
        else c = Logic.mergeImport(lines, r.lines, cbUpdate.isChecked, mode, r.cols)
        pendingImport = null; impBox.visibility = View.GONE
        cancelEdit(); save(); render(); msg("", "")
        val txt = Logic.importSummary(c, r.skipped.size, mode, replace)
        store.logAction("Import", (if (replace) "liste remplacée : " else "terminé : ") + txt.replace("\n", " ; "))
        alert("Import terminé", txt + "\n\nLes détails servent aussi de mémoire par code.")
    }

    /** Fichier recu par « Partager » (piece jointe d'un e-mail) ou ouvert avec Scancounter. */
    private fun handleIncoming(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_VIEW) i.action = null // ne pas retraiter
        if (uri == null) {
            if (i.type != null) alert("Aucun fichier reçu", "Le menu Partager n'a transmis aucun fichier.\n\nOuvrez le mail, appuyez sur la pièce jointe puis « Partager », et choisissez Scancounter. Sinon, utilisez le bouton Importer.")
            return
        }
        val name = displayName(uri)
        try {
            val bytes = readUri(uri)
            val r = analyze(bytes)
            val fresh = r.lines.count { Logic.prefill(lines, it.code) == null }
            val info = ReceivedInfo(name, if (Xlsx.isXlsx(bytes)) "xlsx" else "csv", r.count, fresh, r.count - fresh, r.skipped.size)
            store.logAction("Fichier reçu", name + " (" + Logic.receivedSummary(info).split("\n").drop(1).take(4).joinToString(", ") + ")")
            confirm("Fichier reçu", Logic.receivedSummary(info), "Voir l'aperçu d'import", "Annuler") { showImportPreview(name, r) }
        } catch (e: Exception) {
            store.logAction("Fichier reçu", "illisible : ${e.localizedMessage}")
            alert("Fichier reçu illisible", "Cause : ${e.localizedMessage ?: "erreur"}\nNom : $name")
        }
    }

    // ------------------------------------------------------------------ resultats d'activites

    @Deprecated("Uses activity result for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { if (requestCode == RC_EXPORT_XLSX) pendingBytes = null; return }
        when (requestCode) {
            RC_EXPORT_XLSX -> data?.data?.let { writeXlsx(it) } ?: msg("Aucun emplacement choisi.", "err")
            RC_IMPORT -> {
                val uri = data?.data
                if (uri == null) { msg("Aucun fichier choisi.", "err"); return }
                try { processImport(displayName(uri), readUri(uri)) } catch (e: Exception) { msg("Import impossible : fichier illisible", "err") }
            }
            RC_PHOTO_CODE, RC_PHOTO_LABEL -> {
                val f = photoFile?.takeIf { it.exists() }
                if (f == null) { msg("Impossible de lire la photo.", "err"); return }
                try {
                    val bmp = readBitmap(Uri.fromFile(f))
                    if (requestCode == RC_PHOTO_CODE) readBarcodeFromPhoto(bmp) else readLabel(bmp)
                } catch (e: Exception) {
                    msg("Erreur photo : ${e.localizedMessage}", "err")
                }
            }
        }
    }
}
