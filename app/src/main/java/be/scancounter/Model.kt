package be.scancounter

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** Fiche produit, commune a toutes ses lignes de stock. La photo est rangee par code (Photos.kt). alts = autres codes reconnus au scan. */
class Product(
    val id: String,
    var code: String,
    var ref: String = "",
    var nom: String = "",
    var affectation: String = "",
    var marque: String = "",
    var modified: String = "",
    val alts: MutableList<String> = mutableListOf()
) {
    fun copyOf() = Product(id, code, ref, nom, affectation, marque, modified, alts.toMutableList())
    fun hasCode(c: String) = code == c || alts.contains(c)
}

/** Une carte de l'ecran Articles : produit + magasin + emplacement + quantite. */
class StockLine(
    val id: String,
    val productId: String,
    var magasin: String = "",
    var emplacement: String = "",
    var qty: Double = 0.0,
    var modified: String = ""
) {
    fun copyOf() = StockLine(id, productId, magasin, emplacement, qty, modified)
}

/** Session de comptage. magasin vide = tous ; zone = debut d'emplacement (vide = tout). */
class Inventory(
    val id: String,
    var name: String,
    var magasin: String = "",
    var zone: String = "",
    var open: Boolean = true,
    val created: String = "",
    var modified: String = "",
    var ended: String = ""
) {
    /** Une ligne fait partie du perimetre si son magasin et le debut de son emplacement correspondent. */
    fun covers(l: StockLine): Boolean =
        (magasin.isEmpty() || l.magasin.equals(magasin, ignoreCase = true)) &&
            (zone.isEmpty() || l.emplacement.startsWith(zone, ignoreCase = true))
}

/** Quantite comptee d'une ligne de stock dans une session (separee du stock jusqu'a Terminer). */
class Count(
    val id: String,
    val inventoryId: String,
    val lineId: String,
    var qty: Double,
    var modified: String = "",
    /** Stock au moment de Terminer (garde pour le rapport d'un inventaire termine) ; -1 = pas encore fige. */
    var expected: Double = -1.0
)

/** Resume d'un contenu de sauvegarde, affiche avant une restauration. */
data class DataSummary(val products: Int, val lines: Int, val inventories: Int, val counts: Int, val photos: Int) {
    fun text(): String = listOf(
        "Produits : $products", "Lignes de stock : $lines", "Inventaires : $inventories",
        "Comptages : $counts", "Photos : $photos"
    ).joinToString("\n")
}

/** Resultat de Terminer : combien de lignes ont change. */
data class FinishResult(val applied: Int, val unchanged: Int, val notCounted: Int)
