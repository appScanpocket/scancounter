package be.scancounter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Petits reglages locaux (SharedPreferences) : dernier export, inventaire en cours de comptage, journal des 10 dernieres actions. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("scancounter", Context.MODE_PRIVATE)

    var lastExport: String
        get() = prefs.getString(KEY_EXPORT, "") ?: ""
        set(v) { prefs.edit().putString(KEY_EXPORT, v).apply() }

    /** Id de l'inventaire en cours de comptage (vide = aucun). */
    var currentInv: String
        get() = prefs.getString(KEY_INV, "") ?: ""
        set(v) { prefs.edit().putString(KEY_INV, v).apply() }

    fun journal(): List<JournalEntry> {
        val out = mutableListOf<JournalEntry>()
        try {
            val arr = JSONArray(prefs.getString(KEY_LOG, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(JournalEntry(o.optString("t"), o.optString("action"), o.optString("result")))
            }
        } catch (_: Exception) { }
        return out
    }

    fun logAction(action: String, result: String) {
        val list = (listOf(JournalEntry(Logic.nowIso(), action, result)) + journal()).take(10)
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().apply { put("t", e.t); put("action", e.action); put("result", e.result) })
        prefs.edit().putString(KEY_LOG, arr.toString()).apply()
    }

    private companion object {
        const val KEY_EXPORT = "dernier_export"
        const val KEY_INV = "inventaire_en_cours"
        const val KEY_LOG = "journal"
    }
}
