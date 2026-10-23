package be.scancounter

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
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
import kotlin.math.abs

/**
 * Scancounter natif : Articles (stock = produit + magasin + emplacement + quantite), Inventaire (sessions de comptage), Outils.
 * Menu hamburger, tous les messages dans des fenetres de l'application.
 */
class MainActivity : Activity() {
    private companion object {
        const val RC_PHOTO_CODE = 101
        const val RC_PHOTO_LABEL = 102
        const val RC_EXPORT_XLSX = 103
        const val RC_IMPORT = 104
        const val RC_PHOTO_ART = 105
        const val RC_GALLERY = 106
        const val PAGE = 10
        const val VERSION = "v2.1"
        val BG = Ui.BG
        val INK = 0xFF1B1F24.toInt()
        val GREY = 0xFF555555.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val SUB = 0xFF3B4660.toInt()
    }

    private lateinit var store: Store
    private lateinit var repo: Repo
    private var lastExport = ""
    private val handler = Handler(Looper.getMainLooper())

    // etat de la liste
    private var shown = PAGE
    private val sorts = mutableListOf(Sort("date", -1))
    private var query = ""
    private var showAll = false
    private var searchRunnable: Runnable? = null

    // photo / import / export en attente
    private var photoFile: File? = null
    private var pendingBytes: ByteArray? = null
    private var pendingImport: ImportData? = null
    private var pendingName = ""
    private var pendingStockExport = true
    private var pendingRestore: Backup.Content? = null
    private var ocrBusy = false
    private var labelFromMain = false
    private var activeBox: PhotoBox? = null
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // vues generales
    private lateinit var msgBox: LinearLayout
    private lateinit var msgText: TextView
    private lateinit var msgLink: TextView
    private lateinit var content: FrameLayout
    private lateinit var titleTv: TextView
    private lateinit var invChip: TextView
    private lateinit var drawer: FrameLayout
    private val paneNames = listOf("art", "inv", "out")
    private val paneTitles = mapOf("art" to "Articles", "inv" to "Inventaire", "out" to "Outils")
    private val paneViews = mutableMapOf<String, View>()
    private val drawerItems = mutableMapOf<String, TextView>()
    private var currentPane = "art"

    // Articles
    private lateinit var cbar: LinearLayout
    private lateinit var cbarText: TextView
    private lateinit var cbarAll: TextView
    private lateinit var etSearch: EditText
    private lateinit var countTv: TextView
    private lateinit var sectTv: TextView
    private lateinit var listBox: LinearLayout

    // formulaire de creation
    private lateinit var sheet: FrameLayout
    private lateinit var sheetTitle: TextView
    private lateinit var sheetBox: PhotoBox
    private lateinit var etCode: EditText
    private lateinit var etRef: EditText
    private lateinit var etNom: EditText
    private lateinit var etAff: AutoCompleteTextView
    private lateinit var etMarque: AutoCompleteTextView
    private lateinit var etMag: AutoCompleteTextView
    private lateinit var etEmp: AutoCompleteTextView
    private lateinit var etQty: EditText

    // Inventaire / Outils
    private lateinit var invList: LinearLayout
    private lateinit var lastExportTv: TextView
    private lateinit var impBox: LinearLayout
    private lateinit var impText: TextView
    private lateinit var rbQtyKeep: RadioButton
    private lateinit var rbQtyReplace: RadioButton
    private lateinit var rbQtyAdd: RadioButton
    private lateinit var cbPhotos: CheckBox
    private lateinit var rbComplete: RadioButton
    private lateinit var rbReplace: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        repo = Repo(this)
        File(cacheDir, "export").listFiles()?.forEach { it.delete() } // exports / sauvegardes deja partages
        lastExport = store.lastExport
        buildUi()
        showPane("art")
        renderAll()
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    // ------------------------------------------------------------------ construction de l'ecran

