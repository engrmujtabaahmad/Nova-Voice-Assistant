package com.mujtaba.nova.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object CalendarHelper {

    // Picks a visible calendar we can write to. Prefers a Google (email) account.
    fun writableCalendarId(context: Context): Long? {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null
        )
        var fallback: Long? = null
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val account = c.getString(1) ?: ""
                if (account.contains("@")) return id
                if (fallback == null) fallback = id
            }
        }
        return fallback
    }

    fun parseDateTime(s: String): LocalDateTime? = try {
        LocalDateTime.parse(s.trim().removeSuffix("Z"))
    } catch (e: Exception) {
        null
    }

    val niceFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH)
}

class AddCalendarEventTool : Tool {
    override val name = "add_calendar_event"
    override val description =
        "Calendar mein event ya reminder banata hai aur us waqt par alert lagata hai. " +
                "Reminders ke liye bhi yahi use karo (remind_minutes_before = 0). " +
                "start local time mein 'yyyy-MM-ddTHH:mm' format mein do (24 ghante). " +
                "repeat: none, daily, weekly, monthly ya yearly."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("title", JSONObject().put("type", "STRING").put("description", "Event ya reminder ka title"))
                .put("start", JSONObject().put("type", "STRING").put("description", "yyyy-MM-ddTHH:mm"))
                .put("duration_minutes", JSONObject().put("type", "INTEGER").put("description", "Default 30"))
                .put("remind_minutes_before", JSONObject().put("type", "INTEGER").put("description", "Default 0"))
                .put("repeat", JSONObject().put("type", "STRING").put("description", "none/daily/weekly/monthly/yearly"))
        )
        .put("required", JSONArray().put("title").put("start"))

    override val permissions = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return ToolResult(false, "Reminder ka title samajh nahi aaya.")

        val start = CalendarHelper.parseDateTime(args.optString("start"))
            ?: return ToolResult(false, "Tareekh ya waqt samajh nahi aaya.")

        val zone = ZoneId.systemDefault()
        val startMs = start.atZone(zone).toInstant().toEpochMilli()
        if (startMs < System.currentTimeMillis() - 60_000) {
            return ToolResult(false, "Ye waqt guzar chuka hai, aane wala waqt batao.")
        }

        val duration = args.optInt("duration_minutes", 30).coerceIn(1, 1440)
        val remind = args.optInt("remind_minutes_before", 0).coerceIn(0, 10080)
        val repeat = args.optString("repeat").lowercase()

        return try {
            val calId = CalendarHelper.writableCalendarId(context)
                ?: return ToolResult(
                    false,
                    "Phone mein koi calendar nahi mila. Calendar app kholo aur Google account sync karo."
                )

            val rule = when (repeat) {
                "daily" -> "FREQ=DAILY"
                "weekly" -> "FREQ=WEEKLY"
                "monthly" -> "FREQ=MONTHLY"
                "yearly" -> "FREQ=YEARLY"
                else -> null
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                if (rule != null) {
                    put(CalendarContract.Events.RRULE, rule)
                    put(CalendarContract.Events.DURATION, "P${duration * 60}S")
                } else {
                    put(CalendarContract.Events.DTEND, startMs + duration * 60_000L)
                }
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return ToolResult(false, "Calendar mein event nahi ban saka.")
            val eventId = ContentUris.parseId(uri)

            val rem = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, remind)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, rem)

            val when_ = start.format(CalendarHelper.niceFormat)
            val rep = if (rule != null) ", repeat: $repeat" else ""
            ToolResult(true, "Calendar mein '$title' save ho gaya: $when_$rep. Alert $remind minute pehle lagega.")
        } catch (e: SecurityException) {
            ToolResult(false, "Calendar ki permission nahi hai.")
        } catch (e: Exception) {
            ToolResult(false, "Calendar event nahi ban saka: ${e.message}")
        }
    }
}

class ReadCalendarTool : Tool {
    override val name = "read_calendar"
    override val description =
        "Calendar ke events parhta hai. date 'yyyy-MM-dd' (shuru ka din), days kitne din dekhne hain (default 1). " +
                "Misaal: 'aaj kya hai' ya 'kal meri kya meetings hain'."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("date", JSONObject().put("type", "STRING").put("description", "yyyy-MM-dd"))
                .put("days", JSONObject().put("type", "INTEGER").put("description", "Default 1"))
        )
        .put("required", JSONArray().put("date"))

    override val permissions = listOf(Manifest.permission.READ_CALENDAR)

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val day = try {
            LocalDate.parse(args.optString("date").trim())
        } catch (e: Exception) {
            LocalDate.now()
        }
        val days = args.optInt("days", 1).coerceIn(1, 14)
        val zone = ZoneId.systemDefault()
        val startMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = day.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

        return try {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, startMs)
            ContentUris.appendId(b, endMs)

            val cursor = context.contentResolver.query(
                b.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY
                ),
                null, null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)
            val lines = mutableListOf<String>()
            cursor?.use { c ->
                while (c.moveToNext() && lines.size < 15) {
                    val title = c.getString(0)?.ifBlank { null } ?: "(bina title)"
                    val begin = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    val dt = Instant.ofEpochMilli(begin).atZone(zone)
                    lines.add(
                        if (allDay) "$title (poora din)"
                        else "${dt.format(if (days > 1) dayFmt else timeFmt)} - $title"
                    )
                }
            }

            if (lines.isEmpty()) {
                ToolResult(true, "Is muddat mein calendar mein koi event nahi hai.")
            } else {
                ToolResult(true, "Calendar events: " + lines.joinToString("; "))
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Calendar ki permission nahi hai.")
        } catch (e: Exception) {
            ToolResult(false, "Calendar parh nahi saka: ${e.message}")
        }
    }
}