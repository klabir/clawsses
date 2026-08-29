package com.clawsses.phone.media

import com.clawsses.shared.ChatAttachment
import com.clawsses.shared.ChatMessage
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatAttachmentFileStoreTest {
    private lateinit var directory: java.io.File
    private lateinit var store: ChatAttachmentFileStore

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clawsses-attachments").toFile()
        store = ChatAttachmentFileStore(directory, maxAttachmentBytes = 16)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `materialize replaces base64 with deduplicated app file`() {
        val bytes = "image".toByteArray()
        val source = ChatAttachment(mimeType = "image/png", base64 = Base64.getEncoder().encodeToString(bytes))

        val first = requireNotNull(store.materialize(source))
        val second = requireNotNull(store.materialize(source))

        assertNull(first.base64)
        assertEquals(first.localPath, second.localPath)
        assertEquals(bytes.size.toLong(), first.sizeBytes)
        assertArrayEquals(bytes, store.readBytes(first))
        assertEquals(1, directory.listFiles().orEmpty().size)
        assertEquals(java.io.File(requireNotNull(first.localPath)).nameWithoutExtension, store.thumbnailCacheIdentity(first))
    }

    @Test
    fun `deduplication hashes decoded bytes instead of base64 formatting`() {
        val compact = ChatAttachment(mimeType = "image/png", base64 = "aW1hZ2U=")
        val wrapped = ChatAttachment(mimeType = "image/png", base64 = "aW1h\nZ2U=")

        val first = requireNotNull(store.materialize(compact))
        val second = requireNotNull(store.materialize(wrapped))

        assertEquals(first.localPath, second.localPath)
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `oversized and external attachments are rejected`() {
        val oversized = Base64.getEncoder().encodeToString(ByteArray(17))
        assertNull(store.materialize(ChatAttachment(base64 = oversized)))
        assertNull(store.materialize(ChatAttachment(localPath = "/tmp/not-owned.jpg")))
    }

    @Test
    fun `retain only deletes evicted files`() {
        val first = requireNotNull(store.materialize(attachment("first")))
        val second = requireNotNull(store.materialize(attachment("second")))

        store.retainOnly(listOf(ChatMessage(id = "m", role = "user", content = "", attachments = listOf(second))))

        assertFalse(java.io.File(requireNotNull(first.localPath)).exists())
        assertTrue(java.io.File(requireNotNull(second.localPath)).exists())
    }

    @Test
    fun `local file metadata never enters protocol JSON`() {
        val stored = requireNotNull(store.materialize(attachment("wire")))
        val json = ChatMessage(id = "m", role = "user", content = "", attachments = listOf(stored)).toJson()

        assertFalse(json.contains("localPath"))
        assertFalse(json.contains("sizeBytes"))
        assertFalse(json.contains(directory.absolutePath))
    }

    private fun attachment(value: String) = ChatAttachment(
        mimeType = "image/jpeg",
        base64 = Base64.getEncoder().encodeToString(value.toByteArray()),
    )
}
