package com.exemplo.fotostv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * A TV inteira e uma WebView em tela cheia apontando para o servidor local
 * deste mesmo aparelho. Assim a interface do telao e exatamente a mesma que
 * os convidados veem no celular, e o controle remoto comanda o slideshow.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var store: PhotoStore
    private lateinit var appSettings: Settings
    private var server: PhotoServer? = null
    private var cloud: CloudSync? = null
    private var port = 0

    @Volatile
    private var menuOpen = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = PhotoStore(this)
        appSettings = Settings(this)

        // Segundo canal de entrada: fotos que os convidados mandam pela internet.
        // Cai no mesmo album das que chegam pela rede local.
        cloud = CloudSync(appSettings, store, cacheDir) { onPhotoAdded() }

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    hideSystemUi()
                }
            }
            with(this.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            addJavascriptInterface(TvBridge(), "AndroidTv")
        }
        setContentView(webView)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        when (val started = startServer()) {
            null -> showStartupError()
            else -> {
                cloud?.iniciar()
                webView.loadUrl("http://127.0.0.1:" + started + "/tv")
            }
        }
    }

    // ------------------------------------------------------------------
    // Servidor
    // ------------------------------------------------------------------

    /** Tenta as portas conhecidas em ordem; devolve a que funcionou. */
    private fun startServer(): Int? {
        for (candidate in PhotoServer.PORT_CANDIDATES) {
            val instance = PhotoServer(applicationContext, store, appSettings, candidate)
            try {
                instance.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                instance.onPhotoAdded = { onPhotoAdded() }
                instance.cloud = cloud
                server = instance
                port = candidate
                Log.i(TAG, "servidor no ar em http://" + (NetUtils.localIpv4() ?: "?") + ":" + candidate)
                return candidate
            } catch (e: IOException) {
                Log.w(TAG, "porta " + candidate + " ocupada", e)
                instance.stop()
            }
        }
        return null
    }

    /** Avisa a pagina da TV na hora, sem esperar o proximo polling. */
    private fun onPhotoAdded() {
        runOnUiThread {
            webView.evaluateJavascript("window.tvOnNewPhoto && window.tvOnNewPhoto();", null)
        }
    }

    private fun showStartupError() {
        val html = """
            <html><body style="background:#000;color:#fff;font-family:sans-serif;
                 display:flex;align-items:center;justify-content:center;height:100%;text-align:center">
              <div>
                <h1 style="color:#ffca28">Nao consegui abrir o servidor</h1>
                <p>Todas as portas de rede testadas estao ocupadas.<br>
                   Feche o app, aguarde alguns segundos e abra de novo.</p>
              </div>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // ------------------------------------------------------------------
    // Controle remoto
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            // Deixa o ACTION_UP das teclas que tratamos morrer aqui tambem
            return if (mapKey(event.keyCode) != null) true else super.dispatchKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Com o menu aberto, Voltar fecha o menu; fora dele, sai do app
            if (menuOpen) {
                sendToPage("back")
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        val action = mapKey(event.keyCode) ?: return super.dispatchKeyEvent(event)
        sendToPage(action)
        return true
    }

    private fun mapKey(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "next"

        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND -> "prev"

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "ok"

        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_X -> "menu"
        else -> null
    }

    private fun sendToPage(action: String) {
        webView.evaluateJavascript("window.tvRemote && window.tvRemote('" + action + "');", null)
    }

    /** Exposto ao JavaScript da pagina da TV como `AndroidTv`. */
    inner class TvBridge {
        @JavascriptInterface
        fun setMenuOpen(open: Boolean) {
            menuOpen = open
        }

        @JavascriptInterface
        fun isTv(): Boolean = true

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onDestroy() {
        cloud?.parar()
        cloud = null
        server?.stop()
        server = null
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FotosTv"
    }
}
