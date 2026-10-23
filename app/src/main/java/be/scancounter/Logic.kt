package be.scancounter

import java.math.BigInteger
import java.text.Collator
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Une carte de l'ecran Articles : ligne de stock + fiche produit. */
class Card(val l: StockLine, val p: Product)

/** Ligne a plat pour l'export / l'import (un produit a un magasin et un emplacement). */
class Row(
    val code: String, val ref: String, val nom: String, val affectation: String, val marque: String,
    val magasin: String, val emplacement: String, val qty: Double, val time: String
)

data class MergeCounts(var created: Int = 0, var updated: Int = 0, var kept: Int = 0, var infoChanged: Int = 0, var cleared: Int = 0)

data class Sort(val k: String, val d: Int)

data class ReceivedInfo(val name: String, val kind: String, val rows: Int, val fresh: Int, val known: Int, val skipped: Int)

data class EtiquetteApply(val fields: Map<String, String>, val missing: List<String>, val focus: String, val kind: String, val message: String)

data class JournalEntry(val t: String, val action: String, val result: String)

/** Logique pure (sans ecran) : port de logic.js de la PWA Scancounter, adapte au modele produit / stock / inventaire. */
object Logic {
    val FIELD_TITLES = listOf("Ref. fournisseur", "Nom", "Affectation", "Marque")

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

    fun addQty(a: Double, b: Double): Double = round3(a + b)

    fun fmtQty(q: Double): String {
        if (q == Math.floor(q) && Math.abs(q) < 1e12) return q.toLong().toString()
        return String.format(Locale.ROOT, "%.3f", q).trimEnd('0').trimEnd('.').replace('.', ',')
    }
    /** Pour la cellule Excel : point decimal. */
    fun fmtQtyDot(q: Double): String = fmtQty(q).replace(',', '.')

    fun total(lines: List<StockLine>): Double = round3(lines.sumOf { it.qty })

    // ---- CSV : memes colonnes que l'Excel, separateur point-virgule, BOM UTF-8 ----
    val STOCK_HEADERS = listOf("code", "ref_fournisseur", "nom", "affectation", "marque", "magasin", "emplacement", "quantite", "heure")
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

    fun toRows(cards: List<Card>): List<Row> =
        cards.map { Row(it.p.code, it.p.ref, it.p.nom, it.p.affectation, it.p.marque, it.l.magasin, it.l.emplacement, it.l.qty, it.l.modified) }

