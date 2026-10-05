package com.mujtaba.nova

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiClient {

    private const val MODEL = "gemini-3.1-flash-lite"

    private const val SYSTEM_PROMPT =
        "Tum Nova ho, ek personal voice assistant. " +
                "ZAROORI QAIDA: user ke LATEST message ki zubaan aur script mein hi jawab do. " +
                "Agar user English mein bole to sirf English mein jawab do. " +
                "Agar user Roman Urdu mein bole to Roman Urdu mein jawab do. " +
                "Agar user Urdu script (اردو) mein bole to Urdu script mein jawab do. " +
                "Kabhi bina wajah zubaan mat badlo. " +
                "Jawab chhota rakho (1-3 jumle) kyunke ye bol kar sunaya jayega. " +
                "Markdown ya emoji mat use karo. " +
                "Agar tumhe current/live info chahiye jo tumhare paas nahi, to saaf bata do."
    // history: list of (role, text), role is "user" or "model"
    fun ask(history: List<Pair<String, String>>): Result<String> {
        return try {
            val key = BuildConfig.GEMINI_API_KEY
            if (key.isBlank()) {
                return Result.failure(Exception("API key local.properties mein nahi mili"))
            }

            val contents = JSONArray()
            for ((role, text) in history) {
                contents.put(
                    JSONObject()
                        .put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            }
            val body = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                    )
                )
                .put("contents", contents)

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
                return Result.failure(Exception("HTTP $code: ${response.take(200)}"))
            }

            val text = JSONObject(response)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}