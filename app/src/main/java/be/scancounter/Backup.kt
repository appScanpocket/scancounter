package be.scancounter

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Sauvegarde / restauration : un seul zip (data.json + photos/). Restauration par remplacement, avec copie automatique avant. */
object Backup {
    private const val DATA = "data.json"
    private const val CSV = "stock.csv"
    private const val PHOTOS = "photos/"

    fun fileName(d: Date = Date()) = "scancounter_sauvegarde_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(d) + ".zip"

    private fun photoFiles(ctx: Context): List<File> =
        File(ctx.filesDir, "photos").listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") } ?: emptyList()

    fun write(ctx: Context, repo: Repo, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(DATA)); z.write(repo.toJson().toByteArray(Charsets.UTF_8)); z.closeEntry()
            for (f in photoFiles(ctx)) {
                z.putNextEntry(ZipEntry(PHOTOS + f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    private fun userDir(ctx: Context) = File(ctx.filesDir, "sauvegardes").apply { mkdirs() }

    /** Sauvegarde gardee dans l'espace prive de l'appli (invisible des autres applications, supprimable depuis l'appli). */
    fun saveLocal(ctx: Context, repo: Repo): File {
        val f = File(userDir(ctx), fileName())
        try { f.outputStream().use { write(ctx, repo, it) } } catch (e: Exception) { f.delete(); throw e }
        return f
    }

    /** Sauvegardes et copies automatiques gardees dans l'appli, la plus recente d'abord. */
    fun stored(ctx: Context): List<File> {
        val all = mutableListOf<File>()
        userDir(ctx).listFiles()?.let { all.addAll(it) }
        File(ctx.filesDir, "backups").listFiles()?.let { all.addAll(it) }
        return all.filter { f -> f.isFile && f.name.endsWith(".zip") }.sortedByDescending { f -> f.lastModified() }
    }

    /** Contenu lu d'un zip, en memoire (pour le resume avant remplacement). */
    class Content(val json: String, val photos: Map<String, ByteArray>)

    /** Zip de partage : stock.csv + photos des seuls articles exportes (pas de data.json : ce n'est pas une sauvegarde). */
    fun writeShare(ctx: Context, rows: List<Row>, out: OutputStream) {
        val wanted = rows.map { Photos.file(ctx, it.code).name }.toSet()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(CSV)); z.write(Logic.toCsv(rows).toByteArray(Charsets.UTF_8)); z.closeEntry()
            for (f in photoFiles(ctx)) if (f.name in wanted) {
                z.putNextEntry(ZipEntry(PHOTOS + f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    fun shareFileName(d: Date = Date()) = "scancounter_partage_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(d) + ".zip"

    /** Contenu d'un zip recu : data.json (sauvegarde) et/ou stock.csv (partage), plus les photos. */
    class Incoming(val json: String?, val csv: ByteArray?, val photos: Map<String, ByteArray>)

    fun readAny(input: InputStream): Incoming {
        var json: String? = null
        var csv: ByteArray? = null
        val photos = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                if (e.isDirectory) continue
                if (name == DATA) json = z.readBytes().toString(Charsets.UTF_8)
                else if (name == CSV) csv = z.readBytes()
                else if (name.startsWith(PHOTOS) && name.endsWith(".jpg")) {
                    val base = name.removePrefix(PHOTOS)
                    if (!base.contains('/') && !base.contains("..")) photos[base] = z.readBytes()
                }
            }
        }
        if (json == null && csv == null) throw IllegalArgumentException("Ce fichier n'est pas un zip Scancounter (sauvegarde ou partage).")
        json?.let { org.json.JSONObject(it) }
        return Incoming(json, csv, photos)
    }

    /** Lit et valide un zip de sauvegarde. Leve une exception si ce n'est pas une sauvegarde Scancounter. */
    fun read(input: InputStream): Content {
        val i = readAny(input)
        val j = i.json ?: throw IllegalArgumentException("Ce fichier n'est pas une sauvegarde Scancounter.")
        return Content(j, i.photos)
    }

    /** Lignes de stock d'un data.json (une par ligne de stock, avec les infos de son produit), pour une fusion. */
    fun rowsFromJson(json: String): List<Row> {
        val o = org.json.JSONObject(json)
        val prods = HashMap<String, org.json.JSONObject>()
        o.optJSONArray("products")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { prods[it.getString("id")] = it } }
        val rows = mutableListOf<Row>()
        val lines = o.optJSONArray("lines") ?: return rows
        for (k in 0 until lines.length()) {
            val l = lines.getJSONObject(k)
            val p = prods[l.getString("productId")] ?: continue
            rows.add(Row(p.optString("code"), p.optString("ref"), p.optString("nom"), p.optString("affectation"), p.optString("marque"),
                l.optString("magasin"), l.optString("emplacement"), l.optDouble("qty", 0.0), l.optString("modified")))
        }
        return rows
    }

    fun summary(c: Content): DataSummary {
        val o = org.json.JSONObject(c.json)
        fun n(k: String) = o.optJSONArray(k)?.length() ?: 0
        return DataSummary(n("products"), n("lines"), n("inventories"), n("counts"), c.photos.size)
    }

    /** Copie de l'etat actuel dans filesDir/backups, avant un remplacement. Garde les 5 plus recentes. */
    fun autoBackup(ctx: Context, repo: Repo): File {
        val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val f = File(dir, "avant_restauration_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".zip")
        f.outputStream().use { write(ctx, repo, it) }
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        return f
    }

    /** Restauration par remplacement : sauvegarde auto, puis donnees et photos remplacees. */
    fun restore(ctx: Context, repo: Repo, c: Content): File {
        val backup = autoBackup(ctx, repo)
        repo.replaceWith(c.json)
        val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        for ((name, bytes) in c.photos) File(dir, name).writeBytes(bytes)
        return backup
    }
}
