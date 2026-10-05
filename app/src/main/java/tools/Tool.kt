package com.mujtaba.nova.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ToolResult(val ok: Boolean, val message: String)

interface Tool {
    val name: String
    val description: String
    val parameters: JSONObject?
    fun execute(context: Context, args: JSONObject): ToolResult
}

object ToolRegistry {

    private val tools: List<Tool> = listOf(
        OpenAppTool(),
        BatteryTool(),
        SetAlarmTool()
    )

    fun find(name: String): Tool? = tools.firstOrNull { it.name == name }

    fun declarations(): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val d = JSONObject()
                .put("name", t.name)
                .put("description", t.description)
            t.parameters?.let { d.put("parameters", it) }
            arr.put(d)
        }
        return arr
    }
}