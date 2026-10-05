package com.mujtaba.nova.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject

class OpenAppTool : Tool {
    override val name = "open_app"
    override val description =
        "Phone par installed app kholta hai (jaise YouTube, WhatsApp, Chrome, Settings, Camera, Maps, Gmail). " +
                "app_name English mein wahi likho jo phone par app ka naam hota hai."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject().put(
                "app_name",
                JSONObject().put("type", "STRING").put("description", "App ka naam, jaise YouTube")
            )
        )
        .put("required", JSONArray().put("app_name"))

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val wanted = args.optString("app_name").trim()
        if (wanted.isEmpty()) return ToolResult(false, "App ka naam samajh nahi aaya.")

        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)

        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(wanted, ignoreCase = true) }
            ?: return ToolResult(false, "'$wanted' naam ki app phone mein nahi mili.")

        val label = match.loadLabel(pm).toString()
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return ToolResult(false, "$label ko kholne ka raasta nahi mila.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(launch)
            ToolResult(true, "$label khol raha hun.")
        } catch (e: Exception) {
            ToolResult(false, "$label khol nahi saka: ${e.message}")
        }
    }
}

class BatteryTool : Tool {
    override val name = "get_battery"
    override val description = "Phone ki battery percentage, charging state aur temperature batata hai."
    override val parameters: JSONObject? = null

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ToolResult(false, "Battery ki maloomat nahi mil saki.")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return ToolResult(false, "Battery ki maloomat nahi mil saki.")

        val pct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f

        val chargeText = if (charging) "charging ho rahi hai" else "charging nahi ho rahi"
        val tempText = if (temp > 0f) " Temperature $temp degree Celsius hai." else ""
        return ToolResult(true, "Battery $pct percent hai aur $chargeText.$tempText")
    }
}

class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val description =
        "Alarm lagata hai. hour 24-hour format mein (0 se 23), minute 0 se 59. " +
                "Misaal: subah 7:30 = hour 7, minute 30. Sham 8 baje = hour 20."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("hour", JSONObject().put("type", "INTEGER").put("description", "0 se 23"))
                .put("minute", JSONObject().put("type", "INTEGER").put("description", "0 se 59"))
                .put("label", JSONObject().put("type", "STRING").put("description", "Alarm ka naam (optional)"))
        )
        .put("required", JSONArray().put("hour").put("minute"))

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult(false, "Alarm ka waqt samajh nahi aaya.")
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            val label = args.optString("label")
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val t = String.format("%02d:%02d", hour, minute)
            ToolResult(true, "Alarm $t ke liye set kar diya. Clock app mein check kar sakte ho.")
        } catch (e: Exception) {
            ToolResult(false, "Alarm set nahi ho saka: ${e.message}")
        }
    }
}