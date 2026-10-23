package be.scancounter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stockage local (SharedPreferences) : lignes, dernier export, journal des 10 dernieres actions. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("scancounter", Context.MODE_PRIVATE)

    fun loadLines(): MutableList<Line> {
        val out = mutableListOf<Line>()
        try {
            val arr = JSONArray(prefs.getString(KEY_LINES, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Line(
                    o.optString("code"),
                    Details(o.optString("ref"), o.optString("nom"), o.optString("affectation"), o.optString("marque"), o.optString("emplacement")).clean(),
                    o.optDouble("qty", 0.0),
                    o.optString("time")
                ))
            }
        } catch (_: Exception) { }
        return out
    }

    fun saveLines(lines: List<Line>) {
        val arr = JSONArray()
        for (l in lines) arr.put(JSONObject().apply {
            put("code", l.code); put("ref", l.d.ref); put("nom", l.d.nom); put("affectation", l.d.affectation)
            put("marque", l.d.marque); put("emplacement", l.d.emplacement); put("qty", l.qty); put("time", l.time)
        })
        prefs.edit().putString(KEY_LINES, arr.toString()).apply()
    }

    var lastExport: String
        get() = prefs.getString(KEY_EXPORT, "") ?: ""
        set(v) { prefs.edit().putString(KEY_EXPORT, v).apply() }

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
        const val KEY_LINES = "lignes"
        const val KEY_EXPORT = "dernier_export"
        const val KEY_LOG = "journal"
    }
}
