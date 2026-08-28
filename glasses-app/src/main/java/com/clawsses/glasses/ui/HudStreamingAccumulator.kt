package com.clawsses.glasses.ui

data class HudStreamingSnapshot(
    val id: String,
    val content: String,
    val revision: Long,
)

/** Keeps the active response outside the immutable chat-history list. */
class HudStreamingAccumulator {
    private var activeId: String? = null
    private val content = StringBuilder()
    private var publishedLength = 0
    private var revision = 0L

    @Synchronized
    fun append(id: String, chunk: String): Boolean {
        if (id.isBlank() || chunk.isEmpty()) return false
        val startedNewMessage = activeId != id
        if (startedNewMessage) {
            activeId = id
            content.clear()
            publishedLength = 0
        }
        content.append(chunk)
        return startedNewMessage
    }

    @Synchronized
    fun snapshotIfChanged(): HudStreamingSnapshot? {
        val id = activeId ?: return null
        if (content.length == publishedLength) return null
        publishedLength = content.length
        revision += 1
        return HudStreamingSnapshot(id = id, content = content.toString(), revision = revision)
    }

    @Synchronized
    fun finish(id: String): HudStreamingSnapshot? {
        if (activeId != id) return null
        revision += 1
        val snapshot = HudStreamingSnapshot(id = id, content = content.toString(), revision = revision)
        reset()
        return snapshot
    }

    @Synchronized
    fun hasUnpublishedChanges(): Boolean = content.length != publishedLength

    @Synchronized
    fun clear(id: String? = null) {
        if (id != null && activeId != id) return
        reset()
    }

    private fun reset() {
        activeId = null
        content.clear()
        publishedLength = 0
    }
}
