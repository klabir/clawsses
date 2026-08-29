package com.clawsses.glasses.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.LruCache
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class ThumbnailHandle(
    val key: String,
    internal val encoded: String? = null,
    internal val format: String? = null,
    internal val width: Int = 0,
    internal val height: Int = 0,
)

/** Small decoded-thumbnail cache; chat thumbnails are 80x60 and never include originals. */
object ThumbnailBitmapCache {
    private const val MAX_BYTES = 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val localSequence = AtomicLong()

    @Synchronized
    fun decode(
        encoded: String,
        format: String? = null,
        width: Int = 0,
        height: Int = 0,
    ): ThumbnailHandle? {
        val payload = encoded.substringAfter(',')
        if (payload.isBlank()) return null
        val keyMaterial = "$format:$width:$height:$payload"
        val key = MessageDigest.getInstance("SHA-256")
            .digest(keyMaterial.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val handle = ThumbnailHandle(key, payload, format, width, height)
        cache.get(key)?.let { return handle }
        val bitmap = decode(handle) ?: return null
        cache.put(key, bitmap)
        return handle
    }

    @Synchronized
    fun put(bitmap: Bitmap): ThumbnailHandle {
        val key = "local-${localSequence.incrementAndGet()}"
        cache.put(key, bitmap)
        return ThumbnailHandle(key)
    }

    @Synchronized
    fun resolve(handle: ThumbnailHandle): Bitmap? {
        cache.get(handle.key)?.let { return it }
        val bitmap = decode(handle) ?: return null
        cache.put(handle.key, bitmap)
        return bitmap
    }

    private fun decode(handle: ThumbnailHandle): Bitmap? {
        val payload = handle.encoded ?: return null
        return runCatching {
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            if (handle.format == "mono1" && handle.width > 0 && handle.height > 0) {
                decodeMono1(bytes, handle.width, handle.height)
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
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
