package com.wavesync.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationCaptureService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("wavesync_audio", MODE_PRIVATE)
        if (!prefs.getBoolean("notification_sync", false)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        prefs.edit().putString("latest_notification", "${sbn.packageName}: $title $text".take(240)).apply()
    }
}
