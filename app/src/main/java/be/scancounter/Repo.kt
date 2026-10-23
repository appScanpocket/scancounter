package be.scancounter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Donnees de l'app : produits, lignes de stock, inventaires, comptages.
 * Un seul fichier JSON (filesDir/data.json), ecrit de facon atomique. Chaque element a un id unique et une date de modification
 * (prevu pour une future fusion de sauvegardes).
 */
class Repo(private val ctx: Context) {
    val products = mutableListOf<Product>()
    val lines = mutableListOf<StockLine>()
    val inventories = mutableListOf<Inventory>()
    val counts = mutableListOf<Count>()

    init { load() }

    // ---- fichier ----
    private fun file() = File(ctx.filesDir, FILE)

    fun load() {
        products.clear(); lines.clear(); inventories.clear(); counts.clear()
        val f = file()
        if (!f.exists()) return
        try { parse(f.readText(Charsets.UTF_8)) } catch (_: Exception) { }
    }

    fun save() {
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(toJson(), Charsets.UTF_8)
        val f = file()
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("products", JSONArray().also { a -> products.forEach { p -> a.put(JSONObject().apply {
            put("id", p.id); put("code", p.code); put("ref", p.ref); put("nom", p.nom)
            put("affectation", p.affectation); put("marque", p.marque); put("modified", p.modified)
            put("alts", JSONArray(p.alts)) }) } })
        put("lines", JSONArray().also { a -> lines.forEach { l -> a.put(JSONObject().apply {
            put("id", l.id); put("productId", l.productId); put("magasin", l.magasin)
            put("emplacement", l.emplacement); put("qty", l.qty); put("modified", l.modified) }) } })
        put("inventories", JSONArray().also { a -> inventories.forEach { i -> a.put(JSONObject().apply {
            put("id", i.id); put("name", i.name); put("magasin", i.magasin); put("zone", i.zone); put("open", i.open)
            put("created", i.created); put("modified", i.modified); put("ended", i.ended) }) } })
        put("counts", JSONArray().also { a -> counts.forEach { c -> a.put(JSONObject().apply {
            put("id", c.id); put("inventoryId", c.inventoryId); put("lineId", c.lineId)
            put("qty", c.qty); put("modified", c.modified); put("expected", c.expected) }) } })
    }.toString()

