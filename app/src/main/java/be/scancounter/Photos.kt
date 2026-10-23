package be.scancounter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Photos d'articles : un fichier JPEG par code (filesDir/photos), sans toucher au modele Line ni a l'export. */
object Photos {
    private const val MAX_SIDE = 1280

    private fun dir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun file(ctx: Context, code: String): File {
        val hex = code.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val name = if (hex.length <= 200) hex else Integer.toHexString(code.hashCode()) + hex.take(150)
        return File(dir(ctx), "$name.jpg")
    }

    fun fileIfExists(ctx: Context, code: String): File? = file(ctx, code).takeIf { it.exists() }

    fun delete(ctx: Context, code: String) { file(ctx, code).delete() }

    /** La photo suit le produit quand son code change (ecrase une photo orpheline du nouveau code). */
    fun rename(ctx: Context, oldCode: String, newCode: String) {
        val from = file(ctx, oldCode); if (!from.exists()) return
        val to = file(ctx, newCode); if (to.exists()) to.delete()
        if (!from.renameTo(to)) { from.copyTo(to, true); from.delete() }
    }

    fun deleteAll(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    fun save(ctx: Context, code: String, bmp: Bitmap) {
        file(ctx, code).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    fun scaleDown(b: Bitmap, maxSide: Int = MAX_SIDE): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= maxSide) return b
        val k = maxSide.toFloat() / big
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
    }

    /** Decodage reduit (vignettes) : evite de charger une grande image pour une petite case. */
    fun decode(f: File, targetPx: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= targetPx) s *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Exception) { null }
    }
}
