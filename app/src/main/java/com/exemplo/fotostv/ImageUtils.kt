package com.exemplo.fotostv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Reduz e normaliza as fotos recebidas. Isso e o que mantem o Fire TV Stick
 * fluido: o WebView nunca precisa decodificar um JPEG de 12 MP.
 */
object ImageUtils {

    /**
     * Le [source], corrige a rotacao pelo EXIF, reduz para no maximo [maxDim]
     * pixels no lado maior e grava um JPEG em [target].
     *
     * @return true se conseguiu decodificar a imagem.
     */
    fun processToJpeg(source: File, target: File, maxDim: Int, quality: Int = 86): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        }

        var bmp = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return false

        bmp = applyExifRotation(source, bmp)
        bmp = scaleDown(bmp, maxDim)

        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        bmp.recycle()
        return true
    }

    /** Gera uma miniatura a partir de um JPEG ja processado. */
    fun writeThumb(source: File, target: File, maxDim: Int = 420, quality: Int = 78): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        }
        var bmp = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return false
        bmp = scaleDown(bmp, maxDim)

        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        bmp.recycle()
        return true
    }

    private fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDim && h / 2 >= maxDim) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val ratio = maxDim.toFloat() / longest
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private fun applyExifRotation(source: File, bmp: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bmp
        }

        return try {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated !== bmp) bmp.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            bmp
        }
    }
}
