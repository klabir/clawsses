package com.clawsses.phone.media

import android.content.Context
import com.clawsses.shared.ChatAttachment
import com.clawsses.shared.ChatMessage
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** Moves embedded chat images out of long-lived heap state into bounded app-owned files. */
class ChatAttachmentFileStore internal constructor(
    private val directory: File,
    private val maxAttachmentBytes: Long = MAX_ATTACHMENT_BYTES,
) {
    constructor(context: Context) : this(
        File(context.cacheDir, DIRECTORY_NAME),
        MAX_ATTACHMENT_BYTES,
    )

    init {
        require(maxAttachmentBytes > 0)
        directory.mkdirs()
        pruneStartupFiles()
    }

    fun materialize(messages: List<ChatMessage>): List<ChatMessage> = messages.map { message ->
        val attachments = message.attachments.mapNotNull(::materialize)
        if (attachments == message.attachments) message else message.copy(attachments = attachments)
    }

    fun materialize(attachment: ChatAttachment): ChatAttachment? {
        val localPath = attachment.localPath
        if (localPath != null) return attachment.takeIf { safeFile(localPath)?.isFile == true }
        val rawBase64 = attachment.base64
        val encoded = rawBase64?.substringAfter(',', rawBase64)?.takeIf(String::isNotBlank)
            ?: return null
        val digestBuilder = MessageDigest.getInstance("SHA-256")
        encoded.forEach { digestBuilder.update(it.code.toByte()) }
        val digest = digestBuilder.digest()
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$digest.${extension(attachment.mimeType)}")
        if (!target.exists()) {
            val temporary = File(directory, "$digest.tmp")
            val written = runCatching {
                Base64.getMimeDecoder().wrap(AsciiStringInputStream(encoded)).use { input ->
                    temporary.outputStream().use { output -> copyBounded(input, output, maxAttachmentBytes) }
                }
            }.getOrNull()
            if (written == null || written <= 0L || !temporary.renameTo(target)) {
                temporary.delete()
                return null
            }
        }
        val size = target.length()
        if (size !in 1..maxAttachmentBytes) {
            target.delete()
            return null
        }
        return attachment.copy(base64 = null, localPath = target.absolutePath, sizeBytes = size)
    }

    fun readBytes(attachment: ChatAttachment): ByteArray? {
        val file = attachment.localPath?.let(::safeFile)
        if (file?.isFile == true && file.length() in 1..maxAttachmentBytes) {
            return runCatching { file.readBytes() }.getOrNull()
        }
        val rawBase64 = attachment.base64 ?: return null
        val encoded = rawBase64.substringAfter(',', rawBase64)
        return runCatching { Base64.getMimeDecoder().decode(encoded) }
            .getOrNull()?.takeIf { it.size.toLong() <= maxAttachmentBytes }
    }

    @Synchronized
    fun retainOnly(messages: List<ChatMessage>) {
        val retained = messages.asSequence()
            .flatMap { it.attachments.asSequence() }
            .mapNotNull(ChatAttachment::localPath)
            .mapNotNull(::safeFile)
            .mapTo(HashSet()) { it.absolutePath }
        directory.listFiles().orEmpty()
            .filter(File::isFile)
            .filterNot { it.absolutePath in retained }
            .forEach(File::delete)
    }

    private fun safeFile(path: String): File? {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf { it.parentFile == root }
    }

    private fun extension(mimeType: String?): String = when (mimeType?.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }

    private fun pruneStartupFiles(nowMs: Long = System.currentTimeMillis()) {
        var retainedBytes = 0L
        directory.listFiles().orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .forEach { file ->
                val keep = !file.name.endsWith(".tmp") &&
                    nowMs - file.lastModified() <= MAX_FILE_AGE_MS &&
                    file.length() in 1..maxAttachmentBytes &&
                    retainedBytes + file.length() <= MAX_TOTAL_BYTES
                if (keep) retainedBytes += file.length() else file.delete()
            }
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            if (total > maxBytes) error("attachment exceeds byte budget")
            output.write(buffer, 0, count)
        }
    }

    private class AsciiStringInputStream(private val value: String) : InputStream() {
        private var index = 0
        override fun read(): Int = if (index >= value.length) -1 else value[index++].code and 0xff
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= value.length) return -1
            val count = minOf(length, value.length - index)
            repeat(count) { buffer[offset + it] = value[index++].code.toByte() }
            return count
        }
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 8L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 16L * 1024L * 1024L
        const val MAX_FILE_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val DIRECTORY_NAME = "chat-attachments"
    }
}
