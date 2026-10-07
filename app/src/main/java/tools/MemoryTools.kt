package com.mujtaba.nova.tools

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object NovaMemory {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("nova_memory", Context.MODE_PRIVATE)
        }
    }

    fun enabled(): Boolean = prefs?.getBoolean("enabled", true) ?: true

    fun setEnabled(on: Boolean) {
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
    }

    fun all(): List<String> {
        val a = JSONArray(prefs?.getString("items", "[]") ?: "[]")
        return List(a.length()) { a.getString(it) }
    }

    private fun save(items: List<String>) {
        prefs?.edit()?.putString("items", JSONArray(items).toString())?.apply()
    }

    fun add(fact: String): String? {
        if (!enabled()) return "Memory band hai. Settings mein ON karo."
        val f = fact.trim()
        if (f.isEmpty()) return "Kya yaad rakhna hai, ye samajh nahi aaya."
        if (f.length > 200) return "Ye bohot lamba hai, chhota karke batao."
        if (Regex("\\d{9,}").containsMatchIn(f.replace(Regex("[\\s-]"), ""))) {
            return "Lambay numbers (card, ID, account) main yaad nahi rakhta."
        }
        val items = all().toMutableList()
        if (items.any { it.equals(f, ignoreCase = true) }) return null
        items.add(f)
        while (items.size > 50) items.removeAt(0)
        save(items)
        return null
    }

    fun forget(keyword: String): Int {
        val items = all()
        val keep = items.filterNot { it.contains(keyword.trim(), ignoreCase = true) }
        save(keep)
        return items.size - keep.size
    }

    fun clear() = save(emptyList())

    fun promptText(): String {
        if (!enabled()) return ""
        val items = all()
        if (items.isEmpty()) return ""
        return " Things the user asked you to remember: ${items.joinToString("; ")}. " +
                "Use them only when relevant and do not recite them unless asked."
    }
}

class RememberTool : Tool {
    override val name = "remember_fact"
    override val description =
        "User ne jo baat yaad rakhne ko kaha (jaise 'meri meeting Thursday ko hai') use save karta hai. " +
                "Passwords, card ya ID numbers kabhi save na karo."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject().put("fact", JSONObject().put("type", "STRING").put("description", "Yaad rakhne wali baat, chhoti aur saaf"))
        )
        .put("required", JSONArray().put("fact"))

    override fun execute(context: Context, args: JSONObject): ToolResult {
        NovaMemory.init(context)
        val err = NovaMemory.add(args.optString("fact"))
        return if (err != null) ToolResult(false, err) else ToolResult(true, "Yaad kar liya.")
    }
}

class ListMemoryTool : Tool {
    override val name = "list_memory"
    override val description = "Dikhata hai ke user ne kya kya yaad rakhne ko kaha hai."
    override val parameters: JSONObject? = null

    override fun execute(context: Context, args: JSONObject): ToolResult {
        NovaMemory.init(context)
        val items = NovaMemory.all()
        return if (items.isEmpty()) ToolResult(true, "Abhi mujhe kuch yaad rakhne ko nahi kaha gaya.")
        else ToolResult(true, "Mujhe ye yaad hai: " + items.joinToString("; "))
    }
}

class ForgetMemoryTool : Tool {
    override val name = "forget_memory"
    override val description =
        "Yaad ki hui cheez bhulata hai. what mein us baat ka koi lafz do, ya 'all' likho sab bhulane ke liye."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject().put("what", JSONObject().put("type", "STRING").put("description", "Baat ka lafz, ya all"))
        )
        .put("required", JSONArray().put("what"))

    override val needsConfirmation = true

    override fun confirmText(context: Context, args: JSONObject): String? =
        if (args.optString("what").trim().equals("all", ignoreCase = true))
            "Nova ki saari yaad ki hui baatein mita doon?" else null

    override fun execute(context: Context, args: JSONObject): ToolResult {
        NovaMemory.init(context)
        val what = args.optString("what").trim()
        if (what.isEmpty()) return ToolResult(false, "Kya bhulana hai, ye samajh nahi aaya.")
        if (what.equals("all", ignoreCase = true)) {
            NovaMemory.clear()
            return ToolResult(true, "Sab kuch bhula diya.")
        }
        val n = NovaMemory.forget(what)
        return if (n == 0) ToolResult(false, "'$what' se milti koi baat yaad nahi thi.")
        else ToolResult(true, "$n baat bhula di.")
    }
}