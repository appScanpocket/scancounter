package be.scancounter

import java.math.BigInteger
import java.text.Collator
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Details d'un article (tout sauf code, quantite et heure). Ordre = Logic.FIELD_TITLES. */
data class Details(
    val ref: String = "",
    val nom: String = "",
    val affectation: String = "",
    val marque: String = "",
    val emplacement: String = ""
) {
    operator fun get(i: Int): String = when (i) { 0 -> ref; 1 -> nom; 2 -> affectation; 3 -> marque; else -> emplacement }
    fun with(i: Int, v: String): Details = when (i) {
        0 -> copy(ref = v); 1 -> copy(nom = v); 2 -> copy(affectation = v); 3 -> copy(marque = v); else -> copy(emplacement = v)
    }
    fun any(): Boolean = (0..4).any { this[it].isNotEmpty() }
    fun clean(): Details = Details(Logic.normField(ref), Logic.normField(nom), Logic.normField(affectation), Logic.normField(marque), Logic.normField(emplacement))
}

class Line(var code: String, var d: Details, var qty: Double, var time: String) {
    fun copyOf() = Line(code, d, qty, time)
}

data class AddResult(
    val ok: Boolean,
    val action: String = "",
    val error: String = "",
    val exists: Boolean = false,
    val index: Int = -1,
    val current: Double = 0.0
)

data class MergeCounts(var created: Int = 0, var updated: Int = 0, var kept: Int = 0, var infoChanged: Int = 0, var cleared: Int = 0)

data class Sort(val k: String, val d: Int)

data class ReceivedInfo(val name: String, val kind: String, val rows: Int, val fresh: Int, val known: Int, val skipped: Int)

data class EtiquetteApply(val fields: Map<String, String>, val missing: List<String>, val focus: String, val kind: String, val message: String)

data class JournalEntry(val t: String, val action: String, val result: String)

/** Logique pure (sans ecran) : port de logic.js de la PWA Scancounter v0.18. */
object Logic {
    val FIELD_TITLES = listOf("Ref. fournisseur", "Nom", "Affectation", "Marque", "Emplacement")

    fun normCode(s: String?): String = (s ?: "").replace(Regex("[\\r\\n\\t;]+"), " ").trim()
    fun normField(s: String?): String = (s ?: "").replace(Regex("[\\r\\n\\t]+"), " ").trim()

    private fun round3(x: Double): Double = Math.round(x * 1000.0) / 1000.0

    /** Comme le JS : une quantite vide vaut 0 ; virgule ou point acceptes. */
    fun normQty(v: String?): Double? {
        val t = (v ?: "").trim().replace(',', '.')
        val n = if (t.isEmpty()) 0.0 else t.toDoubleOrNull() ?: return null
        if (n.isNaN() || n.isInfinite() || n < 0) return null
        return round3(n)
    }

    fun fmtQty(q: Double): String {
        if (q == Math.floor(q) && Math.abs(q) < 1e12) return q.toLong().toString()
        return String.format(Locale.ROOT, "%.3f", q).trimEnd('0').trimEnd('.').replace('.', ',')
    }
    /** Pour la cellule Excel : point decimal. */
    fun fmtQtyDot(q: Double): String = fmtQty(q).replace(',', '.')

    fun findIndex(lines: List<Line>, code: String): Int {
        for (i in lines.indices) if (lines[i].code == code) return i
        return -1
    }

    /** Memoire par code : details de la ligne portant ce code (ou null). */
    fun prefill(lines: List<Line>, code: String): Details? {
        val i = findIndex(lines, normCode(code))
        return if (i < 0) null else lines[i].d.clean()
    }

    /** mode : "new", "add" (additionner) ou "replace" (remplacer). */
    fun addLine(lines: MutableList<Line>, codeIn: String, qtyIn: String, mode: String, now: String, details: Details?): AddResult {
        val code = normCode(codeIn)
        val q = normQty(qtyIn)
        if (code.isEmpty()) return AddResult(false, error = "Code vide")
        if (q == null) return AddResult(false, error = "Quantité invalide")
        val clean = (details ?: Details()).clean()
        val i = findIndex(lines, code)
        if (i >= 0) {
            if (mode == "add" || mode == "replace") {
                val ln = lines[i]
                var nd = ln.d
                for (f in 0..4) if (clean[f].isNotEmpty()) nd = nd.with(f, clean[f])
                ln.d = nd
                ln.qty = if (mode == "add") round3(ln.qty + q) else q
                ln.time = now
                return AddResult(true, action = if (mode == "add") "added" else "replaced")
            }
            return AddResult(false, exists = true, index = i, current = lines[i].qty)
        }
        lines.add(Line(code, clean, q, now))
        return AddResult(true, action = "created")
    }

