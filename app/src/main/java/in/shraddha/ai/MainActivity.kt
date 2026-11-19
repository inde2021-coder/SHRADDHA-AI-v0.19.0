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
import `in`.shraddha.ai.brain.LocalOfflineLlm
import `in`.shraddha.ai.brain.LocalKnowledgeBase
import `in`.shraddha.ai.link.DeviceLinkManager
import `in`.shraddha.ai.overlay.ShraddhaOverlayService
import `in`.shraddha.ai.screen.ScreenShareService
import `in`.shraddha.ai.vision.RemoteCameraService
import org.json.JSONObject
import org.json.JSONArray
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import `in`.shraddha.ai.biometric.LocalBiometricModelEngine
import `in`.shraddha.ai.biometric.BiometricConsentVault
import `in`.shraddha.ai.biometric.BiometricCaptureActivity
import com.Lia.assistant.data.VoiceGatewaySettings

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
    @Volatile private var lastCameraJpeg: ByteArray? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastPeerId: String? = null
    private var livePreviewSourceId: String? = null
    private val speechRequest = 41
    private val screenShareRequest = 42
    private val dataFileRequest = 43
    private val backupFileRequest = 45
    private val backupImportRequest = 46
    private val offlineModelImportRequest = 47
    private val faceBiometricModelRequest = 48
    private val speakerBiometricModelRequest = 49
    private val faceCaptureRequest = 52
    private val speakerCaptureRequest = 53
    private val biometricEngine by lazy { LocalBiometricModelEngine() }
    private val dataPermissionSwitches = linkedMapOf<String, Switch>()
    private var pendingShareTarget: String? = null
    private var pendingShareCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF070B14.toInt()
        window.navigationBarColor = 0xFF0B1220.toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val startupPermissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) startupPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missingStartupPermissions = startupPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingStartupPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingStartupPermissions.toTypedArray(), 11)
        }

        pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
            setBackgroundColor(0xFF070B14.toInt())
        }
        pageScroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(pageRoot) }
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070B14.toInt())
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
        restoreBiometricModels()
        installLinkCallbacks()
    }

    private fun showHome() {
        currentPage = "home"
        updateBottomNavigation()
        pageRoot.removeAllViews()

        // Premium MYRA/Lia-inspired command cockpit. All live indicators are explicitly
        // labelled; the panel never fabricates activity when no agent is connected.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(14))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        brand.addView(TextView(this).apply {
            text = "S H R A D D H A   /   A I"
            textSize = 11f; letterSpacing = .12f; setTextColor(0xFF9F8BFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        brand.addView(TextView(this).apply {
            text = "Your intelligent universe"
            textSize = 22f; setTextColor(0xFFF4F6FF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, dp(4), 0, 0)
        })
        header.addView(brand)
        header.addView(TextView(this).apply {
            text = "●  LOCAL"
            textSize = 10f; letterSpacing = .08f; setTextColor(0xFF69E7C0.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(0xFF102A2A.toInt(), 30)
        })
        pageRoot.addView(header)

        val hero = MaterialCardView(this).apply {
            radius = dp(30).toFloat(); cardElevation = dp(12).toFloat()
            strokeWidth = dp(1); strokeColor = 0xFF6357B9.toInt()
            setCardBackgroundColor(0xFF11182C.toInt())
        }
        val heroBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1B2044.toInt(), 0xFF111B2B.toInt(), 0xFF0B1421.toInt())
            ).apply { cornerRadius = dp(30).toFloat() }
        }
        hero.addView(heroBody)
        val heroTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val intro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        intro.addView(TextView(this).apply {
            text = "PERSONAL AI • MULTI-AGENT"
            textSize = 9f; letterSpacing = .12f; setTextColor(0xFF9FBCFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        intro.addView(TextView(this).apply {
            text = "One mind.\nMany capabilities."
            textSize = 25f; setTextColor(-1); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(6))
        })
        intro.addView(TextView(this).apply {
            text = "Voice • Vision • Memory • Automation"
            textSize = 11f; setTextColor(0xFFB9C5DD.toInt())
        })
        heroTop.addView(intro)
        heroTop.addView(ShraddhaOrbView(this), LinearLayout.LayoutParams(dp(138), dp(138)))
        heroBody.addView(heroTop)
        heroBody.addView(actionButton("✦  START A CONVERSATION", "Hindi / English • Live voice engine") { startActivity(Intent(this@MainActivity, com.Lia.assistant.MainActivity::class.java)) })
        pageRoot.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(13) })

        val liveCard = section(pageRoot, "LIVE WORKSPACE", "वास्तविक screen/task activity यहाँ दिखाई जाएगी; connection न होने पर कोई नकली live status नहीं दिखेगा") {
            addView(TextView(this@MainActivity).apply {
                text = "◉  WAITING FOR AN ACTIVE TASK"
                textSize = 11f; letterSpacing = .06f; setTextColor(0xFF79DCCB.toInt())
                setPadding(dp(12), dp(13), dp(12), dp(13))
                background = rounded(0xFF0D2027.toInt(), 14)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Connect App Operator or a paired desktop/phone to stream verified actions, screen previews and task results."
                textSize = 12f; setTextColor(0xFF9EABC2.toInt()); setPadding(0, dp(10), 0, dp(2))
            })
            addView(actionButton("OPEN LIVE APP OPERATOR", "Observe → act → verify") {
                startActivity(Intent(this@MainActivity, `in`.shraddha.ai.operator.OperatorActivity::class.java))
            })
        }

        section(pageRoot, "COMMAND CENTER", "सभी systems एक unified dashboard से") {
            addView(workspaceTile("✦  AI BRAIN + LIA VOICE ENGINE", "Continuous Hindi/English voice • Live audio • Personality • Memory") { startActivity(Intent(this@MainActivity, com.Lia.assistant.MainActivity::class.java)) })
            addView(workspaceTile("◈  MYRA PC COMMAND CENTER", "Windows/Linux companion • MYRA Coder • Website Forge • PC automation") { showPage("devices") })
            addView(workspaceTile("◈  EXPERT STUDIO", "Engineering • Medical learning • Agriculture • Law • Creator") { showPage("expert") })
            addView(workspaceTile("⌘  DEVICE UNIVERSE", "Paired phones • PC companion • Remote screen") { showPage("devices") })
            addView(workspaceTile("▣  APP OPERATOR", "Visual UI control • Workflow • Verification") { startActivity(Intent(this@MainActivity, `in`.shraddha.ai.operator.OperatorActivity::class.java)) })
            addView(workspaceTile("◇  AI PROJECT LAB", "Agents • Code • Website • App • Automation") { showPage("lab") })
            
            addView(workspaceTile("◎  PEOPLE MEMORY", "Consent-based person profiles and recognition roadmap") { showPage("people") })
            addView(workspaceTile("☁  CLOUD & DATA", "Firebase sync • Secure memory • Sharing") { showPage("cloud") })
            addView(workspaceTile("⚙  CONTROL & PRIVACY", "Permissions • Security • Device access") { showPage("permissions") })
            addView(workspaceTile("✧  FUTURE INTELLIGENCE", "Advanced systems • workflow safety • capability status") { showPage("future") })
        }
        section(pageRoot, "SYSTEM STATUS", "यहाँ केवल app द्वारा ज्ञात वास्तविक स्थिति दिखाई जाएगी") {
            addView(TextView(this@MainActivity).apply {
                text = "ON-DEVICE CORE  •  READY\nCLOUD SYNC  •  CONFIGURATION REQUIRED\nPC COMPANION  •  PAIRING REQUIRED"
                textSize = 11f; setLineSpacing(dp(5).toFloat(), 1f); setTextColor(0xFFB8C6DF.toInt())
                background = rounded(0xFF0B1422.toInt(), 16); setPadding(dp(14), dp(14), dp(14), dp(14))
            })
        }
    }

    private fun workspaceTile(title: String, subtitle: String, action: () -> Unit): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(13), dp(11))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1C2945.toInt(), 0xFF101A2D.toInt())
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF35496F.toInt())
            }
            foreground = android.util.TypedValue().let { value ->
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                ContextCompat.getDrawable(context, value.resourceId)
            }
            elevation = dp(4).toFloat()
            minimumHeight = dp(78)
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $subtitle"
            setOnClickListener { action() }
        }
        val accent = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF9D7BFF.toInt(), 0xFF35C9DF.toInt())
            ).apply { cornerRadius = dp(4).toFloat() }
        }
        tile.addView(accent, LinearLayout.LayoutParams(dp(4), dp(42)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(8), dp(2))
        }
        copy.addView(TextView(this).apply {
            text = title
            textSize = 13.5f
            letterSpacing = .02f
            maxLines = 2
            setTextColor(0xFFF0F1FF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        copy.addView(TextView(this).apply {
            text = subtitle
            textSize = 10.5f
            maxLines = 2
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(0xFFAAB8D1.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        tile.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        tile.addView(TextView(this).apply {
            text = "›"
            textSize = 27f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFBCA9FF.toInt())
        }, LinearLayout.LayoutParams(dp(22), -1))
        tile.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        return tile
    }

    private fun showPage(page: String) {
        currentPage = page
        pageRoot.removeAllViews()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(14)) }
        top.addView(Button(this).apply { text = "‹  Home"; isAllCaps = false; setTextColor(-1); backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF283454.toInt()); setOnClickListener { showHome() } })
        top.addView(TextView(this).apply { text = when(page) { "assistant" -> "AI ASSISTANT"; "expert" -> "EXPERT HUB"; "devices" -> "DEVICE CONTROL"; "data" -> "DATA SHARING"; "lab" -> "AI LAB"; "kids" -> "KIDS UNIVERSE"; "people" -> "PERSON MEMORY"; "cloud" -> "SECURE CLOUD SYNC"; "future" -> "FUTURE INTELLIGENCE"; else -> "PERMISSIONS" }; textSize = 13f; setTextColor(0xFFBFAEFF.toInt()); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(14), 0, 0, 0) })
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
            assistantHero.addView(actionButton("🎙  Speak to Shraddha", "Live Hindi / English voice session • Tap to open") { startActivity(Intent(this@MainActivity, com.Lia.assistant.MainActivity::class.java)) })
            pageRoot.addView(assistantHero)
        }
        pageRoot.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        when (page) {
            "assistant" -> {
                if (!::relayUrl.isInitialized) relayUrl = edit("Cloud Relay URL", "https://your-domain.example").apply { setText(VoiceGatewaySettings.get(this@MainActivity).baseUrl) }
                if (!::gatewayToken.isInitialized) gatewayToken = edit("AI gateway token", "Saved securely in Settings").apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText(VoiceGatewaySettings.get(this@MainActivity).gatewayToken)
                }
                addAiBrainSection(pageRoot)
            }
            "expert" -> {
                if (!::relayUrl.isInitialized) relayUrl = edit("Cloud Relay URL", "https://your-domain.example").apply { setText(VoiceGatewaySettings.get(this@MainActivity).baseUrl) }
                if (!::gatewayToken.isInitialized) gatewayToken = edit("AI gateway token", "Saved securely in Settings").apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText(VoiceGatewaySettings.get(this@MainActivity).gatewayToken)
                }
                addCompleteExpertHubSection(pageRoot)
            }
            "devices" -> addDeviceLinkSection(pageRoot)
            "lab" -> {
                if (!::relayUrl.isInitialized) relayUrl = edit("Cloud Relay URL", "https://your-domain.example").apply { setText(VoiceGatewaySettings.get(this@MainActivity).baseUrl) }
                if (!::gatewayToken.isInitialized) gatewayToken = edit("AI gateway token", "Saved securely in Settings").apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText(VoiceGatewaySettings.get(this@MainActivity).gatewayToken)
                }
                addAdvancedAiSection(pageRoot)
            }
            "data" -> {
                if (!::targetDevice.isInitialized) targetDevice = edit("Target device ID", "Select a paired phone first")
                addDataHubSection(pageRoot)
            }
            "kids" -> addKidsUniverse(pageRoot)
            "people" -> addPersonMemory(pageRoot)
            "cloud" -> addFirebaseCloudSection(pageRoot)
            "future" -> addFutureIntelligence(pageRoot)
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
                addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
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
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gemini", "openai", "anthropic", "groq", "perplexity"))
            }
            addView(provider)
            val prompt = edit("Ask Shraddha", "हिंदी या English में अपना सवाल लिखें").apply { isSingleLine = false; minLines = 3 }
            addView(prompt)
            val answer = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 14f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF111B2D.toInt(), 14) }
            addView(answer)
            addView(actionButton("Ask AI • Auto Failover", "Auto चुनने पर configured providers क्रम से try होंगे; API key server पर ही रहेगी") {
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
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gemini", "openai", "anthropic", "groq", "perplexity"))
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
            val localModelPrompt = edit("Local AI prompt (no internet)", "Shraddha, मेरे सवाल का उत्तर दो…").apply { isSingleLine = false; minLines = 2 }
            addView(localModelPrompt)
            val localBackend = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gpu", "npu", "cpu"))
            }
            addView(TextView(this@MainActivity).apply { text = "HARDWARE BACKEND • AUTO tries NPU → GPU → CPU fallback"; setTextColor(0xFFB9C8E4.toInt()); textSize = 11f })
            addView(localBackend)
            addView(actionButton("SELECT ON-DEVICE LLM MODEL", "अपनी compatible .litertlm model file चुनें; model APK में शामिल नहीं है") {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream" }
                startActivityForResult(picker, offlineModelImportRequest)
            })
            addView(actionButton("CHECK DEVICE RAM & STORAGE", "Model load से पहले available RAM, low-memory mode, Java/native heap और storage जाँचें") {
                val model = java.io.File(filesDir, "offline-models/shraddha.litertlm")
                val check = LocalOfflineLlm.checkDevice(this@MainActivity, model.absolutePath)
                offlineResult.text = check.report
            })
            addView(actionButton("RUN LOCAL OFFLINE AI", "Local inference • NPU/GPU acceleration where supported • safe CPU fallback") {
                val model = java.io.File(filesDir, "offline-models/shraddha.litertlm")
                val prompt = localModelPrompt.text.toString().trim()
                if (!model.isFile || model.length() < 10_000_000L) { offlineResult.text = "पहले compatible .litertlm model import करें।" }
                else if (prompt.isBlank() || prompt.length > 6000) { offlineResult.text = "Prompt 1-6000 characters का होना चाहिए।" }
                else {
                    val check = LocalOfflineLlm.checkDevice(this@MainActivity, model.absolutePath)
                    offlineResult.text = check.report + "\n\nLocal model लोड हो रहा है…"
                    if (check.allowed) Thread {
                        val answer = runCatching { LocalOfflineLlm.generate(this@MainActivity, model.absolutePath, prompt, localBackend.selectedItem?.toString() ?: "auto") }
                        runOnUiThread { offlineResult.text = LocalOfflineLlm.lastRuntimeInfo + "\n\n" + answer.getOrElse { "Local inference failed: ${it.message}" } }
                    }.start()
                }
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
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gemini", "openai", "anthropic", "groq", "perplexity"))
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
            val providerPicker = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gemini", "openai", "anthropic", "groq", "perplexity")) }
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
                        if (!checked) {
                            when (key) {
                                "screen" -> { stopService(Intent(this@MainActivity, ScreenShareService::class.java)); lastScreenJpeg = null }
                                "camera" -> { stopService(Intent(this@MainActivity, RemoteCameraService::class.java)); lastCameraJpeg = null }
                            }
                        }
                        publishDataPermissions()
                    }
                }
                dataPermissionSwitches[key] = toggle
                addView(toggle)
            }
            addView(TextView(this@MainActivity).apply {
                text = "LIVE VISUAL SHARING • अलग-अलग स्पष्ट अनुमति"
                setTextColor(0xFFB9A9FF.toInt()); textSize = 13f; setPadding(0, dp(12), 0, dp(4))
            })
            listOf("screen" to "इस फोन की स्क्रीन साझा करने दें", "camera" to "इस फोन का कैमरा साझा करने दें").forEach { (key, label) ->
                val toggle = Switch(this@MainActivity).apply {
                    text = label; setTextColor(-1)
                    isChecked = getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean(key, false)
                    setOnCheckedChangeListener { _, checked ->
                        getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).edit().putBoolean(key, checked).apply()
                        if (!checked) {
                            when (key) {
                                "screen" -> stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                                "camera" -> stopService(Intent(this@MainActivity, RemoteCameraService::class.java))
                            }
                        }
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
        if (!getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean("camera", false)) {
            status.text = "पहले Data Hub में Camera sharing की अनुमति ON करें। इसे बंद करते ही कैमरा शेयरिंग रुक जाएगी।"
            return
        }
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
        json.put("remote_control", remoteConsent.isChecked)
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

    private fun handleIncomingControlRequest(msg: JSONObject) {
        val from = msg.optString("fromDeviceId")
        val requester = msg.optString("requesterName", "Paired device")
        val allowed = getSharedPreferences("shraddha_device_link", MODE_PRIVATE).getBoolean("remote_control_consent", false)
        runOnUiThread {
            if (!allowed) {
                DeviceLinkManager.respondRemoteControl(from, false)
                Toast.makeText(this, "Remote control इस फोन पर बंद है।", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Remote control permission")
                .setMessage("$requester इस फोन को अधिकतम 5 मिनट तक नियंत्रित करना चाहता है। केवल भरोसेमंद व्यक्ति को अनुमति दें।")
                .setNegativeButton("Deny") { _, _ -> DeviceLinkManager.respondRemoteControl(from, false) }
                .setPositiveButton("Allow for 5 minutes") { _, _ -> DeviceLinkManager.respondRemoteControl(from, true, 5) }
                .setOnCancelListener { DeviceLinkManager.respondRemoteControl(from, false) }
                .show()
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
                setOnCheckedChangeListener { _, checked ->
                    getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit().putBoolean("remote_control_consent", checked).apply()
                    if (DeviceLinkManager.sessionId != null) publishDataPermissions()
                }
            }
            addView(remoteConsent)
            mainOnlyMode = Switch(this@MainActivity).apply {
                text = "Main phone only mode (OFF = Mutual control)"
                setTextColor(-1); isChecked = false; isEnabled = false; setPadding(0, dp(4), 0, dp(8))
                setOnCheckedChangeListener { _, checked -> if (isSessionOwner) { val sent = DeviceLinkManager.setControlMode(checked); status.text = if (sent) (if (checked) "Main phone only mode active" else "Mutual control mode active") else "Relay not connected" } }
            }
            addView(mainOnlyMode)
            addView(actionButton("Share This Phone Screen", "Android system confirmation हर बार जरूरी है") { requestScreenShare() })
            addView(actionButton("Stop Screen Sharing", "Live screen stream तुरंत बंद करें") { stopService(Intent(this@MainActivity, ScreenShareService::class.java)); lastScreenJpeg = null; status.text = "Screen sharing stopped on this phone." })
        }

        section(root, "03  •  REMOTE APP CONTROL", "चुने हुए paired phone पर अनुमत gestures और data entry") {
            val handoffTitle = edit("Task handoff title", "उदाहरण: Prepare project report")
            val handoffDescription = edit("Task description", "दूसरे paired device को भेजने वाला काम")
            val handoffSteps = edit("Task steps (one per line)", "1. Review files\n2. Prepare summary\n3. Send result").apply {
                minLines = 3
                maxLines = 5
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            addView(handoffTitle); addView(handoffDescription); addView(handoffSteps)
            addView(actionButton("SEND TASK TO PAIRED DEVICE", "15-minute task offer • receiver must accept • never auto-executes") {
                val target = targetDevice.text.toString().trim()
                val title = handoffTitle.text.toString().trim()
                val description = handoffDescription.text.toString().trim()
                val steps = handoffSteps.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
                if (target.isBlank() || title.isBlank() || description.isBlank()) {
                    status.text = "Target device, task title और description जरूरी हैं।"
                } else {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Share task with paired device?")
                        .setMessage(title + "\n\n" + description + "\n\nयह केवल task plan भेजेगा; कोई app action अपने-आप execute नहीं होगा।")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Share") { _, _ ->
                            status.text = if (DeviceLinkManager.sendTaskHandoff(target, title, description, steps))
                                "Task offer भेज दी गई। Receiver की स्वीकृति का इंतजार है।"
                            else "Relay connected नहीं है या task भेजा नहीं जा सका।"
                        }.show()
                }
            })
            addView(actionButton("VIEW TASK INBOX", "दूसरे paired devices से स्वीकार किए गए task plans") {
                val tasks = runCatching { JSONArray(EncryptedFeatureStore(this@MainActivity).get("task_inbox").getOrDefault("[]")) }.getOrDefault(JSONArray())
                if (tasks.length() == 0) {
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle("Task Inbox").setMessage("अभी कोई task नहीं है।").setPositiveButton("OK", null).show()
                } else {
                    val labels = Array(tasks.length()) { i -> tasks.optJSONObject(i)?.optString("title", "Task") ?: "Task" }
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle("Task Inbox")
                        .setItems(labels) { _, index ->
                            val task = tasks.optJSONObject(index) ?: return@setItems
                            val stepArray = task.optJSONArray("steps")
                            val stepText = (0 until (stepArray?.length() ?: 0)).joinToString("\n") { n -> stepArray?.optString(n).orEmpty() }
                            android.app.AlertDialog.Builder(this@MainActivity).setTitle(task.optString("title"))
                                .setMessage(task.optString("description") + "\n\n" + stepText)
                                .setPositiveButton("OK", null).setNegativeButton("Remove") { _, _ ->
                                    val updated = JSONArray()
                                    for (i in 0 until tasks.length()) if (i != index) updated.put(tasks.optJSONObject(i))
                                    EncryptedFeatureStore(this@MainActivity).put("task_inbox", updated.toString())
                                }.show()
                        }.setNegativeButton("Close", null).show()
                }
            })
            remoteText = edit("Text to enter", "केवल focused, visible, non-password field")
            addView(remoteText)
            addView(actionButton("Request 5-Minute Remote Control", "Target phone पर हर session के लिए approval dialog आएगा") {
                val target = targetDevice.text.toString().trim().ifBlank { lastPeerId.orEmpty() }
                status.text = if (target.isBlank()) "पहले paired target चुनें।" else if (DeviceLinkManager.requestRemoteControl(target, 5)) "Control request भेजी गई। Target phone की मंजूरी का इंतजार है।" else "पहले relay से connect करें।"
            })
            addView(actionButton("Type Text on Paired Phone", "Approval मिलने के बाद focused editable field में लिखें") { sendRemote(JSONObject().put("kind", "type").put("text", remoteText.text.toString())) })
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
            val pcRelativePath = edit("Windows approved-folder relative path", "उदाहरण: Reports या notes.txt")
            addView(pcRelativePath)
            addView(actionButton("Read Active Windows Text (OCR)", "Windows companion में --allow-ocr जरूरी; हर request पर PC confirmation") {
                sendRemote(JSONObject().put("kind", "ocr"))
            })
            addView(actionButton("List Approved Windows Folder", "Windows companion में --file-root configure होना चाहिए") {
                sendRemote(JSONObject().put("kind", "file_list").put("path", pcRelativePath.text.toString().trim()))
            })
            addView(actionButton("Preview Windows Text File", "केवल allowed text/code files; कोई execute/delete नहीं") {
                val relative = pcRelativePath.text.toString().trim()
                if (relative.isBlank()) status.text = "पहले approved folder के अंदर relative file path लिखें।"
                else sendRemote(JSONObject().put("kind", "file_read").put("path", relative))
            })
            val pcSearchQuery = edit("Search text inside approved Windows folder", "उदाहरण: invoice number या project title")
            addView(pcSearchQuery)
            addView(actionButton("SEARCH APPROVED WINDOWS FILES", "केवल सीमित text/code files; snippets से संभावित credentials भी redact होंगे") {
                val query = pcSearchQuery.text.toString().trim()
                if (query.isBlank() || query.length > 120) status.text = "Search query 1-120 characters की होनी चाहिए।"
                else sendRemote(JSONObject().put("kind", "file_search").put("path", pcRelativePath.text.toString().trim())
                    .put("query", query).put("limit", 20))
            })
            val offlinePrompt = edit("Offline AI prompt for paired Windows PC", "बिना इंटरनेट local model से सवाल पूछें").apply {
                minLines = 3; maxLines = 5; isSingleLine = false
            }
            addView(offlinePrompt)
            addView(actionButton("ASK LOCAL OFFLINE AI ON WINDOWS", "PC companion को --offline-model के साथ GGUF model से शुरू करें; prompt पर PC confirmation आएगी") {
                val prompt = offlinePrompt.text.toString().trim()
                if (prompt.isBlank() || prompt.length > 4000) status.text = "Offline prompt 1-4000 characters का होना चाहिए।"
                else sendRemote(JSONObject().put("kind", "offline_chat").put("prompt", prompt))
            })
            addView(actionButton("Send Remote Home", "फोन की Home screen पर जाएँ") { sendRemote(JSONObject().put("kind", "home")) })
            addView(actionButton("Send Remote Back", "पिछली screen पर जाएँ") { sendRemote(JSONObject().put("kind", "back")) })
            addView(actionButton("Disconnect This Phone", "इस device का live connection बंद करें") { DeviceLinkManager.disconnect(); status.text = "This phone disconnected." })
        }

        section(root, "04  •  VISUAL LENS – LIVE SCREEN & REMOTE CAMERA", "स्क्रीन और कैमरा अलग-अलग, स्पष्ट अनुमति से साझा करें") {
            addView(actionButton("Start Rear Camera Share", "Selected paired device को camera दिखाएँ") { startCameraShare("back") })
            addView(actionButton("Start Front Camera Share", "Selected paired device को selfie camera दिखाएँ") { startCameraShare("front") })
            addView(actionButton("Stop This Phone Camera Share", "Remote camera stream तुरंत बंद करें") { stopService(Intent(this@MainActivity, RemoteCameraService::class.java)); lastCameraJpeg = null; status.text = "Camera sharing stopped." })
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
            gatewayToken = edit("AI gateway token", "Saved securely in Settings").apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(VoiceGatewaySettings.get(this@MainActivity).gatewayToken)
            }
            addView(gatewayToken)
            visionProvider = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gemini", "openai", "anthropic")) }
            addView(visionProvider)
            val visionMode = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("general", "objects", "document", "machine"))
            }
            addView(visionMode)
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
                DeviceLinkManager.analyzeScreen(url, gatewayToken.text.toString(), visionProvider.selectedItem.toString(), visionPrompt.text.toString(), frame, visionMode.selectedItem?.toString() ?: "general") { result -> runOnUiThread {
                    result.onSuccess { visionResult.text = it; status.text = "Remote screen analysis complete." }
                        .onFailure { visionResult.text = "Analysis error: ${it.message}" }
                } }
            })
            addView(actionButton("ASK AI ABOUT REMOTE CAMERA", "Latest consented paired-phone camera frame का Visual Lens analysis") {
                val frame = lastCameraJpeg
                if (frame == null) { status.text = "पहले paired phone पर camera sharing ON करें और camera permission दें।"; return@actionButton }
                val url = relayUrl.text.toString().trim()
                if (!validRelay(url)) { status.text = "HTTPS relay URL जरूरी है।"; return@actionButton }
                visionResult.text = "Visual Lens remote camera frame समझ रहा है…"
                DeviceLinkManager.analyzeScreen(url, gatewayToken.text.toString(), visionProvider.selectedItem.toString(), visionPrompt.text.toString(), frame) { result -> runOnUiThread {
                    result.onSuccess { visionResult.text = it; status.text = "Remote camera visual analysis complete." }
                        .onFailure { visionResult.text = "Camera analysis error: ${it.message}" }
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


    private fun addFutureIntelligence(root: LinearLayout) {
        val catalog = `in`.shraddha.ai.future.CapabilityCatalog.all
        section(root, "ADVANCED CAPABILITY MAP", "हर क्षमता की वास्तविक स्थिति साफ दिखाई जाती है। Roadmap का अर्थ है कि वह अभी पूर्ण रूप से उपलब्ध नहीं है।") {
            val grouped = catalog.groupBy { it.group }
            grouped.forEach { (group, items) ->
                addView(TextView(this@MainActivity).apply { text = group.uppercase(); textSize = 11f; letterSpacing = .08f; setTextColor(0xFFBFAEFF.toInt()); setPadding(0, dp(10), 0, dp(5)) })
                items.forEach { item ->
                    val state = when(item.state) {
                        `in`.shraddha.ai.future.CapabilityState.AVAILABLE -> "AVAILABLE"
                        `in`.shraddha.ai.future.CapabilityState.PERMISSION_REQUIRED -> "PERMISSION REQUIRED"
                        `in`.shraddha.ai.future.CapabilityState.COMPANION_REQUIRED -> "COMPANION REQUIRED"
                        `in`.shraddha.ai.future.CapabilityState.CONFIGURATION_REQUIRED -> "SETUP REQUIRED"
                        `in`.shraddha.ai.future.CapabilityState.ROADMAP -> "ROADMAP"
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = "${item.title}  •  $state\n${item.detail}"
                        textSize = 12f; setLineSpacing(dp(3).toFloat(),1f); setTextColor(0xFFDCE5F6.toInt())
                        setPadding(dp(11),dp(10),dp(11),dp(10)); background = rounded(0xFF0C1525.toInt(),14)
                    })
                    addView(View(this@MainActivity).apply { layoutParams=LinearLayout.LayoutParams(1,dp(5)) })
                }
            }
        }
        section(root, "WORKFLOW SAFETY ENGINE", "संदेश भेजने, भुगतान, delete, account बदलाव या अन्य संवेदनशील काम से पहले स्पष्ट पुष्टि; हर action के बाद verification") {
            val title=edit("Workflow title","उदाहरण: report तैयार करके review के लिए रखना")
            val preview=TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize=12f; setPadding(dp(12),dp(12),dp(12),dp(12)); background=rounded(0xFF0C1525.toInt(),14) }
            addView(title); addView(preview)
            addView(actionButton("Create safe workflow preview","यह preview बनाता है; किसी बाहरी ऐप पर action नहीं करता") {
                val name=title.text.toString().trim()
                if(name.isBlank()) { preview.text="पहले workflow का नाम लिखें।"; return@actionButton }
                val steps=listOf("Understand goal" to "READ_ONLY","Prepare draft" to "REVERSIBLE","Ask user approval" to "SENSITIVE","Execute only after approval" to "SENSITIVE","Verify result" to "READ_ONLY")
                preview.text="WORKFLOW: $name\n\n"+steps.mapIndexed { i,step -> "${i+1}. ${step.first} [${step.second}]" }.joinToString("\n")+"\n\nStatus: DRAFT • No external action executed"
                `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("workflow_preview","created",name)
            })
        }
        section(root, "AUTHORIZED SOCIAL PUBLISHING", "Telegram, Mastodon, Bluesky या LinkedIn पर user-confirmed post भेजें। Credentials केवल backend environment में रहेंगी; publish से पहले exact preview की पुष्टि जरूरी है।") {
            val endpoint = edit("Secure backend URL (HTTPS)", "https://your-domain.example")
            val tokenField = edit("Gateway token", "Server gateway token").apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val platform = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("telegram", "mastodon", "bluesky", "linkedin"))
            }
            val post = edit("Post text", "यहाँ अपना संदेश लिखें").apply {
                minLines = 4; maxLines = 8; isSingleLine = false
            }
            val result = TextView(this@MainActivity).apply {
                setTextColor(0xFFE4E9F5.toInt()); textSize = 12f
                setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(0xFF0C1525.toInt(), 14)
            }
            addView(endpoint); addView(tokenField); addView(platform); addView(post); addView(result)
            addView(actionButton("REVIEW EXACT POST & PUBLISH", "External publication • final confirmation required") {
                val base = endpoint.text.toString().trim().trimEnd('/')
                val secret = tokenField.text.toString().trim()
                val exact = post.text.toString()
                val selected = platform.selectedItem?.toString().orEmpty()
                if (!base.startsWith("https://") || secret.isBlank() || exact.isBlank() || exact.length > 5000) {
                    result.text = "HTTPS backend URL, gateway token और 1-5000 character post जरूरी हैं।"
                } else {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Confirm $selected publication")
                        .setMessage("यह exact text बाहरी platform पर भेजा जाएगा:\n\n$exact\n\nPublish करने के बाद इसे platform से हटाना अलग action होगा।")
                        .setPositiveButton("PUBLISH NOW") { _, _ ->
                            result.text = "Publishing request भेजी जा रही है…"
                            DeviceLinkManager.publishSocialPost(base, secret, selected, exact) { response ->
                                runOnUiThread {
                                    response.onSuccess { raw ->
                                        val body = runCatching { JSONObject(raw) }.getOrNull()
                                        result.text = if (body?.optBoolean("ok") == true) "Published on $selected. Reference: " + body.optString("postId", body.optString("messageId", "accepted")) else "Platform response: $raw"
                                        if (body?.optBoolean("ok") == true) `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("social_publish", selected, "User-confirmed external post")
                                    }.onFailure { result.text = "Publish failed: " + (it.message ?: "Unknown error") }
                                }
                            }
                        }
                        .setNegativeButton("CANCEL", null)
                        .show()
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "Server setup: Telegram = TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT_ID; Mastodon = MASTODON_BASE_URL + MASTODON_ACCESS_TOKEN; Bluesky = BLUESKY_IDENTIFIER + BLUESKY_APP_PASSWORD (app password only); LinkedIn = LINKEDIN_ACCESS_TOKEN + LINKEDIN_AUTHOR_URN + LINKEDIN_VERSION. Mastodon defaults to unlisted. LinkedIn requires approved API access and the matching member/page posting permission. Media upload and Instagram/Facebook/YouTube are not enabled here."
                textSize = 11f; setTextColor(0xFF9EABC2.toInt()); setPadding(0, dp(8), 0, 0)
            })
        }
        section(root, "SECURITY AUDIT TRAIL", "इस फोन पर सीमित metadata log; passwords, API keys, screen recordings या biometric data log नहीं होते") {
            val audit=`in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity)
            addView(actionButton("View local audit log","Recent workflow events") { val log=audit.exportText(); android.app.AlertDialog.Builder(this@MainActivity).setTitle("Security Audit Trail").setMessage(log.ifBlank { "अभी कोई audit event नहीं है।" }).setPositiveButton("OK",null).setNeutralButton("Clear") { _,_ -> audit.clear(); status.text="Audit log cleared." }.show() })
        }
        section(root, "LOCAL COUNCIL PLAN TEMPLATE", "बिना इंटरनेट के पाँच भूमिकाओं वाला planning template तैयार करें। वास्तविक provider calls के लिए ऊपर LIVE MULTI-AI COUNCIL उपयोग करें।") {
            val goal=edit("Council goal","उदाहरण: मेरी दुकान के लिए inventory system बनाना")
            val result=TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize=12f; setPadding(dp(12),dp(12),dp(12),dp(12)); background=rounded(0xFF0C1525.toInt(),14) }
            addView(goal); addView(result)
            addView(actionButton("Build 5-role council plan","Local planning only • no external action") {
                runCatching { `in`.shraddha.ai.future.AgentCouncil.plan(goal.text.toString()) }.onSuccess { plan ->
                    result.text="GOAL: ${plan.goal}\n\n"+plan.roles.mapIndexed { i,r -> "${i+1}. ${r.name}\n${r.responsibility}\nOutput: ${r.output}" }.joinToString("\n\n")+"\n\nGUARDRAILS\n• "+plan.guardrails.joinToString("\n• ")
                    getSharedPreferences("shraddha_council", MODE_PRIVATE).edit().putString("last_goal",plan.goal).apply()
                    `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("council_plan","created",plan.goal)
                }.onFailure { result.text=it.message ?: "Goal required" }
            })
        }
        section(root, "LIVE MULTI-AI COUNCIL", "अब configured backend के माध्यम से अलग-अलग AI providers से Planner, Researcher, Specialist, Critic और Verifier की स्वतंत्र राय ली जा सकती है। कोई device action execute नहीं होता।") {
            val endpoint = edit("Secure backend URL (HTTPS)", "https://your-domain.example")
            val tokenField = edit("Gateway token (required)", "Bearer token").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val providersField = edit("Providers (comma separated)", "gemini,openai")
            val councilGoal = edit("Council task / goal", "उदाहरण: एक सुरक्षित Android app architecture तैयार करो").apply { minLines = 3; isSingleLine = false }
            val councilOutput = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f; setPadding(dp(12),dp(12),dp(12),dp(12)); background = rounded(0xFF0C1525.toInt(),14) }
            addView(endpoint); addView(tokenField); addView(providersField); addView(councilGoal); addView(councilOutput)
            addView(actionButton("RUN LIVE 5-ROLE AI COUNCIL", "Uses configured provider APIs • network and API charges may apply") {
                val base = endpoint.text.toString().trim().trimEnd('/')
                val goalText = councilGoal.text.toString().trim()
                val providers = providersField.text.toString().split(',').map { it.trim().lowercase() }.filter { it in listOf("openai","gemini","anthropic","groq","perplexity") }.distinct().take(5)
                if (!base.startsWith("https://") || goalText.isBlank() || providers.size < 2) {
                    councilOutput.text = "HTTPS backend URL, task goal और कम-से-कम दो valid providers दें (openai, gemini, anthropic, groq, perplexity)."; return@actionButton
                }
                val gatewayTokenValue = tokenField.text.toString().trim()
                if (gatewayTokenValue.isBlank()) { councilOutput.text = "Gateway token जरूरी है। Backend में GATEWAY_TOKEN सेट करें।"; return@actionButton }
                councilOutput.text = "Council के पाँच roles को भेजा जा रहा है… Provider quota/network के अनुसार समय लग सकता है।"
                Thread {
                    try {
                        val payload = JSONObject().put("goal", goalText).put("providers", JSONArray(providers)).toString().toByteArray(Charsets.UTF_8)
                        val connection = (java.net.URL("$base/api/agents/council").openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 120000; doOutput = true
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            setRequestProperty("Authorization", "Bearer $gatewayTokenValue")
                        }
                        connection.outputStream.use { it.write(payload) }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        connection.disconnect()
                        val data = JSONObject(raw.ifBlank { "{}" })
                        val display = if (code in 200..299) {
                            val roleArray = data.optJSONArray("roles") ?: JSONArray()
                            val roleText = (0 until roleArray.length()).mapNotNull { roleArray.optJSONObject(it) }.joinToString("\n\n") { role ->
                                val label = "${role.optString("role")} (${role.optString("provider")})"
                                "$label\n${role.optString("answer", role.optString("error", "No response"))}"
                            }
                            "LIVE COUNCIL • ${data.optInt("successfulRoles")}/5 roles succeeded\nSynthesis: ${data.optString("synthesisProvider")}\n\n${data.optString("synthesis")}\n\nINDIVIDUAL REVIEWS\n$roleText\n\nNo external action was executed."
                        } else "Council request failed (HTTP $code): ${data.optString("error", raw.take(1000))}"
                        runOnUiThread { councilOutput.text = display; `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("live_ai_council", if(code in 200..299) "completed" else "failed", "${providers.joinToString(",")}; HTTP $code") }
                    } catch (e: Exception) { runOnUiThread { councilOutput.text = "Connection error: ${e.message ?: "Unknown error"}. Backend URL, HTTPS certificate, internet and provider configuration जाँचें।" } }
                }.start()
            })
        }
        section(root, "AUTONOMOUS PROJECT WORKSPACE", "Milestones और progress फोन में local save होते हैं। यह tracker है, background में अपने-आप काम चलाने वाला executor नहीं।") {
            val projectName=edit("Project name","उदाहरण: Website launch")
            val milestones=edit("Milestones (हर लाइन में एक)","Requirements तय करना\nDesign बनाना\nImplementation\nTesting\nRelease") .apply { minLines=4; isSingleLine=false }
            val projectView=TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize=12f; setPadding(dp(12),dp(12),dp(12),dp(12)); background=rounded(0xFF0C1525.toInt(),14) }
            addView(projectName); addView(milestones); addView(projectView)
            val prefs=getSharedPreferences("shraddha_projects", MODE_PRIVATE)
            fun renderProject() {
                val raw=prefs.getString("active_project",null)
                if(raw.isNullOrBlank()) { projectView.text="अभी कोई project नहीं बनाया गया।"; return }
                val obj=JSONObject(raw); val arr=obj.optJSONArray("milestones") ?: JSONArray()
                val lines=(0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { "${i+1}. ${it.optString("title")} — ${it.optString("state")}" } }
                projectView.text="${obj.optString("title")}\nProgress: ${obj.optInt("progress")}%\n\n"+lines.joinToString("\n")
            }
            renderProject()
            addView(actionButton("Create / replace project plan","Saves milestone list locally") {
                runCatching {
                    val plan=`in`.shraddha.ai.future.ProjectWorkspace.create(projectName.text.toString(),milestones.text.toString().lines())
                    val arr=JSONArray(); plan.milestones.forEach { m -> arr.put(JSONObject().put("id",m.id).put("title",m.title).put("state",m.state.name)) }
                    prefs.edit().putString("active_project",JSONObject().put("title",plan.title).put("progress",0).put("milestones",arr).toString()).apply()
                    `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("project_plan","saved",plan.title); renderProject()
                }.onFailure { projectView.text=it.message ?: "Project could not be saved" }
            })
            addView(actionButton("Mark next milestone complete","Manual progress update; no task is executed") {
                runCatching {
                    val raw=prefs.getString("active_project",null) ?: error("पहले project plan बनाएँ")
                    val obj=JSONObject(raw); val arr=obj.getJSONArray("milestones"); var found=false
                    for(i in 0 until arr.length()) { val item=arr.getJSONObject(i); if(!found && item.optString("state")!="DONE") { item.put("state","DONE"); found=true } }
                    val done=(0 until arr.length()).count { arr.getJSONObject(it).optString("state")=="DONE" }
                    obj.put("progress",if(arr.length()==0) 0 else done*100/arr.length()); prefs.edit().putString("active_project",obj.toString()).apply()
                    `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("project_progress","updated","${obj.optString("title")}: ${obj.optInt("progress")}%"); renderProject()
                }.onFailure { projectView.text=it.message ?: "Update failed" }
            })
        }
        section(root, "PERSONAL KNOWLEDGE GRAPH", "आप स्वयं जानकारी जोड़ें और खोजें। अभी automatic face/voice identification या hidden sensitive inference नहीं होता।") {
            val label=edit("Memory title","उदाहरण: मेरी दुकान")
            val kind=edit("Category","Project / Person / Device / Place")
            val note=edit("Memory note","इस जानकारी को याद रखने के लिए लिखें") .apply { minLines=2; isSingleLine=false }
            val query=edit("Search saved memories","दुकान, project, device...")
            val graphResult=TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize=12f; setPadding(dp(12),dp(12),dp(12),dp(12)); background=rounded(0xFF0C1525.toInt(),14) }
            addView(label); addView(kind); addView(note); addView(query); addView(graphResult)
            val memoryPrefs=getSharedPreferences("shraddha_knowledge_graph", MODE_PRIVATE)
            fun graphArray()=runCatching { JSONArray(memoryPrefs.getString("nodes","[]")) }.getOrElse { JSONArray() }
            addView(actionButton("Save knowledge node","Explicitly user-entered local memory") {
                if(label.text.isNullOrBlank()) { graphResult.text="Memory title जरूरी है।"; return@actionButton }
                val arr=graphArray(); arr.put(JSONObject().put("id","n-${System.currentTimeMillis()}").put("label",label.text.toString().trim()).put("kind",kind.text.toString().trim().ifBlank { "Note" }).put("note",note.text.toString().take(1000)).put("updated",System.currentTimeMillis()))
                memoryPrefs.edit().putString("nodes",arr.toString()).apply(); `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("knowledge_node","saved",label.text.toString()); graphResult.text="Memory locally saved. Total nodes: ${arr.length()}"
            })
            addView(actionButton("Search knowledge graph","Search titles, categories and notes") {
                val q=query.text.toString().trim(); val arr=graphArray()
                val hits=(0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("label").contains(q,true) || it.optString("kind").contains(q,true) || it.optString("note").contains(q,true) }
                graphResult.text=if(hits.isEmpty()) "कोई matching memory नहीं मिली।" else hits.joinToString("\n\n") { "${it.optString("label")} [${it.optString("kind")}]\n${it.optString("note")}" }
            })
        }
        addAdvancedFeatureStudios(root)
        section(root, "FUTURE EXPANSION PRIORITIES", "वे क्षमताएँ जिनके लिए external runtime, verified models या companion integration अभी बाकी है") {
            listOf("1. On-device face and speaker identity models with consent, anti-spoofing and accuracy validation", "2. Native offline LLM inference runtime and hardware-aware model manager", "3. Signed plugin packages with real sandbox isolation and revocation", "4. Durable project execution, resumable tasks and verified rollback", "5. Full MYRA/Lia runtime integration and PC screen streaming companion", "6. Smart-home connectors with local-first allowlists and manual override", "7. Actual image, video, audio and CAD generation/export pipelines", "8. Full encrypted backup restore including supported encrypted vault data", "9. Research retrieval with live source citations and verification", "10. Automated device test runner and global emergency access revocation").forEach { item -> addView(TextView(this@MainActivity).apply { text=item; textSize=12f; setTextColor(0xFFDCE5F6.toInt()); setPadding(0,dp(5),0,dp(5)) }) }
        }
    }

    private fun addAdvancedFeatureStudios(root: LinearLayout) {
        section(root, "ENGINEERING WORKBENCH", "Unit-aware offline calculators • conceptual estimates only • safety-critical design के लिए licensed professional जरूरी") {
            val mode = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Ohm's law: enter Voltage + Current", "Ohm's law: enter Current + Resistance", "Ohm's law: enter Voltage + Resistance", "Electrical power", "Concrete volume", "Shaft torque")) }
            val a = edit("Value A", "V / A / kW / metre")
            val b = edit("Value B", "A / Ω / RPM / metre")
            val c = edit("Value C (optional)", "Power factor / thickness")
            val out = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize=13f; setPadding(dp(12),dp(12),dp(12),dp(12)); background=rounded(0xFF0C1525.toInt(),14) }
            addView(mode); addView(a); addView(b); addView(c); addView(out)
            addView(actionButton("CALCULATE AND SHOW FORMULA", "Offline deterministic calculation") {
                fun number(v: android.widget.EditText): Double? = v.text.toString().trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                runCatching {
                    val x=number(a) ?: error("पहला numeric value दें")
                    val y=number(b) ?: error("दूसरा numeric value दें")
                    val z=number(c)
                    when(mode.selectedItemPosition) {
                        0 -> `in`.shraddha.ai.future.EngineeringWorkbench.ohms(voltage=x,current=y)
                        1 -> `in`.shraddha.ai.future.EngineeringWorkbench.ohms(current=x,resistance=y)
                        2 -> `in`.shraddha.ai.future.EngineeringWorkbench.ohms(voltage=x,resistance=y)
                        3 -> `in`.shraddha.ai.future.EngineeringWorkbench.electricPower(x,y,z ?: 1.0)
                        4 -> `in`.shraddha.ai.future.EngineeringWorkbench.concreteVolume(x,y,z ?: error("Thickness तीसरे field में दें"))
                        else -> `in`.shraddha.ai.future.EngineeringWorkbench.torque(x,y)
                    }
                }.onSuccess { r -> out.text="${r.title}\n${"%.4f".format(java.util.Locale.US,r.value)} ${r.unit}\n\n${r.formula}\n\n${r.note}"; `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("engineering_calculation","completed",r.title) }
                    .onFailure { out.text=it.message ?: "Calculation failed" }
            })
        }
        section(root, "CREATOR STUDIO", "Offline outline, storyboard और production brief तैयार करें। Actual image/video/audio rendering के लिए provider integration और export pipeline जरूरी है।") {
            val kind=Spinner(this@MainActivity).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("document","video","website","presentation","3d")) }
            val title=edit("Project title","उदाहरण: Product launch video")
            val brief=edit("Creative brief","Audience, purpose, language and key message").apply { isSingleLine=false; minLines=3 }
            val result=TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize=12f; setPadding(dp(12),dp(12),dp(12),dp(12)); background=rounded(0xFF0C1525.toInt(),14) }
            addView(kind); addView(title); addView(brief); addView(result)
            addView(actionButton("GENERATE OFFLINE CREATIVE BRIEF","Creates editable text outline; not a rendered media file") {
                runCatching { `in`.shraddha.ai.future.CreatorStudioEngine.draft(kind.selectedItem.toString(),title.text.toString(),brief.text.toString()) }
                    .onSuccess { d -> result.text="${d.title} • ${d.type.uppercase()}\n\n"+d.sections.joinToString("\n\n")+"\n\n${d.disclosure}"; getSharedPreferences("shraddha_creator",MODE_PRIVATE).edit().putString("last_draft",result.text.toString()).apply(); `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("creator_draft","created",d.type) }
                    .onFailure { result.text=it.message ?: "Draft could not be created" }
            })
            addView(actionButton("COPY CREATIVE BRIEF","Copy generated text") { val text=result.text.toString(); if(text.isNotBlank()) { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Shraddha Creator Draft",text)); Toast.makeText(this@MainActivity,"Copied",Toast.LENGTH_SHORT).show() } })
        }
        section(root, "DIGITAL TWIN • USER-EDITED PROFILE", "सिर्फ आपकी लिखी हुई पसंद सेव होती है; कोई छिपी निगरानी या संवेदनशील अनुमान नहीं") {
            val prefs=getSharedPreferences("shraddha_digital_twin",MODE_PRIVATE)
            val name=edit("Display name","आपका पसंदीदा नाम").apply { setText(prefs.getString("name", "")) }
            val language=edit("Preferred language","Hindi / English").apply { setText(prefs.getString("language","Hindi")) }
            val style=edit("Answer style","उदाहरण: detail, concise, step-by-step").apply { setText(prefs.getString("style","step-by-step")) }
            val result=TextView(this@MainActivity).apply { setTextColor(0xFFBFE8D2.toInt()); textSize=12f }
            addView(name); addView(language); addView(style); addView(result)
            addView(actionButton("SAVE MY EDITABLE DIGITAL TWIN","User-controlled preferences only") {
                if(name.text.isNullOrBlank()) { result.text="Display name भरें।"; return@actionButton }
                prefs.edit().putString("name",name.text.toString().take(60)).putString("language",language.text.toString().take(20)).putString("style",style.text.toString().take(100)).apply()
                result.text="Saved locally. आप किसी भी समय values बदल सकते हैं।"; `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("digital_twin","saved","user preferences")
            })
        }
        section(root, "PREDICTIVE ASSISTANT • OPT-IN ONLY", "कोई background tracking नहीं। आप manually events लिखें; repeated items पर सुझाव बनेगा।") {
            val enabled=CheckBox(this@MainActivity).apply { text="I opt in to suggestions from manually entered activity"; setTextColor(0xFFE4E9F5.toInt()); isChecked=getSharedPreferences("shraddha_predictive",MODE_PRIVATE).getBoolean("enabled",false) }
            val events=edit("Manual activity history","उदाहरण: daily stock report, daily stock report, weekly backup").apply { isSingleLine=false; minLines=3 }
            val result=TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize=12f }
            addView(enabled); addView(events); addView(result)
            addView(actionButton("ANALYZE OPTED-IN ACTIVITY","Suggestions only; nothing is scheduled automatically") {
                getSharedPreferences("shraddha_predictive",MODE_PRIVATE).edit().putBoolean("enabled",enabled.isChecked).apply()
                val suggestions=`in`.shraddha.ai.future.PredictiveSuggestionEngine.suggest(enabled.isChecked,events.text.toString().split(Regex("[,\n]")))
                result.text=if(!enabled.isChecked) "Opt-in बंद है; कोई suggestion नहीं।" else if(suggestions.isEmpty()) "तीन बार या अधिक manually repeat हुई activity नहीं मिली।" else suggestions.joinToString("\n") { "• ${it.title}\n  ${it.reason}" }
            })
        }
        section(root, "PLUGIN PERMISSION REGISTRY", "Manifest और scopes की जाँच। इस registry में third-party code execute या dynamically load नहीं होता।") {
            val id=edit("Plugin ID","उदाहरण: com.example.notes")
            val pname=edit("Plugin name","Plugin display name")
            val version=edit("Semantic version","1.0.0")
            val scopes=edit("Requested scopes (comma separated)","read_notes,create_draft")
            val result=TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize=12f }
            addView(id); addView(pname); addView(version); addView(scopes); addView(result)
            addView(actionButton("VALIDATE PLUGIN MANIFEST","Validation only • no plugin installation") {
                runCatching {
                    val requested=scopes.text.toString().split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    `in`.shraddha.ai.future.PluginPermissionPolicy.validate(`in`.shraddha.ai.future.PluginPermissionPolicy.Manifest(id.text.toString(),pname.text.toString(),version.text.toString(),requested))
                }.onSuccess { m -> result.text="VALID MANIFEST\n${m.name} ${m.version}\nRequested: ${m.requestedScopes.joinToString()}\nApproved: none\nCode execution: disabled"; `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("plugin_manifest","validated",m.id) }
                    .onFailure { result.text="Rejected: ${it.message}" }
            })
        }
        section(root, "DEVICE HEALTH & RECOVERY", "Local device diagnostics और user-triggered backup export") {
            val reminderTitle=edit("Reminder title","उदाहरण: Weekly backup")
            val reminderNote=edit("Reminder note","काम याद दिलाने का संदेश")
            addView(reminderTitle); addView(reminderNote)
            addView(actionButton("SCHEDULE REMINDER","Choose date and time • Android may delay inexact alarms") {
                val now=java.util.Calendar.getInstance()
                android.app.DatePickerDialog(this@MainActivity,{ _,year,month,day ->
                    android.app.TimePickerDialog(this@MainActivity,{ _,hour,minute ->
                        val whenAt=java.util.Calendar.getInstance().apply { set(year,month,day,hour,minute,0); set(java.util.Calendar.MILLISECOND,0) }.timeInMillis
                        if(whenAt<=System.currentTimeMillis()) { Toast.makeText(this@MainActivity,"Future date/time चुनें।",Toast.LENGTH_LONG).show(); return@TimePickerDialog }
                        val id=(whenAt % Int.MAX_VALUE).toInt()
                        val alarm=getSystemService(ALARM_SERVICE) as android.app.AlarmManager
                        val pi=android.app.PendingIntent.getBroadcast(this@MainActivity,id,Intent(this@MainActivity,`in`.shraddha.ai.future.ReminderReceiver::class.java).putExtra("title",reminderTitle.text.toString().ifBlank { "Shraddha AI reminder" }).putExtra("note",reminderNote.text.toString()),android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                        alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,whenAt,pi)
                        `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("reminder","scheduled",reminderTitle.text.toString().take(80))
                        Toast.makeText(this@MainActivity,"Reminder scheduled. Android battery policy may delay delivery.",Toast.LENGTH_LONG).show()
                    },now.get(java.util.Calendar.HOUR_OF_DAY),now.get(java.util.Calendar.MINUTE),false).show()
                },now.get(java.util.Calendar.YEAR),now.get(java.util.Calendar.MONTH),now.get(java.util.Calendar.DAY_OF_MONTH)).show()
            })
            addView(actionButton("CHECK DEVICE HEALTH","Battery, storage and Android build") {
                val bm=getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                val battery=bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val stat=android.os.StatFs(filesDir.absolutePath)
                val free=stat.availableBytes/(1024L*1024L); val total=stat.totalBytes/(1024L*1024L)
                val info="Battery: ${if(battery>=0) "$battery%" else "Unavailable"}\nApp storage volume: $free MB free / $total MB total\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nNetwork status and thermal sensors are not sampled in background."
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Device Health Snapshot").setMessage(info).setPositiveButton("OK",null).show()
            })
            addView(actionButton("EXPORT ENCRYPTED BACKUP","AES-256-GCM • passphrase protected • selected user data only") {
                val backup=JSONObject().put("schema","shraddha-backup-v2").put("createdAt",System.currentTimeMillis())
                    .put("knowledgeGraph",getSharedPreferences("shraddha_knowledge_graph",MODE_PRIVATE).getString("nodes","[]"))
                    .put("activeProject",getSharedPreferences("shraddha_projects",MODE_PRIVATE).getString("active_project",""))
                    .put("localNotes",getSharedPreferences("shraddha_local_memory",MODE_PRIVATE).getString("notes",""))
                    .put("digitalTwin",JSONObject().put("name",getSharedPreferences("shraddha_digital_twin",MODE_PRIVATE).getString("name","")).put("language",getSharedPreferences("shraddha_digital_twin",MODE_PRIVATE).getString("language","Hindi")).put("style",getSharedPreferences("shraddha_digital_twin",MODE_PRIVATE).getString("style","step-by-step")))
                val password=EditText(this@MainActivity).apply { hint="Backup passphrase (12+ characters)"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Encrypt Shraddha backup").setMessage("Use a strong passphrase and keep it safe. It cannot be recovered if lost. API keys, pairing tokens, permissions and biometric data are excluded.")
                    .setView(password).setNegativeButton("Cancel",null).setPositiveButton("Encrypt & Save") { _,_ ->
                        val secret=password.text.toString().toCharArray()
                        try {
                            val encrypted=`in`.shraddha.ai.future.EncryptedBackupCodec.encrypt(backup.toString(),secret)
                            getSharedPreferences("shraddha_backup_pending",MODE_PRIVATE).edit().putString("payload",encrypted).apply()
                            val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/json"; putExtra(Intent.EXTRA_TITLE,"shraddha-ai-encrypted-backup-${System.currentTimeMillis()}.json") }
                            @Suppress("DEPRECATION")
                            startActivityForResult(intent,backupFileRequest)
                        } catch(e:Exception) { status.text="Backup encryption failed: ${e.message}" }
                        finally { secret.fill('\u0000'); password.text?.clear() }
                    }.show()
            })
            addView(actionButton("RESTORE ENCRYPTED BACKUP","Choose a Shraddha encrypted backup file and enter its passphrase") {
                val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/json" }
                @Suppress("DEPRECATION")
                startActivityForResult(intent,backupImportRequest)
            })
        }
        section(root, "ANDROID ON-DEVICE LLM", "LiteRT-LM CPU inference उपलब्ध है। compatible .litertlm model को AI Assistant workspace से import करें; model APK में bundled नहीं है।") {
            val model = java.io.File(filesDir, "offline-models/shraddha.litertlm")
            addView(TextView(this@MainActivity).apply {
                text = if (model.isFile) "Imported local model: ${model.length() / 1_000_000} MB\\nInference: on-device CPU\\nCloud fallback: disabled"
                    else "Model status: not installed\\nChoose a compatible .litertlm file in AI Assistant workspace."
                setTextColor(0xFFDCE5F6.toInt()); textSize=12f; setPadding(dp(12),dp(12),dp(12),dp(12)); background=rounded(0xFF0C1525.toInt(),14)
            })
        }
    }

    private fun addFirebaseCloudSection(root: LinearLayout) {
        section(root, "FIREBASE ACCOUNT & ENCRYPTED CLOUD VAULT", "पहले Firebase Console में Android app register करें और local.properties में config भरें। Cloud backup केवल आपकी सहमति से होगा।") {
            val email = edit("Email", "name@example.com")
            val password = edit("Password (कम-से-कम 8 अक्षर)", "Firebase account password").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val localVault = SecureMemoryVault(this@MainActivity)
            val note = edit("Private memory to sync", "स्थानीय encrypted memory से notes यहाँ आएँगे").apply {
                minLines = 3; isSingleLine = false
                setText(localVault.load().getOrElse { "" })
            }
            val consent = CheckBox(this@MainActivity).apply { text = "मैं इस private memory note को अपने Firebase खाते में encrypted रूप में sync/restore करने की अनुमति देता/देती हूँ।"; setTextColor(0xFFE4E9F5.toInt()) }
            val result = TextView(this@MainActivity).apply { setTextColor(0xFFBFE8D2.toInt()); textSize = 13f }
            addView(email); addView(password); addView(note); addView(consent); addView(result)
            addView(actionButton("Create Firebase Account", "Email/password authentication") {
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Valid email और कम-से-कम 8 अक्षर का password दें।"; return@actionButton }
                val emailValue = email.text.toString().trim(); val passwordValue = password.text.toString()
                result.text = "Firebase account बनाया जा रहा है…"
                Thread { val r = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity).createAccount(emailValue, passwordValue)
                    runOnUiThread { result.text = r.fold({ "Account created: ${it}" }, { "Firebase error: ${it.message}" }) }
                }.start()
            })
            addView(actionButton("Sign In & Sync Encrypted Memory", "Cloud में केवल encrypted payload जाएगा; password/biometric data upload नहीं होगा") {
                if (!consent.isChecked) { result.text = "पहले cloud sync consent checkbox चुनें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8 || note.text.isNullOrBlank()) { result.text = "Email, password और memory note भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim(); val passwordValue = password.text.toString(); val noteValue = note.text.toString()
                result.text = "Sign-in और encrypted upload चल रहा है…"
                Thread {
                    val r = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity).signInAndSave(emailValue, passwordValue, "personal_memory", noteValue)
                    if (r.isSuccess) localVault.save(noteValue)
                    runOnUiThread { result.text = r.fold({ "Encrypted cloud backup saved and local encrypted vault updated for account ${it}." }, { "Sync failed: ${it.message}" }) }
                }.start()
            })
            addView(actionButton("Restore Encrypted Memory", "उसी email और password से दूसरे device पर restore करें") {
                if (!consent.isChecked) { result.text = "Restore से पहले cloud consent चुनें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim(); val passwordValue = password.text.toString()
                result.text = "Encrypted backup download और decrypt हो रहा है…"
                Thread { val r = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity).signInAndLoad(emailValue, passwordValue, "personal_memory")
                    runOnUiThread { result.text = r.fold({ value -> if (value == null) "इस account में backup नहीं मिला।" else {
                    val localSave = localVault.save(value)
                    if (localSave.isFailure) "Cloud data decrypt हुई, लेकिन local vault save नहीं हुई: ${localSave.exceptionOrNull()?.message}"
                    else {
                        getSharedPreferences("shraddha_local_memory", MODE_PRIVATE).edit().putString("notes", value).apply()
                        note.setText(value)
                        "Encrypted memory cloud से restore होकर local vault में save हुई।"
                    }
                } }, { "Restore failed: ${it.message}" }) }
                }.start()
            })
            val bundleConsent = CheckBox(this@MainActivity).apply {
                text = "मैं person/child profiles, task inbox और चुनी हुई app memory को encrypted Firebase backup/restore करने की अनुमति देता/देती हूँ।"
                setTextColor(0xFFE4E9F5.toInt())
            }
            addView(bundleConsent)
            val featureStore = EncryptedFeatureStore(this@MainActivity)
            val bundleKeys = listOf("person_directory", "kids_profile", "kids_care_log", "task_inbox", "workflow_checkpoint", "active_project", "digital_twin")
            addView(actionButton("BACK UP SELECTED DEVICE MEMORY", "Selected local records का client-side AES-GCM encrypted bundle") {
                if (!bundleConsent.isChecked) { result.text = "पहले selected-data backup consent दें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val items = JSONObject()
                var count = 0
                bundleKeys.forEach { key ->
                    val value = featureStore.get(key).getOrDefault("")
                    if (value.isNotBlank()) { items.put(key, value); count++ }
                }
                val memory = localVault.load().getOrDefault("")
                val bundle = JSONObject().put("version", 1).put("createdAt", System.currentTimeMillis())
                    .put("items", items).put("memory", memory).toString()
                if (bundle.length > 100000) { result.text = "Selected data 100 KB limit से अधिक है; पहले पुराने records कम करें।"; return@actionButton }
                val emailValue = email.text.toString().trim(); val passwordValue = password.text.toString()
                result.text = "Selected records encrypt करके Firebase पर भेजे जा रहे हैं…"
                Thread {
                    val saved = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity).signInAndSaveBundle(emailValue, passwordValue, bundle)
                    runOnUiThread { result.text = saved.fold({ "Encrypted device recovery backup saved. $count feature collections + local memory." }, { "Backup failed: ${it.message}" }) }
                }.start()
            })
            addView(actionButton("RESTORE SELECTED DEVICE MEMORY", "दूसरे device पर login करके चुने records recover करें; restore से पहले overwrite preview आएगा") {
                if (!bundleConsent.isChecked) { result.text = "पहले selected-data restore consent दें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim(); val passwordValue = password.text.toString()
                result.text = "Encrypted recovery bundle download हो रहा है…"
                Thread {
                    val restored = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity).signInAndLoadBundle(emailValue, passwordValue)
                    runOnUiThread {
                        restored.onSuccess { raw ->
                            if (raw.isNullOrBlank()) { result.text = "इस Firebase account में device recovery backup नहीं मिला।"; return@onSuccess }
                            val parsed = runCatching { JSONObject(raw) }.getOrNull()
                            val items = parsed?.optJSONObject("items")
                            if (parsed?.optInt("version") != 1 || items == null) { result.text = "Backup format unsupported या corrupt है।"; return@onSuccess }
                            val keys = items.keys().asSequence().toList().filter { it in bundleKeys }
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Restore ${keys.size} collections?")
                                .setMessage("यह backup इन local collections को replace करेगा:\n\n" + keys.joinToString("\n") + "\n\nLocal encrypted memory भी restore होगी।")
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("RESTORE") { _, _ ->
                                    val failures = mutableListOf<String>()
                                    keys.forEach { key -> if (featureStore.put(key, items.optString(key)).isFailure) failures.add(key) }
                                    val memory = parsed.optString("memory", "")
                                    if (memory.isNotBlank()) {
                                        if (localVault.save(memory).isFailure) failures.add("personal_memory")
                                        else getSharedPreferences("shraddha_local_memory", MODE_PRIVATE).edit().putString("notes", memory).apply()
                                    }
                                    result.text = if (failures.isEmpty()) "Recovery complete: ${keys.size} collections and local memory restored." else "Partial restore. Failed: " + failures.joinToString()
                                }.show()
                        }.onFailure { result.text = "Recovery failed: ${it.message}" }
                    }
                }.start()
            })
            addView(actionButton("RECOVERY HISTORY • RESTORE OLDER SNAPSHOT", "पिछले अधिकतम 10 encrypted device backups में से चुनें") {
                if (!bundleConsent.isChecked) { result.text = "पहले selected-data restore consent दें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                result.text = "Encrypted recovery history खोजी जा रही है…"
                Thread {
                    val vault = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity)
                    val listed = vault.signInAndListBundleRevisions(emailValue, passwordValue)
                    runOnUiThread {
                        if (listed.isFailure) {
                            result.text = "Recovery history failed: ${listed.exceptionOrNull()?.message}"
                        } else {
                            val revisions = listed.getOrDefault(emptyList())
                            if (revisions.isEmpty()) {
                                result.text = "इस account में पुराने recovery snapshots नहीं मिले।"
                            } else {
                                val labels = revisions.map { revision ->
                                    java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(revision.updatedAt))
                                }.toTypedArray()
                                android.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Choose encrypted recovery snapshot")
                                    .setItems(labels) { _, which ->
                                        val selected = revisions[which]
                                        result.text = "Selected snapshot decrypt हो रहा है…"
                                        Thread {
                                            val loaded = vault.signInAndLoadBundleRevision(emailValue, passwordValue, selected.id)
                                            runOnUiThread {
                                                loaded.onSuccess { raw ->
                                                    if (raw.isNullOrBlank()) { result.text = "Selected snapshot खाली है।"; return@onSuccess }
                                                    val parsed = runCatching { JSONObject(raw) }.getOrNull()
                                                    val items = parsed?.optJSONObject("items")
                                                    if (parsed?.optInt("version") != 1 || items == null) { result.text = "Snapshot format unsupported या corrupt है।"; return@onSuccess }
                                                    val keys = items.keys().asSequence().toList().filter { it in bundleKeys }
                                                    android.app.AlertDialog.Builder(this@MainActivity)
                                                        .setTitle("Restore older snapshot?")
                                                        .setMessage("यह snapshot इन local collections को replace करेगा:\n\n" + keys.joinToString("\n") + "\n\nCurrent local values पहले अपने आप overwrite नहीं होंगे; केवल आपकी पुष्टि पर restore होगा।")
                                                        .setNegativeButton("CANCEL", null)
                                                        .setPositiveButton("RESTORE") { _, _ ->
                                                            val failures = mutableListOf<String>()
                                                            keys.forEach { key -> if (featureStore.put(key, items.optString(key)).isFailure) failures.add(key) }
                                                            val memory = parsed.optString("memory", "")
                                                            if (memory.isNotBlank()) {
                                                                if (localVault.save(memory).isFailure) failures.add("personal_memory")
                                                                else getSharedPreferences("shraddha_local_memory", MODE_PRIVATE).edit().putString("notes", memory).apply()
                                                            }
                                                            result.text = if (failures.isEmpty()) "Older snapshot restored: ${keys.size} collections + local memory." else "Partial restore. Failed: " + failures.joinToString()
                                                        }.show()
                                                }.onFailure { result.text = "Snapshot restore failed: ${it.message}" }
                                            }
                                        }.start()
                                    }.setNegativeButton("CLOSE", null).show()
                            }
                        }
                    }
                }.start()
            })
            addView(TextView(this@MainActivity).apply { text = "सुरक्षा: Firebase client config secret नहीं होता, लेकिन database rules जरूरी हैं। Password किसी server पर नहीं भेजा जाता; उससे client पर encryption key बनती है। Face/voice biometric templates इस backup में शामिल नहीं हैं। Firebase project config सेट किए बिना cloud buttons काम नहीं करेंगे।"; setTextColor(0xFF9EABC2.toInt()); textSize = 12f; setPadding(0, dp(10), 0, 0) })
        }
    }

    private fun addKidsUniverse(root: LinearLayout) {
        val store = EncryptedFeatureStore(this)
        val ageOptions = listOf("0–6 months", "6–12 months", "1–2 years", "2–3 years", "3–4 years", "4–5 years", "5–6 years")
        val age = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ageOptions) }
        val curriculumAge = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ageOptions) }
        val childName = edit("Child nickname (optional)", "Only stored on this device")
        val parentName = edit("Parent / guardian name", "Optional")
        val dailyGoal = edit("Daily learning goal (minutes)", "Example: 15")
        val saved = store.get("kids_profile").getOrDefault("")
        val old = runCatching { JSONObject(saved) }.getOrNull()
        if (old != null) {
            childName.setText(old.optString("child")); parentName.setText(old.optString("guardian")); dailyGoal.setText(old.optString("goal", "15"))
            val idx = ageOptions.indexOf(old.optString("age")); if (idx >= 0) age.setSelection(idx)
        } else dailyGoal.setText("15")
        section(root, "BABY CARE & CHILD PROFILE", "0–6 वर्ष • अभिभावक-नियंत्रित, डिवाइस पर encrypted रिकॉर्ड") {
            addView(childName); addView(parentName); addView(age); addView(dailyGoal)
            addView(actionButton("Save Child Profile", "जानकारी encrypted local storage में रहेगी") {
                val obj = JSONObject().put("child", childName.text.toString().take(60)).put("guardian", parentName.text.toString().take(60)).put("age", age.selectedItem.toString()).put("goal", dailyGoal.text.toString().take(4))
                store.put("kids_profile", obj.toString()).onSuccess { status.text = "बच्चे की प्रोफाइल सुरक्षित रूप से सेव हुई।" }.onFailure { status.text = "Save error: ${it.message}" }
            })
        }
        val careItems = listOf("Feeding / दूध पिलाने का रिकॉर्ड", "Sleep / नींद का रिकॉर्ड", "Diaper / डायपर रिकॉर्ड", "Bath & hygiene / सफाई", "Tummy time / खेल गतिविधि", "Medicine or appointment note / दवा या डॉक्टर अपॉइंटमेंट")
        val careLog = edit("Care note", "जैसे 10:30 AM feeding 120 ml")
        section(root, "DAILY BABY CARE JOURNAL", "माता-पिता द्वारा दर्ज किया गया लॉग; यह medical diagnosis या emergency monitor नहीं है") {
            careItems.forEach { item -> addView(TextView(this@MainActivity).apply { text = "• $item"; setTextColor(0xFFE2E8F0.toInt()); textSize = 13f; setPadding(0, dp(5), 0, dp(5)) }) }
            addView(careLog)
            addView(actionButton("Save Care Entry", "समय सहित encrypted journal में जोड़ें") {
                val note = careLog.text.toString().trim()
                if (note.isBlank()) { status.text = "पहले care note लिखें।"; return@actionButton }
                val arr = runCatching { JSONArray(store.get("kids_care_log").getOrDefault("")) }.getOrElse { JSONArray() }
                arr.put(JSONObject().put("at", System.currentTimeMillis()).put("note", note.take(500)))
                store.put("kids_care_log", arr.toString()).onSuccess { careLog.text.clear(); status.text = "Care entry सेव हुई (${arr.length()} entries)।" }.onFailure { status.text = "Save error: ${it.message}" }
            })
            addView(actionButton("View / Export Care Log", "दर्ज entries पढ़ें") {
                val entries = runCatching { JSONArray(store.get("kids_care_log").getOrDefault("")) }.getOrElse { JSONArray() }
                val lines = (0 until entries.length()).mapNotNull { i -> entries.optJSONObject(i)?.let { "• ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.optLong("at")))} — ${it.optString("note")}" } }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Baby Care Journal").setMessage(if (lines.isEmpty()) "अभी कोई entry नहीं है।" else lines.joinToString("\n\n")).setPositiveButton("OK", null).show()
            })
        }
        val curricula = linkedMapOf(
            "0–6 months" to listOf("Parent voice & bonding", "Gentle sensory interaction", "Safe tummy-time guidance", "Sleep and feeding journal"),
            "6–12 months" to listOf("Object permanence play", "Sound and name response games", "Safe grasping activities", "Picture and texture exploration"),
            "1–2 years" to listOf("First words and naming", "Colors and simple shapes", "Sorting and stacking", "Action songs with caregiver"),
            "2–3 years" to listOf("Hindi and English vocabulary", "Counting 1–10", "Matching and simple puzzles", "Drawing and pretend play"),
            "3–4 years" to listOf("Pre-writing strokes", "Alphabet and phonics introduction", "Counting 1–20", "Patterns, stories and GK"),
            "4–5 years" to listOf("ABC / Hindi letters", "Numbers 1–50", "Abacus introduction and bead values", "Memory, maze and logic games"),
            "5–6 years" to listOf("Reading readiness and phonics", "Numbers 1–100", "Abacus addition/subtraction foundations", "Science observation and school readiness")
        )
        section(root, "AGE-WISE CURRICULUM", "उम्र चुनकर पूरा syllabus देखें • बच्चे की readiness और रुचि के अनुसार गति") {
            addView(curriculumAge)
            addView(actionButton("Load Selected Age Syllabus", "पाठ्यक्रम और गतिविधियाँ देखें") {
                val selectedAge = curriculumAge.selectedItem?.toString() ?: "0–6 months"
                val lessons = curricula[selectedAge] ?: curricula.values.first()
                val syllabus = lessons.mapIndexed { i, lesson -> "${i + 1}. $lesson" }.joinToString("\n")
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("$selectedAge • Learning Plan").setMessage(syllabus).setPositiveButton("Start first activity") { _, _ ->
                    val first = lessons.first()
                    val detail = if (first.contains("Abacus", true)) "पहले 1-bead और 5-bead का अर्थ समझें। बच्चे को बिना दबाव के मोतियों से गिनने दें।" else "अभिभावक पहले गतिविधि दिखाएँ, फिर बच्चे को सुरक्षित तरीके से कोशिश करने दें। 5–10 मिनट पर्याप्त हो सकते हैं। बच्चे की रुचि खत्म हो तो विराम दें।"
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle(first).setMessage(detail).setPositiveButton("OK", null).show()
                }.setNegativeButton("Close", null).show()
            })
        }
        section(root, "ABACUS & MIND ACTIVITIES", "4–6 वर्ष के लिए readiness-based शुरुआत; छोटे बच्चों के लिए play-first activities") {
            listOf("Level 1 • Abacus introduction and bead values", "Level 2 • Finger technique and number representation", "Level 3 • Single-digit addition", "Level 4 • Single-digit subtraction", "Level 5 • Mixed addition and subtraction", "Level 6 • Two-digit foundations with concrete beads", "Level 7 • Visualization introduction (only when ready)", "Level 8 • Accuracy and gentle timed practice (optional)", "Mind • Picture matching and visual memory", "Mind • Patterns AB / AAB and sorting", "Mind • Simple maze and story completion", "Mind • Listening, rhythm and observation games").forEach { addView(TextView(this@MainActivity).apply { text = "✦  $it"; setTextColor(0xFFE2E8F0.toInt()); textSize = 13f; setPadding(0, dp(6), 0, dp(6)) }) }
        }
        section(root, "PARENT SAFETY NOTES", "बच्चे की सुरक्षा और वास्तविक विकास सबसे पहले") {
            addView(TextView(this@MainActivity).apply { text = "0–2 वर्ष: यह मॉड्यूल माता-पिता के लिए care journal और real-world activity guide है; शिशु को स्क्रीन पर पढ़ाने का विकल्प नहीं। Feeding, sleep, growth और vaccination की जानकारी अभिभावक/चिकित्सक से सत्यापित करें। यह medical advice, diagnosis, cry detection या emergency alert system नहीं है। कैमरा/माइक background में निगरानी नहीं करते।"; setTextColor(0xFFFFD7A8.toInt()); textSize = 12f })
            addView(actionButton("Delete All Kids Data", "बच्चे की प्रोफाइल और journal हटाएँ") {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Delete kids data?").setMessage("यह इस फोन से kids profile और care journal हटाएगा।").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> store.remove("kids_profile"); store.remove("kids_care_log"); status.text = "Kids data इस डिवाइस से हटाया गया।" }.show()
            })
        }
    }

    private fun restoreBiometricModels() {
        Thread({
            val dir = java.io.File(filesDir, "biometric-models")
            val face = java.io.File(dir, "sface.onnx")
            val speaker = java.io.File(dir, "ecapa-any-length.onnx")
            if (face.isFile) runCatching { biometricEngine.loadFaceModel(face.absolutePath) }
            if (speaker.isFile) runCatching { biometricEngine.loadSpeakerModel(speaker.absolutePath) }
        }, "BiometricModelRestore").start()
    }

    private fun addPersonMemory(root: LinearLayout) {
        val store = EncryptedFeatureStore(this)
        val biometricVault = BiometricConsentVault(store)
        val personName = edit("Person name", "Example: Rahul")
        val relation = edit("Relationship / introduction", "Friend, family, colleague…")
        val notes = edit("Optional notes", "Only information you are allowed to store")
        val consent = Switch(this).apply { text = "इस व्यक्ति की स्पष्ट सहमति प्राप्त है"; setTextColor(-1); textSize = 13f; setPadding(0, dp(8), 0, dp(10)) }
        fun readPeople(): JSONArray {
            val arr = runCatching { JSONArray(store.get("person_directory").getOrDefault("[]")) }.getOrElse { JSONArray() }
            var changed = false
            for (i in 0 until arr.length()) {
                val person = arr.optJSONObject(i) ?: continue
                if (person.optString("id").isBlank()) { person.put("id", java.util.UUID.randomUUID().toString().replace("-", "")); changed = true }
            }
            if (changed) store.put("person_directory", arr.toString())
            return arr
        }
        fun people(): List<JSONObject> {
            val arr = readPeople()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optBoolean("consent", false) }
        }
        section(root, "PERSON DIRECTORY", "परिचित व्यक्तियों के consent-based profiles • encrypted, local-only") {
            addView(personName); addView(relation); addView(notes); addView(consent)
            addView(actionButton("Save Person Profile", "व्यक्ति की स्पष्ट अनुमति के बाद profile सुरक्षित करें") {
                val name = personName.text.toString().trim()
                if (name.isBlank() || !consent.isChecked) { status.text = "नाम और व्यक्ति की स्पष्ट सहमति आवश्यक है।"; return@actionButton }
                val arr = readPeople()
                val id = java.util.UUID.randomUUID().toString().replace("-", "")
                arr.put(JSONObject().put("id", id).put("name", name.take(80)).put("relation", relation.text.toString().take(100)).put("notes", notes.text.toString().take(500)).put("consent", true).put("consentAt", System.currentTimeMillis()).put("createdAt", System.currentTimeMillis()))
                store.put("person_directory", arr.toString()).onSuccess { status.text = "Person profile सुरक्षित रूप से सेव हुआ।"; personName.text.clear(); relation.text.clear(); notes.text.clear(); consent.isChecked = false }.onFailure { status.text = "Save error: ${it.message}" }
            })
            addView(actionButton("View Saved People", "परिचय और profiles देखें") {
                val rows = people().map { "${it.optString("name")} — ${it.optString("relation")}\n${it.optString("notes")}" }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Person Memory").setMessage(if (rows.isEmpty()) "कोई consented profile सेव नहीं है।" else rows.joinToString("\n\n")).setPositiveButton("OK", null).show()
            })
            addView(actionButton("Withdraw Consent / Delete One Person", "उस व्यक्ति के notes, face और voice templates हटाएँ") {
                val list = people()
                if (list.isEmpty()) { status.text = "कोई consented profile सेव नहीं है।"; return@actionButton }
                val names = list.map { it.optString("name") }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("किस व्यक्ति की consent वापस लेनी है?").setItems(names.toTypedArray()) { _, index ->
                    val selected = list[index]
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle("Withdraw ${names[index]}?").setMessage("Local profile और सभी biometric templates मिटेंगे। पहले से बने encrypted cloud backups में पुरानी copy अलग से रह सकती है।")
                        .setNegativeButton("Cancel", null).setPositiveButton("Withdraw & Delete") { _, _ ->
                            val deleted = biometricVault.withdraw(selected.optString("id"))
                            val updated = JSONArray()
                            val all = readPeople()
                            for (i in 0 until all.length()) { val p = all.optJSONObject(i) ?: continue; if (p.optString("id") != selected.optString("id")) updated.put(p) }
                            if (deleted.isSuccess) store.put("person_directory", updated.toString()).onSuccess { status.text = "Consent withdrawn; local profile and face/voice templates deleted." }.onFailure { status.text = "Profile delete failed: ${it.message}" }
                            else status.text = "Biometric delete failed: ${deleted.exceptionOrNull()?.message}"
                        }.show()
                }.show()
            })
        }
        section(root, "FACE & SPEAKER RECOGNITION", "On-device SFace + ECAPA • randomized live challenge • encrypted local templates") {
            addView(TextView(this@MainActivity).apply {
                text = "पहले दोनों compatible ONNX models import करें। हर enrollment से पहले व्यक्ति की स्पष्ट सहमति switch ON करनी होगी। Camera में blink + head-turn challenge और speaker के लिए on-device random phrase challenge चलेगा। Match केवल manually calibrated threshold पर होगा; Unknown default है।"
                setTextColor(0xFFB9C8E4.toInt()); textSize = 12f
            })
            val profileSpinner = Spinner(this@MainActivity)
            fun refreshProfiles() {
                val labels = people().map { it.optString("name") + " • " + it.optString("relation") }
                profileSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, if (labels.isEmpty()) listOf("No consented people") else labels)
            }
            refreshProfiles()
            addView(TextView(this@MainActivity).apply { text = "CONSENTED PERSON"; setTextColor(0xFFB9C8E4.toInt()) })
            addView(profileSpinner)
            val faceThreshold = edit("Face cosine threshold (calibrated 0–1)", "")
            faceThreshold.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            val speakerThreshold = edit("Speaker cosine threshold (calibrated 0–1)", "")
            speakerThreshold.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            addView(faceThreshold); addView(speakerThreshold)
            addView(actionButton("CALIBRATE FACE THRESHOLD", "कम-से-कम 2 consented लोगों के 2-2 face samples से genuine/impostor score separation जाँचें") {
                biometricVault.calibrateThreshold("face").onSuccess { result -> faceThreshold.setText("%.4f".format(java.util.Locale.US, result.threshold)); status.text = "Face threshold calibrated: %.4f • genuine min %.3f • impostor max %.3f • %d/%d pairs".format(java.util.Locale.US, result.threshold, result.minimumGenuine, result.maximumImpostor, result.genuinePairs, result.impostorPairs) }
                    .onFailure { status.text = "Face calibration blocked: ${it.message}" }
            })
            addView(actionButton("CALIBRATE SPEAKER THRESHOLD", "कम-से-कम 2 consented लोगों के 2-2 voice samples से threshold निकालें") {
                biometricVault.calibrateThreshold("speaker").onSuccess { result -> speakerThreshold.setText("%.4f".format(java.util.Locale.US, result.threshold)); status.text = "Speaker threshold calibrated: %.4f • genuine min %.3f • impostor max %.3f • %d/%d pairs".format(java.util.Locale.US, result.threshold, result.minimumGenuine, result.maximumImpostor, result.genuinePairs, result.impostorPairs) }
                    .onFailure { status.text = "Speaker calibration blocked: ${it.message}" }
            })
            fun launchCapture(kind: String, mode: String) {
                val selected = people().getOrNull(profileSpinner.selectedItemPosition)
                if (selected == null) { status.text = "पहले consent वाला Person Profile बनाएँ।"; return }
                if (!consent.isChecked) { status.text = "Capture से पहले व्यक्ति की स्पष्ट consent दोबारा confirm करें।"; return }
                val thresholdText = if (kind == "face") faceThreshold.text.toString().trim() else speakerThreshold.text.toString().trim()
                val threshold = if (mode == "match") thresholdText.toFloatOrNull() else Float.NaN
                if (mode == "match" && (threshold == null || threshold !in 0f..1f)) { status.text = "Match के लिए पहले locally calibrated threshold 0 से 1 के बीच भरें।"; return }
                val request = Intent(this@MainActivity, BiometricCaptureActivity::class.java)
                    .putExtra(BiometricCaptureActivity.EXTRA_KIND, kind)
                    .putExtra(BiometricCaptureActivity.EXTRA_MODE, mode)
                    .putExtra(BiometricCaptureActivity.EXTRA_PERSON_ID, selected.optString("id"))
                    .putExtra(BiometricCaptureActivity.EXTRA_PERSON_NAME, selected.optString("name"))
                    .putExtra(BiometricCaptureActivity.EXTRA_CONSENT, true)
                    .putExtra(BiometricCaptureActivity.EXTRA_THRESHOLD, threshold ?: Float.NaN)
                startActivityForResult(request, if (kind == "face") faceCaptureRequest else speakerCaptureRequest)
            }
            addView(actionButton("ENROLL FACE • LIVE CAMERA", "Random blink + head-turn liveness check; camera runs only on this screen") { launchCapture("face", "enroll") })
            addView(actionButton("MATCH FACE • LIVE CAMERA", "Compare with consented templates; no threshold means no match") { launchCapture("face", "match") })
            addView(actionButton("ENROLL SPEAKER • LIVE MICROPHONE", "On-device random phrase check then 5-second voice sample") { launchCapture("speaker", "enroll") })
            addView(actionButton("MATCH SPEAKER • LIVE MICROPHONE", "On-device random phrase check then compare with consented voiceprints") { launchCapture("speaker", "match") })
            addView(actionButton("IMPORT FACE MODEL • SFace ONNX", "sface.onnx • 112×112 aligned RGB → face embedding") {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream" }
                startActivityForResult(pick, faceBiometricModelRequest)
            })
            addView(actionButton("IMPORT SPEAKER MODEL • ECAPA ONNX", "ecapa-any-length.onnx • mono 16 kHz audio → 192D voiceprint") {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream" }
                startActivityForResult(pick, speakerBiometricModelRequest)
            })
            addView(actionButton("DELETE ALL BIOMETRIC TEMPLATES", "स्थानीय face और speaker templates मिटाएँ") {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Delete all biometric templates?").setMessage("यह सभी enrolled face और voice templates मिटाएगा।")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        val ok = biometricVault.clear()
                        status.text = if (ok) "All local biometric templates deleted." else "Some biometric records could not be deleted."
                    }.show()
            })
            addView(TextView(this@MainActivity).apply {
                text = "Threshold calibration: collect multiple genuine samples and multiple consenting non-matching test samples in the same lighting/mic environment. Choose a threshold that rejects all test impostors; if you cannot validate it, do not use Match. This is not certified anti-spoofing or authentication. Never use results to unlock devices, approve payments, or authorize sensitive actions."
                setTextColor(0xFFFFD7A8.toInt()); textSize = 11f
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
            } else if (msg.optString("type") == "control_request") {
                handleIncomingControlRequest(msg)
            } else if (msg.optString("type") == "control_result") {
                val approved = msg.optBoolean("approved")
                runOnUiThread {
                    status.text = if (approved) "Remote control की अनुमति मिली (${msg.optInt("expiresInMinutes", 5)} मिनट)।" else "Remote control request अस्वीकार हुई: ${msg.optString("reason", "Target ने अनुमति नहीं दी।")}"
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            } else if (msg.optString("type") == "remote_action_result") {
                val kind = msg.optString("actionKind", "PC result")
                val resultText = msg.optString("result").take(14000)
                runOnUiThread {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Windows $kind result")
                        .setMessage(resultText.ifBlank { "No result returned." })
                        .setPositiveButton("Close", null)
                        .show()
                    status.text = "Windows $kind result received."
                }
            } else if (msg.optString("type") == "task_handoff") {
                val task = msg.optJSONObject("task")
                if (task != null) {
                    val fromId = msg.optString("fromDeviceId")
                    val fromName = msg.optString("fromDeviceName", "Paired device")
                    val expiresAt = msg.optLong("expiresAt", 0L)
                    if (expiresAt <= System.currentTimeMillis()) {
                        DeviceLinkManager.respondTaskHandoff(fromId, task.optString("id"), false, "Task offer expired")
                    } else runOnUiThread {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Task from " + fromName)
                            .setMessage(task.optString("title") + "\n\n" + task.optString("description") + "\n\nAccepting saves the plan only. It will NOT run app actions.")
                            .setPositiveButton("Accept & Save") { _, _ ->
                                val store = EncryptedFeatureStore(this@MainActivity)
                                val inbox = runCatching { JSONArray(store.get("task_inbox").getOrDefault("[]")) }.getOrDefault(JSONArray())
                                if (inbox.length() < 10) {
                                    val safeSteps = JSONArray()
                                    val rawSteps = task.optJSONArray("steps")
                                    for (i in 0 until minOf(rawSteps?.length() ?: 0, 8)) safeSteps.put(rawSteps?.optString(i).orEmpty().take(150))
                                    inbox.put(JSONObject().put("id", task.optString("id")).put("title", task.optString("title").take(120))
                                        .put("description", task.optString("description").take(1500)).put("steps", safeSteps)
                                        .put("fromDeviceId", fromId).put("fromDeviceName", fromName.take(48)).put("receivedAt", System.currentTimeMillis()))
                                    val saved = store.put("task_inbox", inbox.toString()).isSuccess
                                    DeviceLinkManager.respondTaskHandoff(fromId, task.optString("id"), saved, if (saved) "Saved to encrypted Task Inbox" else "Encrypted local storage failed")
                                    status.text = if (saved) "Task accepted and saved in encrypted local inbox. It has not been executed." else "Encrypted Task Inbox में save नहीं हो सकी।"
                                } else {
                                    DeviceLinkManager.respondTaskHandoff(fromId, task.optString("id"), false, "Task inbox is full")
                                    status.text = "Task Inbox full है; task save नहीं हुई।"
                                }
                            }
                            .setNegativeButton("Reject") { _, _ ->
                                DeviceLinkManager.respondTaskHandoff(fromId, task.optString("id"), false, "Rejected by receiver")
                            }.setOnCancelListener {
                                DeviceLinkManager.respondTaskHandoff(fromId, task.optString("id"), false, "Dismissed by receiver")
                            }.show()
                    }
                }
            } else if (msg.optString("type") == "task_handoff_response") {
                runOnUiThread {
                    status.text = if (msg.optBoolean("accepted")) "Task " + msg.optString("taskId") + " accepted by " + msg.optString("fromDeviceName", "paired device") + "."
                    else "Task offer was not accepted: " + msg.optString("note", "No reason supplied")
                }
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
                if (::livePreview.isInitialized) {
                    livePreviewSourceId = sourceId
                    livePreview.setImageBitmap(bitmap)
                }
                status.text = "Live screen received from device $sourceId"
            }
        }
        DeviceLinkManager.onCameraFrame = { sourceId, bytes ->
            lastCameraJpeg = bytes
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) runOnUiThread {
                if (::cameraPreview.isInitialized) cameraPreview.setImageBitmap(bitmap)
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
        if (!getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean("screen", false)) {
            status.text = "पहले Data Hub में Screen sharing की अनुमति ON करें। इसे बंद करते ही स्क्रीन शेयरिंग रुक जाएगी।"
            return
        }
        val target = targetDevice.text.toString().trim().ifBlank { lastPeerId.orEmpty() }
        if (target.isBlank() || peerOnline[target] != true) {
            status.text = "पहले Device Control में एक paired और online device चुनें। Screen किसी भी device पर स्वतः broadcast नहीं होगी।"
            return
        }
        pendingShareTarget = target
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), screenShareRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 44) {
            val visualShareAllowed = getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean("camera", false)
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && visualShareAllowed) {
                val prefs = getSharedPreferences("shraddha_device_link", MODE_PRIVATE)
                val facing = prefs.getString("pending_camera_facing", "back") ?: "back"
                val target = prefs.getString("pending_camera_target", "").orEmpty()
                ContextCompat.startForegroundService(this, Intent(this, RemoteCameraService::class.java).putExtra(RemoteCameraService.EXTRA_FACING, facing).putExtra(RemoteCameraService.EXTRA_TARGET, target))
                status.text = "Camera permission मिली; $facing camera share शुरू किया गया।"
            } else status.text = if (!visualShareAllowed) "Camera sharing अनुमति बंद है; कैमरा शुरू नहीं किया गया।" else "Camera permission नहीं मिली; Remote Camera शुरू नहीं हुआ।"
        }
    }

    @Deprecated("Compatibility with starter project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == screenShareRequest) {
            if (resultCode == Activity.RESULT_OK && data != null &&
                getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean("screen", false)) {
                val target = pendingShareTarget.orEmpty()
                if (target.isBlank() || peerOnline[target] != true) {
                    status.text = "Selected device is no longer online. Screen sharing was not started."
                    pendingShareTarget = null
                    return
                }
                val service = Intent(this, ScreenShareService::class.java)
                    .putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenShareService.EXTRA_DATA, data)
                    .putExtra(ScreenShareService.EXTRA_TARGET_DEVICE_ID, target)
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, service) else startService(service)
                status.text = "Screen sharing started for the selected device only. Android shows an active-sharing notification."
                pendingShareTarget = null
            } else status.text = if (!getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean("screen", false)) "Screen sharing permission is off; capture was not started." else "Screen sharing cancelled."
            pendingShareTarget = null
            return
        }

        if (requestCode == faceCaptureRequest || requestCode == speakerCaptureRequest) {
            status.text = data?.getStringExtra(BiometricCaptureActivity.EXTRA_RESULT) ?: "Biometric capture cancelled or did not complete."
            return
        }

        if ((requestCode == faceBiometricModelRequest || requestCode == speakerBiometricModelRequest) && resultCode == Activity.RESULT_OK && data?.data != null) {
            val isFace = requestCode == faceBiometricModelRequest
            val expectedName = if (isFace) "sface.onnx" else "ecapa-any-length.onnx"
            try {
                val uri = data.data!!
                val directory = java.io.File(filesDir, "biometric-models").apply { mkdirs() }
                val target = java.io.File(directory, expectedName)
                val temporary = java.io.File(directory, expectedName + ".part")
                val freeBytes = android.os.StatFs(directory.absolutePath).availableBytes
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            val cap = if (isFace) 150_000_000L else 250_000_000L
                            if (total > cap || total + 100_000_000L > freeBytes) error("Model size/storage limit exceeded")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                } ?: error("Model file could not be read")
                if (temporary.length() < 500_000L) error("ONNX file is too small")
                try {
                    java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                Thread({
                    runCatching {
                        if (isFace) biometricEngine.loadFaceModel(target.absolutePath)
                        else biometricEngine.loadSpeakerModel(target.absolutePath)
                    }.onSuccess {
                        runOnUiThread { status.text = (if (isFace) "SFace face embedding model" else "ECAPA speaker embedding model") + " loaded locally. Live capture is available in People Memory." }
                    }.onFailure { error ->
                        target.delete()
                        runOnUiThread { status.text = "Biometric model rejected: " + (error.message ?: "unknown error") }
                    }
                }, "BiometricModelLoad").start()
            } catch (error: Exception) {
                java.io.File(filesDir, "biometric-models/" + expectedName + ".part").delete()
                status.text = "Biometric model import failed: " + (error.message ?: "unknown error")
            }
            return
        }
        if (requestCode == offlineModelImportRequest && resultCode == Activity.RESULT_OK && data?.data != null) {
            try {
                val uri = data.data!!
                val displayName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }.orEmpty()
                if (!displayName.lowercase().endsWith(".litertlm")) error("केवल .litertlm मॉडल फ़ाइल चुनें")
                val directory = java.io.File(filesDir, "offline-models").apply { mkdirs() }
                val target = java.io.File(directory, "shraddha.litertlm")
                val temporary = java.io.File(directory, "shraddha.litertlm.part")
                val freeBytes = android.os.StatFs(directory.absolutePath).availableBytes
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 5_000_000_000L || total + 512_000_000L > freeBytes) error("Model size/storage limit exceeded; keep 512 MB free")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                } ?: error("Model file पढ़ी नहीं जा सकी")
                if (temporary.length() < 10_000_000L) error("Model file बहुत छोटी या invalid है")
                try {
                    java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                status.text = "Offline model import complete • ${target.length() / 1_000_000} MB. अब Local Offline AI चला सकते हैं।"
            } catch (error: Exception) {
                java.io.File(filesDir, "offline-models/shraddha.litertlm.part").delete()
                status.text = "Offline model import failed: ${error.message}"
            }
            return
        }
        if (requestCode == backupFileRequest && resultCode == Activity.RESULT_OK && data?.data != null) {
            val payload=getSharedPreferences("shraddha_backup_pending",MODE_PRIVATE).getString("payload",null)
            try {
                if(payload.isNullOrBlank()) error("Encrypted backup payload missing")
                contentResolver.openOutputStream(data.data!!)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) } ?: error("Could not open destination")
                getSharedPreferences("shraddha_backup_pending",MODE_PRIVATE).edit().remove("payload").apply()
                `in`.shraddha.ai.future.LocalAuditTrail(this).append("backup_export","completed","encrypted user-selected backup")
                status.text="Encrypted backup export complete. Keep the passphrase and file separately."
            } catch(e:Exception) { status.text="Backup export failed: ${e.message}" }
            return
        }
        if (requestCode == backupImportRequest && resultCode == Activity.RESULT_OK && data?.data != null) {
            try {
                val bytes=contentResolver.openInputStream(data.data!!)?.use { it.readBytes() } ?: error("Could not read backup file")
                if(bytes.isEmpty() || bytes.size > 2_100_000) error("Backup file is empty or too large")
                val envelope=String(bytes,Charsets.UTF_8)
                val password=EditText(this).apply { hint="Backup passphrase"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                android.app.AlertDialog.Builder(this).setTitle("Decrypt and restore backup").setMessage("Restore replaces the selected local notes, knowledge graph, project and digital twin values. Review the source file before continuing.")
                    .setView(password).setNegativeButton("Cancel",null).setPositiveButton("Decrypt & Restore") { _,_ ->
                        val secret=password.text.toString().toCharArray()
                        try {
                            val plain=`in`.shraddha.ai.future.EncryptedBackupCodec.decrypt(envelope,secret)
                            val backup=JSONObject(plain)
                            require(backup.optString("schema")=="shraddha-backup-v2") { "Unsupported backup data schema" }
                            val graph=backup.optString("knowledgeGraph","[]")
                            JSONArray(graph)
                            val project=backup.optString("activeProject","").take(100_000)
                            val notes=backup.optString("localNotes","").take(8_000)
                            val twin=backup.optJSONObject("digitalTwin") ?: JSONObject()
                            val graphPrefs=getSharedPreferences("shraddha_knowledge_graph",MODE_PRIVATE)
                            val projectPrefs=getSharedPreferences("shraddha_projects",MODE_PRIVATE)
                            val twinPrefs=getSharedPreferences("shraddha_digital_twin",MODE_PRIVATE)
                            graphPrefs.edit().putString("nodes",graph).commit()
                            projectPrefs.edit().putString("active_project",project).commit()
                            getSharedPreferences("shraddha_local_memory",MODE_PRIVATE).edit().putString("notes",notes).commit()
                            twinPrefs.edit().putString("name",twin.optString("name","")).putString("language",twin.optString("language","Hindi")).putString("style",twin.optString("style","step-by-step")).commit()
                            `in`.shraddha.ai.future.LocalAuditTrail(this).append("backup_restore","completed","encrypted backup v2")
                            status.text="Backup decrypted and restored successfully."
                        } catch(e:Exception) { status.text="Restore failed; backup was not accepted: ${e.message}" }
                        finally { secret.fill('\u0000'); password.text?.clear() }
                    }.show()
            } catch(e:Exception) { status.text="Backup file could not be opened: ${e.message}" }
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
        Thread { LocalOfflineLlm.close(); runCatching { biometricEngine.close() } }.start()
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
    private fun actionButton(label: String, hint: String, action: () -> Unit): LinearLayout {
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(11), dp(14), dp(11))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF493B80.toInt(), 0xFF26385C.toInt())
            ).apply {
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), 0xFF7464B8.toInt())
            }
            foreground = android.util.TypedValue().let { value ->
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                ContextCompat.getDrawable(context, value.resourceId)
            }
            elevation = dp(3).toFloat()
            minimumHeight = dp(58)
            isClickable = true
            isFocusable = true
            contentDescription = "$label. $hint"
            setOnClickListener { action() }
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        textColumn.addView(TextView(this).apply {
            text = hint
            textSize = 10.5f
            setTextColor(0xFFD0D5EA.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        button.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        button.addView(TextView(this).apply {
            text = "↗"
            textSize = 18f
            setTextColor(0xFFE0D8FF.toInt())
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), -1))
        button.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        return button
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


/** Animated 3D-style energy orb. Its animation is decorative; microphone state is reported separately. */
private class ShraddhaOrbView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 7200L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        contentDescription = "Shraddha AI animated energy orb. Microphone state is shown by the voice controls."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) * .285f
        if (base <= 0f) return
        val wave = (kotlin.math.sin(phase * Math.PI * 2.0).toFloat() + 1f) * .5f
        val pulse = 1f + .035f * wave
        val rotation = phase * 360f

        // Soft atmospheric halo behind the core.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, base * 2.05f,
            intArrayOf(0x443D7BFF, 0x222B4DCE, 0x003D7BFF),
            floatArrayOf(0f, .52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, base * 2.05f, paint)
        paint.shader = null

        // Three tilted orbital rings create a layered, dimensional silhouette.
        val ringColors = intArrayOf(0xAA74E6FF.toInt(), 0x997D76FF.toInt(), 0x8878B7FF.toInt())
        for (i in 0..2) {
            canvas.save()
            canvas.rotate(rotation * (if (i == 1) -0.72f else 0.48f) + i * 58f, cx, cy)
            canvas.scale(1f, if (i == 1) .34f else .48f, cx, cy)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = base * (if (i == 0) .027f else .016f)
            paint.color = ringColors[i]
            canvas.drawCircle(cx, cy, base * (1.28f + i * .12f), paint)
            paint.style = Paint.Style.FILL
            val dotAngle = Math.toRadians((rotation * (if (i == 1) -0.72f else 0.48f) + i * 58f).toDouble())
            val orbit = base * (1.28f + i * .12f)
            val dx = cx + kotlin.math.cos(dotAngle).toFloat() * orbit
            val dy = cy + kotlin.math.sin(dotAngle).toFloat() * orbit
            paint.color = if (i == 0) 0xFFE8FCFF.toInt() else 0xFFD8C8FF.toInt()
            canvas.drawCircle(dx, dy, base * .055f, paint)
            canvas.restore()
        }

        // Core depth: dark outer shell, luminous radial volume, then specular highlights.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - base * .30f, cy - base * .36f, base * 1.48f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFDAD4FF.toInt(), 0xFF8F79FF.toInt(), 0xFF414DB5.toInt(), 0xFF111A3B.toInt()),
            floatArrayOf(0f, .16f, .42f, .76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, base * pulse, paint)
        paint.shader = null

        paint.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(cx - base * .31f, cy - base * .36f, base * .16f, paint)
        paint.color = 0x44B7F4FF
        canvas.drawCircle(cx + base * .34f, cy + base * .28f, base * .24f, paint)

        // Small orbiting energy points add motion without implying a live camera feed.
        for (i in 0 until 12) {
            val angle = phase * Math.PI * 2.0 + i * (Math.PI * 2.0 / 12.0)
            val orbit = base * (1.58f + .08f * kotlin.math.sin(angle * 2.0).toFloat())
            val x = cx + kotlin.math.cos(angle).toFloat() * orbit
            val y = cy + kotlin.math.sin(angle).toFloat() * orbit * .72f
            paint.color = if (i % 3 == 0) 0xFFD9FBFF.toInt() else 0x997F9DFF.toInt()
            canvas.drawCircle(x, y, base * (if (i % 3 == 0) .026f else .014f), paint)
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
