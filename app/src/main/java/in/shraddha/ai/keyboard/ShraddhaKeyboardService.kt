package `in`.shraddha.ai.keyboard

import android.inputmethodservice.InputMethodService
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.AssistantBrand
import android.graphics.Color
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class ShraddhaKeyboardService : InputMethodService() {
    private var recognizer: SpeechRecognizer? = null
    override fun onCreateInputView(): View {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8); setBackgroundColor(Color.rgb(18, 24, 39)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(key("🎙 बोलें") { startDictation() })
        row.addView(key("हिंदी / EN") { currentInputConnection?.commitText("", 1) })
        row.addView(key("↵ Enter") { currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)) })
        row.addView(key("⌫") { currentInputConnection?.deleteSurroundingText(1, 0) })
        panel.addView(row)
        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("आज की तारीख", "धन्यवाद", "जी हाँ", "कृपया संपर्क करें").forEach { value -> shortcuts.addView(key(value) { currentInputConnection?.commitText(value, 1) }) }
        panel.addView(shortcuts)
        val assistantName = NovaPreferences.getString(this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME).trim().ifBlank { AssistantBrand.NAME }
        val info = Button(this).apply { text = "$assistantName • Voice dictation के लिए ऐप का Talk बटन खोलें"; isAllCaps = false; setOnClickListener { requestHideSelf(0); Toast.makeText(this@ShraddhaKeyboardService, "$assistantName खोलकर Talk दबाएँ।", Toast.LENGTH_SHORT).show() } }
        panel.addView(info)
        return panel
    }
    private fun startDictation() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition इस डिवाइस पर उपलब्ध नहीं है।", Toast.LENGTH_SHORT).show(); return
        }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { }
            override fun onError(error: Int) { Toast.makeText(this@ShraddhaKeyboardService, "आवाज़ समझ नहीं आई। फिर कोशिश करें।", Toast.LENGTH_SHORT).show() }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) currentInputConnection?.commitText(spoken, 1)
            }
            override fun onPartialResults(partialResults: Bundle?) { }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(intent)
    }

    override fun onDestroy() { recognizer?.destroy(); recognizer = null; super.onDestroy() }

    private fun key(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(45, 55, 78));
        layoutParams = LinearLayout.LayoutParams(0, 54, 1f).apply { setMargins(3, 3, 3, 3) }
        setOnClickListener { action() }
    }
}
