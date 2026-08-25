package com.clawsses.shared

/** Pure policy shared by the phone and glasses Compose adapters. */
class ChatScrollCoordinator(initiallyFollowingTail: Boolean = true) {
    enum class Mode { FOLLOWING_TAIL, USER_READING, RESTORING_HISTORY, EXPLICIT }

    var mode: Mode = if (initiallyFollowingTail) Mode.FOLLOWING_TAIL else Mode.USER_READING
        private set

    fun onViewportChanged(atEnd: Boolean, userDriven: Boolean) {
        if (userDriven) {
            mode = if (atEnd) Mode.FOLLOWING_TAIL else Mode.USER_READING
        } else if (atEnd && mode != Mode.RESTORING_HISTORY) {
            mode = Mode.FOLLOWING_TAIL
        }
    }

    fun onExplicitScroll(targetIndex: Int, lastIndex: Int) {
        mode = if (targetIndex >= lastIndex) Mode.FOLLOWING_TAIL else Mode.EXPLICIT
    }

    fun beginHistoryRestore() {
        mode = Mode.RESTORING_HISTORY
    }

    fun finishHistoryRestore(atEnd: Boolean) {
        mode = if (atEnd) Mode.FOLLOWING_TAIL else Mode.USER_READING
    }

    fun shouldFollowNewContent(): Boolean = mode == Mode.FOLLOWING_TAIL
}
