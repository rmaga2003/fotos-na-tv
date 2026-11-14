package com.exemplo.fotostv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Configuracoes da festa, persistidas em SharedPreferences e editaveis pela
 * pagina /admin (ou pelo menu do controle na TV).
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fotos_tv", Context.MODE_PRIVATE)

    var eventTitle: String
        get() = prefs.getString(K_TITLE, "Album da Festa")!!
        set(v) = prefs.edit().putString(K_TITLE, v).apply()

    var subtitle: String
        get() = prefs.getString(K_SUBTITLE, "Mande sua foto para o telao")!!
        set(v) = prefs.edit().putString(K_SUBTITLE, v).apply()

    /** Segundos que cada foto fica na tela no modo normal. */
    var slideSeconds: Int
        get() = prefs.getInt(K_SLIDE, 6).coerceIn(2, 120)
        set(v) = prefs.edit().putInt(K_SLIDE, v.coerceIn(2, 120)).apply()

    /** Segundos de destaque quando uma foto nova chega. */
    var newPhotoHoldSeconds: Int
        get() = prefs.getInt(K_HOLD, 15).coerceIn(0, 120)
        set(v) = prefs.edit().putInt(K_HOLD, v.coerceIn(0, 120)).apply()

    var shuffle: Boolean
        get() = prefs.getBoolean(K_SHUFFLE, false)
        set(v) = prefs.edit().putBoolean(K_SHUFFLE, v).apply()

    var showQr: Boolean
        get() = prefs.getBoolean(K_SHOW_QR, true)
        set(v) = prefs.edit().putBoolean(K_SHOW_QR, v).apply()

    var showStats: Boolean
        get() = prefs.getBoolean(K_SHOW_STATS, true)
        set(v) = prefs.edit().putBoolean(K_SHOW_STATS, v).apply()

    var showThumbs: Boolean
        get() = prefs.getBoolean(K_SHOW_THUMBS, true)
        set(v) = prefs.edit().putBoolean(K_SHOW_THUMBS, v).apply()

    var wifiSsid: String
        get() = prefs.getString(K_WIFI_SSID, "")!!
        set(v) = prefs.edit().putString(K_WIFI_SSID, v).apply()

    var wifiPassword: String
        get() = prefs.getString(K_WIFI_PASS, "")!!
        set(v) = prefs.edit().putString(K_WIFI_PASS, v).apply()

    var wifiSecurity: String
        get() = prefs.getString(K_WIFI_SEC, "WPA")!!
        set(v) = prefs.edit().putString(K_WIFI_SEC, v).apply()

    /** Alvo da contagem regressiva, em "yyyy-MM-dd HH:mm". Vazio = desligada. */
    var countdownAt: String
        get() = prefs.getString(K_COUNTDOWN, "")!!
        set(v) = prefs.edit().putString(K_COUNTDOWN, v).apply()

    var countdownLabel: String
        get() = prefs.getString(K_COUNTDOWN_LABEL, "Contagem regressiva")!!
        set(v) = prefs.edit().putString(K_COUNTDOWN_LABEL, v).apply()

    var celebrationText: String
        get() = prefs.getString(K_CELEBRATION, "FELIZ ANO NOVO!")!!
        set(v) = prefs.edit().putString(K_CELEBRATION, v).apply()

    /** Quantidade maxima de fotos guardadas; as mais antigas saem primeiro. */
    var maxPhotos: Int
        get() = prefs.getInt(K_MAX_PHOTOS, 500).coerceIn(20, 5000)
        set(v) = prefs.edit().putInt(K_MAX_PHOTOS, v.coerceIn(20, 5000)).apply()

    /** Maior lado da imagem guardada, em pixels. */
    var maxDimension: Int
        get() = prefs.getInt(K_MAX_DIM, 1920).coerceIn(800, 4096)
        set(v) = prefs.edit().putInt(K_MAX_DIM, v.coerceIn(800, 4096)).apply()

    /** PIN opcional para abrir /admin. Vazio = sem PIN. */
    var adminPin: String
        get() = prefs.getString(K_PIN, "")!!
        set(v) = prefs.edit().putString(K_PIN, v.trim()).apply()

    fun toJson(): JSONObject = JSONObject().apply {
        put("eventTitle", eventTitle)
        put("subtitle", subtitle)
        put("slideSeconds", slideSeconds)
        put("newPhotoHoldSeconds", newPhotoHoldSeconds)
        put("shuffle", shuffle)
        put("showQr", showQr)
        put("showStats", showStats)
        put("showThumbs", showThumbs)
        put("wifiSsid", wifiSsid)
        put("wifiPassword", wifiPassword)
        put("wifiSecurity", wifiSecurity)
        put("countdownAt", countdownAt)
        put("countdownLabel", countdownLabel)
        put("celebrationText", celebrationText)
        put("maxPhotos", maxPhotos)
        put("maxDimension", maxDimension)
        put("hasPin", adminPin.isNotEmpty())
    }

    /** Aplica apenas as chaves presentes no JSON recebido. */
    fun applyJson(json: JSONObject) {
        if (json.has("eventTitle")) eventTitle = json.getString("eventTitle").take(80)
        if (json.has("subtitle")) subtitle = json.getString("subtitle").take(120)
        if (json.has("slideSeconds")) slideSeconds = json.getInt("slideSeconds")
        if (json.has("newPhotoHoldSeconds")) newPhotoHoldSeconds = json.getInt("newPhotoHoldSeconds")
        if (json.has("shuffle")) shuffle = json.getBoolean("shuffle")
        if (json.has("showQr")) showQr = json.getBoolean("showQr")
        if (json.has("showStats")) showStats = json.getBoolean("showStats")
        if (json.has("showThumbs")) showThumbs = json.getBoolean("showThumbs")
        if (json.has("wifiSsid")) wifiSsid = json.getString("wifiSsid").take(64)
        if (json.has("wifiPassword")) wifiPassword = json.getString("wifiPassword").take(64)
        if (json.has("wifiSecurity")) wifiSecurity = json.getString("wifiSecurity").take(8)
        if (json.has("countdownAt")) countdownAt = json.getString("countdownAt").take(20)
        if (json.has("countdownLabel")) countdownLabel = json.getString("countdownLabel").take(60)
        if (json.has("celebrationText")) celebrationText = json.getString("celebrationText").take(60)
        if (json.has("maxPhotos")) maxPhotos = json.getInt("maxPhotos")
        if (json.has("maxDimension")) maxDimension = json.getInt("maxDimension")
        if (json.has("adminPin")) adminPin = json.getString("adminPin").take(12)
    }

    private companion object {
        const val K_TITLE = "eventTitle"
        const val K_SUBTITLE = "subtitle"
        const val K_SLIDE = "slideSeconds"
        const val K_HOLD = "newPhotoHoldSeconds"
        const val K_SHUFFLE = "shuffle"
        const val K_SHOW_QR = "showQr"
        const val K_SHOW_STATS = "showStats"
        const val K_SHOW_THUMBS = "showThumbs"
        const val K_WIFI_SSID = "wifiSsid"
        const val K_WIFI_PASS = "wifiPassword"
        const val K_WIFI_SEC = "wifiSecurity"
        const val K_COUNTDOWN = "countdownAt"
        const val K_COUNTDOWN_LABEL = "countdownLabel"
        const val K_CELEBRATION = "celebrationText"
        const val K_MAX_PHOTOS = "maxPhotos"
        const val K_MAX_DIM = "maxDimension"
        const val K_PIN = "adminPin"
    }
}
