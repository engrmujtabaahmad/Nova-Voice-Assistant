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
                "sab ke liye. limit kitni dikhani hain (default 5). Is ko sirf EK baar call karo."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("app", JSONObject().put("type", "STRING").put("description", "App ka naam, optional"))
                .put("limit", JSONObject().put("type", "INTEGER").put("description", "Default 5, max 10"))
        )

    // Result is spoken directly, no second Gemini call
    override val directReply = true

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
        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        val fmt = SimpleDateFormat("h:mm a", Locale.ENGLISH)

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
                if (app.isEmpty()) "Koi notification nahi mili."
                else "$app ki koi notification nahi mili."
            )
        }

        val lines = items.mapIndexed { i, it ->
            val body = (if (it.title.isNotEmpty() && it.text.isNotEmpty()) "${it.title}: ${it.text}"
            else it.title + it.text).take(110)
            "${i + 1}) ${it.app} at ${fmt.format(Date(it.time))}, $body"
        }
        return ToolResult(true, "Notifications. " + lines.joinToString(". "))
    }
}