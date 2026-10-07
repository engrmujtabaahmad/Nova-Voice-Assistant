package com.mujtaba.nova

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mujtaba.nova.tools.NovaMemory
import com.mujtaba.nova.ui.theme.NovaTheme

object NovaSettings {
    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)

    fun debug(c: Context): Boolean = prefs(c).getBoolean("debug", true)
    fun setDebug(c: Context, on: Boolean) = prefs(c).edit().putBoolean("debug", on).apply()
}

class SettingsActivity : ComponentActivity() {

    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NovaMemory.init(applicationContext)
        setContent { NovaTheme { SettingsScreen() } }
    }

    override fun onResume() {
        super.onResume()
        refresh++ // re-check permissions when coming back from system settings
    }

    @androidx.compose.runtime.Composable
    private fun SettingsScreen() {
        @Suppress("UNUSED_VARIABLE") val r = refresh

        var debugOn by remember { mutableStateOf(NovaSettings.debug(this)) }
        var memOn by remember { mutableStateOf(NovaMemory.enabled()) }
        var memCount by remember { mutableIntStateOf(NovaMemory.all().size) }

        val perms = listOf(
            "Microphone (bolne ke liye)" to Manifest.permission.RECORD_AUDIO,
            "Contacts (naam dhoondne ke liye)" to Manifest.permission.READ_CONTACTS,
            "Phone calls (call lagane ke liye)" to Manifest.permission.CALL_PHONE,
            "SMS bhejna" to Manifest.permission.SEND_SMS,
            "Calendar parhna" to Manifest.permission.READ_CALENDAR,
            "Calendar likhna" to Manifest.permission.WRITE_CALENDAR
        )

        Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
            Column(
                modifier = Modifier
                    .padding(inner)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finish() }) { Text("< Wapas") }
                    Text("Nova Settings", style = MaterialTheme.typography.headlineSmall)
                }

                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                for ((label, perm) in perms) {
                    val ok = ContextCompat.checkSelfPermission(this@SettingsActivity, perm) ==
                            PackageManager.PERMISSION_GRANTED
                    Text((if (ok) "✅ " else "❌ ") + label)
                }
                Text(
                    "Jo ❌ hai wo Nova pehli baar zaroorat par khud maangega. Badalna ho to neeche wala button dabao.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Phone ki app permissions kholo") }

                Spacer(Modifier.height(8.dp))
                Text("Developer / Debug", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚙ lines dikhao (tool, arguments, natija)")
                    Switch(checked = debugOn, onCheckedChange = {
                        debugOn = it
                        NovaSettings.setDebug(this@SettingsActivity, it)
                    })
                }

                Spacer(Modifier.height(8.dp))
                Text("Memory (yaad ki hui baatein)", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Memory istemal karo")
                    Switch(checked = memOn, onCheckedChange = {
                        memOn = it
                        NovaMemory.setEnabled(it)
                    })
                }
                Text("Abhi $memCount baatein yaad hain.")
                Button(
                    onClick = {
                        NovaMemory.clear()
                        memCount = 0
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Saari yaad mita do") }

                Spacer(Modifier.height(8.dp))
                Text("Privacy", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• Memory sirf is phone mein save hoti hai.\n" +
                            "• Aap jo bolte ho wo jawab ke liye Google Gemini ko bheja jata hai.\n" +
                            "• Call, SMS aur WhatsApp se pehle Nova aap se poochta hai.\n" +
                            "• Mic sirf tab chalta hai jab aap tile ya Bolo dabate ho.\n" +
                            "• Nova kisi ki private chats nahi parhta.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}