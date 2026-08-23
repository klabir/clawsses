package com.clawsses.phone.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationRelayService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        val allowedPackages = NotificationFilter.parseAllowedPackages(
            prefs.getString(KEY_ALLOWED_PACKAGES, "").orEmpty()
        )
        val isOngoing = sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (!NotificationFilter.shouldRelay(sbn.packageName, allowedPackages, isOngoing, isGroupSummary)) return

        val appName = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)

        sbn.toRelayedNotification(appName)?.let(NotificationRelay::enqueue)
    }

    companion object {
        const val PREFS_NAME = "clawsses_notifications"
        const val KEY_ENABLED = "notification_relay_enabled"
        const val KEY_ALLOWED_PACKAGES = "notification_allowed_packages"
    }
}
