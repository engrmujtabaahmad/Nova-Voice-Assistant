package com.mujtaba.nova.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

class WhatsAppCallTool : Tool {
    override val name = "whatsapp_call"
    override val description =
        "WhatsApp voice ya video call ki screen kholta hai kisi contact ke liye. " +
                "Ye confirm nahi kar sakta ke call connect hui. video true ho to video call."
    override val parameters: JSONObject? = JSONObject()
        .put("type", "OBJECT")
        .put(
            "properties",
            JSONObject()
                .put("contact", JSONObject().put("type", "STRING").put("description", "Contact ka naam, jaisa user ne bola"))
                .put("video", JSONObject().put("type", "BOOLEAN").put("description", "true for video call, default false"))
        )
        .put("required", JSONArray().put("contact"))

    override val permissions = listOf(Manifest.permission.READ_CONTACTS)
    override val needsConfirmation = true

    override fun confirmText(context: Context, args: JSONObject): String? {
        val m = ContactResolver.resolve(context, args.optString("contact"))
        if (m !is ContactMatch.Found) return null
        val kind = if (args.optBoolean("video", false)) "video" else "voice"
        return "WhatsApp $kind call karni hai: ${m.name}?"
    }

    override fun execute(context: Context, args: JSONObject): ToolResult {
        val query = args.optString("contact")
        val video = args.optBoolean("video", false)

        return when (val m = ContactResolver.resolve(context, query)) {
            is ContactMatch.NotFound -> ToolResult(false, "'$query' naam ka contact nahi mila.")
            is ContactMatch.Ambiguous ->
                ToolResult(false, "Kai contacts milte hain: ${m.options.joinToString(", ")}. Poora naam batao.")
            is ContactMatch.Found -> {
                val mime = if (video) "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
                else "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"

                var dataId: Long? = null
                try {
                    context.contentResolver.query(
                        ContactsContract.Data.CONTENT_URI,
                        arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME),
                        "${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(mime),
                        null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            if (c.getString(1).equals(m.name, ignoreCase = true)) {
                                dataId = c.getLong(0)
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    return ToolResult(false, "WhatsApp contacts parh nahi saka: ${e.message}")
                }

                val id = dataId ?: return ToolResult(
                    false,
                    "${m.name} ke liye WhatsApp call ka raasta nahi mila. WhatsApp chat kholo aur upar call icon dabao."
                )

                try {
                    val i = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse("content://com.android.contacts/data/$id"), mime)
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(i)
                    ToolResult(true, "${m.name} ke liye WhatsApp call screen khol di. Call connect hui ya nahi, ye mujhe nahi pata.")
                } catch (e: ActivityNotFoundException) {
                    ToolResult(false, "WhatsApp call shuru nahi ho saki. Chat kholkar call icon dabao.")
                } catch (e: Exception) {
                    ToolResult(false, "WhatsApp call nahi lag saki: ${e.message}")
                }
            }
        }
    }
}