    fun toCsv(rows: List<Row>): String {
        val out = StringBuilder("\uFEFF").append(STOCK_HEADERS.joinToString(";")).append("\r\n")
        for (r in rows) {
            val cols = listOf(r.code, r.ref, r.nom, r.affectation, r.marque, r.magasin, r.emplacement).map { csvTextField(it) } +
                listOf(fmtQty(r.qty), csvField(r.time))
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
     * Import (modifie products et lines ; passer des copies pour un essai a blanc).
     * qtyMode (lignes presentes a quantite > 0) : "keep" garde l'actuelle, "replace" prend celle du fichier, "add" additionne les deux.
     * infoMode : "complete" (seuls les details vides sont completes) ou "replace" (aligne sur le fichier, une cellule vide efface).
     * cols : indices (0 ref, 1 nom, 2 affectation, 3 marque) dont la colonne existe dans le fichier (colonne absente != cellule vide).
     */
    fun mergeImport(products: MutableList<Product>, lines: MutableList<StockLine>, rows: List<Row>, qtyMode: String, infoMode: String, cols: Set<Int>?): MergeCounts {
        val res = MergeCounts()
        val present = cols ?: setOf(0, 1, 2, 3)
        val byCode = HashMap<String, Product>().apply { products.forEach { p -> put(p.code, p); p.alts.forEach { a -> put(a, p) } } }
        for (r in rows) {
            val code = normCode(r.code)
            val mag = normField(r.magasin); val emp = normField(r.emplacement)
            val incoming = listOf(normField(r.ref), normField(r.nom), normField(r.affectation), normField(r.marque))
            var p = byCode[code]
            if (p == null) {
                p = Product(newId(), code, incoming[0], incoming[1], incoming[2], incoming[3], r.time)
                products.add(p); byCode[code] = p
            } else {
                val cur = listOf(p.ref, p.nom, p.affectation, p.marque).toMutableList()
                var changed = false
                for (f in 0..3) {
                    val v = incoming[f]
                    if (infoMode == "replace") {
                        if (f !in present) continue
                        if (v != cur[f]) { if (v.isEmpty()) res.cleared++; cur[f] = v; changed = true }
                    } else if (v.isNotEmpty() && cur[f].isEmpty()) { cur[f] = v; changed = true }
                }
                if (changed) {
                    p.ref = cur[0]; p.nom = cur[1]; p.affectation = cur[2]; p.marque = cur[3]; p.modified = r.time
                    res.infoChanged++
                }
            }
            val line = lines.firstOrNull { it.productId == p.id && it.magasin.equals(mag, true) && it.emplacement.equals(emp, true) }
            if (line == null) { lines.add(StockLine(newId(), p.id, mag, emp, r.qty, r.time)); res.created++; continue }
            if (line.qty == 0.0) { line.qty = r.qty; line.modified = r.time; res.created++ }
            else if (qtyMode == "replace") { line.qty = r.qty; line.modified = r.time; res.updated++ }
            else if (qtyMode == "add") { line.qty += r.qty; line.modified = r.time; res.updated++ }
            else res.kept++
        }
        return res
    }

    // ---- Recherche : tous les champs, sans casse ni accents, plusieurs mots = ET ----
    fun foldText(s: String?): String =
        Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Cle de controle EAN-13 d'un corps de 12 chiffres. */
    private fun eanCle(c: String): String = ((10 - c.mapIndexed { i, ch -> (ch - '0') * (if (i % 2 == 0) 1 else 3) }.sum() % 10) % 10).toString()

    /** Prochain code interne libre : 200 + 9 chiffres + cle de controle (known = tous les codes deja utilises). */
    fun nextCode200(known: Collection<String>): String {
        val taken = known.toHashSet()
        var seq = 0
        taken.forEach { if (Regex("^200\\d{10}$").matches(it)) seq = maxOf(seq, it.substring(3, 12).toInt()) }
        while (true) {
            seq++
            val body = "200" + seq.toString().padStart(9, '0')
            val code = body + eanCle(body)
            if (code !in taken) return code
        }
    }

    private fun searchText(c: Card): String =
        foldText((listOf(c.p.code) + c.p.alts + listOf(c.p.ref, c.p.nom, c.p.affectation, c.p.marque, c.l.magasin, c.l.emplacement)).joinToString(" "))
    fun buildSearchIndex(cards: List<Card>): List<String> = cards.map { searchText(it) }
    fun searchTokens(q: String): List<String> = foldText(q).split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Indices des cartes contenant TOUS les mots de q, parcourues dans l'ordre order. */
    fun searchCards(q: String, index: List<String>, order: List<Int>): List<Int> {
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
    val SORT_ORDER = listOf("date", "nom", "code", "emplacement", "qty", "marque", "magasin")
    val SORT_LABELS = mapOf(
        "date" to "Derniers modifiés", "nom" to "Nom", "code" to "Code", "emplacement" to "Emplacement",
        "qty" to "Quantité", "marque" to "Marque", "magasin" to "Magasin"
    )
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

    private fun cmpKey(k: String, x: Card, y: Card): Int = when (k) {
        "date" -> x.l.modified.compareTo(y.l.modified)
        "qty" -> x.l.qty.compareTo(y.l.qty)
        "code" -> naturalCmp(x.p.code, y.p.code)
        "nom" -> naturalCmp(x.p.nom, y.p.nom)
        "marque" -> naturalCmp(x.p.marque, y.p.marque)
        "magasin" -> naturalCmp(x.l.magasin, y.l.magasin)
        "emplacement" -> naturalCmp(x.l.emplacement, y.l.emplacement)
        else -> 0
    }

    /** Defaut : date decroissante. Egalite sur tous les criteres : plus recent d'abord. */
    fun sortedIndexes(cards: List<Card>, criteria: List<Sort>? = null): List<Int> {
        val crit = (if (criteria.isNullOrEmpty()) listOf(Sort("date", -1)) else criteria).filter { it.k in SORT_LABELS }
        return cards.indices.sortedWith { a, b ->
            for (c in crit) {
                val r = cmpKey(c.k, cards[a], cards[b])
                if (r != 0) return@sortedWith if (c.d < 0) -r else r
            }
            val r = cards[b].l.modified.compareTo(cards[a].l.modified)
            if (r != 0) r else b.compareTo(a)
        }
    }

    // ---- Noms de fichiers, dates, resumes ----
    fun exportFileName(d: Date, ext: String, prefix: String = "scancounter"): String =
        prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(d) + "." + ext
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

    /** jj/mm/aaaa seulement. */
    fun formatDate(sIn: String?): String = formatDateTime(sIn).take(10)

    fun fmtFull(iso: String): String {
        val f = formatDateTime(iso)
        return if (f.length == 16) f.substring(0, 10) + " à " + f.substring(11) else f
    }

    fun plural(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

    fun importSummary(c: MergeCounts, skipped: Int, infoMode: String, replace: Boolean): String {
        if (replace) { val n = c.created + c.updated; return "Stock remplacé : ${plural(n, "ligne importée", "lignes importées")}." }
        val out = mutableListOf(
            "Ajoutés (nouveaux) : ${c.created}",
            "Quantités modifiées : ${c.updated}",
            "Conservés (quantité gardée) : ${c.kept}",
            "Ignorés (lignes invalides) : $skipped",
            (if (infoMode == "replace") "Produits dont les infos sont remplacées : " else "Produits dont les infos sont complétées : ") + c.infoChanged
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
