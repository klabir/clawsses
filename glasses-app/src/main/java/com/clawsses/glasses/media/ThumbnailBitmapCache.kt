package com.clawsses.glasses.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.LruCache
import java.security.MessageDigest

/** Small decoded-thumbnail cache; chat thumbnails are 80x60 and never include originals. */
object ThumbnailBitmapCache {
    private const val MAX_BYTES = 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Synchronized
    fun decode(
        encoded: String,
        format: String? = null,
        width: Int = 0,
        height: Int = 0,
    ): Bitmap? {
        val payload = encoded.substringAfter(',')
        if (payload.isBlank()) return null
        val keyMaterial = "$format:$width:$height:$payload"
        val key = MessageDigest.getInstance("SHA-256")
            .digest(keyMaterial.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        cache.get(key)?.let { return it }
        val bitmap = runCatching {
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            if (format == "mono1" && width > 0 && height > 0) {
                decodeMono1(bytes, width, height)
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decodeMono1(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        val pixelCount = width * height
        if (width > 48 || height > 36 || bytes.size * 8 < pixelCount) return null
        val pixels = IntArray(pixelCount) { index ->
            val isLight = bytes[index / 8].toInt() and (1 shl (7 - index % 8)) != 0
            if (isLight) Color.WHITE else Color.BLACK
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
