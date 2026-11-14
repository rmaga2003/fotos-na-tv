package com.exemplo.fotostv

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Servidor HTTP que roda dentro do proprio Fire TV / Android TV.
 *
 * E ele que substitui o notebook do projeto anterior: os celulares da festa
 * falam direto com o aparelho que esta ligado na TV, pela rede Wi-Fi local,
 * sem internet e sem nenhum servidor na nuvem.
 */
class PhotoServer(
    private val context: Context,
    private val store: PhotoStore,
    private val settings: Settings,
    port: Int
) : NanoHTTPD(port) {

    /** Chamado quando uma foto nova chega (a TV usa para reagir na hora). */
    var onPhotoAdded: ((PhotoStore.Photo) -> Unit)? = null

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Throwable) {
            Log.e(TAG, "erro ao atender " + session.uri, e)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "erro interno"))
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        val method = session.method

        // ---------- paginas ----------
        when (uri) {
            // O convidado que escaneia o QR cai direto na pagina de enviar foto
            "/" -> return asset("upload.html")
            "/upload" -> return asset("upload.html")
            "/tv" -> return asset("tv.html")
            "/album" -> return asset("album.html")
            "/admin" -> return asset("admin.html")
            "/favicon.ico" -> return notFound()
        }

        if (uri.startsWith("/static/")) {
            return asset(uri.removePrefix("/static/"))
        }

        // ---------- arquivos do album ----------
        if (uri.startsWith("/photos/")) {
            val file = store.photoFile(uri.removePrefix("/photos/"))
            return serveImage(file)
        }
        if (uri.startsWith("/thumbs/")) {
            val file = store.thumbFile(uri.removePrefix("/thumbs/"))
            return serveImage(file)
        }

        // ---------- API ----------
        return when {
            uri == "/api/state" && method == Method.GET -> apiState()
            uri == "/api/info" && method == Method.GET -> apiInfo()
            uri == "/api/photos" && method == Method.GET -> apiPhotos(session)
            uri == "/api/qr" && method == Method.GET -> apiQr(session)
            uri == "/api/upload" && method == Method.POST -> apiUpload(session)
            uri == "/api/settings" && method == Method.GET -> json(Response.Status.OK, settings.toJson())
            uri == "/api/settings" && method == Method.POST -> apiSaveSettings(session)
            uri == "/api/admin/check" -> apiAdminCheck(session)
            uri == "/api/photos/delete" && method == Method.POST -> apiDelete(session)
            uri == "/api/photos/clear" && method == Method.POST -> apiClear(session)
            uri == "/api/download-all" && method == Method.GET -> apiDownloadAll(session)
            else -> notFound()
        }
    }

    // ------------------------------------------------------------------
    // Endpoints
    // ------------------------------------------------------------------

    private fun apiState(): Response {
        val all = store.list()
        val body = JSONObject().apply {
            put("revision", store.revision())
            put("count", all.size)
            put("lastTimestamp", all.lastOrNull()?.timestamp ?: 0L)
            put("serverTime", System.currentTimeMillis())
        }
        return noStore(json(Response.Status.OK, body))
    }

    private fun apiInfo(): Response {
        val ip = NetUtils.localIpv4()
        val baseUrl = if (ip != null) "http://" + ip + ":" + listeningPort else null
        val body = JSONObject().apply {
            put("ip", ip ?: JSONObject.NULL)
            put("port", listeningPort)
            put("baseUrl", baseUrl ?: JSONObject.NULL)
            put("uploadUrl", baseUrl ?: JSONObject.NULL)
            put("albumUrl", if (baseUrl != null) baseUrl + "/album" else JSONObject.NULL)
            put("adminUrl", if (baseUrl != null) baseUrl + "/admin" else JSONObject.NULL)
            put("version", BuildConfig.VERSION_NAME)
            put("photoCount", store.list().size)
            put("storageBytes", store.totalBytes())
            put("settings", settings.toJson())
            put("wifiPayload", QrUtils.wifiPayload(settings.wifiSsid, settings.wifiPassword, settings.wifiSecurity))
            put("hasWifi", settings.wifiSsid.isNotBlank())
        }
        return noStore(json(Response.Status.OK, body))
    }

    private fun apiPhotos(session: IHTTPSession): Response {
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 500
        val body = JSONObject().apply {
            put("revision", store.revision())
            put("serverTime", System.currentTimeMillis())
            put("photos", store.listJson(limit.coerceIn(1, 5000)))
        }
        return noStore(json(Response.Status.OK, body))
    }

    private fun apiQr(session: IHTTPSession): Response {
        val data = session.parameters["d"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "faltou o parametro d"))
        val size = session.parameters["s"]?.firstOrNull()?.toIntOrNull() ?: 320

        val png = QrUtils.pngFor(data, size)
        val response = newFixedLengthResponse(
            Response.Status.OK, "image/png", ByteArrayInputStream(png), png.size.toLong()
        )
        // O QR so muda se os parametros mudarem, entao pode ficar em cache
        response.addHeader("Cache-Control", "public, max-age=86400")
        return response
    }

    private fun apiUpload(session: IHTTPSession): Response {
        val declared = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (declared > MAX_UPLOAD_BYTES) {
            return json(
                Response.Status.PAYLOAD_TOO_LARGE,
                JSONObject().put("error", "Foto muito grande (limite de 30 MB).")
            )
        }

        val parts = HashMap<String, String>()
        session.parseBody(parts)

        val tempPath = parts["file"] ?: parts.values.firstOrNull()
        if (tempPath.isNullOrBlank()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Nenhuma foto recebida."))
        }

        val temp = File(tempPath)
        if (!temp.exists() || temp.length() == 0L) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Arquivo vazio."))
        }

        val author = session.parameters["author"]?.firstOrNull().orEmpty()
        val caption = session.parameters["caption"]?.firstOrNull().orEmpty()

        val photo = store.save(temp, author, caption, settings.maxDimension, settings.maxPhotos)
            ?: return json(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Nao consegui ler essa imagem. Tente outra foto.")
            )

        onPhotoAdded?.invoke(photo)

        return noStore(
            json(Response.Status.OK, JSONObject().put("ok", true).put("photo", photo.toJson()))
        )
    }

    private fun apiSaveSettings(session: IHTTPSession): Response {
        if (!isAdmin(session)) return forbidden()

        val parts = HashMap<String, String>()
        session.parseBody(parts)

        val raw = parts["postData"]
        val body = if (!raw.isNullOrBlank()) {
            JSONObject(raw)
        } else {
            JSONObject().apply {
                session.parameters.forEach { (key, values) ->
                    values.firstOrNull()?.let { put(key, it) }
                }
            }
        }

        settings.applyJson(body)
        store.enforceLimit(settings.maxPhotos)
        return noStore(json(Response.Status.OK, settings.toJson()))
    }

    private fun apiAdminCheck(session: IHTTPSession): Response {
        val body = JSONObject()
            .put("required", settings.adminPin.isNotEmpty())
            .put("ok", isAdmin(session))
        return noStore(json(Response.Status.OK, body))
    }

    private fun apiDelete(session: IHTTPSession): Response {
        if (!isAdmin(session)) return forbidden()

        val parts = HashMap<String, String>()
        session.parseBody(parts)

        val name = session.parameters["name"]?.firstOrNull()
            ?: session.parameters["filename"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "faltou o nome"))

        val removed = store.delete(name)
        return noStore(json(Response.Status.OK, JSONObject().put("ok", removed)))
    }

    private fun apiClear(session: IHTTPSession): Response {
        if (!isAdmin(session)) return forbidden()
        val parts = HashMap<String, String>()
        session.parseBody(parts)
        val removed = store.clearAll()
        return noStore(json(Response.Status.OK, JSONObject().put("ok", true).put("removed", removed)))
    }

    /** Baixa o album inteiro num zip, transmitido direto (sem copia em disco). */
    private fun apiDownloadAll(session: IHTTPSession): Response {
        if (!isAdmin(session)) return forbidden()

        val photos = store.list()
        if (photos.isEmpty()) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Album vazio."))
        }

        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)

        Thread {
            try {
                ZipOutputStream(pipeOut).use { zip ->
                    photos.forEach { photo ->
                        val file = store.photoFile(photo.name) ?: return@forEach
                        if (!file.exists()) return@forEach
                        zip.putNextEntry(ZipEntry(entryNameFor(photo)))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "zip interrompido", e)
            } finally {
                try {
                    pipeOut.close()
                } catch (ignored: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()

        val response = newChunkedResponse(Response.Status.OK, "application/zip", pipeIn)
        response.addHeader("Content-Disposition", "attachment; filename=\"album-da-festa.zip\"")
        return noStore(response)
    }

    // ------------------------------------------------------------------
    // Apoio
    // ------------------------------------------------------------------

    private fun entryNameFor(photo: PhotoStore.Photo): String {
        val who = photo.author.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().replace(' ', '-')
        return if (who.isEmpty()) photo.name else photo.name.removeSuffix(".jpg") + "_" + who + ".jpg"
    }

    /**
     * O PIN e opcional e serve para a familia nao apagar o album sem querer;
     * a protecao real e a propria rede local da casa.
     *
     * A propria tela da TV fala por 127.0.0.1, entao o menu do controle
     * continua funcionando mesmo com PIN configurado.
     */
    private fun isAdmin(session: IHTTPSession): Boolean {
        if (isLoopback(session)) return true
        val pin = settings.adminPin
        if (pin.isEmpty()) return true
        val sent = session.headers["x-admin-pin"]
            ?: session.parameters["pin"]?.firstOrNull()
            ?: return false
        return sent == pin
    }

    private fun isLoopback(session: IHTTPSession): Boolean {
        val remote = session.remoteIpAddress ?: return false
        return remote == "127.0.0.1" || remote == "::1" || remote == "0:0:0:0:0:0:0:1"
    }

    private fun asset(path: String): Response {
        val clean = path.substringBefore('?').substringBefore('#')
        if (clean.contains("..")) return notFound()
        return try {
            val bytes = context.assets.open("web/" + clean).use { it.readBytes() }
            val response = newFixedLengthResponse(
                Response.Status.OK,
                mimeFor(clean),
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
            // As paginas sao pequenas e mudam com o app: sem cache evita confusao
            noStore(response)
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun serveImage(file: File?): Response {
        if (file == null || !file.exists()) return notFound()
        val response = newFixedLengthResponse(
            Response.Status.OK, "image/jpeg", FileInputStream(file), file.length()
        )
        // O nome do arquivo e unico e o conteudo nunca muda: cache agressivo
        response.addHeader("Cache-Control", "public, max-age=31536000, immutable")
        return response
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())

    private fun noStore(response: Response): Response {
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        return response
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "nao encontrado")

    private fun forbidden(): Response = noStore(
        json(Response.Status.FORBIDDEN, JSONObject().put("error", "PIN do painel incorreto."))
    )

    companion object {
        private const val TAG = "PhotoServer"
        const val MAX_UPLOAD_BYTES = 30L * 1024 * 1024
        val PORT_CANDIDATES = listOf(8080, 8081, 8090, 8181, 9080)
    }
}
