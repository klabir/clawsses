package com.clawsses.glasses.state

data class HudHistorySnapshotMessage(
    val id: String,
    val role: String,
    val content: String,
)

data class HudHistorySnapshot(
    val messages: List<HudHistorySnapshotMessage>,
    val isLoadMore: Boolean,
    val hasMore: Boolean,
)

/**
 * Attempt-scoped assembly for CXR history snapshots.
 *
 * A newer begin marker invalidates every delayed chunk/end marker from an older snapshot. The
 * visible HUD state is only replaced after the matching end marker arrives.
 */
class HudHistorySnapshotAssembler {
    private var activeSnapshotId: String? = null
    private var activeHasMore = false
    private var activeIsLoadMore = false
    private val messages = linkedMapOf<String, PendingMessage>()

    @Synchronized
    fun begin(snapshotId: String, isLoadMore: Boolean, hasMore: Boolean) {
        activeSnapshotId = snapshotId
        activeIsLoadMore = isLoadMore
        activeHasMore = hasMore
        messages.clear()
    }

    @Synchronized
    fun append(
        snapshotId: String,
        id: String,
        role: String,
        content: String,
    ): Boolean {
        if (snapshotId != activeSnapshotId) return false
        messages.getOrPut(id) { PendingMessage(role) }.content.append(content)
        return true
    }

    @Synchronized
    fun finish(snapshotId: String): HudHistorySnapshot? {
        if (snapshotId != activeSnapshotId) return null
        return HudHistorySnapshot(
            messages = messages.map { (id, pending) ->
                HudHistorySnapshotMessage(id, pending.role, pending.content.toString())
            },
            isLoadMore = activeIsLoadMore,
            hasMore = activeHasMore,
        ).also { reset() }
    }

    @Synchronized
    fun clear() = reset()

    private fun reset() {
        activeSnapshotId = null
        activeIsLoadMore = false
        activeHasMore = false
        messages.clear()
    }

    private data class PendingMessage(
        val role: String,
        val content: StringBuilder = StringBuilder(),
    )
}
