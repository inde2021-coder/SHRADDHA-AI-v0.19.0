package `in`.shraddha.ai.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.Gravity
import android.graphics.Color
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale
import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService
import `in`.shraddha.ai.operator.OperatorActivity
import `in`.shraddha.ai.operator.files.FileAccessActivity
import `in`.shraddha.ai.notes.NoteOcrActivity

/**
 * Mobile counterpart of the Windows companion command centre.
 * Uses Android-supported intents, Accessibility and user-approved APIs; no root or hidden settings writes.
 */
class MobileCommandCenterActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var command: EditText
    private val voiceRequest = 7401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.rgb(9, 13, 25))
        }
        root.addView(TextView(this).apply {
            text = "SHRADDHA • MOBILE COMMAND CENTER"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "Android पर PC जैसे tools • Hindi + English • User permission required"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFB9C5DD.toInt())
            setPadding(0, 0, 0, 14)
        })
        command = EditText(this).apply {
            hint = "जैसे: आवाज बढ़ाओ, Chrome खोलो, वेब पर खोजो मौसम"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8793AA.toInt())
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xFF172238.toInt())
        }
        root.addView(command)
        root.addView(action("🎙️ Voice Control • बोलकर command दें") { captureVoice() })
        root.addView(action("▶ Command चलाएँ") { runCommand(command.text.toString()) })
        root.addView(action("Task Automation + App Control") { openOperator() })
        root.addView(action("App Launcher • installed apps") { showAppLauncher() })
        root.addView(action("File Manager • search / folders") { startActivity(Intent(this, FileAccessActivity::class.java)) })
        root.addView(action("Screenshot AI • current screen पढ़ें") { readCurrentScreen() })
        root.addView(action("Web Search • browser में खोजें") { webSearch(command.text.toString().removePrefix("वेब पर खोजो ").removePrefix("search web ").trim()) })
        root.addView(action("Code Assistant • AI workspace") { openAiWorkspace() })
        root.addView(action("Media Control • Play / Pause") { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) })
        root.addView(action("Media Control • Next / Previous") {
            android.app.AlertDialog.Builder(this).setTitle("Media action")
                .setItems(arrayOf("Next track", "Previous track")) { _, which ->
                    media(if (which == 0) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }.show()
        })
        root.addView(action("System Control • Volume up / down") {
            android.app.AlertDialog.Builder(this).setTitle("Volume")
                .setItems(arrayOf("Volume up", "Volume down", "Mute / unmute")) { _, which ->
                    val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val direction = when (which) {
                        0 -> AudioManager.ADJUST_RAISE
                        1 -> AudioManager.ADJUST_LOWER
                        else -> AudioManager.ADJUST_TOGGLE_MUTE
                    }
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                    status.text = "Android media volume control भेजा गया।"
                }.show()
        })
        root.addView(action("System Settings • display / sound / permissions") {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        })
        root.addView(action("Notes + Hindi / English OCR") { startActivity(Intent(this, NoteOcrActivity::class.java)) })
        status = TextView(this).apply {
            text = "तैयार। कुछ controls के लिए Accessibility service चालू करें। Android protected settings को सीधे बदलने के बजाय system settings खोलेगा।"
            textSize = 13f
            setTextColor(0xFFE0E6F5.toInt())
            setPadding(12, 16, 12, 16)
        }
        root.addView(status)
        root.addView(action("← वापस") { finish() })
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun action(label: String, run: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF44377D.toInt())
        setOnClickListener { run() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
    }

    private fun captureVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN,en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Shraddha को mobile command बताइए")
        }
        runCatching { startActivityForResult(intent, voiceRequest) }
            .onFailure { status.text = "Speech recognition इस फोन पर उपलब्ध नहीं है।" }
    }

    @Deprecated("Legacy result callback retained for Android speech recognizer compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == voiceRequest && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            command.setText(spoken)
            runCommand(spoken)
        }
    }

    private fun runCommand(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) { status.text = "पहले command बोलें या लिखें।"; return }
        val lower = text.lowercase(Locale.ROOT)
        when {
            lower in listOf("आवाज बढ़ाओ", "volume up", "volume बढ़ाओ", "आवाज तेज करो") -> {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                status.text = "Volume बढ़ाया।"
            }
            lower in listOf("आवाज कम करो", "volume down", "आवाज घटाओ") -> {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                status.text = "Volume कम किया।"
            }
            lower in listOf("म्यूजिक चलाओ", "play pause", "music play pause") -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            lower in listOf("अगला गाना", "next song", "next track") -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            lower in listOf("पिछला गाना", "previous song", "previous track") -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            lower in listOf("स्क्रीन पढ़ो", "read screen", "screenshot ai", "स्क्रीनशॉट ai") -> readCurrentScreen()
            lower.startsWith("वेब पर खोजो ") -> webSearch(text.substringAfter("वेब पर खोजो ").trim())
            lower.startsWith("search web ") -> webSearch(text.substringAfter("search web ").trim())
            lower in listOf("फाइल मैनेजर खोलो", "open file manager", "फाइल खोजो") -> startActivity(Intent(this, FileAccessActivity::class.java))
            lower.startsWith("नोट बनाओ ") -> startActivity(Intent(this, NoteOcrActivity::class.java).putExtra("initial_note", text.substringAfter("नोट बनाओ ").trim()))
            lower.startsWith("create note ") -> startActivity(Intent(this, NoteOcrActivity::class.java).putExtra("initial_note", text.substringAfter("create note ").trim()))
            lower in listOf("नोट खोलो", "open notes", "नोट बनाओ") -> startActivity(Intent(this, NoteOcrActivity::class.java))
            lower in listOf("ऑटोमेशन खोलो", "task automation", "ऐप कंट्रोल खोलो", "control apps") -> openOperator()
            lower in listOf("code assistant", "कोड असिस्टेंट खोलो") -> openAiWorkspace()
            lower.startsWith("खोलो ") -> launchApp(text.substringAfter("खोलो ").trim())
            lower.startsWith("open app ") -> launchApp(text.substringAfter("open app ").trim())
            else -> {
                val intent = Intent(this, OperatorActivity::class.java).putExtra("mobile_voice_command", text)
                startActivity(intent)
                status.text = "Command App Operator में भेजा गया। चलाने से पहले समीक्षा करें।"
            }
        }
    }

    private fun openOperator() {
        startActivity(Intent(this, OperatorActivity::class.java))
    }

    private fun openAiWorkspace() {
        startActivity(Intent().setClassName(this, "com.Lia.assistant.MainActivity"))
    }

    private fun showAppLauncher() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.ROOT) }
        if (apps.isEmpty()) { status.text = "कोई launchable app नहीं मिला।"; return }
        val shown = apps.take(100)
        android.app.AlertDialog.Builder(this).setTitle("फोन का App Launcher")
            .setItems(shown.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, which ->
                val info = shown[which].activityInfo
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(info.packageName, info.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(launch); status.text = "${info.loadLabel(packageManager)} खोलने का अनुरोध भेजा।" }
                    .onFailure { status.text = "App launch नहीं हो पाया: ${it.message}" }
            }.show()
    }

    private fun launchApp(name: String) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(query, 0).filter {
            it.activityInfo.packageName != packageName &&
                it.loadLabel(packageManager).toString().contains(name, ignoreCase = true)
        }.take(8)
        when (matches.size) {
            0 -> status.text = "इस नाम का installed app नहीं मिला। App Launcher से चुनें।"
            1 -> {
                val info = matches.first().activityInfo
                runCatching {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(info.packageName, info.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    status.text = "${info.loadLabel(packageManager)} launch request भेजा।"
                }.onFailure { status.text = "App launch failed: ${it.message}" }
            }
            else -> android.app.AlertDialog.Builder(this).setTitle("कौन सा app खोलना है?")
                .setItems(matches.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, which ->
                    val info = matches[which].activityInfo
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(info.packageName, info.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.show()
        }
    }

    private fun media(keyCode: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        runCatching { audio.dispatchMediaKeyEvent(down); audio.dispatchMediaKeyEvent(up) }
            .onSuccess { status.text = "Media command भेजा गया। Active media session उपलब्ध होना चाहिए।" }
            .onFailure { status.text = "Media control उपलब्ध नहीं: ${it.message}" }
    }

    private fun webSearch(query: String) {
        if (query.length !in 2..180) { status.text = "Search query 2-180 characters की होनी चाहिए।"; return }
        val url = android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query))
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)); status.text = "Browser में web search खोली गई।" }
            .onFailure { status.text = "Browser उपलब्ध नहीं है।" }
    }

    private fun readCurrentScreen() {
        lifecycleScope.launch {
            status.text = "Accessibility screenshot से हिंदी/English OCR चल रहा है…"
            val text = ShraddhaAccessibilityService.captureOcrText()
            status.text = text?.take(6000) ?: "Screen OCR उपलब्ध नहीं। Android 11+ और Shraddha Accessibility permission चालू करें। Sensitive text अपने आप छिपाया जाता है।"
        }
    }
}
