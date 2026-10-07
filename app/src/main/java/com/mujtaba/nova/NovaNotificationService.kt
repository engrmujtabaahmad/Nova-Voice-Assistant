package com.mujtaba.nova

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

data class NotifItem(
    val key: String,
    val time: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String
)

class NovaNotificationService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NovaNotificationService? = null
        private val buffer = CopyOnWriteArrayList<NotifItem>()
        fun recentItems(): List<NotifItem> = buffer.toList()
    }

    override fun onListenerConnected() {
        instance = this
        try {
            activeNotifications?.forEach { remember(it) }
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) remember(sbn)
    }

    private fun remember(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        var text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: ""

        // Chat apps (WhatsApp etc.): take the last few messages
        @Suppress("DEPRECATION")
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val lines = msgs.takeLast(3).mapNotNull {
                (it as? Bundle)?.getCharSequence("text")?.toString()
            }
            if (lines.isNotEmpty()) text = lines.joinToString(" / ")
        }

        if (title.isEmpty() && text.isEmpty()) return

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

        val item = NotifItem(sbn.key, sbn.postTime, pkg, label, title, text)
        buffer.removeAll { it.key == item.key }
        buffer.add(item)
        while (buffer.size > 60) buffer.removeAt(0)
    }
}