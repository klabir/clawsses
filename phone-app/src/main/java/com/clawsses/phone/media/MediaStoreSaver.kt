package com.clawsses.phone.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/** Saves opted-in Rokid captures to the shared Pictures/Clawsses album. */
object MediaStoreSaver {
    private const val TAG = "MediaStoreSaver"
    private const val ALBUM = "Clawsses"

    fun saveImage(context: Context, bytes: ByteArray): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || bytes.isEmpty()) return null

        val (mimeType, extension) = detectFormat(bytes)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "clawsses_${System.currentTimeMillis()}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        var uri: Uri? = null
        return try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
                ?: throw IllegalStateException("Gallery output stream unavailable")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "Saved capture to Pictures/$ALBUM (${bytes.size} bytes)")
            uri
        } catch (error: Exception) {
            uri?.let { resolver.delete(it, null, null) }
            Log.e(TAG, "Failed to save capture to gallery: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun detectFormat(bytes: ByteArray): Pair<String, String> = when {
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp" to "webp"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() ->
            "image/png" to "png"
        else -> "image/jpeg" to "jpg"
    }
}
