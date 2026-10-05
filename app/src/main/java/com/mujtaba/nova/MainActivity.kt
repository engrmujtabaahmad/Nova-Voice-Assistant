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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mujtaba.nova.ui.theme.NovaTheme
import java.util.Locale

data class ChatMsg(val from: String, val text: String)

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val messages = mutableStateListOf<ChatMsg>()
    private var status by mutableStateOf("Mic dabao aur bolo")

    // Conversation context for Gemini: (role, text)
    private val history = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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

    private fun askNova(userText: String) {
        messages.add(ChatMsg("Aap", userText))
        history.add("user" to userText)
        if (history.size > 20) {
            repeat(history.size - 20) { history.removeAt(0) }
        }
        status = "Soch raha hun..."

        val snapshot = history.toList()
        Thread {
            val result = GeminiClient.ask(snapshot)
            runOnUiThread {
                result.onSuccess { reply ->
                    history.add("model" to reply)
                    messages.add(ChatMsg("Nova", reply))
                    status = "Mic dabao aur bolo"
                    speak(reply)
                }.onFailure { e ->
                    // remove the failed user turn so history stays valid
                    if (history.isNotEmpty() && history.last().first == "user") {
                        history.removeAt(history.size - 1)
                    }
                    val msg = "Jawab nahi mil saka: ${e.message}"
                    messages.add(ChatMsg("Nova", msg))
                    status = "Error. Dobara koshish karo"
                }
            }
        }.start()
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