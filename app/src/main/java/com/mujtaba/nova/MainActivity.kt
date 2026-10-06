package com.mujtaba.nova

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mujtaba.nova.tools.Tool
import com.mujtaba.nova.tools.ToolRegistry
import com.mujtaba.nova.tools.ToolResult
import com.mujtaba.nova.ui.theme.NovaTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ChatMsg(val from: String, val text: String)

data class PendingAction(
    val text: String,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit
)

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val messages = mutableStateListOf<ChatMsg>()
    private var status by mutableStateOf("Mic dabao aur bolo")

    // Confirm popup state
    private var pending by mutableStateOf<PendingAction?>(null)

    // Conversation context: full Gemini content objects
    private val history = mutableListOf<JSONObject>()
    private var lastToolMessage: String? = null
    private var currentLang = "English"

    // Permission request for tools (calls, SMS, contacts, calendar)
    private var permCallback: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cb = permCallback
        permCallback = null
        cb?.invoke(result.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.mujtaba.nova.tools.NovaMemory.init(applicationContext)

        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(listener)
        } else {
            status = "Speech recognition is phone par available nahi"
        }

        setContent {
            NovaTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) startListening() else status = "Mic permission nahi mili"
                }

                val p = pending
                if (p != null) {
                    AlertDialog(
                        onDismissRequest = { p.onCancel() },
                        title = { Text("Confirm karo") },
                        text = { Text(p.text) },
                        confirmButton = {
                            TextButton(onClick = { p.onConfirm() }) { Text("Haan, karo") }
                        },
                        dismissButton = {
                            TextButton(onClick = { p.onCancel() }) { Text("Cancel") }
                        }
                    )
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                            .fillMaxSize()
                    ) {
                        Text("Nova", style = MaterialTheme.typography.headlineMedium)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(messages) { m ->
                                Text("${m.from}: ${m.text}")
                            }
                        }

                        Button(
                            onClick = {
                                val ok = ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (ok) startListening()
                                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.CenterHorizontally)
                        ) {
                            Text("🎤  Bolo")
                        }
                    }
                }
            }
        }
    }

    // Quick Settings tile opens the app with auto_listen = true
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra("auto_listen", false) == true) {
            intent.removeExtra("auto_listen")
            val ok = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (ok) {
                window.decorView.postDelayed({ startListening() }, 700)
            }
        }
    }

    private fun detectLang(t: String): String {
        if (t.any { it in '\u0600'..'\u06FF' }) return "Urdu script"
        val romanUrdu = setOf(
            "kya", "hai", "hain", "kaun", "kitni", "kitna", "karo", "kro", "lagao",
            "kholo", "mujhe", "batao", "aaj", "kal", "mera", "meri", "ka", "ki", "ko",
            "mein", "nahi", "acha", "theek", "subah", "sham", "raat", "bhai", "kar",
            "dena", "bhejo", "bolo", "suno", "kahan", "kab", "kyun", "kaise", "wala", "wali"
        )
        val words = t.lowercase().split(Regex("[^a-z]+"))
        return if (words.any { it in romanUrdu }) "Roman Urdu" else "English"
    }

    private fun startListening() {
        val r = recognizer
        if (r == null) {
            status = "Recognizer ready nahi"
            return
        }
        tts?.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
        }
        r.startListening(intent)
    }

    private fun speak(text: String) {
        val hasUrduScript = text.any { it in '\u0600'..'\u06FF' }
        tts?.language = if (hasUrduScript) Locale.forLanguageTag("ur-PK") else Locale.US
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova")
    }

    // ---------- history helpers (real Gemini format) ----------

    private fun userTextContent(t: String): JSONObject =
        JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", t)))

    private fun modelTextContent(t: String): JSONObject =
        JSONObject().put("role", "model")
            .put("parts", JSONArray().put(JSONObject().put("text", t)))

    private fun modelPartContent(part: JSONObject): JSONObject =
        JSONObject().put("role", "model").put("parts", JSONArray().put(part))

    private fun toolResponseContent(name: String, result: String): JSONObject =
        JSONObject().put("role", "user").put(
            "parts",
            JSONArray().put(
                JSONObject().put(
                    "functionResponse",
                    JSONObject()
                        .put("name", name)
                        .put("response", JSONObject().put("result", result).put("reply_language", currentLang))
                )
            )
        )

    private fun isUserText(c: JSONObject): Boolean {
        if (c.optString("role") != "user") return false
        val parts = c.optJSONArray("parts") ?: return false
        return parts.length() > 0 && parts.getJSONObject(0).has("text")
    }

    private fun trimHistory() {
        while (history.size > 24) history.removeAt(0)
        // history must always start with a normal user text turn
        while (history.isNotEmpty() && !isUserText(history[0])) history.removeAt(0)
    }

    // ---------- agent loop ----------

    private fun askNova(userText: String) {
        messages.add(ChatMsg("Aap", userText))
        currentLang = detectLang(userText)

        val now = java.time.ZonedDateTime.now()
        val nowText = now.format(
            java.time.format.DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH)
        )
        history.add(
            userTextContent(
                "$userText\n[Reply language: $currentLang]\n" +
                        "[Now: $nowText, timezone ${now.zone}. Use this exact date for today, tomorrow and weekdays. Never mention this tag.]"
            )
        )

        trimHistory()
        lastToolMessage = null
        status = "Soch raha hun..."
        callModel(0)
    }

    private fun callModel(depth: Int) {
        val snapshot = history.toList()
        Thread {
            val result = GeminiClient.ask(snapshot)
            runOnUiThread { handleReply(result, depth) }
        }.start()
    }

    private fun handleReply(result: Result<AiReply>, depth: Int) {
        result.onSuccess { reply ->
            when (reply) {
                is AiReply.Text -> {
                    history.add(modelTextContent(reply.text))
                    messages.add(ChatMsg("Nova", reply.text))
                    status = "Mic dabao aur bolo"
                    speak(reply.text)
                }
                is AiReply.ToolCall -> runToolFlow(reply, depth)
            }
        }.onFailure { e ->
            val fallback = lastToolMessage
            if (depth > 0 && fallback != null) {
                // tool already ran, only the follow-up reply failed
                history.add(modelTextContent(fallback))
                messages.add(ChatMsg("Nova", fallback))
                status = "Mic dabao aur bolo"
                speak(fallback)
            } else {
                if (history.isNotEmpty() && isUserText(history.last())) {
                    history.removeAt(history.size - 1)
                }
                messages.add(ChatMsg("Nova", "Jawab nahi mil saka: ${e.message}"))
                status = "Error. Dobara koshish karo"
            }
        }
    }

    // ---------- tool flow: permission -> confirm -> execute ----------

    private fun runToolFlow(call: AiReply.ToolCall, depth: Int) {
        val tool = ToolRegistry.find(call.name)
        if (tool == null) {
            finishTool(call, ToolResult(false, "Ye kaam abhi mujhe nahi aata."), depth)
            return
        }

        val missing = tool.permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            confirmAndRun(call, tool, depth)
            return
        }

        status = "Permission chahiye..."
        permCallback = { allGranted ->
            if (allGranted) {
                confirmAndRun(call, tool, depth)
            } else {
                finishTool(
                    call,
                    ToolResult(false, "Is kaam ke liye zaroori permission nahi mili, is liye main ye nahi kar saka."),
                    depth
                )
            }
        }
        permLauncher.launch(missing.toTypedArray())
    }

    private fun confirmAndRun(call: AiReply.ToolCall, tool: Tool, depth: Int) {
        val confirm = if (tool.needsConfirmation) tool.confirmText(this, call.args) else null
        if (confirm == null) {
            runAndFinish(call, tool, depth)
            return
        }
        status = "Aapki tasdeeq ka intezar..."
        pending = PendingAction(
            text = confirm,
            onConfirm = {
                pending = null
                runAndFinish(call, tool, depth)
            },
            onCancel = {
                pending = null
                finishTool(
                    call,
                    ToolResult(false, "User ne cancel kar diya. Kaam nahi kiya gaya."),
                    depth
                )
            }
        )
    }

    private fun runAndFinish(call: AiReply.ToolCall, tool: Tool, depth: Int) {
        status = "Kaam kar raha hun..."
        val started = System.currentTimeMillis()
        // Tools run on a background thread so internet calls are allowed
        Thread {
            val res: ToolResult = try {
                tool.execute(this, call.args)
            } catch (e: Exception) {
                ToolResult(false, "Kaam fail hua: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
            val ms = System.currentTimeMillis() - started
            runOnUiThread { finishTool(call, res, depth, ms) }
        }.start()
    }

    private fun finishTool(call: AiReply.ToolCall, res: ToolResult, depth: Int, ms: Long = -1) {
        // Debug line: tool name, arguments, result, time
        val time = if (ms >= 0) " (${ms}ms)" else ""
        messages.add(
            ChatMsg("⚙", "${call.name} ${call.args} -> ${if (res.ok) "OK" else "FAIL: ${res.message}"}$time")
        )

        lastToolMessage = res.message
        history.add(modelPartContent(call.rawPart))
        history.add(
            toolResponseContent(
                call.name,
                if (res.ok) res.message else "FAILED. The action did NOT happen. ${res.message}"
            )
        )

        if (depth < 2) {
            status = "Soch raha hun..."
            callModel(depth + 1)
        } else {
            messages.add(ChatMsg("Nova", res.message))
            history.add(modelTextContent(res.message))
            status = "Mic dabao aur bolo"
            speak(res.message)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status = "Sun raha hun..." }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { status = "Process kar raha hun..." }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            status = "Speech error code: $error"
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text == null) {
                status = "Kuch samajh nahi aaya"
                return
            }
            askNova(text)
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}