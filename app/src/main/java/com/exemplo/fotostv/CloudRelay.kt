package com.exemplo.fotostv

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente do relay (o Worker na Cloudflare).
 *
 * O relay nao e um acervo: ele so segura a foto do convidado ate este
 * aparelho buscar. Depois de guardar no album local, mandamos apagar.
 */
class CloudRelay(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')

    val configurado: Boolean get() = base.startsWith("http")

    data class Sala(val codigo: String, val segredo: String, val urlConvidado: String)

    data class Pendente(val id: String, val autor: String, val ts: Long, val bytes: Long)

    /** Cria uma sala nova; o segredo devolvido so vive neste aparelho. */
    fun criarSala(titulo: String): Sala? {
        val corpo = JSONObject().put("titulo", titulo).toString()
        val resposta = pedir("POST", "/api/rooms", corpo, null) ?: return null
        return try {
            val j = JSONObject(resposta)
            Sala(j.getString("code"), j.getString("secret"), j.getString("guestUrl"))
        } catch (e: Exception) {
            Log.w(TAG, "resposta inesperada ao criar sala", e)
            null
        }
    }

    fun pendentes(codigo: String, segredo: String): List<Pendente>? {
        val resposta = pedir("GET", "/api/rooms/$codigo/pending", null, segredo) ?: return null
        return try {
            val arr = JSONObject(resposta).getJSONArray("photos")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Pendente(
                    o.getString("id"),
                    o.optString("author", ""),
                    o.optLong("ts", 0L),
                    o.optLong("size", 0L)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "lista de pendentes invalida", e)
            null
        }
    }

    /** Baixa os bytes da foto para [destino]. */
    fun baixar(codigo: String, segredo: String, id: String, destino: File): Boolean {
        val conn = abrir("GET", "/api/rooms/$codigo/photos/$id", segredo) ?: return false
        return try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "download falhou: HTTP " + conn.responseCode)
                return false
            }
            destino.parentFile?.mkdirs()
            conn.inputStream.use { entrada ->
                FileOutputStream(destino).use { saida -> entrada.copyTo(saida) }
            }
            destino.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "erro ao baixar foto", e)
            false
        } finally {
            conn.disconnect()
        }
    }

    fun apagar(codigo: String, segredo: String, id: String): Boolean =
        pedir("DELETE", "/api/rooms/$codigo/photos/$id", null, segredo) != null

    /** Mantem a sala viva e confirma que ainda temos internet. */
    fun baterPonto(codigo: String, segredo: String): Boolean =
        pedir("POST", "/api/rooms/$codigo/heartbeat", "", segredo) != null

    fun urlConvidado(codigo: String): String = "$base/f/$codigo"

    // ------------------------------------------------------------------

    private fun abrir(metodo: String, caminho: String, segredo: String?): HttpURLConnection? = try {
        (URL(base + caminho).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            connectTimeout = 12000
            readTimeout = 30000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (segredo != null) setRequestProperty("X-Room-Secret", segredo)
        }
    } catch (e: Exception) {
        Log.w(TAG, "nao consegui abrir conexao com o relay", e)
        null
    }

    private fun pedir(metodo: String, caminho: String, corpo: String?, segredo: String?): String? {
        val conn = abrir(metodo, caminho, segredo) ?: return null
        return try {
            if (corpo != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = conn.responseCode
            if (codigo !in 200..299) {
                Log.w(TAG, "$metodo $caminho -> HTTP $codigo")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "falha em $metodo $caminho", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "CloudRelay"
    }
}
