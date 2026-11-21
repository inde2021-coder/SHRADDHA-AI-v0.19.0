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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import org.json.JSONArray
import `in`.shraddha.ai.future.UserBackupManager
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import `in`.shraddha.ai.brain.PersistentChatMemory

class MainActivity : AppCompatActivity() {
    private val assistantDisplayName: String
        get() = getSharedPreferences("nova_prefs", MODE_PRIVATE)
            .getString("assistant_name", "Shraddha AI")?.trim()?.takeIf { it.isNotEmpty() } ?: "Shraddha AI"
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
    private val backupFileRequest = 45
    private val backupImportRequest = 49
    private val faceModelRequest = 46
    private val speakerModelRequest = 47
    private val localVisionRequest = 48
    private var pendingVisionPhoto: java.io.File? = null
    private val dataPermissionSwitches = linkedMapOf<String, Switch>()
    private var pendingShareTarget: String? = null
    private var pendingShareCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Do not request microphone access at app launch. Ask only when the user opens
        // Voice or explicitly starts a voice command.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(20))
            setBackgroundColor(0xFF070B14.toInt())
        }
        pageScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
            addView(pageRoot)
        }
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070B14.toInt())
        }
        bottomNav = buildBottomNavigation()
        shell.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(68)))
        setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            shell.setPadding(0, bars.top, 0, if (keyboardVisible) ime.bottom else 0)
            bottomNav.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            val navParams = bottomNav.layoutParams
            val targetHeight = dp(68) + if (keyboardVisible) 0 else bars.bottom
            if (navParams.height != targetHeight) {
                navParams.height = targetHeight
                bottomNav.layoutParams = navParams
            }
            bottomNav.setPadding(dp(8), dp(5), dp(8), if (keyboardVisible) dp(5) else bars.bottom + dp(5))
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        status = TextView(this).apply {
            text = "Ready • $assistantDisplayName तैयार है। अपनी जरूरत का एक workspace खोलें।"
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

        // Premium Shraddha AI command cockpit. All live indicators are explicitly
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
            text = assistantDisplayName
            textSize = 24f; setTextColor(0xFFF4F6FF.toInt())
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
            radius = dp(28).toFloat(); cardElevation = dp(8).toFloat()
            strokeWidth = dp(1); strokeColor = 0xFF6357B9.toInt()
            setCardBackgroundColor(0xFF11182C.toInt())
        }
        val heroBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF29264F.toInt(), 0xFF17243A.toInt(), 0xFF0D1728.toInt())
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
            text = "Your AI.\nYour universe."
            textSize = 27f; setTextColor(-1); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(6))
        })
        intro.addView(TextView(this).apply {
            text = "VOICE  •  VISION  •  MEMORY  •  ACTION"
            textSize = 11f; setTextColor(0xFFB9C5DD.toInt())
        })
        heroTop.addView(intro)
        heroTop.addView(ShraddhaOrbView(this), LinearLayout.LayoutParams(dp(124), dp(124)))
        heroBody.addView(heroTop)
        heroBody.addView(actionButton("✦  START A CONVERSATION", "Hindi / English • Tap to speak") { startVoiceInput() })
        pageRoot.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(13) })

        section(pageRoot, "LIVE WORKSPACE", "No active task") {
            addView(TextView(this@MainActivity).apply {
                text = "◉  WAITING FOR AN ACTIVE TASK"
                textSize = 11f; letterSpacing = .06f; setTextColor(0xFF79DCCB.toInt())
                setPadding(dp(12), dp(13), dp(12), dp(13))
                background = rounded(0xFF0D2027.toInt(), 14)
            })
            addView(actionButton("OPEN APP OPERATOR", "View and approve app actions") {
                startActivity(Intent(this@MainActivity, `in`.shraddha.ai.operator.OperatorActivity::class.java))
            })
        }

        workspaceGrid(pageRoot)
        // Connection state appears only in its workspace when a real result is available.
    }

    private fun workspaceTile(title: String, subtitle: String, action: () -> Unit): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(112)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1C2945.toInt(), 0xFF111A2C.toInt(), 0xFF0B1422.toInt())
            ).apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xFF394B70.toInt()) }
            elevation = dp(4).toFloat()
            isClickable = true; isFocusable = true
            foreground = android.util.TypedValue().let {
                val out = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                if (out.resourceId != 0) ContextCompat.getDrawable(this@MainActivity, out.resourceId) else null
            }
            contentDescription = "$title. $subtitle"
            setOnClickListener { action() }
        }
        tile.addView(TextView(this).apply {
            text = title; textSize = 13f; letterSpacing = .01f
            maxLines = 2; setTextColor(0xFFF1F3FF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        tile.addView(TextView(this).apply {
            text = subtitle; textSize = 10.5f; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f); setTextColor(0xFFAAB8D0.toInt())
            setPadding(0, dp(8), 0, 0)
        })
        return tile
    }

    private fun workspaceGrid(root: LinearLayout) {
        section(root, "WORKSPACES", "") {
            val items = listOf(
                Triple("✦  AI BRAIN + VOICE", "Chat • Voice • Memory", { showPage("assistant") }),
                Triple("◉  3D VOICE AGENT", "Continuous mic • Orb • Hindi / English", {
                    startActivity(Intent(this@MainActivity, com.Lia.assistant.MainActivity::class.java))
                }),
                Triple("◈  WINDOWS PC", "Pair • Screen • Control", { showPage("devices") }),
                Triple("☎  PHONE CALL AGENT", "SIM no-answer → AI voice bridge", { showPage("phone") }),
                Triple("◈  EXPERT STUDIO", "Research • Expert answers", { showPage("expert") }),
                Triple("⌘  DEVICES", "Phones • PC • Pairing", { showPage("devices") }),
                Triple("▣  APP OPERATOR", "Observe • Approve • Verify", { startActivity(Intent(this@MainActivity, `in`.shraddha.ai.operator.OperatorActivity::class.java)) }),
                Triple("◇  PROJECT LAB", "Agents • Code • Apps", { showPage("lab") }),
                Triple("◎  PEOPLE MEMORY", "Consented profiles", { showPage("people") }),
                Triple("☁  CLOUD & DATA", "Backup • Sync • Sharing", { showPage("cloud") }),
                Triple("⚙  PRIVACY", "Permissions • Security", { showPage("permissions") }),
                Triple("✧  INTELLIGENCE", "Automation • Safe workflows", { showPage("future") }),
                Triple("◷  TASK PLANNER", "Tasks • Daily briefing • Reminders", { startActivity(Intent(this@MainActivity, `in`.shraddha.ai.future.TaskPlannerActivity::class.java)) })
            )
            val search = edit("Search workspaces", "Try voice, devices, people or privacy")
            search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addView(search)
            val grid = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(grid)

            fun render(query: String) {
                grid.removeAllViews()
                val filtered = items.filter {
                    query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true)
                }
                if (filtered.isEmpty()) {
                    grid.addView(TextView(this@MainActivity).apply {
                        text = "No matching workspace"
                        textSize = 13f
                        setTextColor(0xFFAAB8D0.toInt())
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, dp(18), 0, dp(18))
                    })
                    return
                }
                filtered.chunked(2).forEach { pair ->
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.TOP
                    }
                    pair.forEachIndexed { index, item ->
                        val cell = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                                if (index == 0) rightMargin = dp(6) else leftMargin = dp(6)
                                bottomMargin = dp(10)
                            }
                        }
                        cell.addView(workspaceTile(item.first, item.second, item.third), LinearLayout.LayoutParams(-1, -2))
                        row.addView(cell)
                    }
                    if (pair.size == 1) row.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    grid.addView(row)
                }
            }
            render("")
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty().trim())
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
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
                text = "${assistantDisplayName.uppercase()}  /  PERSONAL AI"
                textSize = 11f; letterSpacing = .14f; gravity = android.view.Gravity.CENTER
                setTextColor(0xFFBFAEFF.toInt()); setTypeface(null, android.graphics.Typeface.BOLD)
            })
            assistantHero.addView(actionButton("🎙  Speak to $assistantDisplayName", "Hindi / English • आपके बोलने पर ही voice recognition शुरू होगा") { startVoiceInput() })
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
            "phone" -> {
                if (!::relayUrl.isInitialized) relayUrl = edit("Cloud Relay URL", "https://your-domain.example")
                if (!::gatewayToken.isInitialized) gatewayToken = edit("Gateway token", "Server GATEWAY_TOKEN")
                addTelephonySection(pageRoot)
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
            "kids" -> addKidsUniverse(pageRoot)
            "people" -> addPersonMemory(pageRoot)
            "cloud" -> addFirebaseCloudSection(pageRoot)
            "future" -> addFutureIntelligence(pageRoot)
            "permissions" -> section(pageRoot, "APP CONTROL SETUP", "Android permissions को अपनी इच्छा से चालू करें") {
                addView(actionButton("Allow Contact Lookup", "केवल voice call/message के समय contact नाम और number खोजने की अनुमति") {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.READ_CONTACTS), 45)
                    } else status.text = "Contact lookup permission पहले से enabled है।"
                })
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
            var lastTitle = "$assistantDisplayName Project"
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
                startActivity(Intent.createChooser(share, "Export $assistantDisplayName work"))
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
                "location" to "Location", "device_status" to "Device Status", "ai_memory" to "AI Memory",
                "screen" to "Live Screen Sharing", "camera" to "Live Camera Sharing"
            )
            categories.forEach { (key, label) ->
                val toggle = Switch(this@MainActivity).apply {
                    text = label; setTextColor(-1)
                    isChecked = getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean(key, false)
                    setOnCheckedChangeListener { _, checked ->
                        getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).edit().putBoolean(key, checked).apply()
                        if (!checked && key == "screen") {
                            stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                            status.text = "Screen sharing stopped because its permission was switched off."
                        }
                        if (!checked && key == "camera") {
                            stopService(Intent(this@MainActivity, RemoteCameraService::class.java))
                            status.text = "Camera sharing stopped because its permission was switched off."
                        }
                        publishDataPermissions()
                    }
                }
                dataPermissionSwitches[key] = toggle
                addView(toggle)
            }
            addView(actionButton("Publish My Sharing Permissions", "दूसरे paired devices को मेरी चुनी अनुमति बताएँ") { publishDataPermissions() })
            val shareableCategories = categories.filter { it.first !in setOf("screen", "camera") }
            val categoryPicker = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, shareableCategories.map { it.first })
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
            status.text = "पहले Control & Privacy में Live Camera Sharing की अनुमति ON करें।"
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
                .setPositiveButton("Allow for 5 minutes") { _, _ ->
                    val prefs = getSharedPreferences("shraddha_device_link", MODE_PRIVATE)
                    val granted = DeviceLinkManager.respondRemoteControl(from, true, 5)
                    if (granted) {
                        prefs.edit()
                            .putString("remote_control_grant_peer", from)
                            .putLong("remote_control_grant_expires_at", System.currentTimeMillis() + 5 * 60 * 1000L)
                            .apply()
                        status.text = "इस paired device को 5 मिनट के लिए remote control की अनुमति दी गई है।"
                    } else {
                        prefs.edit().remove("remote_control_grant_peer").remove("remote_control_grant_expires_at").apply()
                        status.text = "Remote control अनुमति relay तक नहीं पहुँच सकी।"
                    }
                }
                .setOnCancelListener { DeviceLinkManager.respondRemoteControl(from, false) }
                .show()
        }
    }

    private fun addTelephonySection(root: LinearLayout) {
        section(root, "INCOMING SIM CALL → SHRADDHA AI", "आपका सामान्य SIM पहले बजेगा। Jio का no-answer forwarding Edesy DID पर जाने के बाद AI कॉल संभालेगा।") {
            addView(relayUrl)
            addView(gatewayToken)
            val bridgeStatus = TextView(this@MainActivity).apply {
                text = "AI call bridge status अभी जाँचा नहीं गया।"
                textSize = 13f
                setTextColor(0xFFB9C8E6.toInt())
                setPadding(0, dp(10), 0, dp(10))
            }
            addView(bridgeStatus)
            addView(actionButton("Check Edesy + GPT-Live readiness", "Backend configuration पढ़ें; कोई call या forwarding चालू नहीं होगी") {
                val url = relayUrl.text.toString().trim()
                val token = gatewayToken.text.toString().trim()
                DeviceLinkManager.telephonyStatus(url, token) { result -> runOnUiThread {
                    result.onSuccess { body ->
                        val configured = body.optBoolean("configured")
                        val caps = body.optJSONObject("capabilities")
                        bridgeStatus.text = buildString {
                            append(if (configured) "Server secrets configured." else "Server secrets missing.")
                            append("\nInbound WebSocket: ").append(if (caps?.optBoolean("inboundWebSocketAudio") == true) "ready" else "not ready")
                            append("\nGPT-Live audio bridge: ").append(if (caps?.optBoolean("liveBidirectionalAiAudio") == true) "ready" else "not ready")
                            append("\nTranscript capture: ").append(if (caps?.optBoolean("liveTranscripts") == true) "ON" else "OFF")
                            append("\nAudio recording: NOT stored")
                            append("\nReal Edesy DID / two-way phone test: still required")
                        }
                    }.onFailure { bridgeStatus.text = "Status check failed: ${it.message}" }
                } }
            })
            addView(actionButton("Refresh recent AI calls", "Backend की आखिरी 30 call entries दिखाएँ") {
                DeviceLinkManager.telephonyCalls(relayUrl.text.toString().trim(), gatewayToken.text.toString().trim()) { result -> runOnUiThread {
                    result.onSuccess { body ->
                        val calls = body.optJSONArray("calls")
                        bridgeStatus.text = if (calls == null || calls.length() == 0) {
                            "अभी कोई AI call history नहीं है।"
                        } else buildString {
                            for (i in 0 until minOf(calls.length(), 10)) {
                                val call = calls.optJSONObject(i) ?: continue
                                append(call.optString("startedAt", "Unknown time"))
                                append(" • ").append(call.optString("caller", "Unknown caller"))
                                append(" • ").append(call.optString("status", "unknown"))
                                if (call.optString("summaryStatus") == "ready") append("\nSummary: ").append(call.optString("summary").take(240))
                                append("\n")
                            }
                        }
                    }.onFailure { bridgeStatus.text = "Call history load failed: ${it.message}" }
                } }
            })
            addView(TextView(this@MainActivity).apply {
                text = "महत्वपूर्ण: केवल Jio no-answer forwarding उपयोग करें। Unconditional forwarding चालू न करें। पहले Edesy DID पर वास्तविक दो-तरफ़ा call, AI की शुरुआती पहचान, hang-up और interruption जाँचें। उसके बाद ही Jio से current forwarding code/delay/charges की पुष्टि करें। Android सामान्य SIM call का audio सीधे AI को नहीं दे सकता।"
                textSize = 12f
                setTextColor(0xFFFFC78A.toInt())
                setPadding(0, dp(12), 0, 0)
            })
        }
    }

    private fun addDeviceLinkSection(root: LinearLayout) {
        section(root, "01  •  WORLDWIDE DEVICE LINK", "Main Android phone यहाँ session बनाएगा; Windows PC Companion या दूसरा phone इसी HTTPS relay और pairing code से join कर सकता है।") {
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
            addView(TextView(this@MainActivity).apply {
                text = "WINDOWS PC: $assistantDisplayName PC Companion.exe चलाएँ, यही HTTPS relay URL और ऊपर बना 6-digit code डालें। PC पर remote control और screen sharing अलग-अलग opt-in हैं; हर control request PC पर approve करनी होगी।"
                setTextColor(0xFFB9C8E6.toInt()); textSize = 12f; setPadding(0, dp(8), 0, dp(10))
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
                    if (!checked) {
                        getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit()
                            .remove("remote_control_grant_peer")
                            .remove("remote_control_grant_expires_at")
                            .apply()
                    }
                    if (DeviceLinkManager.sessionId != null) publishDataPermissions()
                }
            }
            addView(remoteConsent)
            addView(actionButton("Revoke active remote-control approval", "किसी भी समय 5-minute device permission तुरंत बंद करें") {
                getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit()
                    .remove("remote_control_grant_peer")
                    .remove("remote_control_grant_expires_at")
                    .apply()
                status.text = "Active remote-control approval revoked on this phone."
            })
            addView(actionButton("Revoke Selected Paired Device", "यह device relay session से हटेगा; owner permission जरूरी है") {
                val target = targetDevice.text.toString().trim()
                if (target.isBlank()) { status.text = "पहले paired device चुनें।"; return@actionButton }
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Revoke paired device?")
                    .setMessage("Device $target की pairing और उससे जुड़ी active permissions तुरंत हटाई जाएँगी।")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Revoke") { _, _ ->
                        DeviceLinkManager.revokeRemoteDevice(target) { result -> runOnUiThread {
                            result.onSuccess { ok -> status.text = if (ok) "Selected device revoked from relay session." else "Relay ने device revoke नहीं किया।" }
                                .onFailure { status.text = "Device revoke failed: ${it.message}" }
                        } }
                    }.show()
            })
            addView(actionButton("Revoke This Phone Pairing / Session", "Owner पूरे session को बंद करेगा; member केवल अपनी pairing हटाएगा") {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Revoke this phone pairing?")
                    .setMessage("इस phone की relay pairing तुरंत हटेगी। अगर यह owner phone है, तो पूरा paired-device session बंद होगा।")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Revoke pairing") { _, _ ->
                        DeviceLinkManager.revokeCurrentPairing { result -> runOnUiThread {
                            result.onSuccess { ok -> status.text = if (ok) "This phone pairing/session revoked." else "Relay ने pairing revoke नहीं की।" }
                                .onFailure { status.text = "Pairing revoke failed: ${it.message}" }
                        } }
                    }.show()
            })
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
            addView(actionButton("Send Remote Home", "फोन की Home screen पर जाएँ") { sendRemote(JSONObject().put("kind", "home")) })
            addView(actionButton("Send Remote Back", "पिछली screen पर जाएँ") { sendRemote(JSONObject().put("kind", "back")) })
            addView(actionButton("Disconnect This Phone", "इस device का live connection बंद करें और active control approval revoke करें") {
                getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit()
                    .remove("remote_control_grant_peer")
                    .remove("remote_control_grant_expires_at")
                    .apply()
                DeviceLinkManager.disconnect()
                status.text = "This phone disconnected; active remote-control approval revoked."
            })
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
            addView(actionButton("OPEN CAMERA • VISUAL LENS", "Full-resolution photo लें; AI को भेजना अलग से शुरू होगा") {
                try {
                    val folder = java.io.File(cacheDir, "shared").apply { mkdirs() }
                    pendingVisionPhoto = java.io.File.createTempFile("shraddha-visual-lens-", ".jpg", folder)
                    val photoUri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.lia.fileprovider", pendingVisionPhoto!!
                    )
                    val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri)
                        clipData = android.content.ClipData.newUri(contentResolver, "Visual Lens photo", photoUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    startActivityForResult(cameraIntent, localVisionRequest)
                } catch (error: Exception) {
                    pendingVisionPhoto?.delete()
                    pendingVisionPhoto = null
                    status.text = "Camera start failed: ${error.message}"
                }
            })
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
        section(root, "SECURITY AUDIT TRAIL", "इस फोन पर सीमित metadata log; passwords, API keys, screen recordings या biometric data log नहीं होते") {
            val audit=`in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity)
            addView(actionButton("View local audit log","Recent workflow events") {
                val auditLog: String = audit.exportText().getOrElse { error -> "Audit log unavailable: ${error.message ?: "encryption error"}" }
                val dialog = android.app.AlertDialog.Builder(this@MainActivity)
                dialog.setTitle("Security Audit Trail")
                dialog.setMessage(if (auditLog.length == 0) "अभी कोई audit event नहीं है।" else auditLog)
                dialog.setPositiveButton("OK", null)
                dialog.setNeutralButton("Clear") { _: android.content.DialogInterface, _: Int ->
                    audit.clear()
                    status.text = "Audit log cleared."
                }
                dialog.show()
            })
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
        section(root, "PERSONAL KNOWLEDGE GRAPH", "आपकी अनुमति से जोड़ी गई जानकारी Android Keystore के AES-GCM encrypted local vault में सुरक्षित रहती है।") {
            val graphStore = `in`.shraddha.ai.future.PersistentKnowledgeGraph(this@MainActivity)
            val initialLoad = graphStore.load()
            var graph = initialLoad.getOrElse { `in`.shraddha.ai.future.KnowledgeGraph() }
            var storageReady = initialLoad.isSuccess
            val label = edit("Memory title", "उदाहरण: मेरी दुकान")
            val kind = edit("Category", "Project / Person / Device / Place")
            val note = edit("Memory note", "इस जानकारी को याद रखने के लिए लिखें").apply { minLines = 2; isSingleLine = false }
            val query = edit("Search saved memories", "दुकान, project, device...")
            val sourceId = edit("Relationship source ID", "पहले node की ID")
            val relation = edit("Relationship", "जैसे works_on / owns / located_at")
            val targetId = edit("Relationship target ID", "दूसरे node की ID")
            val deleteId = edit("Delete memory ID", "केवल चुनी हुई node ID")
            val graphResult = TextView(this@MainActivity).apply {
                setTextColor(0xFFE4E9F5.toInt()); textSize = 12f
                setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF0C1525.toInt(), 14)
            }
            addView(label); addView(kind); addView(note)
            addView(query); addView(sourceId); addView(relation); addView(targetId); addView(deleteId); addView(graphResult)

            fun renderGraph(message: String = "") {
                val nodes = graph.allNodes()
                val edges = graph.allEdges()
                val lines = buildList {
                    if (message.isNotBlank()) add(message)
                    add("Encrypted local memories: ${nodes.size}/100 • Relationships: ${edges.size}/300")
                    nodes.forEach { add("ID: ${it.id}\n${it.label} [${it.kind}]\n${it.note}") }
                    edges.forEach { add("LINK: ${it.fromId} —${it.relation}→ ${it.toId}") }
                }
                graphResult.text = lines.joinToString("\n\n")
            }
            renderGraph(if (storageReady) "Encrypted knowledge graph loaded." else "Vault could not be decrypted/read. Existing data is protected; clear/reset is required before writing.")
            addView(actionButton("Save encrypted knowledge node", "Explicitly user-entered memory • AES-GCM • Android Keystore") {
                if (!storageReady) { graphResult.text = "Memory save रोक दिया गया: पुराना encrypted vault पढ़ा नहीं जा सका। पहले कारण ठीक करें या नीचे से स्पष्ट रूप से graph reset करें।"; return@actionButton }
                val title = label.text.toString().trim()
                if (title.isBlank()) { graphResult.text = "Memory title जरूरी है।"; return@actionButton }
                val id = "n-" + java.util.UUID.randomUUID().toString()
                runCatching {
                    graph.upsert(`in`.shraddha.ai.future.KnowledgeNode(id, title, kind.text.toString().trim().ifBlank { "Note" }, note.text.toString().take(1000)))
                    graphStore.save(graph).getOrThrow()
                    `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("knowledge_node", "saved", title)
                    renderGraph("Encrypted memory saved. New node ID: $id")
                    label.setText(""); note.setText("")
                }.onFailure { graphResult.text = "Encrypted save failed: ${it.message}" }
            })
            addView(actionButton("Search knowledge graph", "Search titles, categories and notes in encrypted local memory") {
                val q = query.text.toString().trim()
                val hits = graph.allNodes().filter { q.isBlank() || it.label.contains(q, true) || it.kind.contains(q, true) || it.note.contains(q, true) }
                    .sortedByDescending { it.updatedAt }
                graphResult.text = if (hits.isEmpty()) "कोई matching memory नहीं मिली।" else hits.joinToString("\n\n") { "ID: ${it.id}\n${it.label} [${it.kind}]\n${it.note}\nLinks: ${graph.linksFor(it.id).joinToString { edge -> "${edge.relation} → ${if (edge.fromId == it.id) edge.toId else edge.fromId}" }}" }
            })
            addView(actionButton("Connect two memories", "Explicit relationship • दोनों node ID ऊपर से चुनें") {
                if (!storageReady) { graphResult.text = "Encrypted vault उपलब्ध नहीं है।"; return@actionButton }
                runCatching {
                    graph.connect(sourceId.text.toString().trim(), relation.text.toString().trim(), targetId.text.toString().trim())
                    graphStore.save(graph).getOrThrow()
                    `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("knowledge_edge", "saved", relation.text.toString().take(80))
                    renderGraph("Relationship encrypted and saved.")
                }.onFailure { graphResult.text = "Relationship save failed: ${it.message}" }
            })
            addView(actionButton("Delete selected memory", "Node और उससे जुड़े relationships स्थायी रूप से हटेंगे") {
                if (!storageReady) { graphResult.text = "Encrypted vault उपलब्ध नहीं है।"; return@actionButton }
                val id = deleteId.text.toString().trim()
                if (graph.allNodes().none { it.id == id }) { graphResult.text = "इस ID की memory नहीं मिली।"; return@actionButton }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Memory delete करें?")
                    .setMessage("यह node और उससे जुड़े links हटेंगे। यह काम वापस नहीं होगा।")
                    .setNegativeButton("रद्द करें", null)
                    .setPositiveButton("Delete") { _, _ ->
                        graph.remove(id)
                        graphStore.save(graph).fold(
                            { `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("knowledge_node", "deleted", id); renderGraph("Memory deleted.") },
                            { graphResult.text = "Delete save failed: ${it.message}" }
                        )
                    }.show()
            })
            addView(actionButton("Reset encrypted knowledge graph", "सभी nodes और relationships हटाएँ") {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("पूरा Knowledge Graph मिटाएँ?")
                    .setMessage("सभी local encrypted memories और links स्थायी रूप से हट जाएँगे।")
                    .setNegativeButton("रद्द करें", null)
                    .setPositiveButton("सभी हटाएँ") { _, _ ->
                        if (graphStore.clear()) {
                            graph = `in`.shraddha.ai.future.KnowledgeGraph()
                            storageReady = true
                            `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("knowledge_graph", "cleared")
                            renderGraph("Encrypted knowledge graph reset.")
                        } else graphResult.text = "Vault clear नहीं हो पाया।"
                    }.show()
            })
        }
        addAdvancedFeatureStudios(root)
        section(root, "FUTURE EXPANSION PRIORITIES", "वे क्षमताएँ जिनके लिए external runtime, verified models या companion integration अभी बाकी है") {
            listOf("1. On-device face and speaker identity models with consent, anti-spoofing and accuracy validation", "2. Native offline LLM inference runtime and hardware-aware model manager", "3. Signed plugin packages with real sandbox isolation and revocation", "4. Durable project execution, resumable tasks and verified rollback", "5. Complete PC screen-streaming companion and resumable task integration", "6. Smart-home connectors with local-first allowlists and manual override", "7. Actual image, video, audio and CAD generation/export pipelines", "8. Full encrypted backup restore including supported encrypted vault data", "9. Research retrieval with live source citations and verification", "10. Automated device test runner and global emergency access revocation").forEach { item -> addView(TextView(this@MainActivity).apply { text=item; textSize=12f; setTextColor(0xFFDCE5F6.toInt()); setPadding(0,dp(5),0,dp(5)) }) }
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
                val suggestions=`in`.shraddha.ai.future.PredictiveSuggestionEngine.suggest(enabled.isChecked,events.text.toString().split(Regex("[,\\n]")))
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
                        val pi=android.app.PendingIntent.getBroadcast(this@MainActivity,id,Intent(this@MainActivity,`in`.shraddha.ai.future.ReminderReceiver::class.java).putExtra("title",reminderTitle.text.toString().ifBlank { "$assistantDisplayName reminder" }).putExtra("note",reminderNote.text.toString()),android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
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
            addView(actionButton("EXPORT PASSWORD-PROTECTED BACKUP", "AES-256-GCM + PBKDF2 • selected memory, tasks, project and preferences") {
                val password = EditText(this@MainActivity).apply {
                    hint = "Backup passphrase (12+ characters)"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                val confirm = EditText(this@MainActivity).apply {
                    hint = "Confirm passphrase"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                val panel = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
                    addView(password); addView(confirm)
                    addView(TextView(this@MainActivity).apply {
                        text = "API keys, Firebase passwords, biometrics and device-pairing secrets are excluded. Keep the passphrase safe; it cannot be recovered."
                        textSize = 12f; setTextColor(0xFFB8C5DA.toInt())
                    })
                }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Create encrypted backup").setView(panel)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Encrypt & choose file") { _, _ ->
                        val pass = password.text.toString()
                        if (pass.length < 12 || pass != confirm.text.toString()) {
                            status.text = "Passphrase कम-से-कम 12 अक्षर का हो और दोनों fields समान हों।"; return@setPositiveButton
                        }
                        status.text = "Backup encrypt हो रहा है…"
                        val passphrase = pass.toCharArray()
                        Thread {
                            val result = runCatching { UserBackupManager(this@MainActivity).export(passphrase) }
                            runOnUiThread {
                                result.onSuccess { envelope ->
                                    getSharedPreferences("shraddha_backup_pending", MODE_PRIVATE).edit().putString("payload", envelope).apply()
                                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                                        putExtra(Intent.EXTRA_TITLE, "shraddha-ai-encrypted-backup-" + System.currentTimeMillis() + ".json")
                                    }
                                    @Suppress("DEPRECATION")
                                    startActivityForResult(intent, backupFileRequest)
                                }.onFailure { status.text = "Backup encryption failed: " + it.message }
                            }
                        }.start()
                    }.show()
            })
            addView(actionButton("IMPORT ENCRYPTED BACKUP", "Decrypt locally; selected app data will be replaced only after your confirmation") {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, backupImportRequest)
            })
        }
        section(root, "OFFLINE MODEL VAULT STATUS", "Model integrity verifier तैयार है; model execution के लिए native inference runtime, supported model format और device-memory benchmark अभी आवश्यक हैं।") {
            addView(TextView(this@MainActivity).apply { text="SHA-256 verification: available in local engine.\nModel loading / inference: not enabled.\nNo model is silently downloaded or executed."; setTextColor(0xFFDCE5F6.toInt()); textSize=12f; setPadding(dp(12),dp(12),dp(12),dp(12)); background=rounded(0xFF0C1525.toInt(),14) })
        }
    }

    private fun addFirebaseCloudSection(root: LinearLayout) {
        section(root, "FIREBASE ACCOUNT & ENCRYPTED CLOUD VAULT", "Firebase configuration पहले से भरी है। Email/Password Auth, Firestore और Realtime Database चालू रखें; encrypted sync केवल आपकी सहमति से होगा।") {
            val firebaseApiKey = edit("Firebase API Key", "AIza…").apply { setText(BuildConfig.FIREBASE_API_KEY) }
            val firebaseAppId = edit("Firebase Android App ID", "Android App ID").apply { setText(BuildConfig.FIREBASE_APP_ID) }
            val firebaseProjectId = edit("Firebase Project ID", "project-id").apply { setText(BuildConfig.FIREBASE_PROJECT_ID) }
            val firebaseSenderId = edit("Firebase Messaging Sender ID", "sender-id").apply { setText(BuildConfig.FIREBASE_SENDER_ID) }
            val firebaseBucket = edit("Storage bucket (optional)", "project.firebasestorage.app").apply { setText(BuildConfig.FIREBASE_STORAGE_BUCKET) }
            val firebaseDatabaseUrl = edit("Realtime Database URL", "https://project-default-rtdb.firebaseio.com").apply { setText(BuildConfig.FIREBASE_DATABASE_URL) }
            addView(firebaseApiKey); addView(firebaseAppId); addView(firebaseProjectId); addView(firebaseSenderId); addView(firebaseBucket); addView(firebaseDatabaseUrl)
            val email = edit("Email", "name@example.com")
            val password = edit("Firebase account password", "Account sign-in password").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val backupPassphrase = edit("Separate backup encryption passphrase (12+ characters)", "Keep this safe; needed to restore on another device").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
            val note = edit("Cloud memory note", "यह note encrypted रूप में sync होगा").apply { minLines = 3; isSingleLine = false }
            val consent = CheckBox(this@MainActivity).apply { text = "मैं इस note/local conversation recap को अपने Firebase खाते में encrypted रूप में sync करने की अनुमति देता/देती हूँ।"; setTextColor(0xFFE4E9F5.toInt()) }
            val result = TextView(this@MainActivity).apply { setTextColor(0xFFBFE8D2.toInt()); textSize = 13f }
            addView(email); addView(password); addView(backupPassphrase); addView(note)
            addView(TextView(this@MainActivity).apply {
                text = "पुराने backup को restore करते समय, यदि अलग backup passphrase नहीं बनाया था, तो पुराने account password को backup passphrase field में भी भरें। नए backup के लिए अलग passphrase रखें।"
                setTextColor(0xFFFFC78A.toInt()); textSize = 12f; setPadding(0, dp(4), 0, dp(8))
            })
            addView(actionButton("Load Encrypted Local Conversation Recap", "Requires Encrypted Conversation Memory to be ON") {
                val recap = PersistentChatMemory.recap(this@MainActivity)
                if (recap.isNullOrBlank()) result.text = "No saved recap found. Enable Encrypted Conversation Memory and use Shraddha first."
                else { note.setText(recap); result.text = "Local encrypted recap loaded into this draft. Review before syncing." }
            })
            addView(consent); addView(result)
            addView(actionButton("Save Firebase Configuration", "Client config is public; never paste service-account private keys") {
                val configured = `in`.shraddha.ai.cloud.FirebaseCloudVault.configure(
                    this@MainActivity, firebaseApiKey.text.toString(), firebaseAppId.text.toString(),
                    firebaseProjectId.text.toString(), firebaseSenderId.text.toString(), firebaseBucket.text.toString(), firebaseDatabaseUrl.text.toString()
                )
                result.text = configured.fold(
                    { "Firebase config saved. You can now create/sign in to your account. If a previous Firebase config was already initialized, restart the app first." },
                    { "Firebase config error: ${it.message}" }
                )
            })
            addView(actionButton("Create Firebase Account", "Email/password authentication") {
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Valid email और कम-से-कम 8 अक्षर का password दें।"; return@actionButton }
                val emailValue = email.text.toString().trim(); val passwordValue = password.text.toString()
                result.text = "Firebase account बनाया जा रहा है…"
                Thread { val r = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity).createAccount(emailValue, passwordValue)
                    runOnUiThread {
                        password.text.clear()
                        result.text = r.fold({ "Account created: ${it}" }, { "Firebase error: ${it.message}" })
                    }
                }.start()
            })
            addView(actionButton("Sign In & Sync Encrypted Memory", "Cloud में केवल encrypted payload जाएगा; password/biometric data upload नहीं होगा") {
                if (!consent.isChecked) { result.text = "पहले cloud sync consent checkbox चुनें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8 || backupPassphrase.text.length < 12 || note.text.isNullOrBlank()) { result.text = "Email, account password, 12+ character backup passphrase और memory note भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim(); val passwordValue = password.text.toString(); val backupPassphraseValue = backupPassphrase.text.toString(); val noteValue = note.text.toString()
                result.text = "Sign-in और encrypted upload चल रहा है…"
                Thread { val r = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity).signInAndSave(emailValue, passwordValue, "personal_memory", noteValue, backupPassphraseValue)
                    runOnUiThread {
                        password.text.clear()
                        backupPassphrase.text.clear()
                        result.text = r.fold({ "Encrypted cloud backup saved for account ${it}." }, { "Sync failed: ${it.message}" })
                    }
                }.start()
            })
            addView(actionButton("Restore Encrypted Memory", "उसी email और password से दूसरे device पर restore करें") {
                if (!consent.isChecked) { result.text = "Restore से पहले cloud consent चुनें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8 || backupPassphrase.text.length < 12) { result.text = "Email, account password और backup passphrase भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim(); val passwordValue = password.text.toString(); val backupPassphraseValue = backupPassphrase.text.toString()
                result.text = "Encrypted backup download और decrypt हो रहा है…"
                Thread { val r = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity).signInAndLoad(emailValue, passwordValue, "personal_memory", backupPassphraseValue)
                    runOnUiThread {
                        password.text.clear()
                        backupPassphrase.text.clear()
                        result.text = r.fold({ value ->
                            if (value == null) "इस account में backup नहीं मिला।"
                            else PersistentChatMemory.restore(this@MainActivity, value).fold(
                                { "Encrypted recap इस phone की local memory में restore हुआ। Voice session active हो तो नया context load होगा." },
                                { "Local restore failed: ${it.message}" }
                            )
                        }, { "Restore failed: ${it.message}" })
                    }
                }.start()
            })
            addView(TextView(this@MainActivity).apply { text = "सुरक्षा: Firebase account password केवल sign-in के लिए है; backup encryption अलग 12+ character passphrase से होती है। Backup passphrase याद रखें—इसके बिना cloud ciphertext restore नहीं होगा। Passphrase Firebase को नहीं भेजा जाता। Face/voice biometric templates इस backup में शामिल नहीं हैं।"; setTextColor(0xFF9EABC2.toInt()); textSize = 12f; setPadding(0, dp(10), 0, 0) })
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

    private fun addPersonMemory(root: LinearLayout) {
        val store = EncryptedFeatureStore(this)
        val personName = edit("Person name", "Example: Rahul")
        val relation = edit("Relationship / introduction", "Friend, family, colleague…")
        val notes = edit("Optional notes", "Only information you are allowed to store")
        val consent = Switch(this).apply { text = "इस व्यक्ति की स्पष्ट सहमति प्राप्त है"; setTextColor(-1); textSize = 13f; setPadding(0, dp(8), 0, dp(10)) }
        section(root, "PERSON DIRECTORY", "परिचित व्यक्तियों के consent-based profiles • encrypted, local-only") {
            addView(personName); addView(relation); addView(notes); addView(consent)
            addView(actionButton("Save Person Profile", "सहमति के बिना सेव नहीं होगा") {
                val name = personName.text.toString().trim()
                if (name.isBlank() || !consent.isChecked) { status.text = "नाम और व्यक्ति की स्पष्ट सहमति आवश्यक है।"; return@actionButton }
                val arr = runCatching { JSONArray(store.get("person_directory").getOrDefault("")) }.getOrElse { JSONArray() }
                arr.put(JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("name", name.take(80)).put("relation", relation.text.toString().take(100)).put("notes", notes.text.toString().take(500)).put("consent", true).put("createdAt", System.currentTimeMillis()))
                store.put("person_directory", arr.toString()).onSuccess { status.text = "Person profile सुरक्षित रूप से सेव हुआ।"; personName.text.clear(); relation.text.clear(); notes.text.clear(); consent.isChecked = false }.onFailure { status.text = "Save error: ${it.message}" }
            })
            addView(actionButton("View Saved People", "परिचय और प्रोफाइल देखें") {
                val arr = runCatching { JSONArray(store.get("person_directory").getOrDefault("")) }.getOrElse { JSONArray() }
                val rows = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { "${it.optString("name")} — ${it.optString("relation")}\n${it.optString("notes")}" } }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Person Memory").setMessage(if (rows.isEmpty()) "कोई प्रोफाइल सेव नहीं है।" else rows.joinToString("\n\n")).setPositiveButton("OK", null).setNeutralButton("Delete all") { _, _ ->
                    val cleared = `in`.shraddha.ai.biometric.BiometricConsentVault(store).clearAll()
                    if (cleared.isSuccess) {
                        store.remove("person_directory")
                        status.text = "सभी person profiles और encrypted face/voice templates हटाए गए।"
                    } else status.text = "Biometric data पूरी तरह delete नहीं हो सका: ${cleared.exceptionOrNull()?.message}"
                }.show()
            })
        }
        section(root, "ON-DEVICE FACE & VOICE ENROLLMENT", "सहमति-आधारित local capture • मूल फोटो/ऑडियो discard • encrypted template vault") {
            addView(TextView(this@MainActivity).apply { text = "Face enrollment से पहले verified SFace और MiniFASNet model डाउनलोड करें; voice comparison के लिए ECAPA model भी चाहिए। Model files फोन में encrypted template store से अलग local रहती हैं। Blink/head-turn challenge के साथ MiniFASNet photo/screen-replay risk score भी जाँचा जाता है; दोनों supplementary checks हैं, certified anti-spoofing नहीं।"; setTextColor(0xFFFFD7A8.toInt()); textSize = 12f })
            addView(actionButton("Import SFace ONNX Model", "अपना SFace-compatible model चुनें") { pickBiometricModel(faceModelRequest) })
            addView(actionButton("Download verified SFace model • 38.7 MB", "Pinned OpenCV Zoo ONNX • SHA-256 verification • local-only storage") {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Download SFace face model?")
                    .setMessage("यह OpenCV Zoo का SFace ONNX model है। Model directory में Apache-2.0 license दिया गया है। Model weights का training-data provenance model card में पूरी तरह स्पष्ट नहीं है; commercial redistribution से पहले अलग legal review करें। Download लगभग 38.7 MB है; exact size और SHA-256 जाँचे जाएँगे। Face matching similarity-only है, identity/authentication proof नहीं।")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Download and verify") { _, _ ->
                        status.text = "SFace model download शुरू…"
                        `in`.shraddha.ai.biometric.BiometricModelDownloader.downloadFaceModel(this@MainActivity) { result ->
                            runOnUiThread {
                                result.onSuccess { status.text = "SFace model डाउनलोड और SHA-256 verify हो गया (${it.length()} bytes)।" }
                                    .onFailure { status.text = "SFace model download असफल: ${it.message}" }
                            }
                        }
                    }.show()
            })
            addView(actionButton("Download verified MiniFASNet anti-spoof • 1.74 MB", "Apache-2.0 ONNX • SHA-256 verification • local-only storage") {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Download MiniFASNet anti-spoof model?")
                    .setMessage("यह Apache-2.0 licensed MiniFASNet V2 ONNX export है। यह photo/screen-replay के विरुद्ध एक अतिरिक्त passive PAD signal देता है। यह certified/iBeta/ISO 30107-3 evaluated नहीं है, mask/deepfake/injection attacks को नहीं रोकता और अकेले identity authentication के लिए उपयोग नहीं होगा। Model card के अनुसार input BGR 80×80 और classes [print, live, replay], class 1 = real/live हैं। SHA-256 verification के बाद ही local model रखा जाएगा.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Download and verify") { _, _ ->
                        status.text = "MiniFASNet anti-spoof model download शुरू…"
                        `in`.shraddha.ai.biometric.BiometricModelDownloader.downloadAntiSpoofModel(this@MainActivity) { result ->
                            runOnUiThread {
                                result.onSuccess { status.text = "MiniFASNet model डाउनलोड और SHA-256 verify हो गया (${it.length()} bytes)।" }
                                    .onFailure { status.text = "MiniFASNet model download असफल: ${it.message}" }
                            }
                        }
                    }.show()
            })
            addView(actionButton("Import ECAPA ONNX Model", "अपना verified waveform-input compatible model चुनें") { pickBiometricModel(speakerModelRequest) })
            addView(actionButton("Download verified ECAPA model • 42 MB", "Pinned Apache-2.0 ONNX export • SHA-256 verification • local-only storage") {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Download ECAPA speaker model?")
                    .setMessage("यह model SpeechBrain ECAPA-TDNN का ONNX export है। License: Apache-2.0. Training data VoxCeleb 1/2 के English interviews हैं; भाषा, accent, उम्र और recording conditions से accuracy बदल सकती है। यह speaker similarity है, पहचान का प्रमाण या security authentication नहीं। File लगभग 42.3 MB है; download के बाद SHA-256 जाँचा जाएगा और केवल verified file रखी जाएगी।")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Download and verify") { _, _ ->
                        status.text = "ECAPA model download शुरू…"
                        `in`.shraddha.ai.biometric.BiometricModelDownloader.downloadSpeakerModel(this@MainActivity) { result ->
                            runOnUiThread {
                                result.onSuccess { status.text = "ECAPA model डाउनलोड और SHA-256 verify हो गया (${it.length()} bytes)।" }
                                    .onFailure { status.text = "ECAPA model download असफल: ${it.message}" }
                            }
                        }
                    }.show()
            })
            addView(actionButton("Enroll Face Template", "व्यक्ति की अलग biometric सहमति लेकर front camera capture") { launchBiometricEnrollment(personName.text.toString(), consent.isChecked, "face") })
            addView(actionButton("Recognize a saved person • Face match", "Saved face template से live 1:1 comparison • camera पर मौजूद व्यक्ति की अनुमति जरूरी") {
                launchBiometricMatch("face")
            })
            addView(actionButton("Enroll Voice Template", "On-device speech challenge और voice sample capture") { launchBiometricEnrollment(personName.text.toString(), consent.isChecked, "speaker") })
            addView(actionButton("Compare saved voice similarity", "Consent-based local ECAPA comparison • score only, no uncalibrated identity verdict") {
                launchBiometricMatch("speaker")
            })
            addView(actionButton("Delete Person Memory", "सभी profiles और encrypted biometric templates हटाएँ") {
                val arr = runCatching { JSONArray(store.get("person_directory").getOrDefault("")) }.getOrElse { JSONArray() }
                val labels = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { it.optString("name") + " • " + it.optString("id") } }
                if (labels.isEmpty()) { status.text = "कोई person profile नहीं है।"; return@actionButton }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("किस व्यक्ति को हटाएँ?").setItems(labels.toTypedArray()) { _, which ->
                    val selected = arr.optJSONObject(which) ?: return@setItems
                    val id = selected.optString("id")
                    val next = JSONArray()
                    for (i in 0 until arr.length()) if (i != which) next.put(arr.optJSONObject(i))
                    store.put("person_directory", next.toString())
                    `in`.shraddha.ai.biometric.BiometricConsentVault(store).deletePerson(id)
                    status.text = "चुना हुआ profile और biometric templates हटाए गए।"
                }.setNegativeButton("Cancel", null).show()
            })
        }
    }

    private fun pickBiometricModel(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }
        startActivityForResult(intent, requestCode)
    }

    private fun launchBiometricEnrollment(name: String, directoryConsent: Boolean, kind: String) {
        val cleanName = name.trim()
        if (!directoryConsent || cleanName.isBlank()) {
            status.text = "पहले Person Directory में नाम भरें और consent switch चालू करें।"
            return
        }
        val arr = runCatching { JSONArray(EncryptedFeatureStore(this).get("person_directory").getOrDefault("")) }.getOrElse { JSONArray() }
        val profile = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.lastOrNull { it.optString("name") == cleanName }
        val id = profile?.optString("id").orEmpty()
        if (id.isBlank()) { status.text = "पहले इसी नाम का Person Profile save करें।"; return }
        android.app.AlertDialog.Builder(this)
            .setTitle("Biometric consent confirmation")
            .setMessage("क्या $cleanName ने ${if (kind == "face") "face" else "voice"} biometric template इस फोन पर encrypt करके रखने की स्पष्ट अनुमति दी है?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("I have consent") { _, _ ->
                val intent = Intent(this, `in`.shraddha.ai.biometric.BiometricCaptureActivity::class.java)
                    .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_KIND, kind)
                    .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_MODE, "enroll")
                    .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_PERSON_ID, id)
                    .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_PERSON_NAME, cleanName)
                    .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_CONSENT, true)
                startActivity(intent)
            }.show()
    }

    private fun launchBiometricMatch(kind: String) {
        if (kind !in setOf("face", "speaker")) { status.text = "Unsupported biometric comparison type."; return }
        val store = EncryptedFeatureStore(this)
        val arr = runCatching { JSONArray(store.get("person_directory").getOrDefault("")) }.getOrElse { JSONArray() }
        val profiles = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("id").isNotBlank() && it.optString("name").isNotBlank() }
        if (profiles.isEmpty()) { status.text = "पहले People Memory में व्यक्ति का profile और face template save करें।"; return }
        val labels = profiles.map { "${it.optString("name")} • ${it.optString("relation", "Person")}" }
        android.app.AlertDialog.Builder(this)
            .setTitle("किस saved person से मिलान करें?")
            .setItems(labels.toTypedArray()) { _, which ->
                val profile = profiles[which]
                val id = profile.optString("id")
                val name = profile.optString("name")
                android.app.AlertDialog.Builder(this)
                    .setTitle("Live face comparison consent")
                    .setMessage(if (kind == "face") {
                        "क्या $name ने अभी सामने कैमरे पर अपना चेहरा scan करके saved template से compare करने की स्पष्ट अनुमति दी है? SFace का 0.363 cosine cutoff OpenCV का published reference है; इसे इस फोन पर calibrated या authentication-grade न मानें।"
                    } else {
                        "क्या $name ने अभी random phrase बोलकर अपना voice sample saved template से compare करने की स्पष्ट अनुमति दी है? यह raw similarity score देगा; कोई calibrated same-speaker verdict या authentication claim नहीं होगा।"
                    })
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("I have consent") { _, _ ->
                        val intent = Intent(this, `in`.shraddha.ai.biometric.BiometricCaptureActivity::class.java)
                            .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_KIND, kind)
                            .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_MODE, if (kind == "face") "match" else "compare")
                            .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_PERSON_ID, id)
                            .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_PERSON_NAME, name)
                            .putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_CONSENT, true)
                        if (kind == "face") intent.putExtra(`in`.shraddha.ai.biometric.BiometricCaptureActivity.EXTRA_THRESHOLD, 0.363f)
                        startActivity(intent)
                    }.show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun section(root: LinearLayout, title: String, subtitle: String, content: LinearLayout.() -> Unit): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(5).toFloat()
            strokeWidth = dp(1)
            strokeColor = 0xFF2D3D5B.toInt()
            setCardBackgroundColor(0xFF101827.toInt())
            useCompatPadding = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(15))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF151F33.toInt(), 0xFF0E1726.toInt())
            ).apply { cornerRadius = dp(23).toFloat() }
        }
        card.addView(body)
        body.addView(TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = .11f
            setTextColor(0xFFB9A7FF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        if (subtitle.isNotBlank()) body.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(0xFFAAB7CC.toInt())
            setPadding(0, dp(5), 0, dp(10))
        })
        body.content()
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(16)
        })
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
                val revoked = msg.optBoolean("revoked")
                runOnUiThread {
                    if (revoked) {
                        peerIds.remove(peerId)
                        peerNames.remove(peerId)
                        peerOnline.remove(peerId)
                        if (targetDevice.text.toString() == peerId) targetDevice.setText("")
                    } else {
                        if (!peerIds.contains(peerId) && peerId.isNotBlank()) peerIds.add(peerId)
                        if (peerId.isNotBlank()) { peerNames[peerId] = peerName; peerOnline[peerId] = online }
                    }
                    val labels = peerIds.map { id -> "${peerNames[id] ?: "Device"} • ${if (peerOnline[id] == true) "Online" else "Offline"}" }
                    peerPicker.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("No paired devices") })
                    if (targetDevice.text.isNullOrBlank() && peerIds.isNotEmpty()) targetDevice.setText(peerIds.first())
                    status.text = if (revoked) "$peerName pairing revoked by session owner." else "$peerName: ${if (online) "online" else "offline"}"
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
        DeviceLinkManager.onRemoteAction = { fromDeviceId, actionId, action ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val prefs = appContext.getSharedPreferences("shraddha_device_link", MODE_PRIVATE)
                val enabled = prefs.getBoolean("remote_control_consent", false)
                val approvedPeer = prefs.getString("remote_control_grant_peer", null)
                val expiresAt = prefs.getLong("remote_control_grant_expires_at", 0L)
                val grantActive = enabled && fromDeviceId.isNotBlank() &&
                    approvedPeer == fromDeviceId && System.currentTimeMillis() < expiresAt
                if (!grantActive) {
                    if (approvedPeer == fromDeviceId && System.currentTimeMillis() >= expiresAt) {
                        prefs.edit().remove("remote_control_grant_peer").remove("remote_control_grant_expires_at").apply()
                    }
                    status.text = "Remote action blocked: no active approval for this paired device."
                    DeviceLinkManager.respondRemoteAction(actionId, false, "No active local approval for this paired device")
                    return@post
                }

                // A time-limited pairing grant is not blanket consent to execute every
                // command. Require a fresh, action-bound user decision for every command.
                val kind = action.optString("kind", "unknown").take(40)
                val detail = when (kind) {
                    "type" -> "Text entry (content hidden)"
                    "click_text" -> "Tap text: ${action.optString("text").take(80)}"
                    "open_app" -> "Open app: ${action.optString("appName").take(80)}"
                    "tap" -> "Tap screen coordinates"
                    "swipe" -> "Swipe on screen"
                    "home" -> "Go to Home screen"
                    "back" -> "Press Back"
                    "scroll" -> "Scroll screen"
                    else -> "Unsupported action"
                }
                val risk = `in`.shraddha.ai.operator.security.OperatorSafetyPolicy.assess(
                    (if (kind == "type") "TYPE_TEXT" else kind.uppercase()), target = action.optString("text"), value = action.optString("text")
                )
                if (risk.risk == `in`.shraddha.ai.operator.security.OperatorSafetyPolicy.Risk.BLOCK) {
                    status.text = "Remote action blocked by safety policy: ${risk.reason}"
                    DeviceLinkManager.respondRemoteAction(actionId, false, risk.reason)
                    return@post
                }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("$assistantDisplayName • Remote action approval")
                    .setMessage("Device: $fromDeviceId\nAction: $detail\n\nक्या इस एक कार्रवाई की अनुमति दें?")
                    .setNegativeButton("Deny") { _, _ ->
                        status.text = "Remote action denied by you."
                        DeviceLinkManager.respondRemoteAction(actionId, false, "Denied on receiving device")
                    }
                    .setPositiveButton("Allow once") { _, _ ->
                        val stillAuthorized = prefs.getBoolean("remote_control_consent", false) &&
                            prefs.getString("remote_control_grant_peer", null) == fromDeviceId &&
                            System.currentTimeMillis() < prefs.getLong("remote_control_grant_expires_at", 0L)
                        if (!stillAuthorized) {
                            status.text = "Remote action blocked: approval expired before confirmation."
                            DeviceLinkManager.respondRemoteAction(actionId, false, "Approval expired before confirmation")
                        } else {
                            val applied = ShraddhaAccessibilityService.applyRemoteAction(action, userConfirmed = true)
                            status.text = if (applied) "Approved remote action executed." else
                                "Approved action could not be completed; Accessibility service may be unavailable."
                            DeviceLinkManager.respondRemoteAction(actionId, applied, if (applied) "" else "Accessibility service unavailable or action failed")
                        }
                    }
                    .setOnCancelListener {
                        status.text = "Remote action dismissed; no action was executed."
                        DeviceLinkManager.respondRemoteAction(actionId, false, "Approval dialog dismissed")
                    }
                    .show()
            }
        }
        DeviceLinkManager.onRemoteActionResult = { targetId, actionId, ok, reason ->
            runOnUiThread {
                status.text = if (ok) "Remote action verified: $actionId completed on $targetId."
                    else "Remote action failed on $targetId: ${reason.ifBlank { "not completed" }}"
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
        if (!getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean("screen", false)) {
            status.text = "पहले Control & Privacy में Live Screen Sharing की अनुमति ON करें।"
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), screenShareRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 45) {
            status.text = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                "Contact lookup enabled. Shraddha can now search contacts when you ask."
            else "Contact lookup permission denied. You can still speak a phone number directly."
        }
        if (requestCode == 44) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
                getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean("camera", false)) {
                val prefs = getSharedPreferences("shraddha_device_link", MODE_PRIVATE)
                val facing = prefs.getString("pending_camera_facing", "back") ?: "back"
                val target = prefs.getString("pending_camera_target", "").orEmpty()
                ContextCompat.startForegroundService(this, Intent(this, RemoteCameraService::class.java).putExtra(RemoteCameraService.EXTRA_FACING, facing).putExtra(RemoteCameraService.EXTRA_TARGET, target))
                status.text = "Camera permission मिली; $facing camera share शुरू किया गया।"
            } else status.text = "Camera permission नहीं मिली या sharing toggle बंद है; Remote Camera शुरू नहीं हुआ।"
        }
    }

    private fun importEncryptedBackup(uri: Uri) {
        val envelope = runCatching {
            val stream = contentResolver.openInputStream(uri) ?: error("Backup file could not be opened")
            stream.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 2_100_000) { "Encrypted backup exceeds the 2.1 MB import limit" }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }
        }.getOrElse { status.text = "Backup read failed: " + it.message; return }
        val password = EditText(this).apply {
            hint = "Backup passphrase"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val panel = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(password) }
        android.app.AlertDialog.Builder(this).setTitle("Restore encrypted backup")
            .setMessage("यह Knowledge Graph, Tasks, Project, Digital Twin, Assistant Name और opt-in Conversation Memory को backup वाली स्थिति से replace करेगा। API keys, Firebase login, biometrics और device pairing secrets शामिल नहीं हैं।")
            .setView(panel).setNegativeButton("Keep current data", null)
            .setPositiveButton("Decrypt & restore") { _, _ ->
                status.text = "Backup decrypt और restore हो रहा है…"
                val passphrase = password.text.toString().toCharArray()
                password.setText("")
                Thread {
                    val result = UserBackupManager(this@MainActivity).restore(envelope, passphrase)
                    runOnUiThread {
                        result.fold(
                            { status.text = "Encrypted backup restored. Pending task reminders पुनः schedule किए गए। सभी screens refresh करने के लिए app restart करें।" },
                            { status.text = "Backup restore failed: " + it.message }
                        )
                    }
                }.start()
            }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == localVisionRequest) {
            val photo = pendingVisionPhoto
            pendingVisionPhoto = null
            if (resultCode != Activity.RESULT_OK || photo == null || !photo.isFile || photo.length() == 0L) {
                photo?.delete()
                status.text = "Visual Lens photo capture cancelled or empty."
                return
            }
            try {
                if (!::relayUrl.isInitialized || !::gatewayToken.isInitialized) error("Open Device Universe and configure the HTTPS AI gateway first.")
                val url = relayUrl.text.toString().trim()
                require(validRelay(url)) { "Visual Lens के लिए valid HTTPS AI gateway URL जरूरी है।" }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(photo.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Captured photo is unreadable." }
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                val bitmap = android.graphics.BitmapFactory.decodeFile(photo.absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 })
                    ?: error("Could not decode captured photo.")
                val output = java.io.ByteArrayOutputStream()
                try {
                    var quality = 88
                    do {
                        output.reset()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)
                        quality -= 8
                    } while (output.size() > 2_500_000 && quality >= 40)
                } finally { bitmap.recycle(); photo.delete() }
                val jpeg = output.toByteArray()
                require(jpeg.isNotEmpty() && jpeg.size <= 2_500_000) { "Photo could not be reduced below the 2.5 MB upload limit." }
                val prompt = if (::visionPrompt.isInitialized && visionPrompt.text.isNotBlank()) visionPrompt.text.toString()
                    else "इस तस्वीर को ध्यान से समझाओ। जो स्पष्ट दिख रहा है वही बताओ और अनिश्चित बातों को अनुमान के रूप में चिह्नित करो।"
                val provider = if (::visionProvider.isInitialized) visionProvider.selectedItem?.toString() ?: "gemini" else "gemini"
                if (::visionResult.isInitialized) visionResult.text = "Visual Lens फोटो का विश्लेषण कर रहा है…"
                status.text = "Photo captured locally. AI analysis will start now…"
                DeviceLinkManager.analyzeScreen(url, gatewayToken.text.toString(), provider, prompt, jpeg) { result -> runOnUiThread {
                    result.onSuccess {
                        if (::visionResult.isInitialized) visionResult.text = it
                        status.text = "Visual Lens photo analysis complete."
                    }.onFailure {
                        if (::visionResult.isInitialized) visionResult.text = "Analysis error: ${it.message}"
                        status.text = "Visual Lens analysis failed."
                    }
                } }
            } catch (error: Exception) {
                photo.delete()
                status.text = "Visual Lens capture failed: ${error.message}"
            }
            return
        }
        if (requestCode == faceModelRequest || requestCode == speakerModelRequest) {
            if (resultCode != Activity.RESULT_OK || data?.data == null) { status.text = "Model import cancelled."; return }
            try {
                val dir = java.io.File(filesDir, "biometric-models").apply { mkdirs() }
                val target = java.io.File(dir, if (requestCode == faceModelRequest) "sface.onnx" else "ecapa-any-length.onnx")
                val temp = java.io.File(dir, target.name + ".importing")
                contentResolver.openInputStream(data.data!!)?.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 250L * 1024L * 1024L) { "Model file exceeds 250 MB limit." }; output.write(buffer, 0, count) }
                    }
                } ?: error("Could not read selected model.")
                require(temp.length() > 1024) { "Selected model file is empty or invalid." }
                if (!temp.renameTo(target)) { temp.delete(); error("Could not finalize model import.") }
                status.text = "Model imported locally (${target.length()} bytes). License and tensor contract still require verification."
            } catch (e: Exception) { status.text = "Model import failed: ${e.message}" }
            return
        }
        if (requestCode == screenShareRequest) {
            if (resultCode == Activity.RESULT_OK && data != null &&
                getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean("screen", false)) {
                val service = Intent(this, ScreenShareService::class.java).putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode).putExtra(ScreenShareService.EXTRA_DATA, data)
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, service) else startService(service)
                status.text = "Screen sharing started. Android will show its active-sharing notification."
            } else status.text = "Screen sharing cancelled or its permission was revoked."
            return
        }
        if (requestCode == backupImportRequest && resultCode == Activity.RESULT_OK && data?.data != null) {
            importEncryptedBackup(data.data!!)
            return
        }
        if (requestCode == backupFileRequest && resultCode == Activity.RESULT_OK && data?.data != null) {
            val payload=getSharedPreferences("shraddha_backup_pending",MODE_PRIVATE).getString("payload",null)
            try { if(payload.isNullOrBlank()) error("Backup payload missing"); contentResolver.openOutputStream(data.data!!)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) } ?: error("Could not open destination")
                `in`.shraddha.ai.future.LocalAuditTrail(this).append("backup_export","completed","user-selected JSON backup"); status.text="Backup export complete. Keep the file in a secure location."
            } catch(e:Exception) { status.text="Backup export failed: ${e.message}" }
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
        this.hint = label
        contentDescription = "$label. $hint"
        tooltipText = hint
        setHintTextColor(0xFF8291AA.toInt()); setTextColor(-1); textSize = 14f
        setSingleLine(true); setPadding(dp(14), dp(13), dp(14), dp(13)); background = rounded(0xFF0B1220.toInt(), 14)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }
    private fun actionButton(label: String, hint: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        textSize = 13f
        letterSpacing = .01f
        gravity = android.view.Gravity.CENTER
        setTextColor(0xFFF5F6FF.toInt())
        val shape = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF6255B8.toInt(), 0xFF31517E.toInt())
        ).apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0xFF8B80D8.toInt()) }
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x447D8CFF),
            shape,
            rounded(android.graphics.Color.WHITE, 16)
        )
        minHeight = dp(50)
        setPadding(dp(14), 0, dp(14), 0)
        elevation = dp(3).toFloat()
        contentDescription = "$label. $hint"
        tooltipText = hint
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) }
    }

    private fun requestAudioPermission() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10) }
    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestAudioPermission(); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN"); putExtra(RecognizerIntent.EXTRA_PROMPT, "$assistantDisplayName को अपना आदेश बोलें"); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3) }
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
        contentDescription = "Animated assistant orb. Tap to start voice input."
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
