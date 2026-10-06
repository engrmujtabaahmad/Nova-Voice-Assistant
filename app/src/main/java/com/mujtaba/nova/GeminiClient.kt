package com.mujtaba.nova

import com.mujtaba.nova.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed class AiReply {
    data class Text(val text: String) : AiReply()
    data class ToolCall(
        val name: String,
        val args: JSONObject,
        val rawPart: JSONObject
    ) : AiReply()
}

object GeminiClient {

    private const val MODEL = "gemini-3.1-flash-lite"

    private const val SYSTEM_PROMPT =
        "You are Nova, a personal voice assistant on an Android phone. " +
                "ALWAYS reply in the same language and script as the user's latest message: " +
                "English gets English, Roman Urdu gets Roman Urdu, Urdu script gets Urdu script. " +
                "Never switch language on your own. " +
                "Every user message ends with a tag like [Reply language: English]. Reply in exactly that language and never mention or repeat the tag. " +
                "Tool results may be written in Roman Urdu; ignore their language and answer in the user's language. " +
                "Keep replies to 1-3 short sentences because they are spoken aloud. " +
                "No markdown and no emojis. " +
                "When the user asks for an action (open an app, battery status, set an alarm, call a contact, send an SMS, " +
                "set a reminder, create or read calendar events, search the web), " +
                "you MUST call the matching function. Never claim an action was done without calling it. " +
                "If a function result says FAILED, tell the user honestly that it did not work and why. " +
                "For calls and SMS the app itself shows a Confirm/Cancel popup, so never ask for confirmation in text; just call the function. " +
                "For reminders and calendar entries call add_calendar_event; for questions like what do I have today or tomorrow call read_calendar. " +
                "For a reminder with no clear time, ask the user for the time instead of guessing. " +
                "For anything that changes over time (news, prices, exchange rates, weather, current office holders, recent events) " +
                "you MUST call web_search instead of answering from memory, and you must mention the date from the result. " +
                "Do NOT use web_search for timeless general knowledge (history, science, definitions, explanations, people from the past); answer those directly. " +
                "If web_search fails, say you could not get current information; never guess. " +
                "After a function result, confirm briefly in the user's language, mentioning the date and time when relevant."

    // Built on every call so Nova always knows the real date and time
    private fun systemPrompt(): String {
        val now = ZonedDateTime.now()
        val fmt = DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm", Locale.ENGLISH)
        return SYSTEM_PROMPT +
                " Current local date and time: ${now.format(fmt)} (timezone ${now.zone}). " +
                "Use it to work out words like today, tomorrow, next Monday. " +
                "Dates use the format yyyy-MM-dd and date-times use yyyy-MM-ddTHH:mm in 24-hour local time."
    }

    // Retries up to 2 times when Gemini says "503 high demand"
    fun ask(history: List<JSONObject>): Result<AiReply> {
        var r = askOnce(history)
        var tries = 0
        while (r.isFailure && tries < 2 &&
            r.exceptionOrNull()?.message?.startsWith("HTTP 503") == true
        ) {
            tries++
            Thread.sleep(1500L * tries)
            r = askOnce(history)
        }
        return r
    }

    // history: full Gemini "contents" objects (role + parts)
    private fun askOnce(history: List<JSONObject>): Result<AiReply> {
        return try {
            val key = BuildConfig.GEMINI_API_KEY
            if (key.isBlank()) {
                return Result.failure(Exception("API key local.properties mein nahi mili"))
            }

            val contents = JSONArray()
            for (c in history) contents.put(c)

            val body = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemPrompt()))
                    )
                )
                .put("contents", contents)
                .put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("functionDeclarations", ToolRegistry.declarations())
                    )
                )

            val url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return Result.failure(Exception("HTTP $code: ${response.take(300)}"))
            }

            val parts = JSONObject(response)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")

            var call: AiReply.ToolCall? = null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("functionCall")) {
                    val fc = p.getJSONObject("functionCall")
                    call = AiReply.ToolCall(
                        fc.getString("name"),
                        fc.optJSONObject("args") ?: JSONObject(),
                        p // raw part is kept as-is (it may carry a thought signature)
                    )
                } else if (p.has("text") && !p.optBoolean("thought", false)) {
                    sb.append(p.getString("text"))
                }
            }

            if (call != null) {
                Result.success(call)
            } else if (sb.isNotBlank()) {
                Result.success(AiReply.Text(sb.toString().trim()))
            } else {
                Result.failure(Exception("Khali jawab aaya"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}