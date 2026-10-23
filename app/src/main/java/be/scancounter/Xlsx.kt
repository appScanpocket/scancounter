package be.scancounter

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Resultat de la lecture d'un fichier d'import. cols = indices des details dont la colonne existe. */
class ImportData(val lines: MutableList<Line>, val skipped: List<Skipped>, val cols: Set<Int>) {
    val count get() = lines.size
}
class Skipped(val row: Int, val why: String)

/** Export / import .xlsx et .csv : port de xlsx.js (java.util.zip remplace le zip fait main). */
object Xlsx {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    val HEADERS = listOf("code", "ref_fournisseur", "nom", "affectation", "marque", "emplacement", "quantite", "heure")

    // ---------- ecriture ----------
    private fun esc(s: String): String =
        s.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun colName(index: Int): String {
        var i = index + 1
        var s = ""
        while (i > 0) { val m = (i - 1) % 26; s = ('A' + m) + s; i = (i - 1) / 26 }
        return s
    }

    fun build(lines: List<Line>): ByteArray {
        val rows = StringBuilder("<row r=\"1\">")
        HEADERS.forEachIndexed { i, h -> rows.append("<c r=\"${colName(i)}1\" t=\"inlineStr\" s=\"1\"><is><t>$h</t></is></c>") }
        rows.append("</row>")
        lines.forEachIndexed { idx, l ->
            val r = idx + 2
            rows.append("<row r=\"$r\">")
            listOf(l.code, l.d.ref, l.d.nom, l.d.affectation, l.d.marque, l.d.emplacement).forEachIndexed { i, v ->
                rows.append("<c r=\"${colName(i)}$r\" t=\"inlineStr\" s=\"2\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
            }
            rows.append("<c r=\"G$r\"><v>${Logic.fmtQtyDot(l.qty)}</v></c>")
            rows.append("<c r=\"H$r\" t=\"inlineStr\" s=\"2\"><is><t xml:space=\"preserve\">${esc(l.time)}</t></is></c>")
            rows.append("</row>")
        }
        val last = "H" + (lines.size + 1)
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        val files = listOf(
            "[Content_Types].xml" to xml + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to xml + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to xml + """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Scancounter" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to xml + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to xml + """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="49" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""",
            "xl/worksheets/sheet1.xml" to xml + """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:$last"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="15"/><cols><col min="1" max="2" width="18" customWidth="1"/><col min="3" max="3" width="34" customWidth="1"/><col min="4" max="6" width="16" customWidth="1"/><col min="7" max="7" width="10" customWidth="1"/><col min="8" max="8" width="20" customWidth="1"/></cols><sheetData>$rows</sheetData></worksheet>"""
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // ---------- lecture ----------
    private fun unesc(s: String): String =
        s.replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
            .replace(Regex("&#([0-9]+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private val reRph = Regex("<rPh[\\s\\S]*?</rPh>")
    private val reT = Regex("<t(?:\\s[^>]*)?>([\\s\\S]*?)</t>")
    private fun textOf(xml: String): String {
        val src = xml.replace(reRph, "")
        return reT.findAll(src).joinToString("") { unesc(it.groupValues[1]) }
    }

    private fun colIndex(ref: String): Int {
        val m = Regex("^([A-Za-z]+)").find(ref) ?: return -1
        var n = 0
        for (ch in m.groupValues[1].uppercase()) n = n * 26 + (ch.code - 64)
        return n - 1
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory) out[e.name] = zip.readBytes()
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /** Lignes de la premiere feuille : liste de listes de chaines. */
    fun readXlsx(bytes: ByteArray): List<List<String>> {
        val ents = readEntries(bytes)
        val wbBytes = ents["xl/workbook.xml"] ?: throw IllegalArgumentException("Ce fichier n'est pas un classeur .xlsx (xl/workbook.xml absent).")
        fun text(name: String): String? = ents[name]?.toString(Charsets.UTF_8)
        val shared = mutableListOf<String>()
        text("xl/sharedStrings.xml")?.let { sst ->
            Regex("<si(?:\\s[^>]*)?>([\\s\\S]*?)</si>|<si(?:\\s[^>]*)?/>").findAll(sst).forEach { m ->
                shared.add(m.groups[1]?.value?.let { textOf(it) } ?: "")
            }
        }
        val wb = wbBytes.toString(Charsets.UTF_8)
        val rels = text("xl/_rels/workbook.xml.rels")
        var sheetPath: String? = null
        val sm = Regex("<sheet\\b[^>]*?\\br:id=\"([^\"]+)\"").find(wb) ?: Regex("<sheet\\b[^>]*?\\bid=\"([^\"]+)\"").find(wb)
        if (sm != null && rels != null) {
            val rr = Regex("<Relationship\\b[^>]*?\\bId=\"" + Regex.escape(sm.groupValues[1]) + "\"[^>]*>").find(rels)
            val tg = rr?.let { Regex("\\bTarget=\"([^\"]+)\"").find(it.value) }
            if (tg != null) {
                val t = tg.groupValues[1]
                sheetPath = if (t.startsWith("/")) t.substring(1) else "xl/" + t.removePrefix("./")
            }
        }
        if (sheetPath == null || sheetPath !in ents) sheetPath = if ("xl/worksheets/sheet1.xml" in ents) "xl/worksheets/sheet1.xml" else null
        if (sheetPath == null) throw IllegalArgumentException("Aucune feuille trouvée dans le classeur.")
        val sheet = text(sheetPath) ?: ""

        val byRow = TreeMap<Int, List<String>>()
        var rowNo = 0
        for (rm in Regex("<row\\b([^>]*?)(?:/>|>([\\s\\S]*?)</row>)").findAll(sheet)) {
            val rattr = Regex("\\br=\"(\\d+)\"").find(rm.groupValues[1])
            rowNo = rattr?.groupValues?.get(1)?.toInt() ?: (rowNo + 1)
            val cells = ArrayList<String>()
            val body = rm.groups[2]?.value ?: ""
            var seq = 0
            for (cm in Regex("<c\\b([^>]*?)(?:/>|>([\\s\\S]*?)</c>)").findAll(body)) {
                val ca = cm.groupValues[1]
                val ref = Regex("\\br=\"([A-Za-z]+\\d+)\"").find(ca)
                val ci = if (ref != null) colIndex(ref.groupValues[1]) else seq
                seq = ci + 1
                val type = Regex("\\bt=\"([^\"]+)\"").find(ca)?.groupValues?.get(1)
                val inner = cm.groups[2]?.value ?: ""
                val v = Regex("<v(?:\\s[^>]*)?>([\\s\\S]*?)</v>").find(inner)
                val value = when (type) {
                    "s" -> if (v != null) shared.getOrNull(v.groupValues[1].toIntOrNull() ?: -1) ?: "" else ""
                    "inlineStr" -> textOf(inner)
                    else -> if (v != null) unesc(v.groupValues[1]) else ""
                }
                if (ci < 0) continue
                while (cells.size <= ci) cells.add("")
                cells[ci] = value
            }
            byRow[rowNo - 1] = cells
        }
        val maxRow = if (byRow.isEmpty()) -1 else byRow.lastKey()
        return (0..maxRow).map { byRow[it] ?: emptyList() }
    }

    fun isXlsx(bytes: ByteArray) = bytes.size > 3 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /** .xlsx (signature PK) ou .csv (texte UTF-8, separateur ; ou ,). */
    fun readTable(bytes: ByteArray): List<List<String>> {
        if (isXlsx(bytes)) return readXlsx(bytes)
        val text = bytes.toString(Charsets.UTF_8)
        if (text.take(2000).contains('\u0000')) throw IllegalArgumentException("Fichier non reconnu : ni .xlsx ni .csv (texte).")
        return Logic.parseCsv(text)
    }

    // ---------- lignes de tableau -> lignes de l'application ----------
    private fun fold(s: String?): String =
        Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]"), "")

    private val ALIAS = linkedMapOf(
        "code" to listOf("code", "codearticle", "article"),
        "ref" to listOf("reffournisseur", "reference", "ref", "refpiece"),
        "nom" to listOf("nom", "designation", "libelle"),
        "affectation" to listOf("affectation"),
        "marque" to listOf("marque"),
        "emplacement" to listOf("emplacement", "empl"),
        "qty" to listOf("quantite", "qte", "qty", "quantitecomptee"),
        "time" to listOf("heure", "date", "horodatage", "dateheure")
    )

    /** Lance IllegalArgumentException avec un message clair si le fichier n'est pas exploitable. */
    fun rowsToLines(rows: List<List<String>>, now: String): ImportData {
        var hr = -1
        for (i in 0 until minOf(rows.size, 10)) {
            if (rows[i].any { fold(it) in ALIAS.getValue("code") }) { hr = i; break }
        }
        if (hr < 0) throw IllegalArgumentException("Colonne « code » introuvable : la première ligne doit contenir les en-têtes (code, quantité, ...).")
        val idx = HashMap<String, Int>()
        rows[hr].forEachIndexed { ci, c ->
            val f = fold(c)
            for ((k, names) in ALIAS) if (k !in idx && f in names) idx[k] = ci
        }
        if ("qty" !in idx) throw IllegalArgumentException("Colonne « quantité » introuvable dans les en-têtes.")
        val lines = mutableListOf<Line>()
        val skipped = mutableListOf<Skipped>()
        for (r in hr + 1 until rows.size) {
            val row = rows[r]
            if (row.none { it.trim().isNotEmpty() }) continue
            fun get(k: String): String = idx[k]?.let { row.getOrNull(it) } ?: ""
            val det = Details(get("ref"), get("nom"), get("affectation"), get("marque"), get("emplacement"))
            val t = get("time").trim().ifEmpty { now }
            val res = Logic.addLine(lines, get("code"), get("qty"), "add", t, det)
            if (!res.ok) skipped.add(Skipped(r + 1, res.error))
        }
        val names = listOf("ref", "nom", "affectation", "marque", "emplacement")
        val cols = names.indices.filter { names[it] in idx }.toSet()
        return ImportData(lines, skipped, cols)
    }
}
