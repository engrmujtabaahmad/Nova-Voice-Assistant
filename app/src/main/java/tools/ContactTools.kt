package com.mujtaba.nova.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

sealed class ContactMatch {
    data class Found(val name: String, val number: String) : ContactMatch()
    data class Ambiguous(val options: List<String>) : ContactMatch()
    object NotFound : ContactMatch()
}

object ContactResolver {

    // Turns a word into a rough "sound shape": vowels dropped, similar letters merged.
    // Osama/Usama -> "asm", song/zong -> "sng", Ammi/Ami -> "am", Jaan/Jan -> "jn"
    private fun skeleton(word: String): String {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        val sb = StringBuilder()
        for ((i, ch) in w.withIndex()) {
            val mapped = when (ch) {
                'z' -> 's'
                'q' -> 'k'
                'w' -> 'v'
                'c' -> 'k'
                else -> ch
            }
            val isVowel = mapped in "aeiouyh"
            if (i == 0) {
                sb.append(if (isVowel) 'a' else mapped)
            } else if (!isVowel && sb.last() != mapped) {
                sb.append(mapped)
            }
        }
        return sb.toString()
    }

    // Removes emojis, digits and symbols. "Hamza 😎" -> "Hamza"
    private fun cleanName(s: String): String =
        s.filter { it.isLetter() || it.isWhitespace() }
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun tokens(text: String): List<String> =
        text.split(Regex("[^A-Za-z]+")).map { skeleton(it) }.filter { it.isNotEmpty() }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    private fun score(query: String, contactName: String): Double {
        val q = cleanName(query)
        val c = cleanName(contactName)
        if (q.isEmpty() || c.isEmpty()) return 0.0

        // 1) Exact match after removing emojis/symbols
        if (q.equals(c, ignoreCase = true)) return 1.0

        val extraWords = maxOf(0, c.split(" ").size - q.split(" ").size)

        // 2) Contact name contains the spoken name
        if (c.contains(q, ignoreCase = true)) return 0.9 - 0.01 * extraWords

        // 3) Sound-alike match
        val qt = tokens(q)
        val ct = tokens(c)
        if (qt.isEmpty() || ct.isEmpty()) return 0.0

        var total = 0.0
        for (t in qt) {
            val best = ct.maxOf { similarity(t, it) }
            if (best < 0.6) return 0.0
            total += best
        }
        val avg = minOf(total / qt.size, 0.88)
        return avg - 0.03 * extraWords
    }

    private fun last10(n: String) = n.filter { it.isDigit() }.takeLast(10)

    fun resolve(context: Context, query: String): ContactMatch {
        val q = query.trim()
        if (q.isEmpty()) return ContactMatch.NotFound

        // User said a raw phone number
        val looksLikeNumber = q.any { it.isDigit() } && q.none { it.isLetter() }
        if (looksLikeNumber) {
            val num = q.filter { it.isDigit() || it == '+' }
            return ContactMatch.Found(num, num)
        }

        val rows = mutableListOf<Pair<String, String>>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val p = c.getString(1) ?: continue
                rows.add(n to p)
            }
        }
        if (rows.isEmpty()) return ContactMatch.NotFound

        data class Scored(val name: String, val number: String, val score: Double)

        val scored = rows
            .map { Scored(it.first, it.second, score(q, it.first)) }
            .filter { it.score >= 0.7 }
            .sortedByDescending { it.score }
            .distinctBy { last10(it.number) }

        if (scored.isEmpty()) return ContactMatch.NotFound

        val top = scored[0]
        val second = scored.getOrNull(1)
        return if (second == null || top.score - second.score >= 0.05) {
            ContactMatch.Found(top.name, top.number.filter { it.isDigit() || it == '+' })
        } else {
            val close = scored.filter { top.score - it.score < 0.05 }.take(3)
            ContactMatch.Ambiguous(close.map { "${it.name} (${it.number})" })
        }
    }
}

class CallContactTool : Tool {
    override val name = "call_contact"
    override val description =
        "Kisi contact ya phone number ko phone call karta hai. name mein contact ka naam ya number do, " +
                "bilkul waise jaise user ne bola (spelling theek karne ki koshish mat karo)."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject().put(
                "name",
                JSONObject().put("type", "STRING").put("description", "Contact ka naam ya phone number")
            )
        )
        .put("required", JSONArray().put("name"))

    override val permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
    override val needsConfirmation = true

    override fun confirmText(context: Context, args: JSONObject): String? {
        val m = ContactResolver.resolve(context, args.optString("name"))
        return if (m is ContactMatch.Found) "Call karna hai: ${m.name} (${m.number})?" else null
    }

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val query = args.optString("name")
        return when (val m = ContactResolver.resolve(context, query)) {
            is ContactMatch.NotFound ->
                ToolResult(false, "'$query' naam ka contact nahi mila.")
            is ContactMatch.Ambiguous ->
                ToolResult(false, "Kai contacts milte hain: ${m.options.joinToString(", ")}. Poora naam batao.")
            is ContactMatch.Found -> try {
                val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(m.number)))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                ToolResult(true, "${m.name} ko call laga raha hun.")
            } catch (e: SecurityException) {
                ToolResult(false, "Call ki permission nahi hai.")
            } catch (e: Exception) {
                ToolResult(false, "Call nahi lag saki: ${e.message}")
            }
        }
    }
}

class SendSmsTool : Tool {
    override val name = "send_sms"
    override val description =
        "Kisi contact ya number ko SMS bhejta hai. to mein naam ya number (bilkul waise jaise user ne bola), " +
                "message mein SMS ka text."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("to", JSONObject().put("type", "STRING").put("description", "Contact ka naam ya number"))
                .put("message", JSONObject().put("type", "STRING").put("description", "SMS ka text"))
        )
        .put("required", JSONArray().put("to").put("message"))

    override val permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
    override val needsConfirmation = true

    override fun confirmText(context: Context, args: JSONObject): String? {
        val msg = args.optString("message").trim()
        if (msg.isEmpty()) return null
        val m = ContactResolver.resolve(context, args.optString("to"))
        return if (m is ContactMatch.Found)
            "SMS bhejna hai ${m.name} (${m.number}):\n\n\"$msg\"" else null
    }

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val query = args.optString("to")
        val msg = args.optString("message").trim()
        if (msg.isEmpty()) return ToolResult(false, "SMS ka text samajh nahi aaya.")

        return when (val m = ContactResolver.resolve(context, query)) {
            is ContactMatch.NotFound ->
                ToolResult(false, "'$query' naam ka contact nahi mila.")
            is ContactMatch.Ambiguous ->
                ToolResult(false, "Kai contacts milte hain: ${m.options.joinToString(", ")}. Poora naam batao.")
            is ContactMatch.Found -> try {
                val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                val parts = sms.divideMessage(msg)
                sms.sendMultipartTextMessage(m.number, null, parts, null, null)
                ToolResult(true, "SMS ${m.name} ke liye network ko de diya gaya hai.")
            } catch (e: SecurityException) {
                ToolResult(false, "SMS ki permission nahi hai.")
            } catch (e: Exception) {
                ToolResult(false, "SMS nahi ja saka: ${e.message}")
            }
        }
    }
}