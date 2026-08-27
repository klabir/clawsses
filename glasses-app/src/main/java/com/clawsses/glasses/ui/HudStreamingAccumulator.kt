package com.clawsses.glasses.ui

data class HudStreamingSnapshot(
    val id: String,
    val content: String,
    val revision: Long,
)

/** Keeps the active response outside the immutable chat-history list. */
class HudStreamingAccumulator {
    private var activeId: String? = null
    private var content = StringBuilder()
    private var revision = 0L

    fun append(id: String, chunk: String): Boolean {
        val startedNewMessage = activeId != id
        if (startedNewMessage) {
            activeId = id
            content = StringBuilder()
        }
        content.append(chunk)
        revision += 1
        return startedNewMessage
    }

    fun snapshot(): HudStreamingSnapshot? = activeId?.let { id ->
        HudStreamingSnapshot(id = id, content = content.toString(), revision = revision)
    }

    fun finish(id: String): HudStreamingSnapshot? {
        val snapshot = snapshot()?.takeIf { it.id == id }
        if (snapshot != null) clear(id)
        return snapshot
    }

    fun clear(id: String? = null) {
        if (id != null && activeId != id) return
        activeId = null
        content = StringBuilder()
    }
}
