package com.exemplo.fotostv

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Album em disco. Sem banco de dados: cada foto e um JPEG em filesDir/photos,
 * com uma miniatura correspondente em filesDir/thumbs e um arquivo .txt ao
 * lado guardando quem enviou. O timestamp vive no proprio nome do arquivo,
 * entao nada se perde se a data do aparelho mudar.
 */
class PhotoStore(context: Context) {

    val photosDir = File(context.filesDir, "photos")
    val thumbsDir = File(context.filesDir, "thumbs")

    /** Sobe a cada alteracao; a TV usa isso para saber se precisa recarregar. */
    private val revision = AtomicLong(1)

    @Volatile
    private var cache: List<Photo>? = null

    init {
        photosDir.mkdirs()
        thumbsDir.mkdirs()
    }

    data class Photo(
        val name: String,
        val timestamp: Long,
        val author: String,
        val caption: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("url", "/photos/" + name)
            put("thumb", "/thumbs/" + name)
            put("timestamp", timestamp)
            put("author", author)
            put("caption", caption)
        }
    }

    fun revision(): Long = revision.get()

    /** Fotos da mais antiga para a mais nova. */
    @Synchronized
    fun list(): List<Photo> {
        cache?.let { return it }

        val files = photosDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
        val result = (files ?: emptyArray())
            .map { f -> Photo(f.name, timestampOf(f), readMeta(f, 0), readMeta(f, 1)) }
            .sortedBy { it.timestamp }

        cache = result
        return result
    }

    fun listJson(limit: Int): JSONArray {
        val all = list()
        val from = if (limit in 1 until all.size) all.size - limit else 0
        val arr = JSONArray()
        for (i in from until all.size) arr.put(all[i].toJson())
        return arr
    }

    fun photoFile(name: String): File? = resolveSafe(photosDir, name)

    fun thumbFile(name: String): File? {
        val thumb = resolveSafe(thumbsDir, name) ?: return null
        if (thumb.exists()) return thumb
        // Miniatura ausente (versao antiga, ou app reinstalado): gera sob demanda
        val original = resolveSafe(photosDir, name) ?: return null
        if (!original.exists()) return null
        return try {
            if (ImageUtils.writeThumb(original, thumb)) thumb else original
        } catch (e: Exception) {
            original
        }
    }

    /**
     * Converte o arquivo temporario recebido no upload numa foto do album.
     * Retorna null se o arquivo nao for uma imagem decodificavel.
     */
    @Synchronized
    fun save(
        temp: File,
        author: String,
        caption: String,
        maxDimension: Int,
        maxPhotos: Int
    ): Photo? {
        val ts = System.currentTimeMillis()
        val name = "p_" + ts + "_" + (1000..9999).random() + ".jpg"
        val target = File(photosDir, name)

        val ok = try {
            ImageUtils.processToJpeg(temp, target, maxDimension)
        } catch (e: Throwable) {
            Log.w(TAG, "falha ao processar imagem", e)
            false
        }

        if (!ok) {
            target.delete()
            return null
        }

        try {
            ImageUtils.writeThumb(target, File(thumbsDir, name))
        } catch (e: Throwable) {
            Log.w(TAG, "falha ao gerar miniatura", e)
        }

        val cleanAuthor = sanitize(author, 40)
        val cleanCaption = sanitize(caption, 120)
        if (cleanAuthor.isNotEmpty() || cleanCaption.isNotEmpty()) {
            try {
                File(photosDir, name + ".txt").writeText(cleanAuthor + "\n" + cleanCaption)
            } catch (e: Exception) {
                Log.w(TAG, "falha ao gravar metadados", e)
            }
        }

        invalidate()
        enforceLimit(maxPhotos)
        return Photo(name, ts, cleanAuthor, cleanCaption)
    }

    @Synchronized
    fun delete(name: String): Boolean {
        val file = resolveSafe(photosDir, name) ?: return false
        if (!file.exists()) return false
        file.delete()
        File(photosDir, name + ".txt").delete()
        resolveSafe(thumbsDir, name)?.delete()
        invalidate()
        return true
    }

    @Synchronized
    fun clearAll(): Int {
        val all = list()
        photosDir.listFiles()?.forEach { it.delete() }
        thumbsDir.listFiles()?.forEach { it.delete() }
        invalidate()
        return all.size
    }

    /** Mantem o album dentro do limite, descartando as fotos mais antigas. */
    @Synchronized
    fun enforceLimit(maxPhotos: Int) {
        val all = list()
        if (all.size <= maxPhotos) return
        all.take(all.size - maxPhotos).forEach { photo ->
            File(photosDir, photo.name).delete()
            File(photosDir, photo.name + ".txt").delete()
            File(thumbsDir, photo.name).delete()
        }
        invalidate()
    }

    fun totalBytes(): Long =
        (photosDir.listFiles()?.sumOf { it.length() } ?: 0L) +
            (thumbsDir.listFiles()?.sumOf { it.length() } ?: 0L)

    private fun invalidate() {
        cache = null
        revision.incrementAndGet()
    }

    /** Impede que um nome vindo da rede escape do diretorio do album. */
    private fun resolveSafe(dir: File, name: String): File? {
        if (name.isBlank()) return null
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return null
        if (!name.endsWith(".jpg")) return null
        return File(dir, name)
    }

    private fun timestampOf(file: File): Long {
        // p_<epochMillis>_<rand>.jpg
        val parts = file.name.split("_")
        if (parts.size >= 2) parts[1].toLongOrNull()?.let { return it }
        return file.lastModified()
    }

    private fun readMeta(photo: File, line: Int): String {
        val meta = File(photo.parentFile, photo.name + ".txt")
        if (!meta.exists()) return ""
        return try {
            meta.readText().split("\n").getOrNull(line)?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun sanitize(value: String, max: Int): String =
        value.replace("\n", " ").replace("\r", " ").trim().take(max)

    private companion object {
        const val TAG = "PhotoStore"
    }
}