    fun updateLine(lines: MutableList<Line>, index: Int, codeIn: String, qtyIn: String, now: String, details: Details?): AddResult {
        val code = normCode(codeIn)
        val q = normQty(qtyIn)
        if (index !in lines.indices) return AddResult(false, error = "Ligne introuvable")
        if (code.isEmpty()) return AddResult(false, error = "Code vide")
        if (q == null) return AddResult(false, error = "Quantité invalide")
        val j = findIndex(lines, code)
        if (j >= 0 && j != index) return AddResult(false, error = "Ce code existe déjà sur une autre ligne")
        lines[index] = Line(code, (details ?: Details()).clean(), q, now)
        return AddResult(true)
    }

    fun total(lines: List<Line>): Double = round3(lines.sumOf { it.qty })

    fun resetQuantities(lines: List<Line>): Int {
        var n = 0
        lines.forEach { if (it.qty != 0.0) n++; it.qty = 0.0 }
        return n
    }

    // ---- CSV : memes colonnes que l'Excel, separateur point-virgule, BOM UTF-8 ----
    private val CSV_HEADERS = listOf("code", "ref_fournisseur", "nom", "affectation", "marque", "emplacement", "quantite", "heure")
    private val reCsvSpecial = Regex("[;\"\\r\\n]")
    private val reCsvLine = Regex("[;\\r\\n]")
    private val reDigits = Regex("^[0-9]+$")
    private val reSci = Regex("^[0-9]*\\.?[0-9]+[eE][+-]?[0-9]+$")
    private val reDecDate = Regex("^[0-9]+[.,/-][0-9]+([.,/-][0-9]+)?$")
    private val reFormula = Regex("^[=+\\-@]")

    private fun csvField(s: String): String =
        if (reCsvSpecial.containsMatchIn(s)) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Champ texte qu'Excel transformerait (grand nombre, zero de tete, 1E5, debut = + - @) : ecrit ="valeur". */
    private fun needsTextGuard(s: String): Boolean {
        if (reDigits.containsMatchIn(s)) return s.length >= 10 || (s.length > 1 && s[0] == '0')
        if (reSci.containsMatchIn(s)) return true
        if (reDecDate.containsMatchIn(s)) return true
        return reFormula.containsMatchIn(s)
    }

    private fun csvTextField(s: String): String {
        if (!needsTextGuard(s)) return csvField(s)
        val f = "=\"" + s.replace("\"", "\"\"") + "\""
        return if (reCsvLine.containsMatchIn(s)) "\"" + f.replace("\"", "\"\"") + "\"" else f
    }

    fun toCsv(lines: List<Line>): String {
        val out = StringBuilder("\uFEFF").append(CSV_HEADERS.joinToString(";")).append("\r\n")
        for (l in lines) {
            val cols = listOf(l.code, l.d.ref, l.d.nom, l.d.affectation, l.d.marque, l.d.emplacement).map { csvTextField(it) } +
                listOf(fmtQty(l.qty), csvField(l.time))
            out.append(cols.joinToString(";")).append("\r\n")
        }
        return out.toString()
    }

