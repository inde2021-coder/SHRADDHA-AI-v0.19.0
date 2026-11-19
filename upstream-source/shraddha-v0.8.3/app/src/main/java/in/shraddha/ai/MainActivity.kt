package `in`.shraddha.ai

import android.Manifest
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService
import `in`.shraddha.ai.brain.SecureMemoryVault
import `in`.shraddha.ai.brain.OfflineCommandEngine
import `in`.shraddha.ai.brain.LocalKnowledgeBase
import `in`.shraddha.ai.link.DeviceLinkManager
import `in`.shraddha.ai.overlay.ShraddhaOverlayService
import `in`.shraddha.ai.screen.ScreenShareService
import `in`.shraddha.ai.vision.RemoteCameraService
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var pageRoot: LinearLayout
    private lateinit var shell: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var pageScroll: ScrollView
    private var currentPage: String = "home"
    private lateinit var relayUrl: EditText
    private lateinit var deviceName: EditText
    private lateinit var pairingCode: EditText
    private lateinit var targetDevice: EditText
    private lateinit var peerPicker: Spinner
    private val peerIds = mutableListOf<String>()
    private val peerNames = mutableMapOf<String, String>()
    private val peerOnline = mutableMapOf<String, Boolean>()
    private lateinit var remoteConsent: Switch
    private lateinit var mainOnlyMode: Switch
    private var isSessionOwner = false
    private lateinit var livePreview: ImageView
    private lateinit var cameraPreview: ImageView
    private lateinit var remoteText: EditText
    private lateinit var remoteLabel: EditText
    private lateinit var remoteAppName: EditText
    private lateinit var gatewayToken: EditText
    private lateinit var visionPrompt: EditText
    private lateinit var visionProvider: Spinner
    private lateinit var visionResult: TextView
    @Volatile private var lastScreenJpeg: ByteArray? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastPeerId: String? = null
    private var livePreviewSourceId: String? = null
    private val speechRequest = 41
    private val screenShareRequest = 42
    private val dataFileRequest = 43
    private val dataPermissionSwitches = linkedMapOf<String, Switch>()
    private var pendingShareTarget: String? = null
    private var pendingShareCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAudioPermission()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }

        pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
            setBackgroundColor(0xFF080C18.toInt())
        }
        pageScroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(pageRoot) }
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080C18.toInt())
        }
        bottomNav = buildBottomNavigation()
        shell.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(68)))
        setContentView(shell)
        status = TextView(this).apply {
            text = "Ready • Shraddha AI तैयार है। अपनी जरूरत का एक workspace खोलें।"
            textSize = 13f; setTextColor(0xFFE4E9F5.toInt()); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xFF111B2D.toInt(), 16)
        }
        showHome()
        installLinkCallbacks()
    }

    private fun showHome() {
        currentPage = "home"
        updateBottomNavigation()
        pageRoot.removeAllViews()
        val hero = MaterialCardView(this).apply {
            radius = dp(28).toFloat(); cardElevation = dp(10).toFloat(); strokeWidth = dp(1); strokeColor = 0xFF51418C.toInt()
            setCardBackgroundColor(0xFF171B38.toInt())
        }
        val heroBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(25), dp(22), dp(23)) }
        hero.addView(heroBody)
        heroBody.addView(TextView(this).apply { text = "✦  SHRADDHA AI"; textSize = 12f; letterSpacing = .2f; setTextColor(0xFFBFAEFF.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) })
        heroBody.addView(TextView(this).apply { text = "Your personal AI agent"; textSize = 27f; setTextColor(-1); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, dp(12), 0, dp(6)) })
        heroBody.addView(TextView(this).apply { text = "एक काम चुनें। हर सिस्टम अपनी अलग स्क्रीन में खुलेगा।"; textSize = 13f; setTextColor(0xFFD0D6E8.toInt()) })
        pageRoot.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val orbPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(2), dp(12), dp(8))
        }
        orbPanel.addView(ShraddhaOrbView(this), LinearLayout.LayoutParams(dp(176), dp(176)))
        orbPanel.addView(TextView(this).apply {
            text = "MIC OFF  •  TAP ORB TO SPEAK"
            textSize = 10f; letterSpacing = .12f; setTextColor(0xFF8FA8D8.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        })
        orbPanel.setOnClickListener { startVoiceInput() }
        pageRoot.addView(orbPanel, LinearLayout.LayoutParams(-1, -2))
        pageRoot.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        section(pageRoot, "YOUR WORKSPACES", "सभी modules अलग-अलग स्क्रीन में खुलेंगे") {
            addView(workspaceTile("✦  AI Assistant", "Voice chat, AI providers और memory") { showPage("assistant") })
            addView(workspaceTile("◈  Expert Hub", "Engineering, medical education, agriculture, law, repair और creator") { showPage("expert") })
            addView(workspaceTile("⌘  Device Control", "Paired phones, remote screen और app control") { showPage("devices") })
            addView(workspaceTile("▤  Data Sharing", "Selected categories और approval-based file sharing") { showPage("data") })
            addView(workspaceTile("⚙  AI Lab", "Task planner, multi-model council, diagnostics और offline tools") { showPage("lab") })
            addView(workspaceTile("◉  App Permissions", "Accessibility, floating orb और Shraddha keyboard") { showPage("permissions") })
        }
        section(pageRoot, "QUICK ACCESS", "मुख्य voice action") {
            addView(actionButton("🎙  Talk to Shraddha", "Hindi / English voice command") { startVoiceInput() })
        }
    }

    private fun workspaceTile(title: String, subtitle: String, action: () -> Unit): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(0xFF18243A.toInt(), 18); isClickable = true; isFocusable = true; setOnClickListener { action() }
        }
        tile.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(-1); setTypeface(null, android.graphics.Typeface.BOLD) })
        tile.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(0xFF9EABC2.toInt()); setPadding(0, dp(5), 0, 0) })
        tile.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        return tile
    }

    private fun showPage(page: String) {
        currentPage = page
        pageRoot.removeAllViews()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(14)) }
        top.addView(Button(this).apply { text = "‹  Home"; isAllCaps = false; setTextColor(-1); backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF283454.toInt()); setOnClickListener { showHome() } })
        top.addView(TextView(this).apply { text = when(page) { "assistant" -> "AI ASSISTANT"; "expert" -> "EXPERT HUB"; "devices" -> "DEVICE CONTROL"; "data" -> "DATA SHARING"; "lab" -> "AI LAB"; else -> "PERMISSIONS" }; textSize = 13f; setTextColor(0xFFBFAEFF.toInt()); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(14), 0, 0, 0) })
        pageRoot.addView(top)
        updateBottomNavigation()
        if (page == "assistant") {
            val assistantHero = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(2), dp(8), dp(14))
            }
            assistantHero.addView(ShraddhaOrbView(this), LinearLayout.LayoutParams(dp(188), dp(188)))
            assistantHero.addView(TextView(this).apply {
                text = "SHRADDHA  /  PERSONAL AI"
                textSize = 11f; letterSpacing = .14f; gravity = android.view.Gravity.CENTER
                setTextColor(0xFFBFAEFF.toInt()); setTypeface(null, android.graphics.Typeface.BOLD)
            })
            assistantHero.addView(actionButton("🎙  Speak to Shraddha", "Hindi / English • आपके बोलने पर ही voice recognition शुरू होगा") { startVoiceInput() })
            pageRoot.addView(assistantHero)
        }
        pageRoot.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        when (page) {
            "assistant" -> {
                if (!::relayUrl.isInitialized) relayUrl = edit("Cloud Relay URL", "https://your-domain.example")
                if (!::gatewayToken.isInitialized) gatewayToken = edit("Optional AI gateway token", "GATEWAY_TOKEN if configured")
                addAiBrainSection(pageRoot)
            }
            "expert" -> {
                if (!::relayUrl.isInitialized) relayUrl = edit("Cloud Relay URL", "https://your-domain.example")
                if (!::gatewayToken.isInitialized) gatewayToken = edit("Optional AI gateway token", "GATEWAY_TOKEN if configured")
                addCompleteExpertHubSection(pageRoot)
            }
            "devices" -> addDeviceLinkSection(pageRoot)
            "lab" -> {
                if (!::relayUrl.isInitialized) relayUrl = edit("Cloud Relay URL", "https://your-domain.example")
                if (!::gatewayToken.isInitialized) gatewayToken = edit("Optional AI gateway token", "GATEWAY_TOKEN if configured")
                addAdvancedAiSection(pageRoot)
            }
            "data" -> {
                if (!::targetDevice.isInitialized) targetDevice = edit("Target device ID", "Select a paired phone first")
                addDataHubSection(pageRoot)
            }
            "permissions" -> section(pageRoot, "APP CONTROL SETUP", "Android permissions को अपनी इच्छा से चालू करें") {
                addView(actionButton("Enable Accessibility App Control", "Visible UI actions और approved gestures") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
                addView(actionButton("Enable Floating AI Orb", "दूसरे ऐप्स के ऊपर Shraddha shortcut") {
                    if (!Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    else { startService(Intent(this@MainActivity, ShraddhaOverlayService::class.java)); status.text = "Floating Orb requested." }
                })
                addView(actionButton("Enable Shraddha Keyboard", "Voice typing के लिए keyboard settings") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
                addView(TextView(this@MainActivity).apply { text = "Android secure screens, banking/OTP/password screens और कुछ protected apps को control या capture नहीं होने देता।"; textSize = 12f; setTextColor(0xFF9EABC2.toInt()); setPadding(0, dp(8), 0, 0) })
            }
        }
    }

    private fun buildBottomNavigation(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF10182A.toInt())
                cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dp(1), 0xFF273653.toInt())
            }
            listOf(
                Triple("⌂", "Home", "home"),
                Triple("✦", "Assistant", "assistant"),
                Triple("⌘", "Devices", "devices"),
                Triple("◈", "Experts", "expert")
            ).forEach { item ->
                val tab = LinearLayout(this@MainActivity).apply {
                    tag = item.third
                    orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                    isClickable = true; isFocusable = true; setPadding(dp(4), dp(4), dp(4), dp(4))
                    addView(TextView(this@MainActivity).apply {
                        text = item.first; textSize = 19f; gravity = android.view.Gravity.CENTER
                        setTextColor(0xFFBFAEFF.toInt())
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = item.second; textSize = 10f; gravity = android.view.Gravity.CENTER
                        setTextColor(0xFF9EABC2.toInt())
                    })
                    setOnClickListener { if (item.third == "home") showHome() else showPage(item.third) }
                }
                addView(tab, LayoutParams(0, -1, 1f))
            }
        }
    }

    private fun updateBottomNavigation() {
        // Navigation remains fixed while each workspace replaces the scroll content.
        bottomNav.visibility = View.VISIBLE
        val activePage = if (currentPage in setOf("home", "assistant", "devices", "expert")) currentPage else "home"
        for (i in 0 until bottomNav.childCount) {
            val tab = bottomNav.getChildAt(i) as? LinearLayout ?: continue
            val selected = tab.tag == activePage
            tab.background = if (selected) rounded(0xFF202B49.toInt(), 14) else null
            for (j in 0 until tab.childCount) {
                (tab.getChildAt(j) as? TextView)?.setTextColor(if (selected) 0xFFE3D9FF.toInt() else 0xFF9EABC2.toInt())
            }
        }
    }

    @Deprecated("Deprecated in Android API; retained for compatibility")
    override fun onBackPressed() {
        if (currentPage != "home") showHome() else super.onBackPressed()
    }

    private fun addAiBrainSection(root: LinearLayout) {
        section(root, "AI BRAIN & MEMORY", "Gemini, OpenAI, Claude, Groq और Perplexity के लिए सुरक्षित server-side API keys") {
            addView(relayUrl)
            addView(gatewayToken)
            val provider = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("gemini", "openai", "anthropic", "groq", "perplexity"))
            }
            addView(provider)
            val prompt = edit("Ask Shraddha", "हिंदी या English में अपना सवाल लिखें").apply { isSingleLine = false; minLines = 3 }
            addView(prompt)
            val answer = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 14f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF111B2D.toInt(), 14) }
            addView(answer)
            addView(actionButton("Ask Selected AI Provider", "API key backend environment में configure करें") {
                val url = relayUrl.text.toString().trim()
                if (!validRelay(url) || prompt.text.isNullOrBlank()) { status.text = "पहले relay URL और सवाल भरें।"; return@actionButton }
                answer.text = "AI से जवाब लिया जा रहा है…"
                DeviceLinkManager.askChat(url, gatewayToken.text.toString(), provider.selectedItem.toString(), prompt.text.toString()) { result -> runOnUiThread {
                    answer.text = result.getOrElse { "AI request failed: ${it.message}" }
                } }
            })
            val memory = EditText(this@MainActivity).apply {
                hint = "अपनी चुनी हुई AI memory / preferences यहाँ लिखें"; setHintTextColor(0xFF8291AA.toInt()); setTextColor(-1); textSize = 14f
                minLines = 3; setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(0xFF0B1220.toInt(), 14)
                setText(getSharedPreferences("shraddha_local_memory", MODE_PRIVATE).getString("notes", ""))
            }
            addView(memory)
            addView(actionButton("Save Local AI Memory", "यह नोट केवल इसी फोन पर रहेगा; दूसरे डिवाइस पर auto-sync नहीं होगा") {
                getSharedPreferences("shraddha_local_memory", MODE_PRIVATE).edit().putString("notes", memory.text.toString().take(8000)).apply()
                status.text = "Local AI memory इस फोन पर save हुई।"
            })
        }
    }

    private fun addAdvancedAiSection(root: LinearLayout) {
        section(root, "ADVANCED AI LAB", "AI Agent Planner, multi-model council और safe action review") {
            addView(relayUrl)
            addView(gatewayToken)
            val plannerProvider = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("gemini", "openai", "anthropic", "groq", "perplexity"))
            }
            addView(TextView(this@MainActivity).apply { text = "AI Task Planner"; setTextColor(0xFFBFAEFF.toInt()); textSize = 16f; setPadding(0, dp(6), 0, dp(6)) })
            addView(plannerProvider)
            val goal = edit("Task goal", "उदाहरण: दूसरे फोन पर Chrome खोलकर मौसम वेबसाइट दिखाओ").apply { isSingleLine = false; minLines = 3 }
            addView(goal)
            val planResult = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 13f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF111B2D.toInt(), 14) }
            addView(planResult)
            addView(actionButton("Generate Safe AI Plan", "यह केवल चरण सुझाएगा; कोई फोन Action अपने-आप execute नहीं होगा") {
                val url = relayUrl.text.toString().trim(); val task = goal.text.toString().trim()
                if (!validRelay(url) || task.isBlank()) { status.text = "पहले HTTPS relay URL और task goal भरें।"; return@actionButton }
                planResult.text = "AI सुरक्षित Task Plan बना रहा है…"
                DeviceLinkManager.planTask(url, gatewayToken.text.toString(), plannerProvider.selectedItem.toString(), task) { result -> runOnUiThread {
                    planResult.text = result.getOrElse { "Planner error: ${it.message}" }
                } }
            })
            addView(TextView(this@MainActivity).apply { text = "Multi-Model Council • एक सवाल को 2-3 AI providers से compare करें"; setTextColor(0xFFBFAEFF.toInt()); textSize = 15f; setPadding(0, dp(14), 0, dp(6)) })
            val councilPrompt = edit("Compare question", "किसी निर्णय या समस्या पर अलग-अलग AI राय पूछें").apply { isSingleLine = false; minLines = 2 }
            addView(councilPrompt)
            val councilProviders = CheckBox(this@MainActivity).apply { text = "Gemini"; isChecked = true; setTextColor(-1) }
            val councilOpenAi = CheckBox(this@MainActivity).apply { text = "OpenAI"; isChecked = true; setTextColor(-1) }
            val councilClaude = CheckBox(this@MainActivity).apply { text = "Claude"; setTextColor(-1) }
            addView(councilProviders); addView(councilOpenAi); addView(councilClaude)
            val councilResult = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 13f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF111B2D.toInt(), 14) }
            addView(councilResult)
            addView(actionButton("Compare AI Answers", "कम-से-कम दो configured providers चुनें") {
                val selected = buildList { if (councilProviders.isChecked) add("gemini"); if (councilOpenAi.isChecked) add("openai"); if (councilClaude.isChecked) add("anthropic") }
                val url = relayUrl.text.toString().trim(); val question = councilPrompt.text.toString().trim()
                if (!validRelay(url) || question.isBlank() || selected.size < 2) { status.text = "HTTPS relay, सवाल और कम-से-कम दो providers चुनें।"; return@actionButton }
                councilResult.text = "चुने हुए AI Providers से जवाब लिए जा रहे हैं…"
                DeviceLinkManager.compareProviders(url, gatewayToken.text.toString(), selected, question) { result -> runOnUiThread {
                    councilResult.text = result.getOrElse { "Compare error: ${it.message}" }
                } }
            })
            addView(TextView(this@MainActivity).apply { text = "AI PROVIDER STATUS & KNOWLEDGE TOOLS"; setTextColor(0xFFBFAEFF.toInt()); textSize = 15f; setPadding(0, dp(16), 0, dp(6)) })
            val providerStatus = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 13f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF111B2D.toInt(), 14) }
            addView(providerStatus)
            addView(actionButton("Check Configured AI Providers", "API keys server पर ही रहेंगी; केवल configured status दिखेगा") {
                val url = relayUrl.text.toString().trim()
                if (!validRelay(url)) { status.text = "पहले HTTPS relay URL भरें।"; return@actionButton }
                providerStatus.text = "Checking providers…"
                DeviceLinkManager.providerStatus(url, gatewayToken.text.toString()) { result -> runOnUiThread { providerStatus.text = result.getOrElse { "Provider check failed: ${it.message}" } } }
            })
            val summarizeInput = edit("Summarize notes / document text", "यहाँ चुने हुए text को paste करें").apply { isSingleLine = false; minLines = 3 }
            addView(summarizeInput)
            val summarizeResult = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 13f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF111B2D.toInt(), 14) }
            addView(summarizeResult)
            addView(actionButton("AI Summarize Text", "Facts, open questions और action items अलग करेगा") {
                val url = relayUrl.text.toString().trim(); val content = summarizeInput.text.toString().trim()
                if (!validRelay(url) || content.isBlank()) { status.text = "HTTPS relay और text दोनों भरें।"; return@actionButton }
                summarizeResult.text = "Summarizing…"
                DeviceLinkManager.summarize(url, gatewayToken.text.toString(), plannerProvider.selectedItem.toString(), content) { result -> runOnUiThread { summarizeResult.text = result.getOrElse { "Summarize failed: ${it.message}" } } }
            })

            addView(TextView(this@MainActivity).apply { text = "ON-DEVICE INTELLIGENCE • इंटरनेट के बिना भी"; setTextColor(0xFFBFAEFF.toInt()); textSize = 15f; setPadding(0, dp(16), 0, dp(6)) })
            val offlineInput = edit("Offline voice/text command", "जैसे: WhatsApp खोलो / स्क्रीन पढ़ो / टॉर्च चालू करो")
            addView(offlineInput)
            val offlineResult = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 13f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF111B2D.toInt(), 14) }
            addView(offlineResult)
            addView(actionButton("Recognize Offline Command", "स्थानीय bilingual intent engine • केवल proposal, action execute नहीं") {
                val proposal = OfflineCommandEngine.recognize(offlineInput.text.toString())
                offlineResult.text = proposal?.let { "Intent: ${it.intent}\n${it.summary}\nअनुमति/पुष्टि जरूरी: ${it.needsPermission}" } ?: "यह command offline grammar में नहीं मिली। AI provider से पूछने के लिए online chat इस्तेमाल करें।"
            })

            addView(TextView(this@MainActivity).apply { text = "ENCRYPTED PERSONAL MEMORY VAULT"; setTextColor(0xFFBFAEFF.toInt()); textSize = 15f; setPadding(0, dp(16), 0, dp(6)) })
            val vault = SecureMemoryVault(this@MainActivity)
            val secureMemory = edit("Private memory", "निजी पसंद, project facts या चुने हुए notes लिखें").apply { isSingleLine = false; minLines = 3 }
            secureMemory.setText(vault.load().getOrElse { "" })
            addView(secureMemory)
            addView(actionButton("Save Encrypted Memory", "AES-GCM encryption • Android Keystore key • इस फोन पर ही") {
                val result = vault.save(secureMemory.text.toString())
                status.text = if (result.isSuccess) "Encrypted memory सुरक्षित रूप से save हुई।" else "Vault save failed: ${result.exceptionOrNull()?.message}"
            })
            addView(actionButton("Clear Encrypted Memory", "स्थानीय vault में saved note हटाएँ") {
                if (vault.clear()) { secureMemory.setText(""); status.text = "Encrypted local memory cleared." } else status.text = "Vault clear नहीं हो पाया।"
            })

            addView(TextView(this@MainActivity).apply { text = "LOCAL KNOWLEDGE RETRIEVAL (RAG-LITE)"; setTextColor(0xFFBFAEFF.toInt()); textSize = 15f; setPadding(0, dp(16), 0, dp(6)) })
            val knowledgeQuery = edit("Search personal notes", "अपने saved notes में कोई सवाल खोजें")
            addView(knowledgeQuery)
            val knowledgeResult = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 13f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF111B2D.toInt(), 14) }
            addView(knowledgeResult)
            addView(actionButton("Search Local Knowledge", "केवल इसी फोन की encrypted memory पढ़ी जाती है") {
                val notes = vault.load().getOrElse { "" }
                val hits = LocalKnowledgeBase.search(notes, knowledgeQuery.text.toString())
                knowledgeResult.text = if (hits.isEmpty()) "कोई matching note नहीं मिला। पहले encrypted memory में notes save करें।" else hits.joinToString("\n\n") { "• ${it.text}" }
            })

            addView(TextView(this@MainActivity).apply {
                text = "Advanced safety: Planner के सुझाव प्रस्ताव मात्र हैं। ऐप खोलना, कैमरा/स्क्रीन देखना, डेटा भेजना, संदेश भेजना या कोई संवेदनशील/बदलाव वाला Action करने से पहले अलग अनुमति और पुष्टि आवश्यक है। AI के उत्तर को भरोसेमंद निर्देश न मानें।"
                textSize = 12f; setTextColor(0xFF9EABC2.toInt()); setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun addExpertStudioSection(root: LinearLayout) {
        section(root, "08  •  EXPERT COURSE, REPAIR & CREATOR STUDIO", "Electronics, engineering, medical education, agriculture, law और content creation के लिए multi-domain AI workspace") {
            val domains = listOf(
                "electronics" to "Electronics Engineering", "electrical" to "Electrical Engineering",
                "mechanical" to "Mechanical Engineering", "medical" to "Medical & Health Education",
                "agriculture" to "Agriculture & Farming", "civil" to "Civil Engineering",
                "law" to "Law & Legal Studies", "repair" to "Repair & Troubleshooting",
                "creator" to "Creator Studio", "general" to "General Science & Technology"
            )
            val modes = listOf(
                "learn" to "Structured Course / Learn", "solve" to "Problem Solver", "design" to "Project / Design Creator",
                "diagnose" to "Repair / Fault Diagnosis", "exam" to "Exam Preparation", "create" to "Content Creator"
            )
            addView(TextView(this@MainActivity).apply { text = "Choose your expert field"; setTextColor(0xFFE4E9F5.toInt()); textSize = 14f; setPadding(0, dp(6), 0, dp(4)) })
            val domainPicker = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, domains.map { it.second })
            }
            addView(domainPicker)
            addView(TextView(this@MainActivity).apply { text = "Choose what Shraddha should do"; setTextColor(0xFFE4E9F5.toInt()); textSize = 14f; setPadding(0, dp(10), 0, dp(4)) })
            val modePicker = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes.map { it.second })
            }
            addView(modePicker)
            val expertProvider = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("gemini", "openai", "anthropic", "groq", "perplexity"))
            }
            addView(TextView(this@MainActivity).apply { text = "AI provider"; setTextColor(0xFFE4E9F5.toInt()); textSize = 14f; setPadding(0, dp(10), 0, dp(4)) })
            addView(expertProvider)
            val request = edit("Describe your question / project / repair issue", "उदाहरण: 1 HP मोटर की basic working समझाओ; गेहूँ की फसल में पीले पत्तों के संभावित कारण बताओ; 60 सेकंड का science video script बनाओ").apply { isSingleLine = false; minLines = 4 }
            addView(request)
            val result = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 13f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF111B2D.toInt(), 14); text = "आपका उत्तर, course outline, repair decision-tree या creator draft यहाँ दिखाई देगा।" }
            addView(result)
            addView(actionButton("Ask Domain Expert / Create Solution", "AI answer • structured learning • safe troubleshooting • creator drafts") {
                val url = relayUrl.text.toString().trim()
                val question = request.text.toString().trim()
                if (!validRelay(url) || question.isBlank()) { status.text = "पहले HTTPS relay URL और अपना सवाल भरें।"; return@actionButton }
                val domain = domains[domainPicker.selectedItemPosition].first
                val mode = modes[modePicker.selectedItemPosition].first
                result.text = "${domains[domainPicker.selectedItemPosition].second} विशेषज्ञ से उत्तर तैयार हो रहा है…"
                DeviceLinkManager.expertAssist(url, gatewayToken.text.toString(), expertProvider.selectedItem.toString(), domain, mode, question) { response -> runOnUiThread {
                    result.text = response.getOrElse { "Expert Studio error: ${it.message}" }.let { raw ->
                        runCatching { org.json.JSONObject(raw).optString("answer").ifBlank { raw } }.getOrDefault(raw)
                    }
                } }
            })
            addView(TextView(this@MainActivity).apply {
                text = "महत्वपूर्ण: Medical भाग शिक्षा के लिए है, diagnosis/prescription नहीं। Electrical, mechanical, repair और civil काम में जोखिम होने पर विशेषज्ञ/लाइसेंसधारी व्यक्ति से जाँच कराएँ। Law उत्तर सामान्य जानकारी है; सही jurisdiction और वकील की सलाह जरूरी हो सकती है। AI-generated design को निर्माण/मरम्मत से पहले verify करें।"
                textSize = 12f; setTextColor(0xFFFFC98A.toInt()); setPadding(0, dp(10), 0, dp(4))
            })
        }
    }

    private fun addCompleteExpertHubSection(root: LinearLayout) {
        section(root, "09  •  COMPLETE EXPERT HUB", "AI University • CAD/3D • Circuit/PCB • Civil • Medical Education • Agriculture • Law • Repair • Creator • Research • Software") {
            val workspaces = listOf(
                "university" to "AI University & Courses", "electronics" to "Electronics Engineering", "electrical" to "Electrical Engineering",
                "mechanical" to "Mechanical Engineering", "cad3d" to "CAD / 3D Design", "circuit_pcb" to "Circuit & PCB Studio",
                "civil_building" to "Civil & Building Plan", "medical_education" to "Medical Education", "agriculture" to "Agriculture AI",
                "law" to "Law & Legal Studies", "repair" to "Universal Repair", "creator" to "Creator Studio", "software" to "Software & App Builder",
                "research" to "Deep Research Lab", "data_science" to "Data Science", "robotics" to "Robotics", "drone" to "Drone & Mapping",
                "gis" to "GIS & Spatial Analysis", "smart_home" to "Smart Home / IoT", "business" to "Business & Finance"
            )
            val tasks = listOf("teach" to "Teach / Explain", "course" to "Build Full Course", "quiz" to "Quiz & Exam", "solve" to "Solve Problem", "design" to "Design / Create", "diagnose" to "Diagnose / Troubleshoot", "research" to "Research / Compare", "create" to "Create Deliverable", "estimate" to "Estimate / Calculate", "review" to "Review / Audit")
            val workspacePicker = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, workspaces.map { it.second }) }
            val taskPicker = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tasks.map { it.second }) }
            val providerPicker = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("gemini", "openai", "anthropic", "groq", "perplexity")) }
            addView(relayUrl)
            addView(gatewayToken)
            addView(TextView(this@MainActivity).apply { text = "Select expert department"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f })
            addView(workspacePicker)
            addView(TextView(this@MainActivity).apply { text = "Select work mode"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(8), 0, 0) })
            addView(taskPicker)
            addView(TextView(this@MainActivity).apply { text = "AI provider"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(8), 0, 0) })
            addView(providerPicker)
            val request = edit("Project / course / problem", "जैसे 6 महीने का Electronics course बनाओ, या 2BHK का conceptual floor plan तैयार करो").apply { isSingleLine = false; minLines = 4; maxLines = 8 }
            addView(request)
            val output = TextView(this@MainActivity).apply { text = "Generated work यहाँ दिखाई देगा। इसे local project library में save या Android share sheet से export कर सकते हैं।"; setTextColor(0xFFE4E9F5.toInt()); textSize = 13f; setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(0xFF111B2D.toInt(), 16); minHeight = dp(110) }
            addView(output)
            val library = getSharedPreferences("shraddha_expert_library", MODE_PRIVATE)
            var lastTitle = "Shraddha AI Project"
            var lastOutput = ""
            addView(actionButton("✦ Generate with Expert Hub", "Real server-side AI request • selected provider key required") {
                val base = relayUrl.text.toString().trim(); val text = request.text.toString().trim()
                if (!validRelay(base) || text.isBlank()) { status.text = "पहले HTTPS relay URL और अपना काम लिखें।"; return@actionButton }
                output.text = "${workspaces[workspacePicker.selectedItemPosition].second} तैयार कर रहा है…"
                lastTitle = "${workspaces[workspacePicker.selectedItemPosition].second} — ${tasks[taskPicker.selectedItemPosition].second}"
                DeviceLinkManager.studioWork(base, gatewayToken.text.toString(), providerPicker.selectedItem.toString(), workspaces[workspacePicker.selectedItemPosition].first, tasks[taskPicker.selectedItemPosition].first, text) { response -> runOnUiThread {
                    val raw = response.getOrElse { "Request failed: ${it.message}" }
                    lastOutput = runCatching { JSONObject(raw).optString("answer").ifBlank { raw } }.getOrDefault(raw)
                    output.text = lastOutput
                    status.text = if (response.isSuccess) "Expert Hub response ready. Review before using safety-critical output." else "Expert Hub request failed. Check backend/provider setup."
                } }
            })
            addView(actionButton("Save to My Project Library", "Locally saved on this phone; no automatic cloud sync") {
                if (lastOutput.isBlank()) { status.text = "पहले कोई AI output generate करें।"; return@actionButton }
                val entries = library.getStringSet("items", emptySet())?.toMutableList() ?: mutableListOf()
                val record = JSONObject().put("title", lastTitle).put("content", lastOutput.take(12000)).put("savedAt", System.currentTimeMillis()).toString()
                entries.add(0, record); library.edit().putStringSet("items", entries.take(12).toSet()).apply()
                status.text = "Project इस फोन की local library में save हुआ।"
            })
            addView(actionButton("Open Saved Projects", "पिछले 20 locally saved outputs देखें") {
                val entries = library.getStringSet("items", emptySet())?.toList().orEmpty().sortedByDescending { runCatching { JSONObject(it).optLong("savedAt") }.getOrDefault(0L) }
                if (entries.isEmpty()) { Toast.makeText(this@MainActivity, "अभी कोई saved project नहीं है।", Toast.LENGTH_SHORT).show(); return@actionButton }
                val titles = entries.map { runCatching { JSONObject(it).optString("title", "Saved project") }.getOrDefault("Saved project") }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("My Project Library").setItems(titles.toTypedArray()) { _, index ->
                    val item = JSONObject(entries[index]); lastTitle = item.optString("title"); lastOutput = item.optString("content"); output.text = lastOutput
                }.setNegativeButton("Close", null).show()
            })
            addView(actionButton("Share Current Work", "Android share sheet • text/document compatible apps") {
                if (lastOutput.isBlank()) { status.text = "पहले output generate या saved project open करें।"; return@actionButton }
                val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, lastTitle); putExtra(Intent.EXTRA_TEXT, "$lastTitle\n\n$lastOutput") }
                startActivity(Intent.createChooser(share, "Export Shraddha AI work"))
            })
            addView(TextView(this@MainActivity).apply {
                text = "यह workspace वास्तविक AI backend से काम करता है। Saved Projects अभी इसी फोन पर local हैं। CAD/PCB/building outputs conceptual या text-based हैं; automatic professional CAD rendering, PCB fabrication approval, medical diagnosis, legal representation और structural certification शामिल नहीं हैं। Live research citations के लिए अलग verified browsing integration चाहिए।"
                textSize = 12f; setTextColor(0xFFFFC98A.toInt()); setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun addDataHubSection(root: LinearLayout) {
        section(root, "CUSTOM AI DATA HUB", "हर category की sharing अलग-अलग अनुमति से; default में सब बंद") {
            addView(targetDevice)
            val categories = listOf(
                "contacts" to "Contacts", "media" to "Photos & Videos", "documents" to "Documents",
                "messages" to "Messages (restricted data may not be available)", "calendar" to "Calendar & Tasks",
                "location" to "Location", "device_status" to "Device Status", "ai_memory" to "AI Memory"
            )
            categories.forEach { (key, label) ->
                val toggle = Switch(this@MainActivity).apply {
                    text = label; setTextColor(-1)
                    isChecked = getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean(key, false)
                    setOnCheckedChangeListener { _, checked ->
                        getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).edit().putBoolean(key, checked).apply()
                        publishDataPermissions()
                    }
                }
                dataPermissionSwitches[key] = toggle
                addView(toggle)
            }
            addView(actionButton("Publish My Sharing Permissions", "दूसरे paired devices को मेरी चुनी अनुमति बताएँ") { publishDataPermissions() })
            val categoryPicker = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categories.map { it.first })
            }
            addView(categoryPicker)
            addView(actionButton("Request Data from Selected Phone", "Target phone पर approval dialog आएगा") {
                val target = targetDevice.text.toString().trim()
                val category = categoryPicker.selectedItem?.toString().orEmpty()
                if (target.isBlank()) { status.text = "पहले paired target device चुनें।"; return@actionButton }
                val sent = DeviceLinkManager.requestDataShare(target, category, "User requested $category")
                status.text = if (sent) "$category sharing request भेजी गई। Target की अनुमति जरूरी है।" else "Relay connected नहीं है।"
            })
            addView(actionButton("Send a Selected Document / Photo", "पहले target से category permission approve करवाएँ") {
                val target = targetDevice.text.toString().trim()
                val category = categoryPicker.selectedItem?.toString().orEmpty()
                if (target.isBlank()) { status.text = "पहले target device चुनें।"; return@actionButton }
                pendingShareTarget = target; pendingShareCategory = category
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = if (category == "media") "image/*" else "*/*" }
                startActivityForResult(intent, dataFileRequest)
            })
            addView(TextView(this@MainActivity).apply {
                text = "सीमा: इस शुरुआती Data Hub में चुनी हुई छोटी फाइलों का one-time transfer है। Automatic background sync, contacts/calendar import, SMS access और cloud backup अलग implementation व Android permissions मांगते हैं। बड़े files के लिए secure HTTPS upload storage जोड़ा जाएगा।"
                textSize = 12f; setTextColor(0xFF9EABC2.toInt()); setPadding(0, dp(8), 0, dp(4))
            })
        }
    }

    private fun startCameraShare(facing: String) {
        val target = targetDevice.text.toString().trim()
        if (target.isBlank()) { status.text = "Remote camera के लिए पहले target device चुनें।"; return }
        getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit().putString("pending_camera_facing", facing).putString("pending_camera_target", target).apply()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 44)
        } else {
            val intent = Intent(this, RemoteCameraService::class.java).putExtra(RemoteCameraService.EXTRA_FACING, facing).putExtra(RemoteCameraService.EXTRA_TARGET, target)
            ContextCompat.startForegroundService(this, intent)
            status.text = "${if (facing == "front") "Front" else "Rear"} camera sharing started. Android camera indicator दिखाई देगा।"
        }
    }

    private fun publishDataPermissions() {
        val json = JSONObject()
        dataPermissionSwitches.forEach { (key, toggle) -> json.put(key, toggle.isChecked) }
        val sent = DeviceLinkManager.updateSharePermissions(json)
        status.text = if (sent) "आपकी custom data permissions paired devices पर publish हुईं।" else "पहले relay से connect करें; permissions इस फोन पर सुरक्षित save हैं।"
    }

    private fun handleIncomingShareRequest(msg: JSONObject) {
        val from = msg.optString("fromDeviceId")
        val category = msg.optString("category")
        val allowed = getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean(category, false)
        runOnUiThread {
            if (!allowed) {
                DeviceLinkManager.respondDataShare(from, category, false)
                Toast.makeText(this, "$category sharing इस फोन पर बंद है।", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Data sharing request")
                .setMessage("दूसरा paired device '$category' category का डेटा माँग रहा है। क्या आप 5 मिनट के लिए अनुमति देते हैं?")
                .setNegativeButton("Deny") { _, _ -> DeviceLinkManager.respondDataShare(from, category, false) }
                .setPositiveButton("Allow once") { _, _ -> DeviceLinkManager.respondDataShare(from, category, true, 5) }
                .setCancelable(true).show()
        }
    }

    private fun addDeviceLinkSection(root: LinearLayout) {
        section(root, "01  •  WORLDWIDE DEVICE LINK", "दुनिया में कहीं से भी इंटरनेट के जरिए pair करें — दोनों phones पर Shraddha AI और आपका HTTPS relay जरूरी है") {
            relayUrl = edit("Cloud Relay URL", "https://your-domain.example")
            deviceName = edit("This phone name", "e.g. Main Phone")
            addView(relayUrl); addView(deviceName)
            addView(actionButton("Create Main Phone Session", "नया secure pairing code बनाएँ") {
                val url = relayUrl.text.toString().trim()
                if (!validRelay(url)) { status.text = "पहले अपना deployed HTTPS relay URL डालें।"; return@actionButton }
                DeviceLinkManager.createSession(url, deviceName.text.toString().ifBlank { "Shraddha Phone" }) { result -> runOnUiThread {
                    result.onSuccess { isSessionOwner = true; mainOnlyMode.isEnabled = true; pairingCode.setText(it.code); status.text = "Pairing code: ${it.code}\nदूसरे फोन में यह code डालें। Code 5 मिनट में expire होगा।" }
                        .onFailure { status.text = "Pairing error: ${it.message}" }
                } }
            })
            pairingCode = edit("Pairing code", "6-digit code दूसरे फोन से")
            addView(pairingCode)
            addView(actionButton("Join Another Phone", "इस फोन को मौजूदा device group में जोड़ें") {
                val url = relayUrl.text.toString().trim(); val code = pairingCode.text.toString().trim()
                if (!validRelay(url) || code.length != 6) { status.text = "HTTPS relay URL और 6-digit pairing code जरूरी हैं।"; return@actionButton }
                DeviceLinkManager.joinSession(url, deviceName.text.toString().ifBlank { "Shraddha Phone" }, code) { result -> runOnUiThread {
                    result.onSuccess { isSessionOwner = false; mainOnlyMode.isEnabled = false; status.text = "Device relay से जुड़ गया। अब paired device चुनें।" }
                        .onFailure { status.text = "Join failed: ${it.message}" }
                } }
            })
        }

        section(root, "02  •  DEVICE PERMISSIONS & MODE", "हर receiving phone अपने remote-control access को खुद मंजूरी देगा") {
            targetDevice = edit("Target device ID", "Selected peer ID")
            addView(targetDevice)
            peerPicker = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Connected devices will appear here"))
                setPadding(0, dp(4), 0, dp(8))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        peerIds.getOrNull(position)?.let { targetDevice.setText(it) }
                    }
                }
            }
            addView(peerPicker)
            remoteConsent = Switch(this@MainActivity).apply {
                text = "इस फोन को paired device से control करने दें"
                setTextColor(-1)
                isChecked = getSharedPreferences("shraddha_device_link", MODE_PRIVATE).getBoolean("remote_control_consent", false)
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, checked -> getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit().putBoolean("remote_control_consent", checked).apply() }
            }
            addView(remoteConsent)
            mainOnlyMode = Switch(this@MainActivity).apply {
                text = "Main phone only mode (OFF = Mutual control)"
                setTextColor(-1); isChecked = false; isEnabled = false; setPadding(0, dp(4), 0, dp(8))
                setOnCheckedChangeListener { _, checked -> if (isSessionOwner) { val sent = DeviceLinkManager.setControlMode(checked); status.text = if (sent) (if (checked) "Main phone only mode active" else "Mutual control mode active") else "Relay not connected" } }
            }
            addView(mainOnlyMode)
            addView(actionButton("Share This Phone Screen", "Android system confirmation हर बार जरूरी है") { requestScreenShare() })
            addView(actionButton("Stop Screen Sharing", "Live screen stream तुरंत बंद करें") { stopService(Intent(this@MainActivity, ScreenShareService::class.java)); status.text = "Screen sharing stopped on this phone." })
        }

        section(root, "03  •  REMOTE APP CONTROL", "चुने हुए paired phone पर अनुमत gestures और data entry") {
            remoteText = edit("Text to enter", "केवल focused, visible, non-password field")
            addView(remoteText)
            addView(actionButton("Type Text on Paired Phone", "Focused editable field में लिखें") { sendRemote(JSONObject().put("kind", "type").put("text", remoteText.text.toString())) })
            remoteLabel = edit("Visible button / label", "उदाहरण: Submit, Save, Next")
            addView(remoteLabel)
            addView(actionButton("Tap Visible Text", "Accessibility में मिले button या label को चुनें") {
                sendRemote(JSONObject().put("kind", "click_text").put("text", remoteLabel.text.toString()))
            })
            remoteAppName = edit("Open app by name", "उदाहरण: WhatsApp, Chrome, Settings")
            addView(remoteAppName)
            addView(actionButton("Open App on Paired Phone", "Installed launcher app को नाम से खोलें") {
                sendRemote(JSONObject().put("kind", "open_app").put("appName", remoteAppName.text.toString()))
            })
            addView(actionButton("Remote Scroll Down", "अगली visible list / page पर जाएँ") { sendRemote(JSONObject().put("kind", "scroll")) })
            addView(actionButton("Send Remote Home", "फोन की Home screen पर जाएँ") { sendRemote(JSONObject().put("kind", "home")) })
            addView(actionButton("Send Remote Back", "पिछली screen पर जाएँ") { sendRemote(JSONObject().put("kind", "back")) })
            addView(actionButton("Disconnect This Phone", "इस device का live connection बंद करें") { DeviceLinkManager.disconnect(); status.text = "This phone disconnected." })
        }

        section(root, "04  •  VISUAL LENS – LIVE SCREEN & REMOTE CAMERA", "स्क्रीन और कैमरा अलग-अलग, स्पष्ट अनुमति से साझा करें") {
            addView(actionButton("Start Rear Camera Share", "Selected paired device को camera दिखाएँ") { startCameraShare("back") })
            addView(actionButton("Start Front Camera Share", "Selected paired device को selfie camera दिखाएँ") { startCameraShare("front") })
            addView(actionButton("Stop This Phone Camera Share", "Remote camera stream तुरंत बंद करें") { stopService(Intent(this@MainActivity, RemoteCameraService::class.java)); status.text = "Camera sharing stopped." })
            cameraPreview = ImageView(this@MainActivity).apply { setBackgroundColor(0xFF101827.toInt()); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true; contentDescription = "Remote camera preview" }
            cameraPreview.layoutParams = LinearLayout.LayoutParams(-1, dp(210))
            addView(TextView(this@MainActivity).apply { text = "REMOTE CAMERA PREVIEW"; setTextColor(0xFFB9C3D6.toInt()); textSize = 12f; setPadding(0, dp(8), 0, dp(5)) })
            addView(cameraPreview)
            addView(TextView(this@MainActivity).apply { text = "LIVE REMOTE SCREEN  •  Preview पर tap/swipe करने से उसी source phone पर action भेजा जाएगा।"; setTextColor(0xFFB9C3D6.toInt()); textSize = 12f; setPadding(0, 0, 0, dp(8)) })
            livePreview = ImageView(this@MainActivity).apply {
                setBackgroundColor(0xFF101827.toInt()); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
                contentDescription = "Live paired phone screen"
            }
            livePreview.layoutParams = LinearLayout.LayoutParams(-1, dp(250))
            livePreview.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { touchStartX = event.x; touchStartY = event.y; true }
                    MotionEvent.ACTION_UP -> {
                        val x1 = (touchStartX / livePreview.width).coerceIn(0f, 1f); val y1 = (touchStartY / livePreview.height).coerceIn(0f, 1f)
                        val x2 = (event.x / livePreview.width).coerceIn(0f, 1f); val y2 = (event.y / livePreview.height).coerceIn(0f, 1f)
                        val moved = kotlin.math.abs(event.x - touchStartX) + kotlin.math.abs(event.y - touchStartY) > dp(12)
                        val source = livePreviewSourceId
                        if (source == null) status.text = "अभी किसी paired phone की live screen नहीं मिली।"
                        else if (moved) sendRemoteTo(source, JSONObject().put("kind", "swipe").put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2))
                        else sendRemoteTo(source, JSONObject().put("kind", "tap").put("x", x2).put("y", y2))
                        true
                    }
                    else -> true
                }
            }
            addView(livePreview)
            gatewayToken = edit("Optional AI gateway token", "GATEWAY_TOKEN if configured")
            gatewayToken.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            addView(gatewayToken)
            visionProvider = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("gemini", "openai", "anthropic")) }
            addView(visionProvider)
            visionPrompt = edit("Ask AI about this screen", "Visible form fields, labels and safe next step")
            visionPrompt.setText("इस स्क्रीन पर दिख रहे ऐप और फॉर्म को समझो। दिखाई दे रहे फील्ड के नाम पढ़ो और बताओ कि कौन-सी जानकारी कहाँ भरनी है। बिना मेरी पुष्टि कोई कार्रवाई मत करना।")
            visionPrompt.isSingleLine = false; visionPrompt.minLines = 3
            addView(visionPrompt)
            addView(actionButton("Ask Shraddha to Understand Screen", "Latest received screenshot का AI analysis") {
                val frame = lastScreenJpeg
                if (frame == null) { status.text = "पहले दूसरे फोन पर Share This Phone Screen चालू करें।"; return@actionButton }
                val url = relayUrl.text.toString().trim()
                if (!validRelay(url)) { status.text = "HTTPS relay URL जरूरी है।"; return@actionButton }
                visionResult.text = "Shraddha स्क्रीन समझ रही है…"
                DeviceLinkManager.analyzeScreen(url, gatewayToken.text.toString(), visionProvider.selectedItem.toString(), visionPrompt.text.toString(), frame) { result -> runOnUiThread {
                    result.onSuccess { visionResult.text = it; status.text = "Remote screen analysis complete." }
                        .onFailure { visionResult.text = "Analysis error: ${it.message}" }
                } }
            })
            visionResult = TextView(this@MainActivity).apply { text = "AI screen analysis result यहाँ दिखेगा।"; setTextColor(0xFFE4E8F2.toInt()); textSize = 14f; setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(0xFF111B2D.toInt(), 16) }
            addView(visionResult)
            addView(TextView(this@MainActivity).apply {
                text = "Privacy: स्क्रीन फ्रेम आपके relay और चुने AI provider तक जाता है। बैंकिंग, OTP, password या निजी स्क्रीन साझा न करें। Android protected/secure windows capture या control नहीं होने दे सकता।"
                setTextColor(0xFF9EABC2.toInt()); textSize = 12f; setPadding(0, dp(10), 0, dp(4))
            })
        }
    }

    private fun section(root: LinearLayout, title: String, subtitle: String, content: LinearLayout.() -> Unit): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(22).toFloat(); cardElevation = dp(3).toFloat()
            strokeWidth = dp(1); strokeColor = 0xFF26344D.toInt()
            setCardBackgroundColor(0xFF111827.toInt())
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12)) }
        card.addView(body)
        body.addView(TextView(this).apply { text = title; textSize = 14f; letterSpacing = .06f; setTextColor(0xFFBDA8FF.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) })
        body.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(0xFF93A2BA.toInt()); setPadding(0, dp(5), 0, dp(14)) })
        body.content()
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return body
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), 0xFF293750.toInt())
    }

    private fun installLinkCallbacks() {
        DeviceLinkManager.onStatus = { message -> runOnUiThread { status.text = message } }
        DeviceLinkManager.onPeerMessage = { msg ->
            if (msg.optString("type") == "connected") {
                val peers = msg.optJSONArray("devices")
                if (peers != null) {
                    val ids = mutableListOf<String>()
                    val labels = mutableListOf<String>()
                    for (i in 0 until peers.length()) {
                        val peer = peers.optJSONObject(i) ?: continue
                        val id = peer.optString("id")
                        if (id.isNotBlank()) { ids.add(id); labels.add("${peer.optString("name", "Device")} • ${if (peer.optBoolean("online")) "Online" else "Offline"}") }
                    }
                    runOnUiThread {
                        peerIds.clear(); peerIds.addAll(ids)
                        peerNames.clear(); peerOnline.clear()
                        for (i in 0 until ids.size) {
                            val peer = peers?.optJSONObject(i) ?: continue
                            peerNames[ids[i]] = peer.optString("name", "Device")
                            peerOnline[ids[i]] = peer.optBoolean("online")
                        }
                        if (ids.isNotEmpty()) lastPeerId = ids.first()
                        peerPicker.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("No paired devices") })
                        if (targetDevice.text.isNullOrBlank()) targetDevice.setText(lastPeerId.orEmpty())
                    }
                }
                runOnUiThread { mainOnlyMode.isChecked = msg.optString("controlMode") == "master"; publishDataPermissions() }
            } else if (msg.optString("type") == "peer_status") {
                val peerId = msg.optString("deviceId")
                val peerName = msg.optString("name", "Device")
                val online = msg.optBoolean("online")
                runOnUiThread {
                    if (!peerIds.contains(peerId) && peerId.isNotBlank()) peerIds.add(peerId)
                    if (peerId.isNotBlank()) { peerNames[peerId] = peerName; peerOnline[peerId] = online }
                    val labels = peerIds.map { id -> "${peerNames[id] ?: "Device"} • ${if (peerOnline[id] == true) "Online" else "Offline"}" }
                    peerPicker.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("No paired devices") })
                    if (targetDevice.text.isNullOrBlank() && peerId.isNotBlank()) targetDevice.setText(peerId)
                    status.text = "$peerName: ${if (online) "online" else "offline"}"
                }
            } else if (msg.optString("type") == "control_mode") {
                runOnUiThread { mainOnlyMode.isChecked = msg.optString("mode") == "master"; status.text = if (mainOnlyMode.isChecked) "Main phone only control mode is active" else "Mutual phone control mode is active" }
            } else if (msg.optString("type") == "data_share_request") {
                handleIncomingShareRequest(msg)
            } else if (msg.optString("type") == "data_share_result") {
                val approved = msg.optBoolean("approved")
                runOnUiThread {
                    status.text = if (approved) "Permission approved for ${msg.optString("category")}. अब चुनी file भेज सकते हैं।" else "Data sharing request अस्वीकार हुई।"
                    if (approved) Toast.makeText(this, "Permission मिली। अब Send a Selected Document / Photo चुनें।", Toast.LENGTH_LONG).show()
                }
            } else if (msg.optString("type") == "data_share_payload") {
                runCatching {
                    val bytes = android.util.Base64.decode(msg.getString("payload"), android.util.Base64.DEFAULT)
                    val folder = java.io.File(filesDir, "shared").apply { mkdirs() }
                    val safeName = msg.optString("fileName", "shared_file").replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
                    java.io.File(folder, safeName).writeBytes(bytes)
                    runOnUiThread { status.text = "Shared file received: $safeName (${bytes.size} bytes). App storage में सुरक्षित है।" }
                }.onFailure { runOnUiThread { status.text = "Shared file receive failed: ${it.message}" } }
            }
        }
        val appContext = applicationContext
        DeviceLinkManager.onRemoteAction = { _, action ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val allowed = appContext.getSharedPreferences("shraddha_device_link", MODE_PRIVATE).getBoolean("remote_control_consent", false)
                if (allowed) ShraddhaAccessibilityService.applyRemoteAction(action)
            }
        }
        DeviceLinkManager.onScreenFrame = { sourceId, bytes ->
            lastScreenJpeg = bytes
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) runOnUiThread {
                livePreviewSourceId = sourceId
                livePreview.setImageBitmap(bitmap)
                status.text = "Live screen received from device $sourceId"
            }
        }
        DeviceLinkManager.onCameraFrame = { sourceId, bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) runOnUiThread {
                cameraPreview.setImageBitmap(bitmap)
                status.text = "Remote camera frame received from device $sourceId"
            }
        }
    }

    private fun sendRemote(action: JSONObject) {
        val target = targetDevice.text.toString().trim().ifBlank { lastPeerId.orEmpty() }
        sendRemoteTo(target, action)
    }

    private fun sendRemoteTo(target: String, action: JSONObject) {
        // The receiving phone independently decides whether to accept remote control.
        if (target.isBlank()) { status.text = "कोई paired target device online नहीं मिला।"; return }
        val sent = DeviceLinkManager.sendAction(target, action)
        status.text = if (sent) "Remote action sent to $target" else "Relay disconnected; pair both phones first."
    }

    private fun requestScreenShare() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), screenShareRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 44) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                val prefs = getSharedPreferences("shraddha_device_link", MODE_PRIVATE)
                val facing = prefs.getString("pending_camera_facing", "back") ?: "back"
                val target = prefs.getString("pending_camera_target", "").orEmpty()
                ContextCompat.startForegroundService(this, Intent(this, RemoteCameraService::class.java).putExtra(RemoteCameraService.EXTRA_FACING, facing).putExtra(RemoteCameraService.EXTRA_TARGET, target))
                status.text = "Camera permission मिली; $facing camera share शुरू किया गया।"
            } else status.text = "Camera permission नहीं मिली; Remote Camera शुरू नहीं हुआ।"
        }
    }

    @Deprecated("Compatibility with starter project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == screenShareRequest) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val service = Intent(this, ScreenShareService::class.java).putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode).putExtra(ScreenShareService.EXTRA_DATA, data)
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, service) else startService(service)
                status.text = "Screen sharing started. Android will show its active-sharing notification."
            } else status.text = "Screen sharing cancelled."
            return
        }
        if (requestCode == dataFileRequest && resultCode == Activity.RESULT_OK && data?.data != null) {
            val target = pendingShareTarget.orEmpty(); val category = pendingShareCategory.orEmpty()
            try {
                val uri = data.data!!
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
                if (bytes.isEmpty() || bytes.size > 650_000) { status.text = "फाइल खाली है या 650 KB से बड़ी है। अभी छोटे transfer समर्थित हैं।"; return }
                val name = queryDisplayName(uri) ?: "shared_file"
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val payload = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val sent = DeviceLinkManager.sendSharedPayload(target, category, name, mime, payload)
                status.text = if (sent) "चुनी हुई file $target को भेजने का प्रयास किया गया। Receiver पर delivery status जाँचें।" else "File भेजने के लिए relay disconnected है या permission grant expire हो गया।"
            } catch (e: Exception) { status.text = "File share error: ${e.message}" }
            return
        }
        if (requestCode == speechRequest && resultCode == Activity.RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            status.text = "आपने कहा: $spoken"
            val payload = VoiceCommandParser.toAction(spoken)
            if (payload != null) {
                val done = ShraddhaAccessibilityService.fillFocusedField(payload)
                status.append(if (done) "\nFocused field updated." else "\nपहले किसी ऐप में text field चुनें और App Control चालू करें।")
            } else status.append("\nSupported simple commands: ‘लिखो ...’ या ‘मिटाओ’।")
        }
    }

    override fun onDestroy() {
        DeviceLinkManager.onStatus = null
        DeviceLinkManager.onPeerMessage = null
        DeviceLinkManager.onScreenFrame = null
        DeviceLinkManager.onCameraFrame = null
        super.onDestroy()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun validRelay(url: String) = url.startsWith("https://") && !url.contains("your-domain")
    private fun edit(label: String, hint: String) = EditText(this).apply {
        this.hint = "$label  •  $hint"; setHintTextColor(0xFF8291AA.toInt()); setTextColor(-1); textSize = 14f
        setSingleLine(true); setPadding(dp(14), dp(13), dp(14), dp(13)); background = rounded(0xFF0B1220.toInt(), 14)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }
    private fun actionButton(label: String, hint: String, action: () -> Unit) = Button(this).apply {
        text = "$label\n$hint"; isAllCaps = false; textSize = 13f; gravity = android.view.Gravity.CENTER_VERTICAL
        setTextColor(-1); backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF283454.toInt())
        minHeight = dp(56); setPadding(dp(16), dp(10), dp(16), dp(10)); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
    }

    private fun requestAudioPermission() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10) }
    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestAudioPermission(); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN"); putExtra(RecognizerIntent.EXTRA_PROMPT, "Shraddha AI को अपना आदेश बोलें"); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3) }
        try { startActivityForResult(intent, speechRequest) } catch (_: Exception) { Toast.makeText(this, "इस डिवाइस पर Speech Recognition उपलब्ध नहीं है।", Toast.LENGTH_LONG).show() }
    }
}

