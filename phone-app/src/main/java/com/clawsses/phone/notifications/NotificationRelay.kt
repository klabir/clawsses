package com.clawsses.phone.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RelayedNotification(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val postedAt: Long,
)

object NotificationFilter {
    fun parseAllowedPackages(raw: String): Set<String> = raw
        .split(',', '\n', ';')
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

    fun shouldRelay(
        packageName: String,
        allowedPackages: Set<String>,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
    ): Boolean = allowedPackages.isNotEmpty() &&
        packageName in allowedPackages &&
        !isOngoing &&
        !isGroupSummary &&
        packageName != "com.clawsses.phone"
}

/** Process-local bounded inbox. MainScreen consumes entries after delivery to the HUD. */
object NotificationRelay {
    private val mutablePending = MutableStateFlow<List<RelayedNotification>>(emptyList())
    val pending: StateFlow<List<RelayedNotification>> = mutablePending.asStateFlow()

    fun enqueue(notification: RelayedNotification) {
        mutablePending.value = (mutablePending.value.filterNot { it.id == notification.id } + notification).takeLast(20)
    }

    fun consume(id: String) {
        mutablePending.value = mutablePending.value.filterNot { it.id == id }
    }
}

fun StatusBarNotification.toRelayedNotification(appName: String): RelayedNotification? {
    val extras = notification.extras
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val body = (
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        )?.toString()?.trim().orEmpty()
    if (title.isBlank() && body.isBlank()) return null
    return RelayedNotification(
        id = "$packageName:$key:$postTime",
        packageName = packageName,
        appName = appName,
        title = title.ifBlank { appName },
        body = body.ifBlank { title },
        postedAt = postTime,
    )
}
