package com.mujtaba.nova.tools

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.util.Locale

class DeviceInfoTool : Tool {
    override val name = "get_device_info"
    override val description =
        "Phone ki maloomat: storage kitni bachi hai, RAM, internet (Wi-Fi ya mobile data) ki halat, " +
                "phone ka model aur Android version."
    override val parameters: JSONObject? = null

    private fun gb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1073741824.0)

    override fun execute(context: Context, args: JSONObject): ToolResult {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "koi internet connection nahi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi connected"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data connected"
                else -> "internet connected"
            }

            ToolResult(
                true,
                "Storage: ${gb(free)} GB free of ${gb(total)} GB. " +
                        "RAM: ${gb(mi.availMem)} GB free of ${gb(mi.totalMem)} GB. " +
                        "Internet: $net. " +
                        "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}."
            )
        } catch (e: Exception) {
            ToolResult(false, "Phone ki maloomat nahi mil saki: ${e.message}")
        }
    }
}