object VoiceCommandParser {
    fun toAction(input: String): String? {
        val text = input.trim(); val prefixes = listOf("लिखो", "टाइप करो", "भर दो", "type", "write")
        val prefix = prefixes.firstOrNull { text.startsWith(it, ignoreCase = true) } ?: return null
        return text.substring(prefix.length).trim().takeIf { it.isNotBlank() }
    }
}


/** Animated decorative agent orb. It does not imply that the microphone is active. */
private class ShraddhaOrbView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3600L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        animator.start()
        contentDescription = "Shraddha AI animated orb. Tap to start voice input."
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val base = minOf(width, height) * .34f
        val pulse = 1f + .045f * kotlin.math.sin(phase * Math.PI * 2).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = base * .035f
        paint.color = 0x665B8CFF
        canvas.drawCircle(cx, cy, base * (1.28f + .035f * pulse), paint)
        paint.strokeWidth = base * .018f
        paint.color = 0x668B5CF6
        canvas.drawCircle(cx, cy, base * 1.42f, paint)
        paint.style = Paint.Style.FILL
        val radius = base * pulse
        paint.shader = RadialGradient(cx - radius * .28f, cy - radius * .32f, radius * 1.35f,
            intArrayOf(0xFFE8E4FF.toInt(), 0xFF9B83FF.toInt(), 0xFF4C52B8.toInt(), 0xFF111A3B.toInt()),
            floatArrayOf(0f, .28f, .68f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        paint.color = 0x66FFFFFF.toInt()
        canvas.drawCircle(cx - radius * .28f, cy - radius * .32f, radius * .18f, paint)
        paint.color = 0x3387E7FF
        canvas.drawCircle(cx + radius * .34f, cy + radius * .25f, radius * .22f, paint)
    }
    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
