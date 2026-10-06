package com.mujtaba.nova.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class WhatsAppChatTool : Tool {
    override val name = "open_whatsapp_chat"
    override val description =
        "WhatsApp mein kisi contact ya number ki chat kholta hai, aur optional message pehle se likh deta hai. " +
                "Message khud send NAHI hota; user ko WhatsApp mein Send dabana hota hai."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("contact", JSONObject().put("type", "STRING").put("description", "Contact ka naam ya number, jaisa user ne bola"))
                .put("message", JSONObject().put("type", "STRING").put("description", "Pehle se likha jane wala message (optional)"))
        )
        .put("required", JSONArray().put("contact"))

    override val permissions = listOf(Manifest.permission.READ_CONTACTS)
    override val needsConfirmation = true

    // Pakistan default: 03xx... becomes 923xx...
    private fun waNumber(raw: String): String {
        val hadPlus = raw.trim().startsWith("+")
        var d = raw.filter { it.isDigit() }
        if (hadPlus) return d
        if (d.startsWith("00")) return d.drop(2)
        if (d.startsWith("0")) return "92" + d.drop(1)
        return d
    }

    override fun confirmText(context: Context, args: JSONObject): String? {
        val m = ContactResolver.resolve(context, args.optString("contact"))
        if (m !is ContactMatch.Found) return null
        val msg = args.optString("message").trim()
        return if (msg.isEmpty()) {
            "WhatsApp mein ${m.name} ki chat kholni hai?"
        } else {
            "WhatsApp mein ${m.name} (${m.number}) ki chat khol kar ye message tayyar karun?\n\n\"$msg\"\n\n(Send aap khud dabayenge.)"
        }
    }

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val query = args.optString("contact")
        val msg = args.optString("message").trim()

        return when (val m = ContactResolver.resolve(context, query)) {
            is ContactMatch.NotFound -> ToolResult(false, "'$query' naam ka contact nahi mila.")
            is ContactMatch.Ambiguous ->
                ToolResult(false, "Kai contacts milte hain: ${m.options.joinToString(", ")}. Poora naam batao.")
            is ContactMatch.Found -> {
                val num = waNumber(m.number)
                if (num.length < 8) return ToolResult(false, "${m.name} ka number WhatsApp ke liye theek nahi lagta.")

                val link = "https://wa.me/$num" + if (msg.isNotEmpty()) "?text=" + Uri.encode(msg) else ""
                val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
                for (pkg in packages) {
                    try {
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                            setPackage(pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(i)
                        return ToolResult(
                            true,
                            if (msg.isEmpty()) "WhatsApp mein ${m.name} ki chat khol di."
                            else "WhatsApp mein ${m.name} ki chat khol di aur message likh diya. Send abhi nahi hua, aap Send dabayen."
                        )
                    } catch (e: ActivityNotFoundException) {
                        // try next package
                    } catch (e: Exception) {
                        return ToolResult(false, "WhatsApp nahi khul saka: ${e.message}")
                    }
                }
                ToolResult(false, "WhatsApp phone mein install nahi mila.")
            }
        }
    }
}