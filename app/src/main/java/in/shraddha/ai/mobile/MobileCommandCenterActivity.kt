package `in`.shraddha.ai.mobile

import androidx.appcompat.app.AppCompatActivity
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
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences

/**
 * Mobile counterpart of the Windows companion command centre.
 * Uses Android-supported intents, Accessibility and user-approved APIs; no root or hidden settings writes.
 */
class MobileCommandCenterActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var command: EditText
    private lateinit var assistantName: String
    private var speechLanguage = "hi-IN"
    private var torchEnabled = false
    private val cameraPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runCatching {
            torchEnabled = !torchEnabled
            status.text = MobileHardwareController.setTorch(this, torchEnabled)
        }.onFailure { status.text = "Flashlight control failed: ${it.message}" }
        else status.text = "Flashlight के लिए Camera permission नहीं मिली।"
    }
    private val voiceResultLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            command.setText(spoken)
            runCommand(spoken)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speechLanguage = getSharedPreferences("mobile_command_center", Context.MODE_PRIVATE)
            .getString("speech_language", "hi-IN") ?: "hi-IN"
        assistantName = NovaPreferences.getString(
            this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME
        ).trim().ifBlank { AssistantBrand.NAME }

        // One command surface: voice, text, and a single settings entry for pairing/permissions.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(22))
            setBackgroundColor(Color.rgb(7, 13, 24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = assistantName
            textSize = 25f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("⚙") { showCommandSettings() }.apply {
            contentDescription = "Settings: PC pairing, voice and permissions"
            textSize = 19f
            minWidth = dp(48)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(46))
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "आप बोलिए या लिखिए — बाकी Shraddha करेगी"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9BB7CE.toInt())
            setPadding(0, dp(8), 0, dp(26))
        })

        command = EditText(this).apply {
            hint = "Shraddha को कमांड लिखें…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF91A5BC.toInt())
            textSize = 16f
            minLines = 1
            maxLines = 3
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = panelBackground(0xFF101E31.toInt(), 0xFF31516D.toInt())
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    runCommand(text.toString())
                    true
                } else false
            }
        }
        root.addView(command, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(action("🎙") { captureVoice() }.apply {
            contentDescription = "Voice command"
            textSize = 21f
        }, LinearLayout.LayoutParams(dp(58), dp(52)).apply { marginEnd = dp(8) })
        controls.addView(action("कमांड भेजें") { runCommand(command.text.toString()) },
            LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(controls)

        status = TextView(this).apply {
            text = "तैयार • हिंदी और English"
            textSize = 13f
            setTextColor(0xFFE4F2FF.toInt())
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = panelBackground(0xFF101E31.toInt(), 0xFF29445F.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        intent.getStringExtra("mobile_control_command")?.takeIf { it.isNotBlank() }?.let { pending ->
            command.post { runCommand(pending) }
        }
    }

    private fun showCommandSettings() {
        val choices = arrayOf(
            "PC Pairing और Remote Connection",
            "Voice Language",
            "AI Settings और Providers",
            "Android Permissions"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Shraddha Settings")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> openMainPage("devices")
                    1 -> chooseVoiceLanguage()
                    2 -> openAiWorkspace()
                    3 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("बंद करें", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panelBackground(fill: Int, stroke: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun action(label: String, run: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(0xFFEAF7FF.toInt())
        background = panelBackground(0xFF172A42.toInt(), 0xFF315878.toInt())
        elevation = dp(2).toFloat()
        setPadding(dp(12), dp(8), dp(12), dp(8))
        minHeight = dp(48)
        setOnClickListener { run() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun showPhoneLevelControls() {
        android.app.AlertDialog.Builder(this).setTitle("फोन कंट्रोल")
            .setItems(arrayOf("Brightness सेट करें", "Volume सेट करें")) { _, which ->
                askMobilePercent(if (which == 0) "brightness" else "volume")
            }.show()
    }

    private fun showMediaControls() {
        android.app.AlertDialog.Builder(this).setTitle("Media Control")
            .setItems(arrayOf("Play / Pause", "Next track", "Previous track", "Volume up", "Volume down", "Mute / unmute")) { _, which ->
                when (which) {
                    0 -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    1 -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
                    2 -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    else -> {
                        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val direction = when (which) {
                            3 -> AudioManager.ADJUST_RAISE
                            4 -> AudioManager.ADJUST_LOWER
                            else -> AudioManager.ADJUST_TOGGLE_MUTE
                        }
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                        status.text = "फोन का media volume command भेजा गया।"
                    }
                }
            }.show()
    }

    private fun chooseVoiceLanguage() {
        val labels = arrayOf("हिंदी (India)", "English (India)")
        android.app.AlertDialog.Builder(this).setTitle("Voice recognition language")
            .setSingleChoiceItems(labels, if (speechLanguage == "hi-IN") 0 else 1) { dialog, which ->
                speechLanguage = if (which == 0) "hi-IN" else "en-IN"
                getSharedPreferences("mobile_command_center", Context.MODE_PRIVATE).edit().putString("speech_language", speechLanguage).apply()
                status.text = if (which == 0) "Hindi voice recognition चुना गया।" else "English voice recognition चुना गया।"
                dialog.dismiss()
            }.setNegativeButton("Close", null).show()
    }

    private fun captureVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, if (speechLanguage == "hi-IN") "hi-IN,en-IN" else "en-IN,hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "$assistantName को mobile command बताइए")
        }
        runCatching { voiceResultLauncher.launch(intent) }
            .onFailure { status.text = "Speech recognition इस फोन पर उपलब्ध नहीं है।" }
    }

    private fun runCommand(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) { status.text = "पहले command बोलें या लिखें।"; return }
        val lower = text.lowercase(Locale.ROOT)
        when {
            lower.matches(Regex("^(?:set )?(?:phone )?volume (?:100|[0-9]{1,2})%?$")) ||
                lower.matches(Regex("^(?:फोन|मोबाइल) का वॉल्यूम (?:100|[0-9]{1,2})(?: प्रतिशत|%)?$")) -> {
                val percent = lower.filter { it.isDigit() }.toInt()
                runCatching { status.text = MobileHardwareController.setMediaVolume(this, percent) }
                    .onFailure { status.text = it.message ?: "Volume control failed." }
            }
            lower.matches(Regex("^(?:set )?(?:phone )?brightness (?:100|[0-9]{1,2})%?$")) ||
                lower.matches(Regex("^(?:फोन|मोबाइल) की ब्राइटनेस (?:100|[0-9]{1,2})(?: प्रतिशत|%)?$")) -> {
                val percent = lower.filter { it.isDigit() }.toInt()
                runCatching { status.text = MobileHardwareController.setBrightness(this, percent) }
                    .onFailure { status.text = it.message ?: "Brightness control failed." }
            }
            lower in listOf("flashlight on", "torch on", "टॉर्च चालू करो", "फ्लैशलाइट चालू करो") -> {
                torchEnabled = true
                runCatching { status.text = MobileHardwareController.setTorch(this, true) }
                    .onFailure { status.text = "Flashlight failed: ${it.message}" }
            }
            lower in listOf("flashlight off", "torch off", "टॉर्च बंद करो", "फ्लैशलाइट बंद करो") -> {
                torchEnabled = false
                runCatching { status.text = MobileHardwareController.setTorch(this, false) }
                    .onFailure { status.text = "Flashlight failed: ${it.message}" }
            }
            lower in listOf("mobile hardware status", "phone status", "फोन की जानकारी बताओ", "मोबाइल का स्टेटस बताओ") -> showMobileHardwareStatus()
            lower in listOf("phone controls", "फोन कंट्रोल खोलो", "brightness volume controls") -> showPhoneLevelControls()
            lower in listOf("media controls", "मीडिया कंट्रोल खोलो") -> showMediaControls()
            lower in listOf("toggle flashlight", "टॉर्च टॉगल करो") -> togglePhoneTorch()
            lower in listOf("connectivity controls", "कनेक्टिविटी कंट्रोल खोलो") -> showConnectivityControls()
            lower in listOf("clipboard tools", "clipboard text tools", "क्लिपबोर्ड टूल खोलो") -> openClipboardTools()
            lower in listOf("app launcher", "फोन के ऐप दिखाओ", "apps list") -> showAppLauncher()
            lower in listOf("wifi settings", "वाईफाई सेटिंग खोलो") -> openMobileSetting("wifi")
            lower in listOf("bluetooth settings", "ब्लूटूथ सेटिंग खोलो") -> openMobileSetting("bluetooth")
            lower in listOf("internet settings", "इंटरनेट सेटिंग खोलो") -> openMobileSetting("internet")
            lower in listOf("location settings", "लोकेशन सेटिंग खोलो") -> openMobileSetting("location")
            lower in listOf("battery settings", "बैटरी सेटिंग खोलो") -> openMobileSetting("battery")
            lower in listOf("display settings", "डिस्प्ले सेटिंग खोलो", "auto rotate settings", "ऑटो रोटेट सेटिंग खोलो") -> openMobileSetting("display")
            lower in listOf("do not disturb settings", "dnd settings", "डू नॉट डिस्टर्ब सेटिंग खोलो") -> openMobileSetting("dnd")
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

    private fun askMobilePercent(kind: String) {
        val isBrightness = kind == "brightness"
        val dialog = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "0–100"
            setText(if (isBrightness) "50" else "50")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (isBrightness) "Phone brightness %" else "Phone volume %")
            .setView(dialog)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val value = dialog.text.toString().toIntOrNull()
                if (value == null || value !in 0..100) {
                    status.text = "कृपया 0 से 100 के बीच संख्या दें।"
                } else runCatching {
                    status.text = if (isBrightness) MobileHardwareController.setBrightness(this, value)
                                  else MobileHardwareController.setMediaVolume(this, value)
                }.onFailure { status.text = it.message ?: "Control failed." }
            }.show()
    }

    private fun togglePhoneTorch() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            return
        }
        torchEnabled = !torchEnabled
        runCatching { status.text = MobileHardwareController.setTorch(this, torchEnabled) }
            .onFailure { torchEnabled = false; status.text = "Flashlight control failed: ${it.message}" }
    }

    private fun showMobileHardwareStatus() {
        runCatching {
            val data = MobileHardwareController.status(this)
            status.text = data.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }.onFailure { status.text = "Mobile hardware status unavailable: ${it.message}" }
    }

    private fun openMobileSetting(name: String) {
        runCatching {
            startActivity(MobileHardwareController.settingsIntent(name))
            status.text = "${name.replaceFirstChar { it.uppercase() }} settings Android system में खोली गई।"
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            status.text = "इस device पर direct settings panel उपलब्ध नहीं; main Settings खोली।"
        }
    }

    private fun showConnectivityControls() {
        val labels = arrayOf("Wi-Fi", "Bluetooth", "Internet / SIM", "NFC")
        val keys = arrayOf("wifi", "bluetooth", "internet", "nfc")
        android.app.AlertDialog.Builder(this).setTitle("Connectivity controls")
            .setItems(labels) { _, which -> openMobileSetting(keys[which]) }.show()
    }

    private fun openMainPage(page: String) {
        startActivity(Intent(this, `in`.shraddha.ai.MainActivity::class.java).putExtra("initial_page", page))
    }

    private fun openClipboardTools() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val existing = if (clipboard.hasPrimaryClip()) clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty() else ""
        val editor = EditText(this).apply {
            setText(existing)
            hint = "यहाँ text paste करें या पहले clipboard से पढ़ें"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8BAFB8.toInt())
            minLines = 5
            maxLines = 12
            gravity = android.view.Gravity.TOP
            setPadding(16, 14, 16, 14)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF102A36.toInt()); cornerRadius = 18f; setStroke(1, 0xFF2D8490.toInt())
            }
        }
        val choices = arrayOf("Trim spaces", "UPPERCASE", "lowercase", "Collapse whitespace", "Remove blank lines")
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Clipboard AI • Local text tools")
            .setMessage("Text केवल इस फोन पर process होगा। कोई clipboard history save नहीं होगी।")
            .setView(editor)
            .setNeutralButton("Read Clipboard", null)
            .setItems(choices) { _, which ->
                val source = editor.text.toString()
                if (source.isBlank()) { status.text = "पहले text paste या enter करें।"; return@setItems }
                val operation = MobileTextTransform.Operation.values()[which]
                val transformed = MobileTextTransform.apply(source, operation)
                android.app.AlertDialog.Builder(this).setTitle("Transformed text • Review")
                    .setMessage(transformed.take(3500))
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Copy to Clipboard") { _, _ ->
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(assistantName + " transformed text", transformed))
                        status.text = "Transformed text clipboard में copy किया।"
                    }.show()
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editor.setText(if (clipboard.hasPrimaryClip()) clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty() else "")
            }
        }
        dialog.show()
    }

    private fun openOperator() {
        startActivity(Intent(this, OperatorActivity::class.java).putExtra("mobile_voice_command", ""))
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
