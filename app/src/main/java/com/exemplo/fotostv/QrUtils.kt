package com.exemplo.fotostv

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

/**
 * QR codes gerados no proprio aparelho. O projeto original dependia de
 * api.qrserver.com, o que exigia internet; aqui tudo funciona offline.
 */
object QrUtils {

    private const val CACHE_LIMIT = 24
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > CACHE_LIMIT
    }

    fun pngFor(data: String, size: Int): ByteArray {
        val px = size.coerceIn(120, 1024)
        val key = px.toString() + "|" + data

        synchronized(cache) { cache[key] }?.let { return it }

        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, px, px, hints)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val row = IntArray(px)
        for (y in 0 until px) {
            for (x in 0 until px) {
                row[x] = if (matrix[x, y]) BLACK else WHITE
            }
            bmp.setPixels(row, 0, px, 0, y, px, 1)
        }

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        val bytes = out.toByteArray()

        synchronized(cache) { cache[key] = bytes }
        return bytes
    }

    /** String no formato que os celulares entendem para conectar no Wi-Fi. */
    fun wifiPayload(ssid: String, password: String, security: String): String {
        val sec = if (password.isBlank()) "nopass" else security.ifBlank { "WPA" }
        return "WIFI:T:" + sec + ";S:" + escape(ssid) + ";P:" + escape(password) + ";;"
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")
}