    private fun parse(text: String) {
        val o = JSONObject(text)
        o.optJSONArray("products")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { j ->
            products.add(Product(j.getString("id"), j.optString("code"), j.optString("ref"), j.optString("nom"),
                j.optString("affectation"), j.optString("marque"), j.optString("modified"),
                j.optJSONArray("alts")?.let { x -> (0 until x.length()).map { i -> x.getString(i) }.toMutableList() } ?: mutableListOf())) } }
        o.optJSONArray("lines")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { j ->
            lines.add(StockLine(j.getString("id"), j.getString("productId"), j.optString("magasin"),
                j.optString("emplacement"), j.optDouble("qty", 0.0), j.optString("modified"))) } }
        o.optJSONArray("inventories")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { j ->
            inventories.add(Inventory(j.getString("id"), j.optString("name"), j.optString("magasin"), j.optString("zone"),
                j.optBoolean("open", true), j.optString("created"), j.optString("modified"), j.optString("ended"))) } }
        o.optJSONArray("counts")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { j ->
            counts.add(Count(j.getString("id"), j.getString("inventoryId"), j.getString("lineId"),
                j.optDouble("qty", 0.0), j.optString("modified"), j.optDouble("expected", -1.0))) } }
    }

    /** Remplace tout le contenu par celui d'un data.json (restauration). Leve une exception si le texte est invalide. */
    fun replaceWith(json: String) {
        val bp = products.toList(); val bl = lines.toList(); val bi = inventories.toList(); val bc = counts.toList()
        products.clear(); lines.clear(); inventories.clear(); counts.clear()
        try { parse(json) } catch (e: Exception) {
            products.addAll(bp); lines.addAll(bl); inventories.addAll(bi); counts.addAll(bc)
            throw e
        }
        save()
    }

    fun summary(photoCount: Int) = DataSummary(products.size, lines.size, inventories.size, counts.size, photoCount)

    // ---- produits ----
    fun productById(id: String) = products.firstOrNull { it.id == id }
    /** Produit reconnu par son code actuel ou par l'un de ses autres codes. */
    fun productByCode(code: String) = products.firstOrNull { it.hasCode(code) }

    /** Message d'erreur si le code est deja pris par un AUTRE produit (code actuel ou autre code), sinon null. */
    fun codeConflict(p: Product?, codeIn: String): String? {
        val code = Logic.normCode(codeIn)
        if (code.isEmpty()) return "Code vide."
        val other = products.firstOrNull { it !== p && it.id != p?.id && it.hasCode(code) } ?: return null
        return "Ce code est déjà celui de « ${other.nom.ifEmpty { other.code }} »."
    }

    /** Le code devient le code actuel du produit ; l'ancien reste reconnu (autres codes) et la photo suit. Retourne une erreur ou null. */
    fun changeCode(p: Product, codeIn: String): String? {
        val code = Logic.normCode(codeIn)
        codeConflict(p, code)?.let { return it }
        if (code == p.code) return null
        val old = p.code
        if (!p.alts.contains(old)) p.alts.add(old)
        p.alts.remove(code)
        p.code = code
        p.modified = Logic.nowIso()
        Photos.rename(ctx, old, code)
        return null
    }

    /** Ajoute un autre code reconnu pour le produit. Retourne une erreur ou null. */
    fun addAlt(p: Product, codeIn: String): String? {
        val code = Logic.normCode(codeIn)
        codeConflict(p, code)?.let { return it }
        if (code != p.code && !p.alts.contains(code)) { p.alts.add(code); p.modified = Logic.nowIso() }
        return null
    }

    fun allCodes(): List<String> = products.flatMap { listOf(it.code) + it.alts }
    fun linesOf(productId: String) = lines.filter { it.productId == productId }
    fun lineById(id: String) = lines.firstOrNull { it.id == id }

    /** Cree ou met a jour la fiche du code (champs normalises). */
    fun upsertProduct(codeIn: String, ref: String, nom: String, affectation: String, marque: String): Product {
        val code = Logic.normCode(codeIn)
        val now = Logic.nowIso()
        val p = productByCode(code) ?: Product(newId(), code).also { products.add(it) }
        p.ref = Logic.normField(ref); p.nom = Logic.normField(nom)
        p.affectation = Logic.normField(affectation); p.marque = Logic.normField(marque)
        p.modified = now
        return p
    }

    // ---- lignes de stock ----
    fun findLine(productId: String, magasin: String, emplacement: String): StockLine? =
        lines.firstOrNull { it.productId == productId && it.magasin.equals(magasin, true) && it.emplacement.equals(emplacement, true) }

    /** Ajoute une ligne (erreur si le couple produit/magasin/emplacement existe deja). Retourne null si doublon. */
    fun addLine(productId: String, magasinIn: String, emplacementIn: String, qty: Double): StockLine? {
        val m = Logic.normField(magasinIn); val e = Logic.normField(emplacementIn)
        if (findLine(productId, m, e) != null) return null
        return StockLine(newId(), productId, m, e, qty, Logic.nowIso()).also { lines.add(it) }
    }

    /** Modifie une ligne ; false si le nouveau couple magasin/emplacement existe deja pour ce produit. */
    fun updateLine(line: StockLine, magasinIn: String, emplacementIn: String, qty: Double): Boolean {
        val m = Logic.normField(magasinIn); val e = Logic.normField(emplacementIn)
        val other = findLine(line.productId, m, e)
        if (other != null && other !== line) return false
        line.magasin = m; line.emplacement = e; line.qty = qty; line.modified = Logic.nowIso()
        return true
    }

    /** Supprime une ligne et ses comptages ; si c'est la derniere du produit, supprime aussi la fiche et la photo. */
    fun deleteLine(line: StockLine) {
        lines.remove(line)
        counts.removeAll { it.lineId == line.id }
        if (linesOf(line.productId).isEmpty()) {
            productById(line.productId)?.let { p -> products.remove(p); Photos.delete(ctx, p.code) }
        }
    }

    /** Suggestions pour l'autocompletion : valeurs deja saisies, sans doublon, triees. */
    fun distinctMagasins(): List<String> = lines.map { it.magasin }.filter { it.isNotEmpty() }.distinct().sortedBy { Logic.foldText(it) }
    fun distinctEmplacements(magasin: String? = null): List<String> =
        lines.filter { magasin == null || it.magasin.equals(magasin, true) }.map { it.emplacement }.filter { it.isNotEmpty() }
            .distinct().sortedBy { Logic.foldText(it) }

    fun distinctMarques(): List<String> = products.map { it.marque }.filter { it.isNotEmpty() }.distinct().sortedBy { Logic.foldText(it) }
    fun distinctAffectations(): List<String> = products.map { it.affectation }.filter { it.isNotEmpty() }.distinct().sortedBy { Logic.foldText(it) }

    fun cards(): List<Card> {
        val byId = products.associateBy { it.id }
        return lines.mapNotNull { l -> byId[l.productId]?.let { Card(l, it) } }
    }

    /** Remet toutes les quantites du stock a zero ; retourne le nombre de lignes qui en avaient une. */
    fun resetQuantities(): Int {
        val now = Logic.nowIso(); var n = 0
        lines.forEach { if (it.qty != 0.0) { n++; it.qty = 0.0; it.modified = now } }
        return n
    }

    /** Efface le stock (produits, lignes, comptages) ; les inventaires et les photos sont gardes ou effaces selon withAll. */
    fun clearAll() { products.clear(); lines.clear(); inventories.clear(); counts.clear() }

    /** Remplace le stock par les lignes importees (les inventaires sont gardes, leurs comptages sont effaces). */
    fun clearStock() { products.clear(); lines.clear(); counts.clear() }

    // ---- inventaires ----
    fun openInventory(): Inventory? = inventories.firstOrNull { it.open }

    fun createInventory(name: String, magasin: String, zone: String): Inventory {
        val now = Logic.nowIso()
        return Inventory(newId(), Logic.normField(name), Logic.normField(magasin), Logic.normField(zone), true, now, now)
            .also { inventories.add(it) }
    }

    fun countOf(inv: Inventory, line: StockLine) = counts.firstOrNull { it.inventoryId == inv.id && it.lineId == line.id }

    /** Enregistre la quantite comptee d'une ligne (remplace le comptage precedent). */
    fun setCount(inv: Inventory, line: StockLine, qty: Double) {
        val now = Logic.nowIso()
        val c = countOf(inv, line)
        if (c == null) counts.add(Count(newId(), inv.id, line.id, qty, now)) else { c.qty = qty; c.modified = now }
        inv.modified = now
    }

    fun clearCount(inv: Inventory, line: StockLine) { counts.removeAll { it.inventoryId == inv.id && it.lineId == line.id } }

    fun scope(inv: Inventory) = lines.filter { inv.covers(it) }

    /** Apercu de Terminer : lignes du perimetre, comptees (ecart ou non) et non comptees. */
    fun previewFinish(inv: Inventory): FinishResult {
        var applied = 0; var same = 0; var none = 0
        for (l in scope(inv)) {
            val c = countOf(inv, l)
            if (c == null) none++ else if (c.qty != l.qty) applied++ else same++
        }
        return FinishResult(applied, same, none)
    }

    /** Terminer : les quantites comptees remplacent celles du stock ; les lignes non comptees ne changent pas. */
    fun finishInventory(inv: Inventory): FinishResult {
        val res = previewFinish(inv)
        val now = Logic.nowIso()
        for (l in scope(inv)) {
            val c = countOf(inv, l) ?: continue
            c.expected = l.qty
            if (c.qty != l.qty) { l.qty = c.qty; l.modified = now }
        }
        inv.open = false; inv.ended = now; inv.modified = now
        return res
    }

    fun deleteInventory(inv: Inventory) {
        inventories.remove(inv)
        counts.removeAll { it.inventoryId == inv.id }
    }

    private companion object { const val FILE = "data.json" }
}
