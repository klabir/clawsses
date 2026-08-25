package com.clawsses.phone.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

internal class BoundedByteLruCache(private val maxBytes: Int) {
    init {
        require(maxBytes > 0)
    }

    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var currentBytes = 0

    @Synchronized
    fun get(key: String): ByteArray? = entries[key]

    @Synchronized
    fun put(key: String, value: ByteArray) {
        if (value.size > maxBytes) return
        currentBytes -= entries.remove(key)?.size ?: 0
        entries[key] = value
        currentBytes += value.size
        while (currentBytes > maxBytes) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
            currentBytes -= eldest.value.size
        }
    }

    @Synchronized
    fun sizeBytes(): Int = currentBytes

    @Synchronized
    fun entryCount(): Int = entries.size
}

data class ImagePipelineStats(
    val thumbnailCacheHits: Long,
    val thumbnailCacheMisses: Long,
    val decodedImages: Long,
    val decodeFailures: Long,
    val cachedBytes: Int,
    val cachedEntries: Int,
)

data class HudThumbnail(
    val encoded: String,
    val format: String,
    val width: Int,
    val height: Int,
)

/**
 * Shared phone-side image pipeline.
 *
 * Large bitmaps are never retained. Only compressed 80x60-ish HUD thumbnails are cached,
 * bounded by [THUMBNAIL_CACHE_BYTES]. Original attachment bytes are decoded directly without
 * an intermediate Base64 encode/decode round trip.
 */
object ImagePipeline {
    private const val THUMBNAIL_CACHE_BYTES = 2 * 1024 * 1024
    private const val HUD_MAX_WIDTH = 48
    private const val HUD_MAX_HEIGHT = 36
    private const val HUD_ENCODING_VERSION = 3
    const val HUD_FORMAT_MONO_1 = "mono1"

    private val thumbnailCache = BoundedByteLruCache(THUMBNAIL_CACHE_BYTES)
    private val thumbnailCacheHits = AtomicLong()
    private val thumbnailCacheMisses = AtomicLong()
    private val decodedImages = AtomicLong()
    private val decodeFailures = AtomicLong()

    fun decodeBase64Image(encoded: String?, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bytes = decodeBase64Bytes(encoded) ?: return null
        return decodeSampled(bytes, maxWidth, maxHeight)
    }

    fun createHudThumbnail(imageBytes: ByteArray): HudThumbnail? {
        if (imageBytes.isEmpty()) return null
        val key = thumbnailKey(imageBytes, HUD_MAX_WIDTH, HUD_MAX_HEIGHT)
        thumbnailCache.get(key)?.let { cached ->
            thumbnailCacheHits.incrementAndGet()
            val width = cached[0].toInt() and 0xff
            val height = cached[1].toInt() and 0xff
            return HudThumbnail(
                encoded = Base64.encodeToString(cached.copyOfRange(2, cached.size), Base64.NO_WRAP),
                format = HUD_FORMAT_MONO_1,
                width = width,
                height = height,
            )
        }
        thumbnailCacheMisses.incrementAndGet()

        val bitmap = decodeSampled(imageBytes, HUD_MAX_WIDTH, HUD_MAX_HEIGHT) ?: return null
        val scale = minOf(
            HUD_MAX_WIDTH.toFloat() / bitmap.width,
            HUD_MAX_HEIGHT.toFloat() / bitmap.height,
        )
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()

        val packed = ByteArray((pixels.size + 7) / 8)
        pixels.forEachIndexed { index, color ->
            val luminance = (
                0.2126f * ((color shr 16) and 0xff) +
                    0.7152f * ((color shr 8) and 0xff) +
                    0.0722f * (color and 0xff)
                ).toInt()
            if (luminance >= 112) {
                packed[index / 8] = (packed[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
            }
        }
        thumbnailCache.put(key, byteArrayOf(width.toByte(), height.toByte()) + packed)
        return HudThumbnail(
            encoded = Base64.encodeToString(packed, Base64.NO_WRAP),
            format = HUD_FORMAT_MONO_1,
            width = width,
            height = height,
        )
    }

    fun stats(): ImagePipelineStats = ImagePipelineStats(
        thumbnailCacheHits = thumbnailCacheHits.get(),
        thumbnailCacheMisses = thumbnailCacheMisses.get(),
        decodedImages = decodedImages.get(),
        decodeFailures = decodeFailures.get(),
        cachedBytes = thumbnailCache.sizeBytes(),
        cachedEntries = thumbnailCache.entryCount(),
    )

    private fun decodeBase64Bytes(encoded: String?): ByteArray? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            Base64.decode(encoded.substringAfter(','), Base64.DEFAULT)
        }.getOrNull()
    }

    private fun decodeSampled(imageBytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (imageBytes.isEmpty() || maxWidth <= 0 || maxHeight <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            decodeFailures.incrementAndGet()
            return null
        }

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxWidth * 2 ||
            bounds.outHeight / sampleSize > maxHeight * 2
        ) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            imageBytes,
            0,
            imageBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
        if (bitmap == null) decodeFailures.incrementAndGet() else decodedImages.incrementAndGet()
        return bitmap
    }

    private fun thumbnailKey(imageBytes: ByteArray, maxWidth: Int, maxHeight: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(imageBytes)
        val hash = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "$hash:$maxWidth:$maxHeight:$HUD_ENCODING_VERSION"
    }

}
