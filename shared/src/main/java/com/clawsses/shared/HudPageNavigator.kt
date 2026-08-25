package com.clawsses.shared

/** Stable reading policy for the paginated Rokid HUD. */
class HudPageNavigator {
    enum class Mode { FOLLOW_LIVE, USER_READING, RESTORING_ANCHOR }

    var mode: Mode = Mode.FOLLOW_LIVE
        private set

    var pageIndex: Int = 0
        private set

    fun onDocumentChanged(pageCount: Int, restoredAnchorPage: Int? = null) {
        val lastPage = (pageCount - 1).coerceAtLeast(0)
        pageIndex = when (mode) {
            Mode.FOLLOW_LIVE -> lastPage
            Mode.USER_READING, Mode.RESTORING_ANCHOR ->
                (restoredAnchorPage ?: pageIndex).coerceIn(0, lastPage)
        }
        if (mode == Mode.RESTORING_ANCHOR) {
            mode = if (pageIndex == lastPage) Mode.FOLLOW_LIVE else Mode.USER_READING
        }
    }

    fun moveBy(delta: Int, pageCount: Int) {
        val lastPage = (pageCount - 1).coerceAtLeast(0)
        pageIndex = (pageIndex + delta).coerceIn(0, lastPage)
        mode = if (pageIndex == lastPage) Mode.FOLLOW_LIVE else Mode.USER_READING
    }

    fun jumpToLatest(pageCount: Int) {
        pageIndex = (pageCount - 1).coerceAtLeast(0)
        mode = Mode.FOLLOW_LIVE
    }

    fun holdCurrentPage() {
        mode = Mode.USER_READING
    }

    fun beginAnchorRestore() {
        mode = Mode.RESTORING_ANCHOR
    }

    fun hasNewerPages(pageCount: Int): Boolean =
        mode != Mode.FOLLOW_LIVE && pageIndex < pageCount - 1
}
