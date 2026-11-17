package com.felix.faze

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Hands the phone's notifications to [PhoneNotes]. Android only runs this once notification access is granted (Settings turns that on with root). */
class FazeNotifications : NotificationListenerService() {
    private val notes get() = (application as FazeApp).core.notes

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification
            val extras = n.extras
            val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
            notes.posted(PhoneNotes.Note(
                sbn.key, sbn.packageName, app,
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
                (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                sbn.postTime,
            ))
        } catch (_: Exception) { }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try { notes.removed(sbn.key) } catch (_: Exception) { }
    }
}