    /** Lecture CSV : separateur ; , ou tabulation (devine sur la 1re ligne), guillemets, BOM, CRLF. */
    fun parseCsv(textIn: String): List<List<String>> {
        val text = textIn.removePrefix("\uFEFF")
        val first = text.split(Regex("\\r?\\n"))[0]
        val nSemi = first.split(';').size
        val nComma = first.split(',').size
        var sep = if (nSemi >= nComma) ';' else ','
        if (!first.contains(';') && !first.contains(',') && first.contains('\t')) sep = '\t'
        val reGuard = Regex("^=\"([\\s\\S]*)\"$")
        fun fin(v: String): String {
            val m = reGuard.find(v)
            return if (m != null) m.groupValues[1].replace("\"\"", "\"") else v
        }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cur = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } else q = false
                } else cur.append(c)
            } else if (c == '"' && cur.isEmpty()) q = true
            else if (c == sep) { row.add(fin(cur.toString())); cur.setLength(0) }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row.add(fin(cur.toString())); cur.setLength(0); rows.add(row); row = mutableListOf()
            } else cur.append(c)
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) { row.add(fin(cur.toString())); rows.add(row) }
        return rows
    }

    /**
     * Import « Ajouter a la liste » (modifie lines).
     * update : la quantite du fichier remplace l'actuelle (articles presents a quantite > 0).
     * infoMode : "complete" (seuls les details vides sont completes) ou "replace" (aligne sur le fichier, une cellule vide efface).
     * cols : indices des details dont la colonne existe dans le fichier (colonne absente != cellule vide).
     */
    fun mergeImport(lines: MutableList<Line>, imported: List<Line>, update: Boolean, infoMode: String, cols: Set<Int>?): MergeCounts {
        val res = MergeCounts()
        val present = cols ?: setOf(0, 1, 2, 3, 4)
        for (l in imported) {
            val code = normCode(l.code)
            val d = l.d.clean()
            val i = findIndex(lines, code)
            if (i < 0) { lines.add(Line(code, d, l.qty, l.time)); res.created++; continue }
            val cur = lines[i]
            var changed = false
            for (f in 0..4) {
                val v = d[f]
                if (infoMode == "replace") {
                    if (f !in present) continue
                    if (v != cur.d[f]) { if (v.isEmpty()) res.cleared++; cur.d = cur.d.with(f, v); changed = true }
                } else {
                    if (v.isEmpty()) continue
                    if (cur.d[f].isEmpty()) { cur.d = cur.d.with(f, v); changed = true }
                }
            }
            if (changed) res.infoChanged++
            if (cur.qty == 0.0) { cur.qty = l.qty; cur.time = l.time; res.created++ }
            else if (update) { cur.qty = l.qty; cur.time = l.time; res.updated++ }
            else res.kept++
        }
        return res
    }

    // ---- Recherche : tous les champs, sans casse ni accents, plusieurs mots = ET ----
    fun foldText(s: String?): String =
        Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun searchText(l: Line): String = foldText(listOf(l.code, l.d.ref, l.d.nom, l.d.affectation, l.d.marque, l.d.emplacement).joinToString(" "))
    fun buildSearchIndex(lines: List<Line>): List<String> = lines.map { searchText(it) }
    fun searchTokens(q: String): List<String> = foldText(q).split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Indices des lignes contenant TOUS les mots de q, parcourues dans l'ordre order. */
    fun searchLines(lines: List<Line>, q: String, index: List<String>, order: List<Int>): List<Int> {
        val tok = searchTokens(q)
        val out = ArrayList<Int>()
        for (i in order) {
            if (tok.isNotEmpty()) {
                val s = index[i]
                if (!tok.all { s.contains(it) }) continue
            }
            out.add(i)
        }
        return out
    }

    // ---- Tri multi-criteres ----
    val SORT_ORDER = listOf("date", "code", "qty", "nom", "marque", "emplacement")
    val SORT_LABELS = mapOf("date" to "Date", "code" to "Code", "qty" to "Quantité", "nom" to "Nom", "marque" to "Marque", "emplacement" to "Emplacement")
    private val collator: Collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
    private val reTokens = Regex("\\d+|\\D+")

    private fun naturalCmp(a: String, b: String): Int {
        val ta = reTokens.findAll(a.lowercase()).map { it.value }.toList()
        val tb = reTokens.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]; val y = tb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) BigInteger(x).compareTo(BigInteger(y)) else collator.compare(x, y)
            if (r != 0) return r
        }
        return ta.size.compareTo(tb.size)
    }

    private fun cmpKey(k: String, x: Line, y: Line): Int = when (k) {
        "date" -> x.time.compareTo(y.time)
        "qty" -> x.qty.compareTo(y.qty)
        "code" -> naturalCmp(x.code, y.code)
        "nom" -> naturalCmp(x.d.nom, y.d.nom)
        "marque" -> naturalCmp(x.d.marque, y.d.marque)
        "emplacement" -> naturalCmp(x.d.emplacement, y.d.emplacement)
        else -> 0
    }

    /** Defaut : date decroissante. Egalite sur tous les criteres : plus recent d'abord. */
    fun sortedIndexes(lines: List<Line>, criteria: List<Sort>? = null): List<Int> {
        val crit = (if (criteria.isNullOrEmpty()) listOf(Sort("date", -1)) else criteria).filter { it.k in SORT_LABELS }
        return lines.indices.sortedWith { a, b ->
            for (c in crit) {
                val r = cmpKey(c.k, lines[a], lines[b])
                if (r != 0) return@sortedWith if (c.d < 0) -r else r
            }
            val r = lines[b].time.compareTo(lines[a].time)
            if (r != 0) r else b.compareTo(a)
        }
    }

    // ---- Noms de fichiers, dates, resumes ----
    fun exportFileName(d: Date, ext: String): String = "scancounter_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(d) + "." + ext
    fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
    fun exportSubject(name: String): String = "Scancounter " + name.replace(Regex("^scancounter_", RegexOption.IGNORE_CASE), "").replace(Regex("\\.[A-Za-z0-9]+$"), "")

    private val reDateTime = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})")
    private val reDateOnly = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    /** jj/mm/aaaa hh:mm ; autre texte rendu tel quel. */
    fun formatDateTime(sIn: String?): String {
        val s = (sIn ?: "").trim()
        reDateTime.find(s)?.let { m -> val g = m.groupValues; return "${g[3]}/${g[2]}/${g[1]} ${g[4]}:${g[5]}" }
        reDateOnly.find(s)?.let { m -> val g = m.groupValues; return "${g[3]}/${g[2]}/${g[1]}" }
        return s
    }

    fun fmtFull(iso: String): String {
        val f = formatDateTime(iso)
        return if (f.length == 16) f.substring(0, 10) + " à " + f.substring(11) else f
    }

    /** Taille (sp) du code pour qu'il tienne sur une ligne meme tres long. */
    fun codeFontSp(code: String): Float {
        val n = code.length
        return if (n <= 10) 18f else if (n <= 13) 16f else if (n <= 16) 14f else if (n <= 20) 12.5f else 11f
    }

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

    fun importSummary(c: MergeCounts, skipped: Int, infoMode: String, replace: Boolean): String {
        if (replace) { val n = c.created + c.updated; return "Liste remplacée : ${plural(n, "ligne importée", "lignes importées")}." }
        val out = mutableListOf(
            "Ajoutés (nouveaux) : ${c.created}",
            "Mis à jour (quantité remplacée) : ${c.updated}",
            "Conservés (quantité gardée) : ${c.kept}",
            "Ignorés (lignes invalides) : $skipped",
            (if (infoMode == "replace") "Articles dont les infos sont remplacées : " else "Articles dont les infos sont complétées : ") + c.infoChanged
        )
        if (infoMode == "replace") out.add("Champs effacés : ${c.cleared}")
        return out.joinToString("\n")
    }

    fun receivedSummary(i: ReceivedInfo): String {
        val kindName = when (i.kind) { "csv" -> "CSV"; "xlsx" -> "Excel (.xlsx)"; else -> "inconnu" }
        val out = mutableListOf("Fichier : ${i.name}", "Format détecté : $kindName", "Lignes : ${i.rows}", "Codes nouveaux : ${i.fresh}", "Codes déjà présents : ${i.known}")
        if (i.skipped > 0) out.add("Lignes ignorées (invalides) : ${i.skipped}")
        return out.joinToString("\n")
    }

    fun journalLine(e: JournalEntry): String = formatDateTime(e.t) + " - " + e.action + " : " + e.result

    // ---- Etiquette : remplissage de l'ecran a partir d'une lecture ----
    /** res : cles code, ref, nom, affectation, marque, emplacement. Le code est TOUJOURS traite. */
    fun applyEtiquette(res: Map<String, String>): EtiquetteApply {
        val names = linkedMapOf("code" to "Code article", "ref" to "Ref. fournisseur", "nom" to "Nom", "affectation" to "Affectation", "marque" to "Marque", "emplacement" to "Emplacement")
        val fields = linkedMapOf<String, String>()
        val missing = mutableListOf<String>()
        for ((k, title) in names) {
            val v = res[k].orEmpty()
            if (v.isNotEmpty()) fields[k] = v else if (k != "affectation") missing.add(title)
        }
        val code = res["code"].orEmpty()
        return when {
            fields.isEmpty() -> EtiquetteApply(fields, missing, "code", "err",
                "Aucun texte d'étiquette reconnu. Code non lu, saisissez-le. Reprenez la photo (étiquette entière, bien nette) ou saisissez à la main.")
            code.isEmpty() -> EtiquetteApply(fields, missing, "code", "info",
                "Code non lu, saisissez-le (grand numéro du haut). Autres champs à vérifier" +
                    (if (missing.size > 1) " (non lus : " + missing.filter { it != "Code article" }.joinToString(", ") + ")" else "") + ".")
            else -> EtiquetteApply(fields, missing, "qty", if (missing.isNotEmpty()) "info" else "ok",
                "Étiquette lue : vérifiez et corrigez les champs" + (if (missing.isNotEmpty()) " (non lus : " + missing.joinToString(", ") + ")" else "") + ", puis saisissez la quantité.")
        }
    }
}
