package com.meshchat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.meshchat.core.MediaLimits
import java.io.ByteArrayOutputStream

/**
 * Shrinks a photo so it can cross a BLE mesh: longest side <= 480 px, JPEG quality lowered step by step until the file
 * is under ~48 KB (typically 20-45 KB). EXIF orientation is applied, and ALL metadata (including GPS tags stored in the
 * original photo) is dropped because the pixels are re-encoded from a fresh Bitmap.
 */
object ImageCodec {
    private val sides = intArrayOf(480, 400, 320, 256)
    private val qualities = intArrayOf(70, 55, 42, 32)

    fun compress(context: Context, uri: Uri): ByteArray? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 960 && bounds.outHeight / (sample * 2) >= 960) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null

            val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
            val upright = if (rotation != 0) {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            } else decoded

            var best: ByteArray? = null
            for (side in sides) {
                val scale = minOf(1f, side.toFloat() / maxOf(upright.width, upright.height))
                val bmp = if (scale < 1f) {
                    Bitmap.createScaledBitmap(upright, (upright.width * scale).toInt().coerceAtLeast(1), (upright.height * scale).toInt().coerceAtLeast(1), true)
                } else upright
                for (q in qualities) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                    val bytes = out.toByteArray()
                    if (best == null || bytes.size < best.size) best = bytes
                    if (bytes.size <= MediaLimits.IMAGE_TARGET_BYTES) return bytes
                }
            }
            best?.takeIf { it.size <= MediaLimits.IMAGE_MAX_BYTES }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun exifRotation(input: java.io.InputStream): Int =
        try {
            when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
}
