package com.exemplo.fotostv

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puxa do relay as fotos que os convidados mandaram pela internet e as
 * coloca no mesmo album das fotos que chegam pela rede local.
 *
 * O ritmo se adapta: rapido quando a festa esta movimentada, lento quando
 * ninguem manda nada ha um tempo. Isso mantem a conta de requisicoes baixa
 * sem atrasar a foto na TV.
 */
class CloudSync(
    private val settings: Settings,
    private val store: PhotoStore,
    private val cacheDir: File,
    private val aoChegarFoto: (PhotoStore.Photo) -> Unit
) {

    @Volatile
    var online: Boolean = false
        private set

    @Volatile
    var ultimoErro: String? = null
        private set

    @Volatile
    var recebidasNaNuvem: Int = 0
        private set

    private val rodando = AtomicBoolean(false)
    private var thread: Thread? = null

    fun iniciar() {
        if (!settings.cloudEnabled) return
        if (!rodando.compareAndSet(false, true)) return

        thread = Thread({ laco() }, "cloud-sync").apply {
            isDaemon = true
            start()
        }
    }

    fun parar() {
        rodando.set(false)
        thread?.interrupt()
        thread = null
        online = false
    }

    fun reiniciar() {
        parar()
        iniciar()
    }

    /**
     * Liga o modo nuvem: cria a sala no relay e guarda o par codigo/segredo.
     * Devolve null se o relay nao respondeu.
     */
    fun ligar(urlRelay: String): CloudRelay.Sala? {
        val relay = CloudRelay(urlRelay)
        if (!relay.configurado) {
            ultimoErro = "Endereço do relay inválido."
            return null
        }

        // Reaproveita a sala atual se ela ainda estiver viva no relay
        if (settings.roomCode.isNotEmpty() &&
            relay.baterPonto(settings.roomCode, settings.roomSecret)
        ) {
            settings.relayUrl = urlRelay
            settings.cloudEnabled = true
            reiniciar()
            return CloudRelay.Sala(
                settings.roomCode, settings.roomSecret, relay.urlConvidado(settings.roomCode)
            )
        }

        val sala = relay.criarSala(settings.eventTitle)
        if (sala == null) {
            ultimoErro = "Não consegui falar com o relay. Confira o endereço e a internet."
            return null
        }

        settings.relayUrl = urlRelay
        settings.roomCode = sala.codigo
        settings.roomSecret = sala.segredo
        settings.cloudEnabled = true
        ultimoErro = null
        reiniciar()
        return sala
    }

    fun desligar() {
        parar()
        settings.cloudEnabled = false
    }

    fun status(): JSONObject = JSONObject().apply {
        put("enabled", settings.cloudEnabled)
        put("online", online)
        put("relayUrl", settings.relayUrl)
        put("code", settings.roomCode)
        put("pulled", recebidasNaNuvem)
        put("error", ultimoErro ?: JSONObject.NULL)
        put(
            "guestUrl",
            if (settings.cloudEnabled && settings.roomCode.isNotEmpty())
                CloudRelay(settings.relayUrl).urlConvidado(settings.roomCode)
            else JSONObject.NULL
        )
    }

    // ------------------------------------------------------------------

    private fun laco() {
        var vaziosSeguidos = 0

        while (rodando.get()) {
            val relay = CloudRelay(settings.relayUrl)
            val codigo = settings.roomCode
            val segredo = settings.roomSecret

            if (!settings.cloudEnabled || !relay.configurado || codigo.isEmpty()) {
                dormir(INTERVALO_OCIOSO_MS)
                continue
            }

            val pendentes = relay.pendentes(codigo, segredo)

            if (pendentes == null) {
                online = false
                ultimoErro = "Sem conexão com o relay."
                vaziosSeguidos = LIMITE_PARA_DESACELERAR
            } else {
                online = true
                ultimoErro = null

                if (pendentes.isEmpty()) {
                    vaziosSeguidos++
                } else {
                    vaziosSeguidos = 0
                    pendentes.forEach { pendente ->
                        if (!rodando.get()) return@forEach
                        puxar(relay, codigo, segredo, pendente)
                    }
                }
            }

            dormir(
                if (vaziosSeguidos >= LIMITE_PARA_DESACELERAR) INTERVALO_OCIOSO_MS
                else INTERVALO_ATIVO_MS
            )
        }
    }

    private fun puxar(
        relay: CloudRelay,
        codigo: String,
        segredo: String,
        pendente: CloudRelay.Pendente
    ) {
        val temp = File(cacheDir, "nuvem_" + pendente.id + ".jpg")
        try {
            if (!relay.baixar(codigo, segredo, pendente.id, temp)) return

            val foto = store.save(
                temp, pendente.autor, "", settings.maxDimension, settings.maxPhotos
            )

            if (foto != null) {
                recebidasNaNuvem++
                aoChegarFoto(foto)
            } else {
                Log.w(TAG, "foto da nuvem nao pode ser lida: " + pendente.id)
            }

            // Guardada (ou descartada por ser ilegivel): sai do relay de qualquer forma,
            // senao ficaria tentando para sempre a mesma foto quebrada.
            relay.apagar(codigo, segredo, pendente.id)
        } catch (e: Throwable) {
            Log.w(TAG, "erro ao trazer foto da nuvem", e)
        } finally {
            temp.delete()
        }
    }

    private fun dormir(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val TAG = "CloudSync"
        const val INTERVALO_ATIVO_MS = 5_000L
        const val INTERVALO_OCIOSO_MS = 20_000L
        const val LIMITE_PARA_DESACELERAR = 12
    }
}