    private fun buildUi() {
        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }
        val main = vbox()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.NAVY); setPadding(dp(4), dp(6), dp(12), dp(6)); elevation = dp(4).toFloat()
        }
        bar.addView(tv("☰", 24f, WHITE, true).apply { setPadding(dp(14), dp(6), dp(14), dp(6)); setOnClickListener { openDrawer() } })
        titleTv = tv("Articles", 19f, WHITE, true)
        bar.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        invChip = tv("", 13f, Ui.NAVY, true).apply {
            background = round(Ui.YELLOW, 16); setPadding(dp(12), dp(6), dp(12), dp(6)); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(170); setOnClickListener { showPane("inv") }
        }
        bar.addView(invChip)
        main.addView(bar)

        msgBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        msgText = tv("", 15f, INK, false).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        msgLink = tv("", 15f, INK, true).apply { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; setPadding(dp(8), dp(4), dp(4), dp(4)); visibility = View.GONE }
        msgBox.addView(msgText); msgBox.addView(msgLink)
        main.addView(msgBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(8), dp(10), 0) })

        content = FrameLayout(this)
        main.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        paneViews["art"] = buildArticles()
        paneViews["inv"] = buildInventory()
        paneViews["out"] = buildOutils()
        paneViews.values.forEach { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        frame.addView(main, FrameLayout.LayoutParams(-1, -1))
        frame.addView(buildDrawer(), FrameLayout.LayoutParams(-1, -1))

        // Android 15+ : l'app passe sous les barres systeme, on decale le contenu (et le clavier)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val sys = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        setContentView(frame)
    }

    private fun buildDrawer(): View {
        drawer = FrameLayout(this).apply { visibility = View.GONE }
        drawer.addView(View(this).apply { setBackgroundColor(0x66000000); setOnClickListener { closeDrawer() } }, FrameLayout.LayoutParams(-1, -1))
        val panel = vbox().apply { setBackgroundColor(WHITE); elevation = dp(12).toFloat(); isClickable = true }
        val head = vbox().apply { setBackgroundColor(Ui.NAVY); setPadding(dp(18), dp(28), dp(18), dp(18)) }
        head.addView(tv("Scancounter", 20f, WHITE, true))
        head.addView(tv(VERSION, 13f, Ui.YELLOW, false))
        panel.addView(head)
        val icons = mapOf("art" to "📦", "inv" to "📋", "out" to "⚙")
        for (n in paneNames) {
            val item = tv("${icons[n]}   ${paneTitles[n]}", 17f, INK, true).apply {
                setPadding(dp(18), dp(16), dp(18), dp(16)); setOnClickListener { showPane(n) }
            }
            drawerItems[n] = item
            panel.addView(item, LinearLayout.LayoutParams(-1, -2))
        }
        drawer.addView(panel, FrameLayout.LayoutParams(dp(270), -1, Gravity.START))
        return drawer
    }

    private fun openDrawer() { hideKeyboard(); drawer.visibility = View.VISIBLE }
    private fun closeDrawer() { drawer.visibility = View.GONE }

    private fun showPane(name: String) {
        currentPane = name
        for (n in paneNames) {
            paneViews[n]?.visibility = if (n == name) View.VISIBLE else View.GONE
            drawerItems[n]?.apply { setTextColor(if (n == name) Ui.NAVY else GREY); setBackgroundColor(if (n == name) 0xFFFFF3C4.toInt() else 0) }
        }
        titleTv.text = paneTitles[name]
        if (name != "art") invChip.visibility = View.GONE
        closeDrawer(); hideKeyboard()
        if (name == "art") renderArticles()
        if (name == "inv") renderInventories()
    }

    private fun buildArticles(): View {
        val root = FrameLayout(this)
        val col = vbox()

        // bandeau de comptage (inventaire en cours)
        cbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFFFFF3C4.toInt())
            setPadding(dp(12), dp(8), dp(8), dp(8)); visibility = View.GONE
        }
        cbarText = tv("", 13f, INK, true).apply { setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }
        cbar.addView(cbarText, LinearLayout.LayoutParams(0, -2, 1f))
        cbarAll = smallPill("Tout voir", Ui.SLATE) { showAll = !showAll; shown = PAGE; renderAll() }
        cbar.addView(cbarAll)
        cbar.addView(smallPill("Pause", Ui.SLATE) { curInv()?.let { showLeaveCountDialog(it) } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        cbar.addView(smallPill("Terminer", Ui.GREEN) { curInv()?.let { showFinishDialog(it) } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        col.addView(cbar)

        etSearch = EditText(this).apply {
            hint = "Rechercher"; setSingleLine(true); textSize = 16f; setTextColor(INK)
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = round(0xFFF3F4F6.toInt(), 14, 0xFFBBBBBB.toInt()); setPadding(dp(18), dp(13), dp(44), dp(13))
        }
        val sbox = FrameLayout(this)
        sbox.addView(etSearch, FrameLayout.LayoutParams(-1, -2))
        sbox.addView(tv("🔍", 18f, GREY, false), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(14) })
        val srow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        srow.addView(sbox, LinearLayout.LayoutParams(0, -2, 1f))
        srow.addView(tv("⇅", 22f, WHITE, true).apply {
            gravity = Gravity.CENTER; background = round(Ui.NAVY, 14); setOnClickListener { showSortDialog() }
        }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(8) })
        col.addView(srow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(8), dp(10), 0) })
        countTv = tv("", 12f, GREY, false).apply { gravity = Gravity.END }
        col.addView(countTv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), 0) })
        sectTv = tv("Derniers encodés / modifiés", 12f, GREY, true)
        col.addView(sectTv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(6), dp(16), 0) })
        val scroll = ScrollView(this)
        listBox = vbox().apply { setPadding(dp(10), 0, dp(10), dp(250)) }
        scroll.addView(listBox)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        etSearch.addTextChangedListener(simpleWatcher {
            searchRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable { query = etSearch.text.toString(); shown = PAGE; renderArticles() }
            searchRunnable = r
            handler.postDelayed(r, 150)
        })

        // 3 boutons flottants alignes (meme taille, meme bord droit, meme espacement), sans texte
        val fabs = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        fabs.addView(fab(Ui.NAVY, 0, plusIcon(), 60) { openSheet() }, fabParams(60, 0))
        fabs.addView(fab(WHITE, Ui.NAVY, labelIcon(), 60) { onEtiquette(true) }, fabParams(60, 14))
        fabs.addView(fab(Ui.YELLOW, 0, ScanIconView(this, Ui.NAVY), 60) { onLiveScan(true) }, fabParams(60, 14))
        root.addView(fabs, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })

        root.addView(buildSheet(), FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun smallPill(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        background = round(color, 14); setPadding(dp(12), dp(7), dp(12), dp(7)); setOnClickListener { onClick() }
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit) = smallPill(text, color, onClick)

    /** Lecteur de codes Google : un seul code, rendu normalise a onCode. */
    private fun scanOne(onCode: (String) -> Unit) {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { b ->
                val t = Logic.normCode(b.rawValue)
                if (t.isEmpty()) toast("Code vide : réessayez.") else onCode(t)
            }
            .addOnFailureListener { e -> toast("Scanner indisponible (${e.localizedMessage ?: "erreur"}).") }
    }

    private fun plusIcon() = tv("+", 32f, WHITE, true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
    private fun labelIcon() = tv("🏷", 26f, Ui.NAVY, false).apply { gravity = Gravity.CENTER; includeFontPadding = false }

    private fun fab(bg: Int, stroke: Int, icon: View, sizeDp: Int, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this).apply {
            background = round(bg, 18, stroke); elevation = dp(6).toFloat(); setOnClickListener { onClick() }
        }
        f.addView(icon, FrameLayout.LayoutParams(if (icon is ScanIconView) dp(sizeDp / 2 + 4) else -2, if (icon is ScanIconView) dp(sizeDp / 2 + 4) else -2, Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return f
    }

    private fun fabParams(sizeDp: Int, topDp: Int) = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { topMargin = dp(topDp) }

    /** Champ a libelle sur la bordure (maquette). */
    private fun fld(label: String, e: View, bgc: Int): FrameLayout {
        val f = FrameLayout(this)
        f.addView(e, FrameLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        f.addView(tv(label, 11f, GREY, false).apply { setBackgroundColor(bgc); setPadding(dp(5), 0, dp(5), 0) },
            FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        return f
    }

    private fun outlined(e: EditText): EditText = e.apply {
        background = round(0, 12, 0xFF8A93A6.toInt()); setPadding(dp(12), dp(14), dp(12), dp(14))
    }

    /** Champ texte avec suggestions deja saisies (comme scanpocket-app). */
    private fun acEdit(): AutoCompleteTextView = AutoCompleteTextView(this).apply {
        setSingleLine(true); textSize = 17f; setTextColor(INK); threshold = 1
        background = round(0xFFFFFFFF.toInt(), 10, 0xFFB8C0CC.toInt()); setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnFocusChangeListener { _, has -> if (has && text.isEmpty() && adapter != null && adapter.count > 0) post { showDropDown() } }
        setOnClickListener { if (text.isEmpty() && adapter != null && adapter.count > 0) showDropDown() }
    }

    private fun setSuggestions(ac: AutoCompleteTextView, list: List<String>) {
        ac.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, list))
    }

    /** Magasins, marques, affectations deja connus ; les emplacements suivent le magasin saisi. */
    private fun refreshSuggestions(mag: AutoCompleteTextView, emp: AutoCompleteTextView, marque: AutoCompleteTextView, aff: AutoCompleteTextView, watch: Boolean = true) {
        setSuggestions(mag, repo.distinctMagasins())
        setSuggestions(marque, repo.distinctMarques())
        setSuggestions(aff, repo.distinctAffectations())
        fun empl() {
            val m = Logic.normField(mag.text.toString())
            val known = m.isNotEmpty() && repo.distinctMagasins().any { it.equals(m, true) }
            setSuggestions(emp, repo.distinctEmplacements(if (known) m else null))
        }
        empl()
        if (watch) mag.addTextChangedListener(simpleWatcher { empl() })
    }

    private fun buildSheet(): View {
        sheet = FrameLayout(this).apply { setBackgroundColor(BG); visibility = View.GONE; isClickable = true }
        val colm = vbox()
        val hd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.NAVY); setPadding(dp(14), dp(14), dp(14), dp(14)) }
        sheetTitle = tv("Nouvel article", 17f, WHITE, true)
        hd.addView(sheetTitle, LinearLayout.LayoutParams(0, -2, 1f))
        hd.addView(tv("✕", 20f, WHITE, true).apply { setPadding(dp(8), 0, dp(4), 0); setOnClickListener { closeSheet() } })
        colm.addView(hd)
        val sc = ScrollView(this)
        val body = vbox().apply { setPadding(dp(10), dp(6), dp(10), dp(150)) }

        sheetBox = PhotoBox(this) { pickPhoto(it) }
        sheetBox.load(null)
        body.addView(sheetBox.view)

        etCode = outlined(edit())
        etNom = outlined(edit()); etRef = outlined(edit())
        etAff = outlined(acEdit()) as AutoCompleteTextView; etMarque = outlined(acEdit()) as AutoCompleteTextView
        etMag = outlined(acEdit()) as AutoCompleteTextView; etEmp = outlined(acEdit()) as AutoCompleteTextView
        body.addView(fld("Code article", etCode, BG), topMargin(8))
        body.addView(pill("Proposer un code interne (200…)", Ui.ORANGE) { etCode.setText(Logic.nextCode200(repo.allCodes())); prefillFor(etCode.text.toString()) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2); leftMargin = dp(4) })
        body.addView(fld("Nom", etNom, BG), topMargin(0))
        body.addView(fld("Référence fournisseur", etRef, BG), topMargin(0))
        body.addView(fld("Affectation", etAff, BG), topMargin(0))
        body.addView(fld("Marque", etMarque, BG), topMargin(0))
        body.addView(fld("Magasin", etMag, BG), topMargin(0))
        body.addView(fld("Emplacement", etEmp, BG), topMargin(0))
        etQty = edit()
        body.addView(fld("Quantité comptée", stepper(etQty), BG), topMargin(0))
        body.addView(btn("Enregistrer", "pri") { onSaveSheet() }.apply { textSize = 16f; minHeight = dp(54); minimumHeight = dp(54) }, topMargin(16))
        sc.addView(body)
        colm.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.addView(colm, FrameLayout.LayoutParams(-1, -1))

        val mini = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        mini.addView(fab(WHITE, Ui.NAVY, labelIcon(), 60) { onEtiquette(false) }, fabParams(60, 0))
        mini.addView(fab(Ui.YELLOW, 0, ScanIconView(this, Ui.NAVY), 60) { onLiveScan(false) }, fabParams(60, 14))
        sheet.addView(mini, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })

        etCode.setOnFocusChangeListener { _, has -> if (!has) prefillFor(Logic.normCode(etCode.text.toString())) }
        refreshSuggestions(etMag, etEmp, etMarque, etAff)
        return sheet
    }

    /** Ouvre le formulaire ; p non nul = nouvelle ligne de stock pour un produit connu (fiche reprise, non ecrasee). */
    private fun openSheet(p: Product? = null, mag: String = "", emp: String = "") {
        clearForm()
        if (p != null) {
            etCode.setText(p.code); etRef.setText(p.ref); etNom.setText(p.nom); etAff.setText(p.affectation); etMarque.setText(p.marque)
            sheetBox.load(Photos.fileIfExists(this, p.code))
        }
        etMag.setText(mag); etEmp.setText(emp)
        sheetTitle.text = if (p != null) "Nouvelle ligne de stock" else "Nouvel article"
        refreshSuggestions(etMag, etEmp, etMarque, etAff, false)
        sheet.visibility = View.VISIBLE
    }

    private fun closeSheet() {
        sheet.visibility = View.GONE
        hideKeyboard()
    }

    private fun sheetOpen() = sheet.visibility == View.VISIBLE

    private fun clearForm() {
        listOf(etCode, etRef, etNom, etAff, etMarque, etMag, etEmp, etQty).forEach { it.setText("") }
        sheetBox.clear()
    }

    private fun setCode(t: String) { etCode.setText(t); prefillFor(t) }

    /** Memoire par code : complete les champs VIDES depuis la fiche produit deja connue. */
    private fun prefillFor(code: String) {
        if (code.isEmpty()) return
        val p = repo.productByCode(code) ?: return
        var filled = false
        fun fill(e: EditText, v: String) { if (e.text.isEmpty() && v.isNotEmpty()) { e.setText(v); filled = true } }
        fill(etRef, p.ref); fill(etNom, p.nom); fill(etAff, p.affectation); fill(etMarque, p.marque)
        if (sheetBox.bitmap == null && !sheetBox.changed) Photos.fileIfExists(this, p.code)?.let { sheetBox.load(it) }
        val n = repo.linesOf(p.id).size
        msg("Produit déjà connu ($n ${if (n > 1) "lignes de stock" else "ligne de stock"})." + (if (filled) " Fiche reprise." else "") +
            " Un magasin / emplacement différent crée une nouvelle ligne.", "info")
    }

    private fun onSaveSheet() {
        val code = Logic.normCode(etCode.text.toString())
        if (code.isEmpty()) { msg("Code vide : saisissez ou scannez le code.", "err"); return }
        val q = Logic.normQty(etQty.text.toString())
        if (q == null) { msg("Quantité invalide.", "err"); return }
        val mag = Logic.normField(etMag.text.toString()); val emp = Logic.normField(etEmp.text.toString())
        val known = repo.productByCode(code)
        if (known != null) {
            val dup = repo.findLine(known.id, mag, emp)
            if (dup != null) {
                val lu = mapOf("ref" to etRef.text.toString(), "nom" to etNom.text.toString(), "affectation" to etAff.text.toString(), "marque" to etMarque.text.toString())
                msg("", "")
                showDupDialog(Card(dup, known), lu, etQty.text.toString(), "saisie")
                return
            }
        }
        val p = repo.upsertProduct(code, etRef.text.toString(), etNom.text.toString(), etAff.text.toString(), etMarque.text.toString())
        val line = repo.addLine(p.id, mag, emp, q) ?: run { msg("Cette ligne existe déjà.", "err"); return }
        val pb = sheetBox.bitmap
        if (sheetBox.changed) { if (pb != null) Photos.save(this, p.code, pb) else Photos.delete(this, p.code) }
        curInv()?.let { if (it.covers(line)) repo.setCount(it, line, q) }
        save()
        clearForm(); closeSheet()
        msg(if (known != null) "Nouvelle ligne de stock ajoutée." else "Article ajouté.", "ok")
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            drawer.visibility == View.VISIBLE -> closeDrawer()
            sheetOpen() -> closeSheet()
            currentPane != "art" -> showPane("art")
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ inventaire en cours

    private fun curInv(): Inventory? = repo.inventories.firstOrNull { it.id == store.currentInv && it.open }
    private fun counting(c: Card): Boolean = curInv()?.covers(c.l) == true

    // ------------------------------------------------------------------ cartes d'articles

    private fun thumbHost(sizeDp: Int): FrameLayout = FrameLayout(this).apply {
        background = round(0xFFE5E8EE.toInt(), 8); clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun fillThumb(host: FrameLayout, bmp: Bitmap?) {
        host.removeAllViews()
        if (bmp != null) host.addView(ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        else host.addView(tv("🖼", 24f, 0xFF9AA3B2.toInt(), false).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun thumbFor(code: String, sizeDp: Int): FrameLayout {
        val h = thumbHost(sizeDp)
        fillThumb(h, Photos.fileIfExists(this, code)?.let { Photos.decode(it, dp(sizeDp) * 2) })
        return h
    }

    private fun small(t: String) = tv(t, 12.5f, SUB, false).apply { setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }

    private fun cardView(c: Card): View {
        val inv = curInv()
        val inScope = inv != null && inv.covers(c.l)
        val outScope = inv != null && !inv.covers(c.l)
        val cnt = if (inv != null && inScope) repo.countOf(inv, c.l) else null
        val wrap = FrameLayout(this).apply { background = round(Ui.RED, 16); clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND }
        if (inv == null) wrap.addView(tv("🗑", 26f, WHITE, false).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(dp(100), -1, Gravity.END))
        val front = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(WHITE, 16); setPadding(dp(12), dp(10), dp(12), dp(10))
            if (outScope) alpha = 0.45f
        }
        front.addView(thumbFor(c.p.code, 62), LinearLayout.LayoutParams(dp(62), dp(62)))
        val t = vbox().apply { setPadding(dp(10), 0, dp(6), 0) }
        t.addView(tv(c.p.nom.ifEmpty { c.p.code }, 16f, INK, true).apply { setSingleLine(true); ellipsize = TextUtils.TruncateAt.END })
        if (c.p.nom.isNotEmpty()) t.addView(small(c.p.code))
        if (c.p.ref.isNotEmpty()) t.addView(small("Réf : ${c.p.ref}"))
        if (c.p.marque.isNotEmpty()) t.addView(small(c.p.marque))
        val place = listOf(c.l.magasin, c.l.emplacement).filter { it.isNotEmpty() }.joinToString(" / ")
        var line = place
        if (inScope) {
            val diff = cnt?.let { it.qty - c.l.qty }
            line += (if (line.isEmpty()) "" else " - ") + "attendu ${Logic.fmtQty(c.l.qty)}" +
                (if (diff != null && diff != 0.0) " - écart ${(if (diff > 0) "+" else "")}${Logic.fmtQty(diff)}" else "")
        }
        if (line.isNotEmpty()) t.addView(small(line))
        front.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val badgeText = if (inScope) (cnt?.let { Logic.fmtQty(it.qty) } ?: "—") else Logic.fmtQty(c.l.qty)
        val badgeColor = when {
            !inScope -> Ui.NAVY
            cnt == null -> 0xFF9AA3B2.toInt()
            cnt.qty != c.l.qty -> Ui.ORANGE
            else -> Ui.GREEN
        }
        front.addView(tv(badgeText, 15f, WHITE, true).apply {
            background = round(badgeColor, 10); gravity = Gravity.CENTER; setPadding(dp(10), dp(5), dp(10), dp(5)); minWidth = dp(40)
        })
        front.setOnClickListener {
            when {
                inScope -> showCountDialog(c)
                outScope -> infoDialog("Hors périmètre", "Cette ligne de stock (${place.ifEmpty { "sans emplacement" }}) est en dehors du périmètre de l'inventaire en cours : elle ne peut pas être comptée. Utilisez « Périmètre » pour revenir à la vue filtrée.", "warn")
                else -> showEditDialog(c)
            }
        }
        if (inv != null) front.setOnLongClickListener { showEditDialog(c); true }

        if (inv == null) {
            // glisser vers la gauche : fond rouge + poubelle, la fenetre de confirmation s'ouvre au-dela du seuil
            var downX = 0f; var downY = 0f; var drag = false
            front.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; drag = false; false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (!drag && dx < -touchSlop && abs(dx) > abs(dy) * 1.5f) {
                            drag = true; v.isPressed = false; v.parent.requestDisallowInterceptTouchEvent(true)
                        }
                        if (drag) { v.translationX = dx.coerceIn(-dp(120).toFloat(), 0f); true } else false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (drag) {
                            val go = e.actionMasked == MotionEvent.ACTION_UP && v.translationX < -dp(90)
                            v.animate().translationX(0f).setDuration(150).start()
                            drag = false
                            if (go) onDelete(c)
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
        wrap.addView(front, FrameLayout.LayoutParams(-1, -2))
        return wrap
    }

    /** Cartes visibles : recherche, tri, et en comptage filtre sur le perimetre (sauf « Tout voir »). */
    private fun visibleCards(): Pair<List<Card>, Int> {
        val all = repo.cards()
        val index = Logic.buildSearchIndex(all)
        val order = Logic.sortedIndexes(all, sorts)
        var view = Logic.searchCards(query, index, order).map { all[it] }
        val inv = curInv()
        if (inv != null && !showAll) view = view.filter { inv.covers(it.l) }
        return view to all.size
    }

    private fun sortLabel(): String =
        if (sorts.size == 1 && sorts[0].k == "date" && sorts[0].d < 0) "Derniers encodés / modifiés"
        else "Tri : " + sorts.joinToString(", ") { Logic.SORT_LABELS[it.k] + (if (it.d > 0) " ▲" else " ▼") }

    private fun renderArticles() {
        val (view, total) = visibleCards()
        val inv = curInv()
        val filtering = Logic.searchTokens(query).isNotEmpty()
        sectTv.text = if (filtering) "Résultats" else sortLabel()
        countTv.text = if (filtering || inv != null) "${view.size}/$total" else if (total > 0) "$total" else ""
        if (inv != null) {
            val m = repo.scope(inv)
            val n = m.count { repo.countOf(inv, it) != null }
            cbar.visibility = View.VISIBLE
            cbarText.text = "📋 ${inv.name} - ${inv.magasin.ifEmpty { "tous les magasins" }} - $n/${m.size} comptées"
            cbarAll.text = if (showAll) "Périmètre" else "Tout voir"
        } else cbar.visibility = View.GONE
        // badge : nom en comptage, "en pause" si un inventaire est ouvert sans etre compte, rien sinon
        val anyOpen = repo.inventories.any { it.open }
        invChip.text = if (inv != null) inv.name + " ▾" else "Inventaire en pause ▾"
        invChip.visibility = if (inv != null || anyOpen) View.VISIBLE else View.GONE
        listBox.removeAllViews()
        val n = minOf(shown, view.size)
        for (k in 0 until n) listBox.addView(cardView(view[k]), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (view.isEmpty()) listBox.addView(tv(
            if (total == 0) "Aucun article. Touchez + pour en créer un." else if (inv != null && !filtering) "Aucune ligne dans le périmètre de l'inventaire." else "Aucun résultat",
            14f, GREY, false).apply { setPadding(dp(6), dp(12), 0, 0) })
        if (view.size > n) listBox.addView(btn("Afficher ${minOf(PAGE, view.size - n)} de plus (reste ${view.size - n})", "sec") { shown += PAGE; renderArticles() }, topMargin(8))
    }

    // ---- tri : fenetre de choix (toucher le critere actif = inverser asc/desc)
    private fun showSortDialog() {
        val items = Logic.SORT_ORDER.map { k ->
            val i = sorts.indexOfFirst { it.k == k }
            (if (i >= 0) "✓ " else "") + Logic.SORT_LABELS.getValue(k) + (if (i >= 0) (if (sorts[i].d > 0) "  ▲" else "  ▼") + (if (sorts.size > 1) "  (${i + 1})" else "") else "")
        } + listOf("+ Ajouter un critère", "Réinitialiser (derniers modifiés)")
        choiceDialog("Trier par", items) { which ->
            when {
                which < Logic.SORT_ORDER.size -> {
                    val k = Logic.SORT_ORDER[which]
                    val i = sorts.indexOfFirst { it.k == k }
                    if (i >= 0) sorts[i] = sorts[i].copy(d = -sorts[i].d)
                    else { sorts.clear(); sorts.add(Sort(k, if (k == "date" || k == "qty") -1 else 1)) }
                }
                which == Logic.SORT_ORDER.size -> {
                    val free = Logic.SORT_ORDER.filter { k -> sorts.none { it.k == k } }
                    if (free.isEmpty()) { infoDialog("Tri", "Tous les critères sont déjà utilisés.", "inf"); return@choiceDialog }
                    choiceDialog("Ajouter un critère", free.map { Logic.SORT_LABELS.getValue(it) }) { w ->
                        sorts.add(Sort(free[w], if (free[w] == "date" || free[w] == "qty") -1 else 1)); shown = PAGE; renderArticles()
                    }
                    return@choiceDialog
                }
                else -> { sorts.clear(); sorts.add(Sort("date", -1)) }
            }
            shown = PAGE; renderArticles()
        }
    }

    // ------------------------------------------------------------------ fenetres de l'application

    private fun cardDialog(content: View): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = ScrollView(this).apply { background = round(Ui.DIALOG_BG, 22); setPadding(dp(16), dp(16), dp(16), dp(16)); addView(content) }
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return d
    }

    private fun pillBtn(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(WHITE); textSize = 15f; stateListAnimator = null
        background = round(color, 22); setPadding(dp(24), dp(10), dp(24), dp(10)); minHeight = dp(46); minimumHeight = dp(46)
        setOnClickListener { onClick() }
    }

    private fun dlgButtons(a: Button, b: Button?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        addView(a); if (b != null) addView(b, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
    }

    private fun circleBtn(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 24f; setTextColor(color)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(2), color) }
        setOnClickListener { onClick() }
    }

    /** Fenetre d'information (remplace les alertes systeme) : kind inf / warn / ok. */
    private fun infoDialog(heading: String, text: String, kind: String = "inf", onClose: (() -> Unit)? = null) {
        val col = vbox()
        val (sym, color) = when (kind) { "warn" -> "⚠" to Ui.ORANGE; "ok" -> "✓" to Ui.GREEN; "err" -> "!" to Ui.RED; else -> "i" to 0xFF1B6EF3.toInt() }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(sym, 20f, WHITE, true).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(tv(heading, 18f, INK, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(head)
        col.addView(tv(text, 14f, SUB, false), topMargin(12))
        val dlg = cardDialog(col)
        col.addView(dlgButtons(pillBtn("OK", Ui.NAVY) { dlg.dismiss(); onClose?.invoke() }, null), topMargin(16))
        dlg.show()
    }

    private fun confirmDialog(heading: String, text: String, okLabel: String, okColor: Int = Ui.GREEN, onOk: () -> Unit) {
        val col = vbox()
        col.addView(tv(heading, 18f, INK, true))
        col.addView(tv(text, 14f, SUB, false), topMargin(10))
        val dlg = cardDialog(col)
        col.addView(dlgButtons(pillBtn(okLabel, okColor) { dlg.dismiss(); onOk() }, pillBtn("Annuler", Ui.SLATE) { dlg.dismiss() }), topMargin(18))
        dlg.show()
    }

    private fun choiceDialog(heading: String, items: List<String>, onPick: (Int) -> Unit) {
        val col = vbox()
        col.addView(tv(heading, 18f, INK, true))
        val dlg = cardDialog(col)
        items.forEachIndexed { i, label ->
            col.addView(tv(label, 16f, INK, false).apply {
                background = round(WHITE, 14); setPadding(dp(14), dp(13), dp(14), dp(13)); setOnClickListener { dlg.dismiss(); onPick(i) }
            }, topMargin(8))
        }
        col.addView(dlgButtons(pillBtn("Annuler", Ui.SLATE) { dlg.dismiss() }, null), topMargin(14))
        dlg.show()
    }

    private fun dlgHead(p: Product, sub: String?, thumb: View): LinearLayout {
        val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        h.addView(thumb, LinearLayout.LayoutParams(dp(68), dp(68)))
        val t = vbox().apply { setPadding(dp(12), 0, 0, 0) }
        t.addView(tv(p.nom.ifEmpty { p.code }, 18f, INK, true))
        if (p.nom.isNotEmpty()) t.addView(tv(p.code, 12.5f, SUB, false))
        if (!sub.isNullOrEmpty()) t.addView(tv(sub, 12.5f, SUB, false))
        h.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        return h
    }

    private fun placeOf(l: StockLine) = listOf(l.magasin, l.emplacement).filter { it.isNotEmpty() }.joinToString(" / ")

    /** Champ de quantite avec boutons − / + (maquette). */
    private fun stepper(qty: EditText): LinearLayout {
        qty.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(INK); background = null; setSingleLine(true)
        }
        fun step(delta: Double) { val v = Logic.normQty(qty.text.toString()) ?: 0.0; qty.setText(Logic.fmtQty(maxOf(0.0, v + delta))) }
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(0, 14, 0xFF8A93A6.toInt()); setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        s.addView(circleBtn("−", Ui.RED) { step(-1.0) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        s.addView(qty, LinearLayout.LayoutParams(0, -2, 1f))
        s.addView(circleBtn("+", Ui.GREEN) { step(1.0) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        return s
    }

    /** Champs de la fiche produit + magasin / emplacement, avec suggestions, pour les fenetres de modification. */
    private class DlgFields(val nom: EditText, val ref: EditText, val aff: AutoCompleteTextView, val marque: AutoCompleteTextView,
                            val mag: AutoCompleteTextView, val emp: AutoCompleteTextView)

    private fun addDlgFields(col: LinearLayout, c: Card, lu: Map<String, String>?, dbg: Int): DlgFields {
        val nom = outlined(edit()); val ref = outlined(edit())
        val aff = outlined(acEdit()) as AutoCompleteTextView; val marque = outlined(acEdit()) as AutoCompleteTextView
        val mag = outlined(acEdit()) as AutoCompleteTextView; val emp = outlined(acEdit()) as AutoCompleteTextView
        val base = mapOf("nom" to c.p.nom, "ref" to c.p.ref, "affectation" to c.p.affectation, "marque" to c.p.marque)
        val specs = listOf(Triple("nom", "Nom", nom), Triple("ref", "Référence fournisseur", ref), Triple("affectation", "Affectation", aff), Triple("marque", "Marque", marque))
        for ((k, label, e) in specs) {
            val l = lu?.get(k).orEmpty()
            val changed = l.isNotEmpty() && l != base[k]
            e.setText(if (changed) l else base[k])
            if (changed) e.background = round(0, 12, 0xFFE0A800.toInt())
            col.addView(fld(label + (if (changed) " (modifié)" else ""), e, if (changed) 0xFFFFE9A0.toInt() else dbg), topMargin(0))
        }
        mag.setText(c.l.magasin); emp.setText(c.l.emplacement)
        refreshSuggestions(mag, emp, marque, aff)
        col.addView(fld("Magasin", mag, dbg), topMargin(0))
        col.addView(fld("Emplacement", emp, dbg), topMargin(0))
        return DlgFields(nom, ref, aff, marque, mag, emp)
    }

    private fun showEditDialog(c: Card) {
        val dbg = Ui.DIALOG_BG
        val col = vbox()
        val thumb = thumbHost(68)
        val box = PhotoBox(this) { pickPhoto(it) }
        box.load(Photos.fileIfExists(this, c.p.code))
        fillThumb(thumb, box.bitmap)
        col.addView(dlgHead(c.p, null, thumb))
        col.addView(tv("Modification  (touchez la vignette pour la photo)", 14f, SUB, false), topMargin(8))
        val holder = FrameLayout(this).apply { visibility = View.GONE }
        holder.addView(box.view)
        col.addView(holder)
        thumb.setOnClickListener { holder.visibility = if (holder.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        box.onChange = { fillThumb(thumb, box.bitmap) }

        val qty = EditText(this).apply { setText(Logic.fmtQty(c.l.qty)) }
        col.addView(fld("Quantité", stepper(qty), dbg), topMargin(12))
        val codeEt = outlined(edit()).apply { setText(c.p.code) }
        col.addView(fld("Code article", codeEt, dbg), topMargin(0))
        val altsTv = tv("", 12.5f, SUB, false)
        fun showAlts() { altsTv.text = if (c.p.alts.isEmpty()) "Aucun autre code." else "Autres codes reconnus : " + c.p.alts.joinToString(", ") }
        showAlts()
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(pill("Scanner le code", Ui.NAVY) { scanOne { codeEt.setText(it) } })
        tools.addView(pill("Autre code", Ui.GREEN) {
            scanOne { t ->
                val err = repo.addAlt(c.p, t)
                if (err != null) toast(err) else { save(); showAlts(); toast("Code $t ajouté aux autres codes.") }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        tools.addView(pill("Code 200…", Ui.ORANGE) { codeEt.setText(Logic.nextCode200(repo.allCodes())) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        col.addView(tools, topMargin(2))
        col.addView(altsTv, topMargin(4))
        col.addView(tv("Si vous changez le code, l'ancien reste reconnu au scan et la photo suit. « Autre code » : scanner une autre étiquette du même article.", 12f, GREY, false), topMargin(4))
        val f = addDlgFields(col, c, null, dbg)
        col.addView(tv("Nom, référence, affectation et marque sont ceux du produit : ils changent pour toutes ses lignes de stock.", 12f, GREY, false), topMargin(8))

        val dlg = cardDialog(col)
        dlg.setOnDismissListener { if (activeBox === box) activeBox = null }
        col.addView(dlgButtons(
            pillBtn("Enregistrer", Ui.GREEN) {
                val q = Logic.normQty(qty.text.toString())
                if (q == null) { toast("Quantité invalide"); return@pillBtn }
                repo.codeConflict(c.p, codeEt.text.toString())?.let { toast(it); return@pillBtn }
                if (!repo.updateLine(c.l, f.mag.text.toString(), f.emp.text.toString(), q)) { toast("Cette ligne existe déjà pour ce produit."); return@pillBtn }
                repo.changeCode(c.p, codeEt.text.toString())
                repo.upsertProduct(c.p.code, f.ref.text.toString(), f.nom.text.toString(), f.aff.text.toString(), f.marque.text.toString())
                if (box.changed) { val b = box.bitmap; if (b != null) Photos.save(this, c.p.code, b) else Photos.delete(this, c.p.code) }
                dlg.dismiss(); save(); msg("Ligne modifiée.", "ok")
            },
            pillBtn("Annuler", Ui.SLATE) { dlg.dismiss() }
        ), topMargin(18))
        dlg.show()
    }

    /** Code deja connu a ce magasin / emplacement (scan, etiquette ou saisie) : « quantite a ajouter ou a remplacer ? » */
    private fun showDupDialog(c: Card, lu: Map<String, String>?, qtyStr: String, src: String) {
        val dbg = Ui.DIALOG_BG
        val col = vbox()
        col.addView(dlgHead(c.p, placeOf(c.l), thumbFor(c.p.code, 68)))
        col.addView(tv("Code déjà dans la liste à cet emplacement ($src).", 13f, INK, false).apply {
            background = round(0xFFFFF3CD.toInt(), 10); setPadding(dp(10), dp(8), dp(10), dp(8))
        }, topMargin(10))
        col.addView(tv("Quantité actuelle : ${Logic.fmtQty(c.l.qty)}", 16f, INK, true), topMargin(12))
        col.addView(tv("Quantité à ajouter ou à remplacer ?", 15f, INK, true), topMargin(10))

        val qty = EditText(this).apply { setText(qtyStr.ifBlank { "1" }) }
        col.addView(fld("Quantité lue / comptée", stepper(qty), dbg), topMargin(6))

        var mode = "add"
        val titleAdd = tv("", 16f, INK, true); val addSub = tv("", 13f, SUB, false)
        val titleRep = tv("", 16f, INK, true); val repSub = tv("", 13f, SUB, false)
        val optAdd = vbox().apply { setPadding(dp(12), dp(10), dp(12), dp(10)); addView(titleAdd); addView(addSub) }
        val optRep = vbox().apply { setPadding(dp(12), dp(10), dp(12), dp(10)); addView(titleRep); addView(repSub) }
        fun refresh() {
            val cur = c.l.qty; val v = Logic.normQty(qty.text.toString()) ?: 0.0
            addSub.text = "Valeur actuelle ${Logic.fmtQty(cur)} + ${Logic.fmtQty(v)} = ${Logic.fmtQty(Logic.addQty(cur, v))}"
            repSub.text = "Valeur actuelle ${Logic.fmtQty(cur)} devient ${Logic.fmtQty(v)}"
            titleAdd.text = (if (mode == "add") "◉  " else "○  ") + "Additionner"
            titleRep.text = (if (mode == "rep") "◉  " else "○  ") + "Remplacer"
            optAdd.background = round(if (mode == "add") 0xFFEAF6EB.toInt() else WHITE, 14, if (mode == "add") Ui.GREEN else 0xFFC9CFDB.toInt())
            optRep.background = round(if (mode == "rep") 0xFFEAF6EB.toInt() else WHITE, 14, if (mode == "rep") Ui.GREEN else 0xFFC9CFDB.toInt())
        }
        optAdd.setOnClickListener { mode = "add"; refresh() }
        optRep.setOnClickListener { mode = "rep"; refresh() }
        qty.addTextChangedListener(simpleWatcher { refresh() })
        refresh()
        col.addView(optAdd, topMargin(8))
        col.addView(optRep, topMargin(8))
        val f = addDlgFields(col, c, lu, dbg)

        val dlg = cardDialog(col)
        col.addView(dlgButtons(
            pillBtn("Enregistrer", Ui.GREEN) {
                val v = Logic.normQty(qty.text.toString())
                if (v == null) { toast("Quantité invalide"); return@pillBtn }
                val nq = if (mode == "add") Logic.addQty(c.l.qty, v) else v
                if (!repo.updateLine(c.l, f.mag.text.toString(), f.emp.text.toString(), nq)) { toast("Cette ligne existe déjà pour ce produit."); return@pillBtn }
                repo.upsertProduct(c.p.code, f.ref.text.toString(), f.nom.text.toString(), f.aff.text.toString(), f.marque.text.toString())
                dlg.dismiss(); closeSheet(); clearForm(); save()
                msg(if (mode == "add") "Quantité additionnée." else "Quantité remplacée.", "ok")
            },
            pillBtn("Annuler", Ui.SLATE) { dlg.dismiss() }
        ), topMargin(18))
        dlg.show()
    }

    private fun onDelete(c: Card) {
        val col = vbox()
        col.addView(dlgHead(c.p, placeOf(c.l), thumbFor(c.p.code, 68)))
        col.addView(tv("Suppression", 15f, Ui.RED, true), topMargin(8))
        val last = repo.linesOf(c.p.id).size <= 1
        col.addView(tv("Supprimer cette ligne (quantité ${Logic.fmtQty(c.l.qty)}) ?" +
            (if (last) "\nC'est la dernière ligne de ce produit : sa fiche et sa photo seront aussi supprimées." else "") +
            "\nCette action est définitive.", 14f, SUB, false), topMargin(10))
        val dlg = cardDialog(col)
        col.addView(dlgButtons(
            pillBtn("Supprimer", Ui.RED) { dlg.dismiss(); repo.deleteLine(c.l); save() },
            pillBtn("Annuler", Ui.SLATE) { dlg.dismiss() }
        ), topMargin(18))
        dlg.show()
    }

    // ---- comptage d'une ligne pendant un inventaire
    private fun showCountDialog(c: Card) {
        val inv = curInv() ?: return
        val dbg = Ui.DIALOG_BG
        val col = vbox()
        col.addView(dlgHead(c.p, placeOf(c.l), thumbFor(c.p.code, 68)))
        col.addView(tv("Comptage - ${inv.name}", 14f, SUB, false), topMargin(8))
        col.addView(tv("Stock attendu : ${Logic.fmtQty(c.l.qty)}", 16f, INK, true), topMargin(6))
        val qty = EditText(this).apply { repo.countOf(inv, c.l)?.let { setText(Logic.fmtQty(it.qty)) } }
        col.addView(fld("Quantité comptée", stepper(qty), dbg), topMargin(10))
        val diff = tv("", 14f, SUB, false)
        fun refresh() {
            val t = qty.text.toString().trim()
            val v = Logic.normQty(t)
            diff.text = when {
                t.isEmpty() -> "Pas encore compté."
                v == null -> "Quantité invalide."
                v == c.l.qty -> "Conforme au stock attendu."
                else -> "Écart : ${(if (v > c.l.qty) "+" else "")}${Logic.fmtQty(Logic.addQty(v, -c.l.qty))} (attendu ${Logic.fmtQty(c.l.qty)}, compté ${Logic.fmtQty(v)})"
            }
        }
        qty.addTextChangedListener(simpleWatcher { refresh() })
        refresh()
        col.addView(diff, topMargin(4))
        val dlg = cardDialog(col)
        col.addView(dlgButtons(
            pillBtn("Enregistrer le comptage", Ui.GREEN) {
                val t = qty.text.toString().trim()
                if (t.isEmpty()) repo.clearCount(inv, c.l)
                else {
                    val v = Logic.normQty(t)
                    if (v == null) { toast("Quantité invalide"); return@pillBtn }
                    repo.setCount(inv, c.l, v)
                }
                dlg.dismiss(); save()
            },
            pillBtn("Annuler", Ui.SLATE) { dlg.dismiss() }
        ), topMargin(18))
        dlg.show()
    }

    // ---- choix de la photo d'un article (appareil photo ou galerie)
    @Suppress("DEPRECATION")
    private fun pickPhoto(box: PhotoBox) {
        activeBox = box
        choiceDialog("Photo de l'article", listOf("Prendre une photo", "Choisir dans la galerie")) { which ->
            if (which == 0) openCamera(RC_PHOTO_ART)
            else {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
                try { startActivityForResult(i, RC_GALLERY) } catch (e: Exception) { msg("Aucune galerie disponible.", "err") }
            }
        }
    }

    // ------------------------------------------------------------------ ecran Inventaire

    private fun buildInventory(): View {
        val scroll = ScrollView(this)
        val col = vbox().apply { setPadding(dp(10), dp(8), dp(10), dp(16)) }
        scroll.addView(col)
        col.addView(tv("Un inventaire = une session de comptage sur un magasin / une zone. Le stock ne change qu'à la fin (bouton Terminer).", 13f, GREY, false))
        col.addView(btn("+ Nouvel inventaire", "pri") { showNewInventoryDialog() }, topMargin(10))
        invList = vbox()
        col.addView(invList, topMargin(6))
        return scroll
    }

    private fun renderInventories() {
        invList.removeAllViews()
        val open = repo.inventories.filter { it.open }.sortedByDescending { it.created }
        val done = repo.inventories.filter { !it.open }.sortedByDescending { it.ended }
        fun section(label: String, list: List<Inventory>, empty: String) {
            invList.addView(tv(label, 12f, GREY, true), topMargin(14))
            if (list.isEmpty()) invList.addView(tv(empty, 14f, GREY, false), topMargin(6))
            for (inv in list) invList.addView(inventoryCard(inv), topMargin(8))
        }
        section("Sessions ouvertes", open, "Aucune session ouverte.")
        section("Terminées", done, "Aucune.")
    }

    private fun scopeText(inv: Inventory) = (inv.magasin.ifEmpty { "Tous les magasins" }) + (if (inv.zone.isNotEmpty()) " - zone ${inv.zone}" else "")

    private fun inventoryCard(inv: Inventory): View {
        val card = vbox().apply { background = round(WHITE, 16); setPadding(dp(14), dp(12), dp(14), dp(12)); setOnClickListener { showInventoryActions(inv) } }
        if (inv.open && inv.id == store.currentInv) card.addView(tv("En cours de comptage", 11f, Ui.NAVY, true).apply {
            background = round(Ui.YELLOW, 10); setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        card.addView(tv(inv.name, 17f, INK, true))
        card.addView(tv(scopeText(inv), 13f, SUB, false))
        val counted = if (inv.open) repo.scope(inv).let { m -> "${m.count { repo.countOf(inv, it) != null }} / ${m.size} lignes comptées" }
        else "${repo.counts.count { it.inventoryId == inv.id }} lignes comptées"
        card.addView(tv((if (inv.open) "depuis le ${Logic.formatDate(inv.created)}" else "du ${Logic.formatDate(inv.created)} au ${Logic.formatDate(inv.ended)}") + " - $counted", 12.5f, GREY, false))
        return card
    }

    private fun showNewInventoryDialog() {
        val dbg = Ui.DIALOG_BG
        val col = vbox()
        col.addView(tv("Nouvel inventaire", 18f, INK, true))
        val nom = outlined(edit())
        val mag = outlined(acEdit()) as AutoCompleteTextView
        val zone = outlined(acEdit()) as AutoCompleteTextView
        mag.hint = "Tous les magasins"; zone.hint = "Toutes les zones"
        setSuggestions(mag, repo.distinctMagasins()); setSuggestions(zone, repo.distinctEmplacements())
        col.addView(fld("Nom", nom, dbg), topMargin(10))
        col.addView(fld("Magasin", mag, dbg), topMargin(0))
        col.addView(fld("Zone (début d'emplacement)", zone, dbg), topMargin(0))
        col.addView(tv("Laissez vide pour compter tous les magasins / toutes les zones.", 12f, GREY, false), topMargin(4))
        val dlg = cardDialog(col)
        col.addView(dlgButtons(
            pillBtn("Créer", Ui.GREEN) {
                val name = nom.text.toString().ifBlank { "Inventaire ${Logic.formatDate(Logic.nowIso())}" }
                val inv = repo.createInventory(name, mag.text.toString(), zone.text.toString())
                store.currentInv = inv.id; showAll = false
                dlg.dismiss(); save(); showPane("art")
                msg("Inventaire « ${inv.name} » créé : comptez les lignes du périmètre, puis Terminer.", "ok")
            },
            pillBtn("Annuler", Ui.SLATE) { dlg.dismiss() }
        ), topMargin(18))
        dlg.show()
    }

    /** Sortir du mode comptage sans terminer : l'inventaire reste ouvert, comptages conserves. */
    private fun showLeaveCountDialog(inv: Inventory) {
        confirmDialog("Mettre en pause", "L'inventaire « ${inv.name} » reste ouvert et vos comptages sont conservés. Vous pourrez le reprendre par Inventaire > Ouvrir / compter.\nLe stock n'est pas modifié.", "Mettre en pause", Ui.NAVY) {
            store.currentInv = ""; showAll = false; shown = PAGE
            save()
        }
    }

    private fun showInventoryActions(inv: Inventory) {
        val col = vbox()
        col.addView(tv(inv.name, 18f, INK, true))
        col.addView(tv(scopeText(inv) + " - " + (if (inv.open) "ouvert" else "terminé"), 13f, SUB, false))
        val dlg = cardDialog(col)
        fun act(label: String, color: Int, f: () -> Unit) = col.addView(pillBtn(label, color) { dlg.dismiss(); f() }, topMargin(10))
        act("Ouvrir / compter", Ui.GREEN) {
            if (!inv.open) infoDialog("Inventaire terminé", "Cet inventaire est terminé : il n'est plus modifiable. Vous pouvez consulter son rapport des écarts ou l'exporter.", "inf")
            else { store.currentInv = inv.id; showAll = false; save(); showPane("art") }
        }
        act("Rapport des écarts", Ui.NAVY) { showReport(inv) }
        if (inv.open) act("Terminer (appliquer au stock)", Ui.ORANGE) { showFinishDialog(inv) }
        act("Exporter", Ui.NAVY) { onExportInventory(inv) }
        act("Supprimer", Ui.RED) {
            confirmDialog("Supprimer l'inventaire", "Supprimer « ${inv.name} » et ses comptages ?\nLe stock n'est pas modifié.", "Supprimer", Ui.RED) {
                repo.deleteInventory(inv)
                if (store.currentInv == inv.id) store.currentInv = ""
                save()
            }
        }
        col.addView(dlgButtons(pillBtn("Fermer", Ui.SLATE) { dlg.dismiss() }, null), topMargin(14))
        dlg.show()
    }

    private fun showReport(inv: Inventory) {
        val rows = repo.counts.filter { it.inventoryId == inv.id }.mapNotNull { cn ->
            val l = repo.lineById(cn.lineId) ?: return@mapNotNull null
            val p = repo.productById(l.productId) ?: return@mapNotNull null
            val exp = if (inv.open || cn.expected < 0) l.qty else cn.expected
            Triple(p, l, cn.qty to exp)
        }
        val diffs = rows.filter { it.third.first != it.third.second }
        val sb = StringBuilder(inv.name + "\n" + scopeText(inv) + "\n\n")
        sb.append("${rows.size} ligne(s) comptée(s)")
        if (inv.open) { val m = repo.scope(inv); sb.append(" sur ${m.size} (${m.size - rows.size} non comptée(s))") }
        sb.append(", ${diffs.size} écart(s).")
        if (diffs.isNotEmpty()) sb.append("\n")
        for ((p, l, q) in diffs.sortedBy { Logic.foldText(it.first.nom.ifEmpty { it.first.code }) }) {
            val d = Logic.addQty(q.first, -q.second)
            sb.append("\n").append(p.nom.ifEmpty { p.code }).append(" (").append(placeOf(l).ifEmpty { "sans emplacement" }).append(") : attendu ")
                .append(Logic.fmtQty(q.second)).append(", compté ").append(Logic.fmtQty(q.first)).append(", écart ")
                .append(if (d > 0) "+" else "").append(Logic.fmtQty(d))
        }
        infoDialog("Rapport des écarts", sb.toString(), "inf")
    }

    private fun showFinishDialog(inv: Inventory) {
        val r = repo.previewFinish(inv)
        confirmDialog("Terminer l'inventaire",
            "${inv.name}\n${r.applied + r.unchanged} ligne(s) comptée(s) sur ${r.applied + r.unchanged + r.notCounted}.\n" +
                "${r.applied} écart(s) : le stock prendra la quantité comptée.\n" +
                "${r.notCounted} ligne(s) non comptée(s) : stock inchangé.\n\nL'inventaire ne sera plus modifiable.",
            "Appliquer au stock") {
            val res = repo.finishInventory(inv)
            if (store.currentInv == inv.id) store.currentInv = ""
            showAll = false
            store.logAction("Inventaire terminé", "${inv.name} : ${res.applied} écart(s) appliqué(s), ${res.notCounted} non comptée(s)")
            save()
            infoDialog("Inventaire terminé", "Le stock a été mis à jour : ${res.applied} ligne(s) modifiée(s), ${res.unchanged} conforme(s), ${res.notCounted} non comptée(s) (inchangées).", "ok")
        }
    }

    // ------------------------------------------------------------------ ecran Outils

    private fun buildOutils(): View {
        val scroll = ScrollView(this)
        val col = vbox().apply { setPadding(dp(10), 0, dp(10), dp(16)) }
        scroll.addView(col)
        val card = card()
        lastExportTv = tv("", 14f, 0xFF145A1F.toInt(), false)
        card.addView(row(btn("💾 Sauvegarder", "pri") { onBackup() }, btn("♻ Restaurer", "sec") { onPickRestore() }))
        card.addView(tv("Copie complète de l'application dans un seul fichier zip : produits, stock, inventaires et photos. La restauration remplace tout, après une sauvegarde automatique de l'état actuel. Vous pouvez la garder dans l'application (supprimable) ou l'envoyer ailleurs.", 12f, GREY, false), topMargin(6))
        card.addView(lastExportTv, topMargin(14))
        card.addView(row(btn("Export Excel", "pri") { onExportXlsx() }.withIcon(R.drawable.ic_xls_up), btn("Import Excel", "sec") { onPickImport() }.withIcon(R.drawable.ic_xls_down)), topMargin(8))
        card.addView(btn("➦ Partager", "pri") { onShareCsv() }, topMargin(8))
        card.addView(tv("Export / Import portent sur le stock seulement, sans les photos. Partager envoie le stock seul (csv) ou avec les photos (zip) ; l'export d'un inventaire se fait depuis l'écran Inventaire. Fichier ou zip reçu par e-mail : « Partager » vers Scancounter, puis choix de l'intégration (remplacer, fusionner, additionner…).", 12f, GREY, false), topMargin(6))
        card.addView(btn("↺ Quantités à 0", "sec") { onZero() }, topMargin(14))
        card.addView(btn("🕘 Dernières actions", "sec") { showLog() }, topMargin(8))
        card.addView(btn("🗑 Tout effacer", "danger") { onClear() }, topMargin(8))

        impBox = vbox().apply { background = round(0xFFFFF3CD.toInt(), 10); setPadding(dp(10), dp(10), dp(10), dp(10)); visibility = View.GONE }
        impText = tv("", 14f, INK, false)
        impBox.addView(impText)
        impBox.addView(tv("Quantités des lignes déjà présentes :", 14f, INK, true), topMargin(6))
        val rgQty = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        rbQtyKeep = RadioButton(this).apply { id = View.generateViewId(); text = "Garder l'actuelle"; textSize = 14f; isChecked = true }
        rbQtyReplace = RadioButton(this).apply { id = View.generateViewId(); text = "Remplacer par celle du fichier"; textSize = 14f }
        rbQtyAdd = RadioButton(this).apply { id = View.generateViewId(); text = "Additionner (actuelle + fichier)"; textSize = 14f }
        rgQty.addView(rbQtyKeep); rgQty.addView(rbQtyReplace); rgQty.addView(rbQtyAdd)
        impBox.addView(rgQty)
        impBox.addView(tv("Informations du produit :", 14f, INK, true), topMargin(6))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        rbReplace = RadioButton(this).apply { id = View.generateViewId(); text = "Remplacer les libellés (les champs vides du fichier effacent aussi)"; textSize = 14f; isChecked = true }
        rbComplete = RadioButton(this).apply { id = View.generateViewId(); text = "Compléter les infos manquantes"; textSize = 14f }
        rg.addView(rbReplace); rg.addView(rbComplete)
        impBox.addView(rg)
        cbPhotos = CheckBox(this).apply { text = "Remplacer aussi les photos déjà présentes (sinon seules les photos manquantes sont ajoutées)"; textSize = 14f; visibility = View.GONE }
        impBox.addView(cbPhotos, topMargin(6))
        rgQty.setOnCheckedChangeListener { _, _ -> refreshImpPreview() }
        rg.setOnCheckedChangeListener { _, _ -> refreshImpPreview() }
        impBox.addView(row(btn("Ajouter au stock", "pri") { applyImport(false) }, btn("Remplacer le stock", "sec") { applyImport(true) }), topMargin(8))
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
    private fun card() = vbox().apply { background = round(WHITE, 12); setPadding(dp(12), dp(12), dp(12), dp(12)); elevation = dp(2).toFloat() }
    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    private fun topMargin(dpv: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(dpv) }
    private fun edit() = EditText(this).apply {
        setSingleLine(true); textSize = 17f; setTextColor(INK)
        background = round(WHITE, 10, 0xFFB8C0CC.toInt()); setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private fun btn(text: String, kind: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8)); textSize = 15f
        val (bg, fg) = when (kind) { "sec" -> 0xFFE3E8EF.toInt() to INK; "danger" -> 0xFFC62828.toInt() to WHITE; else -> Ui.NAVY to WHITE }
        background = round(bg, 10); setTextColor(fg)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }
    /** Icone a gauche du texte du bouton. */
    private fun Button.withIcon(res: Int): Button {
        val d = getDrawable(res)?.apply { setBounds(0, 0, dp(28), dp(28)) }
        setCompoundDrawables(d, null, null, null); compoundDrawablePadding = dp(8)
        return this
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

    /** Bandeau court dans l'application ; actionLabel + onAction : lien d'action dans le bandeau. */
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

    // ------------------------------------------------------------------ affichage

    private fun save() { repo.save(); renderAll() }

    private fun renderAll() {
        renderArticles()
        if (currentPane == "inv") renderInventories()
        lastExportTv.text = if (lastExport.isNotEmpty()) "Dernier export : " + Logic.fmtFull(lastExport) else "Jamais exporté"
        lastExportTv.setTextColor(if (lastExport.isNotEmpty()) 0xFF145A1F.toInt() else 0xFF8A1111.toInt())
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

    private fun onEtiquette(fromMain: Boolean) {
        if (ocrBusy) { msg("Lecture déjà en cours.", "info"); return }
        labelFromMain = fromMain
        openCamera(RC_PHOTO_LABEL)
    }

    /** Magasin suppose pour une etiquette (qui n'en porte pas) : celui de l'inventaire, sinon l'unique magasin du produit. */
    private fun guessMagasin(p: Product): String {
        curInv()?.let { if (it.magasin.isNotEmpty()) return it.magasin }
        val mags = repo.linesOf(p.id).map { it.magasin }.filter { it.isNotEmpty() }.distinct()
        return if (mags.size == 1) mags[0] else ""
    }

    /** Ligne choisie (scan, liste ou etiquette) : comptage, hors perimetre, ou « ajouter / remplacer ». */
    private fun chooseLine(p: Product, l: StockLine, lu: Map<String, String>?, src: String) {
        val inv = curInv()
        val c = Card(l, p)
        when {
            inv != null && !inv.covers(l) -> infoDialog("Hors périmètre", "Ce produit est à ${placeOf(l).ifEmpty { "un emplacement sans nom" }}, en dehors du périmètre de l'inventaire en cours. Il ne peut pas être compté ici.", "warn")
            inv != null -> showCountDialog(c)
            else -> showDupDialog(c, lu, "1", src)
        }
    }

    /** Produit connu : une seule ligne = directement ; plusieurs = choix de la ligne ou « + Nouvel emplacement ». */
    private fun pickLine(p: Product, lu: Map<String, String>?, src: String) {
        val ls = repo.linesOf(p.id)
        if (ls.size == 1) { chooseLine(p, ls[0], lu, src); return }
        val inv = curInv()
        val items = ls.map { l -> placeOf(l).ifEmpty { "sans emplacement" } + " - " + (if (inv != null && inv.covers(l)) "attendu " else "stock ") + Logic.fmtQty(l.qty) } + "+ Nouvel emplacement"
        choiceDialog("${p.nom.ifEmpty { p.code }} : quelle ligne de stock ?", items) { which ->
            if (which < ls.size) chooseLine(p, ls[which], lu, src)
            else { openSheet(p, guessMagasin(p), ""); etEmp.requestFocus() }
        }
    }

    /** Etiquette sur un produit connu : ligne a l'emplacement lu si elle existe, sinon proposition d'une nouvelle ligne (fiche conservee). */
    private fun onKnownLabel(p: Product, lu: Map<String, String>) {
        msg("", "")
        val empl = lu["emplacement"].orEmpty()
        if (empl.isEmpty()) { pickLine(p, lu, "étiquette"); return }
        val mag = guessMagasin(p)
        val ls = repo.linesOf(p.id)
        val line = repo.findLine(p.id, mag, empl) ?: ls.firstOrNull { it.emplacement.equals(empl, true) }
        if (line != null) { chooseLine(p, line, lu, "étiquette"); return }
        openSheet(p, mag, empl)
        val diffs = listOf("nom" to "nom", "ref" to "référence", "marque" to "marque", "affectation" to "affectation")
            .filter { (k, _) -> lu[k].orEmpty().isNotEmpty() && lu[k] != when (k) { "nom" -> p.nom; "ref" -> p.ref; "marque" -> p.marque; else -> p.affectation } }
            .map { it.second }
        msg("Produit connu : nouvelle ligne à l'emplacement $empl. Fiche conservée" +
            (if (diffs.isNotEmpty()) " (l'étiquette diffère : ${diffs.joinToString(", ")})" else "") + ". Vérifiez le magasin et saisissez la quantité.", "info")
        etQty.requestFocus()
    }

    /** Scanner en direct : lecteur de codes Google (ML Kit), sans autorisation camera propre a l'application. */
    private fun onLiveScan(fromMain: Boolean) {
        msg("Ouverture du scanner...", "info")
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { b ->
                val t = Logic.normCode(b.rawValue)
                if (t.isEmpty()) { msg("Code vide : réessayez ou saisissez-le.", "err"); return@addOnSuccessListener }
                val p = repo.productByCode(t)
                if (p != null && (fromMain || !sheetOpen())) { msg("", ""); pickLine(p, null, "scan"); return@addOnSuccessListener }
                if (p == null && !sheetOpen()) { msg("", ""); showUnknownCodeDialog(t); return@addOnSuccessListener }
                if (!sheetOpen()) openSheet()
                setCode(t)
                msg("Code lu : $t. Saisissez la quantité.", "ok")
                etQty.requestFocus()
            }
            .addOnCanceledListener { msg("", "") }
            .addOnFailureListener { e -> msg("Scanner indisponible (${e.localizedMessage ?: "erreur"}) : utilisez Photo ou la saisie.", "err") }
    }

    /** Code scanne inconnu : creer un article, ou le rattacher a un article existant (il devient son code actuel, l'ancien reste reconnu). */
    private fun showUnknownCodeDialog(code: String) {
        choiceDialog("Code inconnu : $code", listOf("Créer un nouvel article avec ce code", "L'ajouter à un article existant")) { which ->
            if (which == 0) { openSheet(); setCode(code); msg("Code lu : $code. Saisissez la quantité.", "ok"); etQty.requestFocus() }
            else showAttachDialog(code)
        }
    }

    private fun showAttachDialog(code: String) {
        val col = vbox()
        col.addView(tv("Ajouter $code à un article", 18f, INK, true))
        val q = outlined(edit()).apply { hint = "Nom, référence ou ancien code"; inputType = InputType.TYPE_CLASS_TEXT }
        col.addView(q, topMargin(10))
        val list = vbox()
        col.addView(list, topMargin(6))
        val dlg = cardDialog(col)
        fun refresh() {
            list.removeAllViews()
            val tok = Logic.searchTokens(q.text.toString())
            val hits = repo.products.filter { p ->
                val s = Logic.foldText((listOf(p.code) + p.alts + listOf(p.ref, p.nom, p.marque, p.affectation)).joinToString(" "))
                tok.all { s.contains(it) }
            }.sortedBy { Logic.foldText(it.nom.ifEmpty { it.code }) }
            if (hits.isEmpty()) list.addView(tv("Aucun article ne correspond.", 14f, SUB, false))
            hits.take(8).forEach { p ->
                list.addView(vbox().apply {
                    background = round(WHITE, 14); setPadding(dp(14), dp(10), dp(14), dp(10))
                    addView(tv(p.nom.ifEmpty { p.code }, 16f, INK, true))
                    addView(tv(p.code + (if (p.ref.isNotEmpty()) " · ${p.ref}" else ""), 12.5f, SUB, false))
                    setOnClickListener {
                        confirmDialog("Rattacher le code",
                            "Le code $code devient le code actuel de « ${p.nom.ifEmpty { p.code }} ». L'ancien code (${p.code}) reste reconnu au scan.", "Rattacher") {
                            val err = repo.changeCode(p, code)
                            if (err != null) { toast(err); return@confirmDialog }
                            save(); dlg.dismiss(); msg("Code rattaché à « ${p.nom.ifEmpty { p.code }} ».", "ok"); pickLine(p, null, "scan")
                        }
                    }
                }, topMargin(6))
            }
            if (hits.size > 8) list.addView(tv("${hits.size - 8} autres : précisez la recherche.", 12.5f, SUB, false), topMargin(6))
        }
        q.addTextChangedListener(simpleWatcher { refresh() })
        col.addView(dlgButtons(pillBtn("Annuler", Ui.SLATE) { dlg.dismiss() }, null), topMargin(14))
        refresh()
        dlg.show()
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

    /** Etiquette : le code-barres imprime (exact, controle) est lu d'abord ; il prime sur le code lu par OCR. */
    private fun readLabel(bitmap: Bitmap) {
        ocrBusy = true
        msg("Lecture de l'étiquette...", "info")
        val scanner = BarcodeScanning.getClient()
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { list -> scanner.close(); readLabelText(bitmap, Logic.normCode(list.firstOrNull()?.rawValue)) }
            .addOnFailureListener { scanner.close(); readLabelText(bitmap, "") }
    }

    private fun readLabelText(bitmap: Bitmap, barcode: String) {
        val fromMain = labelFromMain
        labelFromMain = false
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val parsed = LabelParser.parse(
                    result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.right, it.bottom) }
                    }
                )
                val ap = Logic.applyEtiquette(mapOf(
                    "code" to barcode.ifEmpty { parsed.code }, "ref" to parsed.reference, "nom" to parsed.nom,
                    "affectation" to parsed.affectation, "marque" to parsed.marque, "emplacement" to parsed.emplacement
                ))
                ocrBusy = false
                recognizer.close()
                val code = ap.fields["code"].orEmpty()
                val known = if (code.isNotEmpty()) repo.productByCode(Logic.normCode(code)) else null
                if (known != null) { onKnownLabel(known, ap.fields); return@addOnSuccessListener }
                if (fromMain || !sheetOpen()) openSheet()
                ap.fields.forEach { (k, v) ->
                    when (k) {
                        "code" -> etCode.setText(v); "ref" -> etRef.setText(v); "nom" -> etNom.setText(v)
                        "affectation" -> etAff.setText(v); "marque" -> etMarque.setText(v); "emplacement" -> etEmp.setText(v)
                        else -> {}
                    }
                }
                msg(ap.message, ap.kind)
                (if (ap.focus == "code") etCode else etQty).requestFocus()
            }
            .addOnFailureListener { e ->
                ocrBusy = false
                recognizer.close()
                val p = if (barcode.isNotEmpty()) repo.productByCode(barcode) else null
                if (p != null) { onKnownLabel(p, emptyMap()); return@addOnFailureListener }
                if (fromMain || !sheetOpen()) openSheet()
                if (barcode.isNotEmpty()) { setCode(barcode); msg("Code-barres lu : $barcode. Texte de l'étiquette illisible : complétez les champs, puis saisissez la quantité.", "info"); etQty.requestFocus() }
                else { msg("Lecture de l'étiquette impossible (${e.localizedMessage ?: "erreur"}). Code non lu, saisissez-le à la main.", "err"); etCode.requestFocus() }
            }
    }

    // ------------------------------------------------------------------ export

    private fun markExport() { lastExport = Logic.nowIso(); store.lastExport = lastExport; renderAll() }
    private fun plurLines() = Logic.plural(repo.lines.size, "ligne", "lignes")

    private fun startXlsxExport(bytes: ByteArray, name: String, isStock: Boolean) {
        pendingBytes = bytes; pendingName = name; pendingStockExport = isStock
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = Xlsx.MIME; putExtra(Intent.EXTRA_TITLE, name)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, RC_EXPORT_XLSX)
    }

    /** Exporter : .xlsx du stock a l'emplacement choisi par l'utilisateur (Telechargements...). */
    private fun onExportXlsx() {
        if (repo.lines.isEmpty()) { infoDialog("Export", "Rien à exporter.", "inf"); return }
        val all = repo.cards()
        val cards = Logic.sortedIndexes(all, sorts).map { all[it] }
        val bytes = try { Xlsx.buildStock(Logic.toRows(cards)) } catch (e: Exception) { infoDialog("Export impossible", e.localizedMessage ?: "erreur", "err"); return }
        startXlsxExport(bytes, Logic.exportFileName(Date(), "xlsx"), true)
    }

    private fun onExportInventory(inv: Inventory) {
        val bytes = try { Xlsx.buildInventory(repo, inv) } catch (e: Exception) { infoDialog("Export impossible", e.localizedMessage ?: "erreur", "err"); return }
        val safe = inv.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "inventaire" }
        startXlsxExport(bytes, Logic.exportFileName(Date(), "xlsx", "inventaire_$safe"), false)
    }

    private fun writeXlsx(uri: Uri) {
        val bytes = pendingBytes ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("fichier inaccessible")
            if (pendingStockExport) markExport()
            store.logAction("Export (.xlsx)", if (pendingStockExport) "$pendingName, ${plurLines()}" else pendingName)
            infoDialog("Export terminé", "Fichier : $pendingName" + (if (pendingStockExport) "\nLignes : ${repo.lines.size}" else "") + "\n\nJoignez-le maintenant à un message (trombone).", "ok")
        } catch (e: Exception) {
            store.logAction("Export (.xlsx)", "échec : ${e.localizedMessage}")
            infoDialog("Export impossible", e.localizedMessage ?: "erreur", "err")
        }
        pendingBytes = null
    }

    /** Partager : stock seul (csv) ou stock + photos (zip), par la feuille de partage Android (e-mail, messagerie...). */
    private fun onShareCsv() {
        if (repo.lines.isEmpty()) { infoDialog("Partage", "Rien à exporter.", "inf"); return }
        choiceDialog("Partager", listOf(
            "Stock seul (csv)\nLéger, s'ouvre dans Excel.",
            "Stock + photos (zip)\nLe destinataire choisit comment l'intégrer."
        )) { which -> shareStock(which == 1) }
    }

    private fun shareStock(withPhotos: Boolean) {
        val name = if (withPhotos) Backup.shareFileName() else Logic.exportFileName(Date(), "csv")
        val kind = if (withPhotos) "zip" else "csv"
        try {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, name)
            val all = repo.cards()
            val rows = Logic.toRows(Logic.sortedIndexes(all, sorts).map { all[it] })
            if (withPhotos) file.outputStream().use { Backup.writeShare(this, rows, it) }
            else file.writeBytes(Logic.toCsv(rows).toByteArray(Charsets.UTF_8))
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (withPhotos) "application/zip" else "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, if (withPhotos) "Scancounter partage" else Logic.exportSubject(name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, if (withPhotos) "Partager le stock et les photos" else "Partager le CSV"))
            markExport()
            store.logAction("Partage ($kind)", "ouvert : $name, ${plurLines()}")
            msg("Partage ouvert : $name", "ok")
        } catch (e: Exception) {
            store.logAction("Partage (csv)", "échec : ${e.localizedMessage}")
            infoDialog("Partage impossible", e.localizedMessage ?: "erreur", "err")
        }
    }

    private fun showLog() {
        val list = store.journal()
        infoDialog("Dernières actions", if (list.isEmpty()) "Aucune action enregistrée pour le moment." else list.joinToString("\n\n") { Logic.journalLine(it) })
    }

    private fun onZero() {
        if (repo.lines.isEmpty()) { infoDialog("Quantités à zéro", "Aucune ligne.", "inf"); return }
        val warn = if (lastExport.isEmpty()) "\n\nAttention : aucun export n'a été fait." else ""
        confirmDialog("Quantités à zéro", "Remettre toutes les quantités du stock à zéro ?\nLes lignes sont conservées.$warn", "Remettre à zéro", Ui.RED) {
            store.logAction("Quantités à 0", plurLines() + " conservées")
            repo.resetQuantities(); save()
            msg("Quantités remises à zéro (${plurLines()} conservées).", "ok")
        }
    }

    private fun onClear() {
        if (repo.lines.isEmpty() && repo.inventories.isEmpty()) { infoDialog("Tout effacer", "Rien à effacer.", "inf"); return }
        val warn = if (lastExport.isEmpty()) "\n\nAttention : aucun export n'a été fait." else ""
        confirmDialog("Tout effacer", "Effacer TOUT : ${plurLines()}, ${Logic.plural(repo.inventories.size, "inventaire", "inventaires")} et les photos ?\nCette action est définitive (pensez à Sauvegarder).$warn", "Tout effacer", Ui.RED) {
            store.logAction("Tout effacer", plurLines() + " supprimées")
            repo.clearAll(); Photos.deleteAll(this); store.currentInv = ""; showAll = false
            save(); msg("Tout est effacé.", "ok")
        }
    }

    // ------------------------------------------------------------------ sauvegarde / restauration

    /** Sauvegarder : dans l'appli (supprimable), envoyee ailleurs (rien ne reste sur le telephone), ou fichier zip choisi par l'utilisateur. */
    private fun onBackup() {
        choiceDialog("Sauvegarder", listOf(
            "Garder dans l'application\nInvisible des autres applications ; vous pouvez la supprimer ici.",
            "Envoyer (e-mail, Drive…)\nRien ne reste sur le téléphone."
        )) { which ->
            if (which == 0) onBackupLocal() else onShareBackup()
        }
    }

    private fun photoCount() = File(filesDir, "photos").listFiles()?.size ?: 0

    private fun onBackupLocal() {
        try {
            Backup.saveLocal(this, repo)
            val s = repo.summary(photoCount())
            store.logAction("Sauvegarde (dans l'appli)", s.text().replace("\n", ", "))
            infoDialog("Sauvegarde terminée", s.text() + "\n\nGardée dans l'application : Restaurer > Mes sauvegardes, où vous pouvez aussi la supprimer. Elle disparaît si l'application est désinstallée.", "ok")
        } catch (e: Exception) {
            store.logAction("Sauvegarde (dans l'appli)", "échec : ${e.localizedMessage}")
            infoDialog("Sauvegarde impossible", e.localizedMessage ?: "erreur", "err")
        }
    }

    /** Zip cree dans le cache puis envoye par la feuille de partage ; le cache est vide au prochain demarrage. */
    private fun onShareBackup() {
        try {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, Backup.fileName())
            file.outputStream().use { Backup.write(this, repo, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Scancounter sauvegarde")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Envoyer la sauvegarde"))
            store.logAction("Sauvegarde (envoi)", file.name)
            msg("Envoi ouvert : ${file.name}. Pour restaurer : ouvrez le zip reçu avec « Partager » vers Scancounter.", "ok")
        } catch (e: Exception) {
            store.logAction("Sauvegarde (envoi)", "échec : ${e.localizedMessage}")
            infoDialog("Envoi impossible", e.localizedMessage ?: "erreur", "err")
        }
    }

    private fun onPickRestore() = showStoredBackups()

    /** Sauvegardes gardees dans l'appli : restaurer en touchant la ligne, supprimer avec la corbeille. */
    private fun showStoredBackups() {
        val files = Backup.stored(this)
        val col = vbox()
        col.addView(tv("Mes sauvegardes", 18f, INK, true))
        val dlg = cardDialog(col)
        if (files.isEmpty()) col.addView(tv("Aucune sauvegarde dans l'application.", 14f, SUB, false), topMargin(10))
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.ROOT)
        files.forEach { f ->
            val auto = f.name.startsWith("avant_restauration")
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(WHITE, 14); setPadding(dp(14), dp(8), dp(6), dp(8)) }
            val t = vbox().apply {
                addView(tv(fmt.format(Date(f.lastModified())), 16f, INK, true))
                addView(tv((if (auto) "Copie automatique avant restauration" else "Sauvegarde") + " - " + (f.length() / 1024 + 1) + " Ko", 12.5f, SUB, false))
                setOnClickListener { dlg.dismiss(); openZip(f.name) { f.inputStream() } }
            }
            line.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(tv("🗑", 22f, INK, false).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    confirmDialog("Supprimer la sauvegarde", "Supprimer la sauvegarde du ${fmt.format(Date(f.lastModified()))} ?\nCette action est définitive.", "Supprimer", Ui.RED) {
                        f.delete(); dlg.dismiss(); store.logAction("Sauvegarde supprimée", f.name); showStoredBackups()
                    }
                }
            })
            col.addView(line, topMargin(8))
        }
        if (files.size > 1) col.addView(btn("Tout supprimer", "danger") {
            confirmDialog("Tout supprimer", "Supprimer les ${files.size} sauvegardes gardées dans l'application ?\nCette action est définitive.", "Tout supprimer", Ui.RED) {
                files.forEach { it.delete() }; dlg.dismiss(); store.logAction("Sauvegardes supprimées", "${files.size} fichiers"); msg("Sauvegardes supprimées.", "ok")
            }
        }, topMargin(12))
        col.addView(dlgButtons(pillBtn("Fermer", Ui.SLATE) { dlg.dismiss() }, null), topMargin(14))
        dlg.show()
    }


    /** Zip recu (partage) ou choisi dans « Mes sauvegardes » : une sauvegarde se remplace ou se fusionne, un zip de partage se fusionne. */
    private fun openZip(name: String, open: () -> java.io.InputStream?) {
        try {
            val inc = open()?.use { Backup.readAny(it) } ?: throw IllegalStateException("fichier illisible")
            val json = inc.json
            if (json == null) { mergeFromZip(name, inc); return }
            choiceDialog("Sauvegarde reçue", listOf(
                "Remplacer tout\nRestauration complète ; l'état actuel est d'abord sauvegardé.",
                "Fusionner\nAjouter le stock et les photos à ce que vous avez déjà."
            )) { which -> if (which == 0) confirmRestore(Backup.Content(json, inc.photos)) else mergeFromZip(name, inc) }
        } catch (e: Exception) {
            infoDialog("Zip illisible", e.localizedMessage ?: "fichier illisible", "err")
        }
    }

    /** Fusion d'un zip : le stock (stock.csv, ou data.json converti en lignes) passe par l'aperçu d'import, les photos suivent. */
    private fun mergeFromZip(name: String, inc: Backup.Incoming) {
        try {
            val csv = inc.csv
            val r = if (csv != null) analyze(csv)
            else ImportData(Backup.rowsFromJson(inc.json ?: "{}").toMutableList(), emptyList(), setOf(0, 1, 2, 3)).also {
                if (it.count == 0) throw IllegalArgumentException("Aucune ligne exploitable dans le fichier.")
            }
            r.photos = inc.photos
            store.logAction("Zip reçu", "$name (${Logic.plural(r.count, "ligne", "lignes")}, ${Logic.plural(r.photos.size, "photo", "photos")})")
            showImportPreview(name, r)
        } catch (e: Exception) {
            infoDialog("Zip illisible", e.localizedMessage ?: "fichier illisible", "err")
        }
    }

    private fun confirmRestore(content: Backup.Content) {
        try {
            pendingRestore = content
            val now = repo.summary(File(filesDir, "photos").listFiles()?.size ?: 0)
            confirmDialog("Restaurer la sauvegarde",
                "Le fichier contient :\n${Backup.summary(content).text()}\n\nIl REMPLACE les données actuelles :\n${now.text()}\n\nL'état actuel est d'abord sauvegardé automatiquement.",
                "Remplacer", Ui.RED) { doRestore() }
        } catch (e: Exception) {
            infoDialog("Restauration impossible", e.localizedMessage ?: "fichier illisible", "err")
        }
    }

    private fun doRestore() {
        val c = pendingRestore ?: return
        pendingRestore = null
        try {
            Backup.restore(this, repo, c)
            store.currentInv = ""; showAll = false
            store.logAction("Restauration", Backup.summary(c).text().replace("\n", ", "))
            save()
            infoDialog("Restauration terminée", Backup.summary(c).text(), "ok")
        } catch (e: Exception) {
            store.logAction("Restauration", "échec : ${e.localizedMessage}")
            repo.load(); renderAll()
            infoDialog("Restauration impossible", e.localizedMessage ?: "erreur", "err")
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
        val r = Xlsx.rowsToRows(Xlsx.readTable(bytes), Logic.nowIso())
        if (r.count == 0) throw IllegalArgumentException("Aucune ligne exploitable dans le fichier.")
        return r
    }

    private fun processImport(name: String, bytes: ByteArray) {
        impBox.visibility = View.GONE; pendingImport = null
        msg("Lecture du fichier $name...", "info")
        try {
            val r = analyze(bytes)
            showImportPreview(name, r)
            store.logAction("Import", "aperçu de $name (${Logic.plural(r.count, "ligne", "lignes")})")
        } catch (e: Exception) {
            store.logAction("Import", "erreur : ${e.localizedMessage ?: "fichier illisible"}")
            infoDialog("Import impossible", e.localizedMessage ?: "fichier illisible", "err")
            msg("", "")
        }
    }

    private fun showImportPreview(name: String, r: ImportData) {
        pendingImport = r; pendingName = name
        rbQtyKeep.isChecked = true; rbReplace.isChecked = true
        cbPhotos.isChecked = false; cbPhotos.visibility = if (r.photos.isEmpty()) View.GONE else View.VISIBLE
        refreshImpPreview()
        impBox.visibility = View.VISIBLE
        showPane("out")
        msg("", "")
    }

    private fun infoMode() = if (rbReplace.isChecked) "replace" else "complete"

    private fun qtyMode() = if (rbQtyAdd.isChecked) "add" else if (rbQtyReplace.isChecked) "replace" else "keep"

    private fun dryRun(r: ImportData): MergeCounts =
        Logic.mergeImport(repo.products.map { it.copyOf() }.toMutableList(), repo.lines.map { it.copyOf() }.toMutableList(),
            r.rows, qtyMode(), infoMode(), r.cols)

    /** Copie les photos recues pour les articles du fichier : les manquantes toujours, les existantes seulement si demande. */
    private fun importPhotos(r: ImportData): Int {
        var n = 0
        val wanted = r.rows.map { Photos.file(this, Logic.normCode(it.code)).name }.toSet()
        for ((name, bytes) in r.photos) {
            if (name !in wanted) continue
            val f = File(File(filesDir, "photos").apply { mkdirs() }, name)
            if (f.exists() && !cbPhotos.isChecked) continue
            f.writeBytes(bytes); n++
        }
        return n
    }

    private fun summaryText(c: MergeCounts, skipped: Int, mode: String): String =
        Logic.plural(c.created, "nouveau", "nouveaux") + ", " + "${c.updated} quantité(s) modifiée(s)" +
            ", " + "${c.kept} conservé(s) (quantité gardée)" + ", " + Logic.plural(skipped, "ignoré (invalide)", "ignorés (invalides)") +
            ". Infos " + (if (mode == "replace") "remplacées" else "complétées") + " pour " + Logic.plural(c.infoChanged, "produit", "produits") +
            (if (mode == "replace") " (" + Logic.plural(c.cleared, "champ effacé", "champs effacés") + ")" else "") + "."

    private fun refreshImpPreview() {
        val r = pendingImport ?: return
        val first = r.rows.take(3).joinToString(" ; ") { l ->
            l.code + " x " + Logic.fmtQty(l.qty) + (if (l.nom.isNotEmpty()) " (" + (if (l.nom.length > 30) l.nom.take(30) + "..." else l.nom) + ")" else "")
        }
        val dry = dryRun(r)
        impText.text = pendingName + " : " + Logic.plural(r.count, "ligne", "lignes") + " à importer" +
            (if (r.skipped.isNotEmpty()) ", " + Logic.plural(r.skipped.size, "ignorée", "ignorées") + " (ex. ligne ${r.skipped[0].row} : ${r.skipped[0].why})" else "") +
            ". Début : $first. Si vous choisissez « Ajouter au stock » : " + summaryText(dry, r.skipped.size, infoMode()) +
            " « Remplacer le stock » efface les " + Logic.plural(repo.lines.size, "ligne actuelle", "lignes actuelles") + " (et les comptages d'inventaire)."
    }

    private fun applyImport(replace: Boolean) {
        val r = pendingImport ?: return
        if (replace && repo.lines.isNotEmpty()) {
            confirmDialog("Remplacer le stock", "Remplacer le stock ?\n${Logic.plural(repo.lines.size, "ligne actuelle sera effacée", "lignes actuelles seront effacées")} (ainsi que les comptages d'inventaire) et remplacée(s) par les ${Logic.plural(r.count, "ligne", "lignes")} du fichier.", "Remplacer le stock", Ui.RED) { doImport(true) }
            return
        }
        if (!replace && infoMode() == "replace") {
            val dry = dryRun(r)
            if (dry.cleared > 0) {
                confirmDialog("Effacer des champs", Logic.plural(dry.cleared, "champ sera effacé", "champs seront effacés") + " : les cellules vides du fichier effacent les informations existantes (nom, ref fournisseur, affectation, marque).\nContinuer ?", "Ajouter et effacer", Ui.RED) { doImport(false) }
                return
            }
        }
        doImport(replace)
    }

    private fun doImport(replace: Boolean) {
        val r = pendingImport ?: return
        val mode = infoMode()
        val c: MergeCounts
        if (replace) { repo.clearStock(); c = Logic.mergeImport(repo.products, repo.lines, r.rows, "replace", "complete", null) }
        else c = Logic.mergeImport(repo.products, repo.lines, r.rows, qtyMode(), mode, r.cols)
        val photos = if (r.photos.isEmpty()) 0 else importPhotos(r)
        pendingImport = null; impBox.visibility = View.GONE
        save(); msg("", "")
        val txt = Logic.importSummary(c, r.skipped.size, mode, replace) + (if (r.photos.isEmpty()) "" else "\nPhotos copiées : $photos")
        store.logAction("Import", (if (replace) "stock remplacé : " else "terminé : ") + txt.replace("\n", " ; "))
        infoDialog("Import terminé", txt + "\n\nLes détails servent aussi de mémoire par code.", "ok")
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
            if (i.type != null) infoDialog("Aucun fichier reçu", "Le menu Partager n'a transmis aucun fichier.\n\nOuvrez le mail, appuyez sur la pièce jointe puis « Partager », et choisissez Scancounter. Sinon, utilisez le bouton Importer.", "warn")
            return
        }
        val name = displayName(uri)
        if (name.lowercase().endsWith(".zip")) { openZip(name) { contentResolver.openInputStream(uri) }; return }
        try {
            val bytes = readUri(uri)
            val r = analyze(bytes)
            val fresh = r.rows.count { repo.productByCode(it.code) == null }
            val info = ReceivedInfo(name, if (Xlsx.isXlsx(bytes)) "xlsx" else "csv", r.count, fresh, r.count - fresh, r.skipped.size)
            store.logAction("Fichier reçu", name + " (" + Logic.receivedSummary(info).split("\n").drop(1).take(4).joinToString(", ") + ")")
            confirmDialog("Fichier reçu", Logic.receivedSummary(info), "Voir l'aperçu d'import") { showImportPreview(name, r) }
        } catch (e: Exception) {
            store.logAction("Fichier reçu", "illisible : ${e.localizedMessage}")
            infoDialog("Fichier reçu illisible", "Cause : ${e.localizedMessage ?: "erreur"}\nNom : $name", "err")
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
                try { processImport(displayName(uri), readUri(uri)) } catch (e: Exception) { infoDialog("Import impossible", "fichier illisible", "err") }
            }
            RC_PHOTO_ART -> {
                val f = photoFile?.takeIf { it.exists() }
                if (f == null) { msg("Impossible de lire la photo.", "err"); return }
                try { activeBox?.setPicked(readBitmap(Uri.fromFile(f))) } catch (e: Exception) { msg("Erreur photo : ${e.localizedMessage}", "err") }
            }
            RC_GALLERY -> {
                val u = data?.data ?: return
                try { activeBox?.setPicked(readBitmap(u)) } catch (e: Exception) { msg("Erreur photo : ${e.localizedMessage}", "err") }
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
