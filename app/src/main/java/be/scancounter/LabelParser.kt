package be.scancounter

import java.text.Normalizer
import kotlin.math.abs

/** Une ligne de texte reconnue par l'OCR, avec sa position dans l'image. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

data class LabelFields(
    val code: String = "",
    val reference: String = "",
    val nom: String = "",
    val affectation: String = "",
    val marque: String = "",
    val emplacement: String = ""
) {
    fun isEmpty() = listOf(code, reference, nom, affectation, marque, emplacement).all { it.isBlank() }
}

/**
 * Associe les libelles de l'etiquette (Reference :, Nom :, ...) a leur valeur.
 * L'OCR separe souvent la colonne des libelles de celle des valeurs : on rattache
 * donc chaque valeur au libelle le plus proche sur la meme rangee, a sa droite.
 */
object LabelParser {
    private val keys = mapOf(
        "code" to "code", "codearticle" to "code",
        "reference" to "reference", "ref" to "reference", "reffournisseur" to "reference",
        "nom" to "nom",
        "affectation" to "affectation",
        "marque" to "marque",
        "emplacement" to "emplacement"
    )

    private class Lab(val key: String, val line: OcrLine, val inline: String)

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z]"), "")

    private fun detect(l: OcrLine): Lab? {
        val idx = l.text.indexOf(':')
        if (idx >= 0) {
            val key = keys[normalize(l.text.substring(0, idx))] ?: return null
            return Lab(key, l, l.text.substring(idx + 1).trim())
        }
        // Deux-points non lu : la ligne ne contient que le libelle.
        val key = keys[normalize(l.text)] ?: return null
        return Lab(key, l, "")
    }

    fun parse(lines: List<OcrLine>): LabelFields {
        val labels = mutableListOf<Lab>()
        val others = mutableListOf<OcrLine>()
        for (l in lines) {
            val lab = detect(l)
            if (lab != null) labels.add(lab) else others.add(l)
        }
        if (labels.isEmpty()) return LabelFields()

        val values = mutableMapOf<Lab, MutableList<OcrLine>>()
        val assigned = mutableSetOf<OcrLine>()
        for (o in others) {
            val best = labels
                .filter { o.left > it.line.centerX }
                .minByOrNull { abs(o.centerY - it.line.centerY) } ?: continue
            if (abs(o.centerY - best.line.centerY) < 1.5 * best.line.height) {
                values.getOrPut(best) { mutableListOf() }.add(o)
                assigned.add(o)
            }
        }

        fun valueOf(key: String): String {
            for (lab in labels.filter { it.key == key }) {
                val parts = listOf(lab.inline) + (values[lab] ?: emptyList<OcrLine>()).sortedBy { it.left }.map { it.text }
                val v = parts.filter { it.isNotBlank() }.joinToString(" ").trim()
                if (v.isNotBlank()) return v
            }
            return ""
        }

        // Le code article est le plus grand nombre non rattache a un libelle.
        val code = others
            .filter { it !in assigned }
            .filter { l -> l.text.replace(" ", "").let { t -> t.length >= 3 && t.all { it.isDigit() } } }
            .maxByOrNull { it.height }
            ?.text?.replace(" ", "") ?: ""

        return LabelFields(
            code = valueOf("code").ifBlank { code },
            // Le caractere parasite « _ » devant la reference est ignore.
            reference = valueOf("reference").trimStart('_', ' '),
            nom = valueOf("nom"),
            affectation = valueOf("affectation"),
            marque = valueOf("marque"),
            emplacement = valueOf("emplacement")
        )
    }
}
