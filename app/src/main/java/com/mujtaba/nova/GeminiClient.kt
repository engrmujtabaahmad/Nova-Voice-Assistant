package com.mujtaba.nova

import com.mujtaba.nova.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
                "When the user asks for an action (open an app, battery status, set an alarm), " +
                "you MUST call the matching function. Never claim an action was done without calling it. " +
                "After a function result, confirm briefly in the user's language. " +
                "If you need live or current information that you do not have, say so honestly."

    // history: full Gemini "contents" objects (role + parts)
    fun ask(history: List<JSONObject>): Result<AiReply> {
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
                        JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
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