package com.mujtaba.nova.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.mujtaba.nova.NovaNotificationService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReadNotificationsTool : Tool {
    override val name = "read_notifications"
    override val description =
        "Phone ki recent notifications parhta hai (sirf preview text): WhatsApp messages, Gmail ya email, " +
                "missed calls, kisi bhi app ki. app mein app ka naam do (jaise WhatsApp, Gmail, Phone) ya khali chhodo " +
                "sab ke liye. limit kitni dikhani hain (default 8)."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("app", JSONObject().put("type", "STRING").put("description", "App ka naam, optional"))
                .put("limit", JSONObject().put("type", "INTEGER").put("description", "Default 8, max 15"))
        )

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        if (!enabled) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // ignore
            }
            return ToolResult(
                false,
                "Notification access abhi band hai. Settings khul gayi hai: Nova ko ON karo, phir dobara poocho."
            )
        }
        if (NovaNotificationService.instance == null) {
            return ToolResult(
                false,
                "Notification service abhi connect nahi hui. Notification access mein Nova ko OFF phir ON karo."
            )
        }

        val app = args.optString("app").trim()
        val limit = args.optInt("limit", 8).coerceIn(1, 15)
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.ENGLISH)

        val items = NovaNotificationService.recentItems()
            .filter {
                app.isEmpty() ||
                        it.app.contains(app, ignoreCase = true) ||
                        it.pkg.contains(app, ignoreCase = true)
            }
            .sortedByDescending { it.time }
            .take(limit)

        if (items.isEmpty()) {
            return ToolResult(
                true,
                if (app.isEmpty()) "Koi notification nahi mili (ya saaf ho chuki hain, ya Nova ke shuru hone ke baad nahi aayi)."
                else "$app ki koi notification nahi mili."
            )
        }

        val lines = items.map {
            val t = fmt.format(Date(it.time))
            val body = (if (it.title.isNotEmpty() && it.text.isNotEmpty()) "${it.title}: ${it.text}"
            else it.title + it.text).take(160)
            "[$t] ${it.app}: $body"
        }
        return ToolResult(
            true,
            "Notification previews (${items.size}); sirf wohi text jo notification mein dikha. " +
                    lines.joinToString(" | ")
        )
    }
}