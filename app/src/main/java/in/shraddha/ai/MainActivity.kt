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
import `in`.shraddha.ai.vision.LiveVisualTranslationSession as LiveVisualTranslationSession
import com.google.android.material.card.MaterialCardView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
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
import `in`.shraddha.ai.biometric.BiometricConsentVault
import `in`.shraddha.ai.biometric.BiometricCaptureActivity
import com.Lia.assistant.data.VoiceGatewaySettings
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.AssistantBrand

class MainActivity : AppCompatActivity() {
    private var pendingActivityRequestCode: Int? = null
    private val activityResultLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val requestCode = pendingActivityRequestCode ?: return@registerForActivityResult
        pendingActivityRequestCode = null
        handleActivityResult(requestCode, result.resultCode, result.data)
    }

    private fun launchActivityForResult(intent: Intent, requestCode: Int) {
        check(pendingActivityRequestCode == null) { "Another activity result request is already in progress" }
        pendingActivityRequestCode = requestCode
        activityResultLauncher.launch(intent)
    }
    private val assistantDisplayName: String
        get() = NovaPreferences.getString(this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME).trim().ifBlank { AssistantBrand.NAME }
    private val securityAudit by lazy { `in`.shraddha.ai.future.LocalAuditTrail(applicationContext) }
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
    @Volatile private var liveVisualTranslationSession: LiveVisualTranslationSession? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastPeerId: String? = null
    private var livePreviewSourceId: String? = null
    private val speechRequest = 41
    private val windowsVoiceRequest = 61
    private var pendingWindowsVoiceAction: JSONObject? = null
    private var pendingWindowsVoiceTarget: String = ""
    private val screenShareRequest = 42
    private val dataFileRequest = 43
    private val backupFileRequest = 45
    private val backupImportRequest = 46
    private val offlineModelImportRequest = 47
    private val faceCaptureRequest = 52
    private val speakerCaptureRequest = 53
    private val dataPermissionSwitches = linkedMapOf<String, Switch>()
    private var pendingShareTarget: String? = null
    private var refreshBiometricStatus: (() -> Unit)? = null
    private var pendingShareCategory: String? = null
    private var approvedShareRequestId: String? = null
    private var approvedShareTarget: String? = null
    private var approvedShareCategory: String? = null
    private var approvedShareExpiresAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage != "home") showHome() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        window.decorView.setBackgroundColor(0xFF070B14.toInt())
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
            text = "Ready • $assistantDisplayName तैयार है। अपनी जरूरत का एक workspace खोलें।"
            textSize = 13f; setTextColor(0xFFE4E9F5.toInt()); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xFF111B2D.toInt(), 16)
        }
        val initialPage = intent.getStringExtra("initial_page")
        if (initialPage in setOf("devices", "assistant", "expert", "lab", "people", "cloud", "future", "permissions")) showPage(initialPage!!) else showHome()
        installLinkCallbacks()
        handlePairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val initialPage = intent.getStringExtra("initial_page")
        if (initialPage in setOf("devices", "assistant", "expert", "lab", "people", "cloud", "future", "permissions")) showPage(initialPage!!)
        handlePairingIntent(intent)
    }

    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "shraddha" || data.host != "pair") return
        val relay = data.getQueryParameter("relay").orEmpty()
        val code = data.getQueryParameter("code").orEmpty()
        if (!validRelay(relay) || !code.matches(Regex("[0-9]{6}"))) {
            Toast.makeText(this, "Pairing QR invalid or expired.", Toast.LENGTH_LONG).show()
            return
        }
        showPage("devices")
        relayUrl.setText(relay)
        pairingCode.setText(code)
        status.text = "QR invitation loaded. Verify the relay and tap Join Another Phone. The code is single-use."
    }

    private fun showPairingQr(relay: String, code: String) {
        val payload = android.net.Uri.Builder().scheme("shraddha").authority("pair")
            .appendQueryParameter("relay", relay.trim().trimEnd('/'))
            .appendQueryParameter("code", code).build().toString()
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 720, 720)
        val bitmap = android.graphics.Bitmap.createBitmap(720, 720, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until 720) for (x in 0 until 720) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(-1, dp(280))
                contentDescription = "Single-use Shraddha device pairing QR code"
            })
            addView(TextView(this@MainActivity).apply {
                text = "Pairing code  $code\\nValid for 5 minutes • One successful join only\\nScan with the other Shraddha AI app."
                gravity = android.view.Gravity.CENTER; textSize = 14f; setTextColor(0xFFE7EAF5.toInt()); setPadding(0, dp(10), 0, 0)
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Secure device pairing")
            .setView(content)
            .setPositiveButton("Done", null)
            .setOnDismissListener { bitmap.recycle() }
            .show()
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
        heroTop.addView(ShraddhaOrbView(this, assistantDisplayName), LinearLayout.LayoutParams(dp(138), dp(138)))
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
            addView(workspaceTile("☎  AI PHONE AGENT", "Incoming call assistant • caller history • call settings") { startActivity(Intent(this@MainActivity, `in`.shraddha.ai.phone.PhoneAgentActivity::class.java)) })
            addView(workspaceTile("✦  AI BRAIN + ${assistantDisplayName.uppercase()} VOICE ENGINE", "Continuous Hindi/English voice • Live audio • Personality • Memory") { startActivity(Intent(this@MainActivity, com.Lia.assistant.MainActivity::class.java)) })
            addView(workspaceTile("◈  ${assistantDisplayName.uppercase()} PC COMPANION", "Windows/Linux companion • Website Forge • PC automation") { showPage("devices") })
            addView(workspaceTile("📱  MOBILE COMMAND CENTER", "Voice • App launcher • System/media • Files • OCR • Web • Automation") { startActivity(Intent(this@MainActivity, `in`.shraddha.ai.mobile.MobileCommandCenterActivity::class.java)) })
            addView(workspaceTile("◈  EXPERT STUDIO", "Engineering • Medical information • Agriculture • Law • Creator") { showPage("expert") })
            addView(workspaceTile("⌘  DEVICE UNIVERSE", "Paired phones • PC companion • Remote screen") { showPage("devices") })
            addView(workspaceTile("▣  APP OPERATOR", "Visual UI control • Workflow • Verification") { startActivity(Intent(this@MainActivity, `in`.shraddha.ai.operator.OperatorActivity::class.java)) })
            addView(workspaceTile("▤  PRIVATE NOTES + OCR", "Hindi / English image text extraction • Local notes • Clipboard copy") { startActivity(Intent(this@MainActivity, `in`.shraddha.ai.notes.NoteOcrActivity::class.java)) })
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
            assistantHero.addView(ShraddhaOrbView(this, assistantDisplayName), LinearLayout.LayoutParams(dp(188), dp(188)))
            assistantHero.addView(TextView(this).apply {
                text = "${assistantDisplayName.uppercase()}  /  PERSONAL AI"
                textSize = 11f; letterSpacing = .14f; gravity = android.view.Gravity.CENTER
                setTextColor(0xFFBFAEFF.toInt()); setTypeface(null, android.graphics.Typeface.BOLD)
            })
            assistantHero.addView(actionButton("🎙  Speak to $assistantDisplayName", "Live Hindi / English voice session • Tap to open") { startActivity(Intent(this@MainActivity, com.Lia.assistant.MainActivity::class.java)) })
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
            "devices" -> { addDeviceLinkSection(pageRoot); pageRoot.addView(actionButton("⇄  OPEN FILE TRANSFER", "Android ↔ paired device • choose, send, receive, pause, resume") { startActivity(Intent(this@MainActivity, `in`.shraddha.ai.link.DeviceTransferActivity::class.java).putExtra("peer", if (::targetDevice.isInitialized) targetDevice.text.toString() else "")) }) }
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
                addView(actionButton("Enable Contacts + Calling", "Voice call के लिए Contacts और Phone permission एक बार दें") {
                    requestFeaturePermissions(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.CALL_PHONE), 12, "Voice calling")
                })
                addView(actionButton("Enable Contacts + SMS", "Voice से SMS भेजने के लिए Contacts और SMS permission एक बार दें") {
                    requestFeaturePermissions(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.SEND_SMS), 13, "SMS sending")
                })
                addView(actionButton("Enable Accessibility App Control", "Visible UI actions और approved gestures") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
                addView(actionButton("Enable Floating AI Orb", "दूसरे ऐप्स के ऊपर $assistantDisplayName shortcut") {
                    if (!Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    else { startService(Intent(this@MainActivity, ShraddhaOverlayService::class.java)); status.text = "Floating Orb requested." }
                })
                addView(actionButton("Enable $assistantDisplayName Keyboard", "Voice typing के लिए keyboard settings") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
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

    private fun addAiBrainSection(root: LinearLayout) {
        section(root, "AI BRAIN & MEMORY", "Gemini, OpenAI, Claude, Groq और Perplexity के लिए सुरक्षित server-side API keys") {
            addView(relayUrl)
            addView(gatewayToken)
            val provider = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gemini", "openai", "anthropic", "groq", "perplexity"))
            }
            addView(provider)
            val prompt = edit("Ask $assistantDisplayName", "हिंदी या English में अपना सवाल लिखें").apply { isSingleLine = false; minLines = 3 }
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
            val localModelPrompt = edit("Local AI prompt (no internet)", "$assistantDisplayName, मेरे सवाल का उत्तर दो…").apply { isSingleLine = false; minLines = 2 }
            addView(localModelPrompt)
            val localBackend = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gpu", "npu", "cpu"))
            }
            addView(TextView(this@MainActivity).apply { text = "HARDWARE BACKEND • AUTO tries NPU → GPU → CPU fallback"; setTextColor(0xFFB9C8E4.toInt()); textSize = 11f })
            addView(localBackend)
            addView(actionButton("SELECT ON-DEVICE LLM MODEL", "अपनी compatible .litertlm model file चुनें; model APK में शामिल नहीं है") {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream" }
                launchActivityForResult(picker, offlineModelImportRequest)
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

    private fun addCompleteExpertHubSection(root: LinearLayout) {
        section(root, "09  •  PRACTICAL EXPERT HUB", "Engineering • Medical information • Agriculture • Law • Repair • CAD/3D • Creator • Research • Software") {
            val workspaces = listOf(
                "electronics" to "Electronics Engineering", "electrical" to "Electrical Engineering",
                "mechanical" to "Mechanical Engineering", "cad3d" to "CAD / 3D Design", "circuit_pcb" to "Circuit & PCB Studio",
                "civil_building" to "Civil & Building Plan", "medical" to "Medical Information Assistant", "agriculture" to "Agriculture AI",
                "law" to "Law & Legal Information", "repair" to "Universal Repair", "creator" to "Creator Studio", "software" to "Software & App Builder",
                "research" to "Deep Research Lab", "data_science" to "Data Science", "robotics" to "Robotics", "drone" to "Drone & Mapping",
                "gis" to "GIS & Spatial Analysis", "smart_home" to "Smart Home / IoT", "business" to "Business & Finance"
            )
            val tasks = listOf("solve" to "Solve Practical Problem", "design" to "Design / Create", "diagnose" to "Diagnose / Troubleshoot", "research" to "Research / Compare", "create" to "Create Deliverable", "estimate" to "Estimate / Calculate", "review" to "Review / Audit")
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
            val request = edit("Practical project / problem", "जैसे 2BHK का conceptual floor plan तैयार करो, मोटर की fault diagnosis करो, या फसल की समस्या का विश्लेषण करो").apply { isSingleLine = false; minLines = 4; maxLines = 8 }
            addView(request)
            val output = TextView(this@MainActivity).apply { text = "Generated work यहाँ दिखाई देगा। इसे local project library में save या Android share sheet से export कर सकते हैं।"; setTextColor(0xFFE4E9F5.toInt()); textSize = 13f; setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(0xFF111B2D.toInt(), 16); minHeight = dp(110) }
            addView(output)
            val library = `in`.shraddha.ai.future.ExpertProjectLibrary(this@MainActivity)
            var lastTitle = "$assistantDisplayName Project"
            var lastOutput = ""
            var lastWorkspace = ""
            var lastTask = ""
            var lastProvider = ""
            addView(actionButton("✦ Generate with Expert Hub", "Real server-side AI request • selected provider key required") {
                val base = relayUrl.text.toString().trim(); val text = request.text.toString().trim()
                if (!validRelay(base) || text.isBlank()) { status.text = "पहले HTTPS relay URL और अपना काम लिखें।"; return@actionButton }
                output.text = "${workspaces[workspacePicker.selectedItemPosition].second} तैयार कर रहा है…"
                lastTitle = "${workspaces[workspacePicker.selectedItemPosition].second} — ${tasks[taskPicker.selectedItemPosition].second}"
                lastWorkspace = workspaces[workspacePicker.selectedItemPosition].first
                lastTask = tasks[taskPicker.selectedItemPosition].first
                lastProvider = providerPicker.selectedItem.toString()
                DeviceLinkManager.studioWork(base, gatewayToken.text.toString(), lastProvider, lastWorkspace, lastTask, text) { response -> runOnUiThread {
                    val raw = response.getOrElse { "Request failed: ${it.message}" }
                    lastOutput = runCatching { JSONObject(raw).optString("answer").ifBlank { raw } }.getOrDefault(raw)
                    output.text = lastOutput
                    status.text = if (response.isSuccess) "Expert Hub response ready. Review before using safety-critical output." else "Expert Hub request failed. Check backend/provider setup."
                } }
            })
            addView(actionButton("Save to My Project Library", "Android Keystore encrypted • included in consented recovery backups") {
                if (lastOutput.isBlank()) { status.text = "पहले कोई AI output generate करें।"; return@actionButton }
                val saved = library.save(lastTitle, lastOutput, lastWorkspace, lastTask, lastProvider)
                status.text = saved.fold(
                    { "Project encrypted library में save हुआ। कुल ${it.size} saved projects." },
                    { "Project save failed safely: ${it.message}" }
                )
            })
            addView(actionButton("Open Saved Projects", "पिछले 20 encrypted projects देखें") {
                val loaded = library.load()
                if (loaded.isFailure) { status.text = "Project library नहीं खुली: ${loaded.exceptionOrNull()?.message}"; return@actionButton }
                val entries = loaded.getOrDefault(emptyList())
                if (entries.isEmpty()) { Toast.makeText(this@MainActivity, "अभी कोई saved project नहीं है।", Toast.LENGTH_SHORT).show(); return@actionButton }
                val labels = entries.map { entry ->
                    val date = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(entry.savedAt))
                    "${entry.title} • $date"
                }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("My Encrypted Project Library").setItems(labels.toTypedArray()) { _, index ->
                    val item = entries[index]
                    lastTitle = item.title
                    lastOutput = item.content
                    lastWorkspace = item.workspace
                    lastTask = item.task
                    lastProvider = item.provider
                    output.text = lastOutput
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
                "location" to "Location", "device_status" to "Device Status", "ai_memory" to "AI Memory", "clipboard" to "Clipboard text"
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
                                "camera" -> { stopService(Intent(this@MainActivity, RemoteCameraService::class.java)); lastCameraJpeg = null; securityAudit.append("camera_share", "stopped", "user_or_permission_revoked") }
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
                val requestId = java.util.UUID.randomUUID().toString().replace("-", "")
                approvedShareRequestId = null; approvedShareTarget = null; approvedShareCategory = null; approvedShareExpiresAt = 0L
                val sent = DeviceLinkManager.requestDataShare(target, category, requestId, "User requested $category")
                status.text = if (sent) "$category sharing request भेजी गई। Target की अनुमति जरूरी है।" else "Relay connected नहीं है।"
            })
            addView(actionButton("Send a Selected Document / Photo", "पहले target से category permission approve करवाएँ") {
                val target = targetDevice.text.toString().trim()
                val category = categoryPicker.selectedItem?.toString().orEmpty()
                if (target.isBlank()) { status.text = "पहले target device चुनें।"; return@actionButton }
                pendingShareTarget = target; pendingShareCategory = category
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = if (category == "media") "image/*" else "*/*" }
                launchActivityForResult(intent, dataFileRequest)
            })
            addView(actionButton("Share Clipboard Text to Paired Phone", "Clipboard केवल इस बटन पर पढ़ा जाएगा; पहले target की one-time approval जरूरी है") {
                val target = targetDevice.text.toString().trim()
                if (target.isBlank()) { status.text = "पहले paired target device चुनें।"; return@actionButton }
                if (approvedShareRequestId.isNullOrBlank() || approvedShareTarget != target || approvedShareCategory != "clipboard" || approvedShareExpiresAt <= System.currentTimeMillis()) {
                    status.text = "पहले category में Clipboard चुनकर Request Data भेजें और target की अनुमति लें।"; return@actionButton
                }
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clipText = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
                if (clipText.isBlank() || clipText.length > 20_000) { status.text = "Clipboard खाली है या 20,000 characters से बड़ा है।"; return@actionButton }
                val bytes = clipText.toByteArray(Charsets.UTF_8)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val requestId = approvedShareRequestId ?: return@actionButton
                val sent = DeviceLinkManager.sendSharedPayload(target, "clipboard", requestId, "clipboard.txt", "text/plain", encoded, bytes.size.toLong(), digest, "clipboard_text")
                if (sent) { approvedShareRequestId = null; approvedShareTarget = null; approvedShareCategory = null }
                status.text = if (sent) "Clipboard text भेजा गया। Receiver पर paste करने से पहले पुष्टि माँगी जाएगी।" else "Clipboard भेजना विफल हुआ; approval expire हो सकती है।"
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
            securityAudit.append("camera_share", "started", "facing=$facing; foreground_service=true")
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
        val requestId = msg.optString("requestId")
        val allowed = getSharedPreferences("shraddha_data_permissions", MODE_PRIVATE).getBoolean(category, false)
        runOnUiThread {
            if (!allowed) {
                DeviceLinkManager.respondDataShare(from, category, requestId, false)
                Toast.makeText(this, "$category sharing इस फोन पर बंद है।", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Data sharing request")
                .setMessage("दूसरा paired device '$category' category का डेटा माँग रहा है। क्या आप 5 मिनट के लिए अनुमति देते हैं?")
                .setNegativeButton("Deny") { _, _ -> DeviceLinkManager.respondDataShare(from, category, requestId, false) }
                .setPositiveButton("Allow once") { _, _ ->
                    DeviceLinkManager.respondDataShare(from, category, requestId, true, 5)
                    if (category == "documents") {
                        startActivity(Intent(this@MainActivity, `in`.shraddha.ai.link.DeviceTransferActivity::class.java)
                            .putExtra("peer", from))
                    }
                }
                .setOnCancelListener { DeviceLinkManager.respondDataShare(from, category, requestId, false) }
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
                .setNegativeButton("Deny") { _, _ ->
                    getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit().putLong("remote_control_grant_until", 0L).apply()
                    DeviceLinkManager.respondRemoteControl(from, false)
                }
                .setPositiveButton("Allow for 5 minutes") { _, _ ->
                    getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit()
                        .putLong("remote_control_grant_until", System.currentTimeMillis() + 5 * 60_000L).apply()
                    DeviceLinkManager.respondRemoteControl(from, true, 5)
                }
                .setOnCancelListener {
                    getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit().putLong("remote_control_grant_until", 0L).apply()
                    DeviceLinkManager.respondRemoteControl(from, false)
                }
                .show()
        }
    }

    private fun addDeviceLinkSection(root: LinearLayout) {
        section(root, "01  •  WORLDWIDE DEVICE LINK", "दुनिया में कहीं से भी इंटरनेट के जरिए pair करें — दोनों phones पर $assistantDisplayName और आपका HTTPS relay जरूरी है") {
            relayUrl = edit("Cloud Relay URL", "https://your-domain.example")
            deviceName = edit("This phone name", "e.g. Main Phone")
            addView(relayUrl); addView(deviceName)
            addView(actionButton("Create Main Phone Session", "नया secure pairing code बनाएँ") {
                val url = relayUrl.text.toString().trim()
                if (!validRelay(url)) { status.text = "पहले अपना deployed HTTPS relay URL डालें।"; return@actionButton }
                DeviceLinkManager.createSession(url, deviceName.text.toString().ifBlank { "$assistantDisplayName Phone" }) { result -> runOnUiThread {
                    result.onSuccess { isSessionOwner = true; mainOnlyMode.isEnabled = true; pairingCode.setText(it.code); status.text = "One-time pairing invite created. Scan the QR on the other Shraddha AI app."; showPairingQr(url, it.code) }
                        .onFailure { status.text = "Pairing error: ${it.message}" }
                } }
            })
            pairingCode = edit("Pairing code", "6-digit code दूसरे फोन से")
            addView(pairingCode)
            addView(actionButton("Join Another Phone", "इस फोन को मौजूदा device group में जोड़ें") {
                val url = relayUrl.text.toString().trim(); val code = pairingCode.text.toString().trim()
                if (!validRelay(url) || code.length != 6) { status.text = "HTTPS relay URL और 6-digit pairing code जरूरी हैं।"; return@actionButton }
                DeviceLinkManager.joinSession(url, deviceName.text.toString().ifBlank { "$assistantDisplayName Phone" }, code) { result -> runOnUiThread {
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
                    getSharedPreferences("shraddha_device_link", MODE_PRIVATE).edit()
                        .putBoolean("remote_control_consent", checked)
                        .putLong("remote_control_grant_until", if (checked) getSharedPreferences("shraddha_device_link", MODE_PRIVATE).getLong("remote_control_grant_until", 0L) else 0L)
                        .apply()
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
            addView(actionButton("REVOKE SELECTED PAIRED DEVICE", "Owner-only • clears session access and grants") {
                val selected = targetDevice.text.toString().trim()
                if (!isSessionOwner) { status.text = "केवल session owner paired device revoke कर सकता है।"; return@actionButton }
                if (selected.isBlank() || !peerIds.contains(selected)) { status.text = "पहले सूची से valid paired device चुनें।"; return@actionButton }
                val label = peerNames[selected] ?: selected
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Revoke $label?")
                    .setMessage("इस device का session access, data grants और remote-control grants तुरंत रद्द होंगे। इसे दोबारा pair करना पड़ेगा।")
                    .setNegativeButton("Keep device", null)
                    .setPositiveButton("Revoke device") { _, _ ->
                        status.text = if (DeviceLinkManager.revokePairedDevice(selected)) "Revocation request sent for $label. Waiting for relay confirmation…" else "Relay disconnected; revoke request could not be sent."
                    }.show()
            })
            addView(actionButton("DISCONNECT THIS DEVICE", "इस phone को paired session से हटाएँ") {
                if (isSessionOwner) { status.text = "Main session owner cannot leave this session. Revoke other devices or disconnect locally."; return@actionButton }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Leave paired session?")
                    .setMessage("This phone will be removed from the device group.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Leave") { _, _ ->
                        DeviceLinkManager.leavePairedSession(); DeviceLinkManager.disconnect()
                        peerIds.clear(); peerNames.clear(); peerOnline.clear(); targetDevice.setText("")
                        status.text = "This phone left the paired session."
                    }.show()
            })
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
            addView(actionButton("🎙  VOICE CONTROL WINDOWS", "बोलें: वापस जाओ, नीचे स्क्रॉल करो, Save पर क्लिक करो, या लिखो hello") {
                val target = targetDevice.text.toString().trim().ifBlank { lastPeerId.orEmpty() }
                if (target.isBlank()) { status.text = "पहले paired Windows PC चुनें।"; return@actionButton }
                val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, "Windows PC को क्या करना है?")
                runCatching { launchActivityForResult(speech, windowsVoiceRequest) }.onFailure { status.text = "Voice recognition उपलब्ध नहीं है: ${it.message}" }
            })
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
            val pcWritePath = edit("Windows file path to create/update", "उदाहरण: project/README.md")
            val pcWriteContent = edit("New Windows file content", "AI द्वारा तैयार किया गया text/code यहाँ रखें").apply {
                minLines = 4; maxLines = 10; isSingleLine = false
            }
            val pcOverwrite = android.widget.Switch(this@MainActivity).apply {
                text = "Allow updating an existing file (Windows backup will be created)"
                setTextColor(0xFFE4E9F5.toInt())
                isChecked = false
            }
            addView(pcWritePath); addView(pcWriteContent); addView(pcOverwrite)
            addView(actionButton("CREATE / UPDATE WINDOWS TEXT OR CODE FILE", "Approved --file-root के भीतर ही • हर write पर PC owner की अलग confirmation • overwrite से पहले backup") {
                val relative = pcWritePath.text.toString().trim()
                val content = pcWriteContent.text.toString()
                if (relative.isBlank() || content.length > 256_000) {
                    status.text = "File path जरूरी है और content 256 KB से छोटा होना चाहिए।"
                } else {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Windows file write approval")
                        .setMessage("Path: $relative\nCharacters: ${content.length}\nOverwrite existing: ${pcOverwrite.isChecked}\n\nWindows PC पर भी दूसरी confirmation आएगी। Existing file होने पर पहले backup बनाया जाएगा।")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Send for PC approval") { _, _ ->
                            sendRemote(JSONObject().put("kind", "file_write").put("path", relative)
                                .put("content", content).put("overwrite", pcOverwrite.isChecked))
                        }.show()
                }
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
            addView(actionButton("WINDOWS CODE ASSISTANT", "Optional approved-folder path की file पढ़कर local GGUF model से code समझें/debug करें; बदलाव के लिए अलग file-write approval चाहिए") {
                val prompt = offlinePrompt.text.toString().trim()
                if (prompt.isBlank() || prompt.length > 4000) {
                    status.text = "Code prompt 1-4000 characters का होना चाहिए।"
                } else {
                    val action = JSONObject().put("kind", "code_assistant").put("prompt", prompt)
                    val relative = pcRelativePath.text.toString().trim()
                    if (relative.isNotBlank()) action.put("path", relative)
                    sendRemote(action)
                }
            })
            addView(actionButton("OPEN APP ON WINDOWS PC", "Allowlisted apps only • PC confirmation required") {
                val name = remoteAppName.text.toString().trim()
                if (name.isBlank()) status.text = "Windows app का नाम लिखें।"
                else sendRemote(JSONObject().put("kind", "open_app").put("name", name))
            })
            addView(actionButton("WINDOWS SYSTEM CONTROL • LOCK", "PC पर local confirmation के बाद workstation lock होगा") {
                sendRemote(JSONObject().put("kind", "system_control").put("command", "lock"))
            })
            addView(actionButton("WINDOWS VOLUME UP / DOWN", "सिर्फ media key control") {
                sendRemote(JSONObject().put("kind", "system_control").put("command", "volume_up"))
            })
            addView(actionButton("WINDOWS BRIGHTNESS UP", "Supported display पर brightness बढ़ाएँ") {
                sendRemote(JSONObject().put("kind", "system_control").put("command", "brightness_up"))
            })
            addView(actionButton("WINDOWS MEDIA • PLAY / PAUSE", "चल रहे media player को control करें") {
                sendRemote(JSONObject().put("kind", "media_control").put("command", "play_pause"))
            })
            addView(actionButton("WINDOWS MEDIA • NEXT TRACK", "अगला media track") {
                sendRemote(JSONObject().put("kind", "media_control").put("command", "next"))
            })
            addView(actionButton("CAPTURE WINDOWS SCREENSHOT", "Active non-sensitive window • PC local confirmation") {
                sendRemote(JSONObject().put("kind", "screenshot"))
            })
            addView(actionButton("TRANSFORM WINDOWS CLIPBOARD • UPPERCASE", "Clipboard content sensitive pattern पर block होगा; PC owner result भेजने की पुष्टि करेगा") {
                sendRemote(JSONObject().put("kind", "clipboard_transform").put("operation", "uppercase"))
            })
            addView(actionButton("SEARCH THE WEB FROM WINDOWS", "Internet required • DuckDuckGo results returned to Shraddha") {
                val query = pcSearchQuery.text.toString().trim()
                if (query.length !in 2..160) status.text = "Web search query 2-160 characters की होनी चाहिए।"
                else sendRemote(JSONObject().put("kind", "web_search").put("query", query))
            })
            addView(actionButton("WINDOWS CODE ASSISTANT • LOCAL MODEL", "PC पर GGUF local model और अलग confirmation जरूरी") {
                val prompt = offlinePrompt.text.toString().trim()
                if (prompt.isBlank() || prompt.length > 4000) status.text = "Code prompt 1-4000 characters का होना चाहिए।"
                else sendRemote(JSONObject().put("kind", "code_assistant").put("prompt", prompt))
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
            addView(actionButton("Ask $assistantDisplayName to Understand Screen", "Latest received screenshot का AI analysis") {
                val frame = lastScreenJpeg
                if (frame == null) { status.text = "पहले दूसरे फोन पर Share This Phone Screen चालू करें।"; return@actionButton }
                val url = relayUrl.text.toString().trim()
                if (!validRelay(url)) { status.text = "HTTPS relay URL जरूरी है।"; return@actionButton }
                visionResult.text = "$assistantDisplayName स्क्रीन समझ रही है…"
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
                securityAudit.append("visual_lens", "requested", "source=paired_camera; provider=${visionProvider.selectedItem}; prompt_not_logged")
                DeviceLinkManager.analyzeScreen(url, gatewayToken.text.toString(), visionProvider.selectedItem.toString(), visionPrompt.text.toString(), frame) { result -> runOnUiThread {
                    result.onSuccess { visionResult.text = it; status.text = "Remote camera visual analysis complete."; securityAudit.append("visual_lens", "completed", "source=paired_camera; result_not_logged") }
                        .onFailure { visionResult.text = "Camera analysis error: ${it.message}"; securityAudit.append("visual_lens", "failed", "source=paired_camera; error_type=${it.javaClass.simpleName}") }
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
                            DeviceLinkManager.publishSocialPost(
                                base, secret, selected, exact,
                                userConfirmed = true, confirmedPlatform = selected, confirmedText = exact
                            ) { response ->
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
            addView(actionButton("View local audit log","Recent workflow events") { val log=audit.exportText().getOrElse { "Audit log unavailable: ${it.message}" }; android.app.AlertDialog.Builder(this@MainActivity).setTitle("Security Audit Trail").setMessage(log.ifBlank { "अभी कोई audit event नहीं है।" }).setPositiveButton("OK",null).setNeutralButton("Clear") { _,_ -> audit.clear(); status.text="Audit log cleared." }.show() })
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

            addView(actionButton("GENERATE COUNCIL TO OPERATOR PLAN", "AI proposal को validate करके Operator में review और step-by-step approval के लिए भेजें") {
                val base = endpoint.text.toString().trim().trimEnd('/')
                val goalText = councilGoal.text.toString().trim()
                val providers = providersField.text.toString().split(',').map { it.trim().lowercase() }
                    .filter { it in listOf("openai","gemini","anthropic","groq","perplexity") }.distinct().take(5)
                val gatewayTokenValue = tokenField.text.toString().trim()
                if (!base.startsWith("https://") || goalText.isBlank() || providers.size < 2 || gatewayTokenValue.isBlank()) {
                    councilOutput.text = "पहले HTTPS backend, gateway token, goal और कम-से-कम दो valid providers configure करें।"
                    return@actionButton
                }
                councilOutput.text = "AI Council से सीमित accessibility action proposal माँगा जा रहा है… अभी कोई action execute नहीं होगा।"
                Thread {
                    try {
                        val payload = JSONObject().put("goal", goalText).put("providers", JSONArray(providers))
                            .toString().toByteArray(Charsets.UTF_8)
                        val connection = (java.net.URL("$base/api/agents/council/operator-plan").openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 60000; doOutput = true
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            setRequestProperty("Authorization", "Bearer $gatewayTokenValue")
                        }
                        connection.outputStream.use { it.write(payload) }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        connection.disconnect()
                        val data = JSONObject(raw.ifBlank { "{}" })
                        if (code !in 200..299 || data.optString("mode") != "council_operator_plan_proposal") {
                            runOnUiThread { councilOutput.text = "Operator plan नहीं बना: HTTP $code • ${data.optString("error", "invalid response")}. कोई action execute नहीं हुआ।" }
                        } else {
                            val steps = data.optJSONArray("steps") ?: JSONArray()
                            if (steps.length() !in 1..12 || !data.optBoolean("requiresUserReview") || !data.optBoolean("requiresPerStepApproval")) {
                                runOnUiThread { councilOutput.text = "Council ने सुरक्षित executable plan नहीं दिया। कोई action execute नहीं हुआ।" }
                            } else {
                                runOnUiThread {
                                    councilOutput.text = "Proposal तैयार: ${steps.length()} step(s). पहले पूरा plan review होगा; हर step की अलग अनुमति आवश्यक है।"
                                    val launch = android.content.Intent(this@MainActivity, `in`.shraddha.ai.operator.OperatorActivity::class.java)
                                        .putExtra("council_operator_plan", data.toString())
                                    startActivity(launch)
                                }
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread { councilOutput.text = "Council Operator plan request failed: ${error.message ?: "network/backend error"}. कोई action execute नहीं हुआ।" }
                    }
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
        addAdvanced25FeatureWorkspace(root)
        section(root, "FUTURE EXPANSION PRIORITIES", "वे क्षमताएँ जिनके लिए external runtime, verified models या companion integration अभी बाकी है") {
            listOf("1. On-device face and speaker identity models with consent, anti-spoofing and accuracy validation", "2. Native offline LLM inference runtime and hardware-aware model manager", "3. Signed plugin packages with real sandbox isolation and revocation", "4. Durable project execution, resumable tasks and verified rollback", "5. Complete Shraddha AI and PC Companion integration", "6. Smart-home connectors with local-first allowlists and manual override", "7. Actual image, video, audio and CAD generation/export pipelines", "8. Full encrypted backup restore including supported encrypted vault data", "9. Research retrieval with live source citations and verification", "10. Automated device test runner and global emergency access revocation").forEach { item -> addView(TextView(this@MainActivity).apply { text=item; textSize=12f; setTextColor(0xFFDCE5F6.toInt()); setPadding(0,dp(5),0,dp(5)) }) }
        }
    }


    private fun addAdvanced25FeatureWorkspace(root: LinearLayout) {
        val encryptedStore = EncryptedFeatureStore(this@MainActivity)

        section(root, "25 ADVANCED SYSTEMS • LIVE WORKSPACE", "नए engines को सुरक्षित local storage और वास्तविक UI controls से जोड़ना। जिन systems को external model, sensor या companion चाहिए, उनकी सीमा साफ दिखाई जाएगी।") {
            val meetingText = edit("Meeting transcript / notes", "DECISION: launch after QA\\nACTION: @team: test Android release\\nQUESTION: who owns deployment?").apply { isSingleLine = false; minLines = 4 }
            val meetingOutput = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF0C1525.toInt(), 14) }
            addView(TextView(this@MainActivity).apply { text = "09 • MEETING INTELLIGENCE"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f })
            addView(meetingText); addView(meetingOutput)
            addView(actionButton("EXTRACT DECISIONS & ACTION ITEMS", "Only explicit DECISION:, ACTION: and QUESTION: transcript markers are extracted") {
                runCatching { `in`.shraddha.ai.future.MeetingIntelligence.parse(meetingText.text.toString()) }
                    .onSuccess { notes ->
                        val display = buildString {
                            append("SUMMARY\\n").append(notes.summary.ifBlank { "No unmarked summary text." })
                            append("\\n\\nDECISIONS\\n").append(notes.decisions.joinToString("\\n") { "• $it" }.ifBlank { "None" })
                            append("\\n\\nACTION ITEMS\\n").append(notes.actions.joinToString("\\n") { "• ${it.owner?.let { owner -> "@$owner: " }.orEmpty()}${it.task}" }.ifBlank { "None" })
                            append("\\n\\nOPEN QUESTIONS\\n").append(notes.openQuestions.joinToString("\\n") { "• $it" }.ifBlank { "None" })
                        }
                        meetingOutput.text = display
                        val saved = encryptedStore.put("advanced25_meeting_latest", display)
                        status.text = if (saved.isSuccess) "Meeting notes encrypted and saved on this device." else "Meeting extraction ready, but encrypted save failed: ${saved.exceptionOrNull()?.message}"
                    }.onFailure { meetingOutput.text = it.message ?: "Transcript could not be parsed." }
            })

            val taskLines = edit("Task plan • one per line: id|title|dependency IDs|priority 1-5|minutes", "research|Research requirements||2|30\\nbuild|Build feature|research|1|90\\ntest|Test feature|build|1|45").apply { isSingleLine = false; minLines = 4 }
            val taskOutput = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF0C1525.toInt(), 14) }
            addView(TextView(this@MainActivity).apply { text = "02 • AUTONOMOUS TASK PLANNER + SELF-REVIEW"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, dp(4)) })
            addView(taskLines); addView(taskOutput)
            addView(actionButton("BUILD DEPENDENCY-AWARE TASK PLAN", "DAG ordering, cycle detection and estimated duration • planning only, no automatic actions") {
                runCatching {
                    val tasks = taskLines.text.toString().lineSequence().map { it.trim() }.filter { it.isNotBlank() }.map { line ->
                        val parts = line.split('|')
                        require(parts.size == 5) { "हर task में 5 fields दें: id|title|dependencies|priority|minutes" }
                        `in`.shraddha.ai.future.AutonomousTaskPlanner.Task(
                            parts[0].trim(), parts[1].trim(),
                            parts[2].split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                            parts[3].trim().toInt(), parts[4].trim().toInt()
                        )
                    }.toList()
                    `in`.shraddha.ai.future.AutonomousTaskPlanner.plan(tasks)
                }.onSuccess { plan ->
                    val display = "EXECUTION ORDER (PROPOSAL)\\n" + plan.ordered.mapIndexed { index, task -> "${index + 1}. ${task.title} [${task.id}] • ${task.estimatedMinutes} min" }.joinToString("\\n") + "\\n\\nTotal estimate: ${plan.totalEstimatedMinutes} minutes\\nNo task has been executed."
                    taskOutput.text = display
                    val saved = encryptedStore.put("advanced25_task_plan", display)
                    status.text = if (saved.isSuccess) "Task plan encrypted and saved." else "Plan ready, but encrypted save failed."
                }.onFailure { taskOutput.text = "Plan rejected: ${it.message}" }
            })
            addView(actionButton("RUN SELF-REVIEW CHECKLIST", "Checks explicit acceptance criteria; it does not pretend to test external systems") {
                val checks = planReviewChecks(taskOutput.text.toString())
                if (checks.isEmpty()) { taskOutput.text = "पहले task plan बनाएं।"; return@actionButton }
                val review = `in`.shraddha.ai.future.SelfReviewEngine.review(checks)
                taskOutput.text = taskOutput.text.toString() + "\\n\\nSELF REVIEW: ${review.scorePercent}%\\n${review.summary}\\n" + review.failedChecks.joinToString("\\n") { "• ${it.description}" }
            })

            val briefingLines = edit("Daily briefing items • priority|kind|time|title", "1|task|09:00|Review release build\\n2|event|11:30|Project meeting\\n3|reminder|17:00|Call supplier").apply { isSingleLine = false; minLines = 3 }
            val briefingOutput = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF0C1525.toInt(), 14) }
            addView(TextView(this@MainActivity).apply { text = "10 • SMART DAILY BRIEFING"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, dp(4)) })
            addView(briefingLines); addView(briefingOutput)
            addView(actionButton("GENERATE DAILY BRIEFING", "Uses only the tasks/events you enter; no calendar is read without a connector") {
                runCatching {
                    val items = briefingLines.text.toString().lineSequence().filter { it.isNotBlank() }.map { line ->
                        val p = line.split('|', limit = 4); require(p.size == 4)
                        `in`.shraddha.ai.future.DailyBriefingEngine.Item(p[3].trim(), p[2].trim(), p[0].trim().toInt(), p[1].trim())
                    }.toList()
                    `in`.shraddha.ai.future.DailyBriefingEngine.compose(items)
                }.onSuccess { b ->
                    val display = "${b.headline}\\n\\nURGENT\\n" + b.urgent.joinToString("\\n") { "• ${it.title}" }.ifBlank { "None" } + "\\n\\nSCHEDULE\\n" + b.schedule.joinToString("\\n") { "• ${it.timeLabel} — ${it.title}" }.ifBlank { "None" } + "\\n\\nREMINDERS\\n" + b.reminders.joinToString("\\n") { "• ${it.timeLabel} — ${it.title}" }.ifBlank { "None" }
                    briefingOutput.text = display
                    encryptedStore.put("advanced25_briefing_latest", display)
                }.onFailure { briefingOutput.text = "Briefing input invalid: ${it.message}" }
            })

            val timelineTitle = edit("Life Timeline • event title", "उदाहरण: Shraddha AI milestone")
            val timelineCategory = edit("Event category", "work / personal / project")
            val timelineNote = edit("Event note", "केवल वही जानकारी जो आप खुद सेव करना चाहते हैं")
            val timelineQuery = edit("Search timeline", "keyword")
            val timelineOutput = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF0C1525.toInt(), 14) }
            addView(TextView(this@MainActivity).apply { text = "21 • LIFE TIMELINE + 22 • CONTEXT RECALL"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, dp(4)) })
            addView(timelineTitle); addView(timelineCategory); addView(timelineNote); addView(timelineQuery); addView(timelineOutput)
            fun loadTimeline(): org.json.JSONArray = runCatching { org.json.JSONArray(encryptedStore.get("advanced25_timeline").getOrDefault("[]")) }.getOrDefault(org.json.JSONArray())
            addView(actionButton("SAVE TIMELINE EVENT", "Explicit user-entered event • encrypted with Android Keystore") {
                runCatching {
                    require(timelineTitle.text.isNotBlank() && timelineCategory.text.isNotBlank())
                    val array = loadTimeline()
                    array.put(JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("title", timelineTitle.text.toString().take(120)).put("category", timelineCategory.text.toString().take(40)).put("note", timelineNote.text.toString().take(1000)).put("time", System.currentTimeMillis()))
                    require(array.length() <= 500)
                    encryptedStore.put("advanced25_timeline", array.toString()).getOrThrow()
                    timelineOutput.text = "Encrypted timeline event saved. Total: ${array.length()}"
                }.onFailure { timelineOutput.text = "Save failed: ${it.message}" }
            })
            addView(actionButton("SEARCH CONTEXT TIMELINE", "Search only your saved timeline events") {
                val query = timelineQuery.text.toString().trim()
                val array = loadTimeline()
                val hits = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter { it.optString("title").contains(query, true) || it.optString("note").contains(query, true) || it.optString("category").contains(query, true) }.sortedByDescending { it.optLong("time") }
                timelineOutput.text = if (hits.isEmpty()) "No matching saved events." else hits.joinToString("\\n\\n") { "${it.optString("title")} • ${it.optString("category")}\\n${it.optString("note")}" }
            })
            addView(actionButton("DELETE ALL TIMELINE EVENTS", "Permanent local deletion • requires confirmation") {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Delete timeline?").setMessage("सभी locally saved timeline events हटेंगे। यह undo नहीं होगा।").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    val removed = encryptedStore.remove("advanced25_timeline")
                    timelineOutput.text = if (removed) "Timeline events deleted." else "Could not delete timeline."
                }.show()
            })

            val skillName = edit("Personal skill name", "उदाहरण: My Hindi writing style")
            val skillInput = edit("Example input", "Example request")
            val skillOutputText = edit("Your approved output", "वह उत्तर जो आपने सही माना").apply { isSingleLine = false; minLines = 2 }
            val skillConsent = CheckBox(this@MainActivity).apply { text = "मैं इस example को future personalization के लिए save करने की अनुमति देता/देती हूँ।"; setTextColor(0xFFE4E9F5.toInt()) }
            val skillStatus = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f }
            addView(TextView(this@MainActivity).apply { text = "23 • PERSONAL SKILL LEARNING"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, dp(4)) })
            addView(skillName); addView(skillInput); addView(skillOutputText); addView(skillConsent); addView(skillStatus)
            addView(actionButton("SAVE APPROVED SKILL EXAMPLE", "Explicit consent • encrypted local storage • user can delete") {
                runCatching {
                    require(skillConsent.isChecked) { "पहले learning consent checkbox चुनें।" }
                    val learning = `in`.shraddha.ai.future.PersonalSkillLearning()
                    learning.add(true, skillName.text.toString(), skillInput.text.toString(), skillOutputText.text.toString())
                    val array = runCatching { org.json.JSONArray(encryptedStore.get("advanced25_skills").getOrDefault("[]")) }.getOrDefault(org.json.JSONArray())
                    array.put(JSONObject().put("skill", skillName.text.toString().take(60)).put("input", skillInput.text.toString().take(2000)).put("approvedOutput", skillOutputText.text.toString().take(4000)).put("approvedAt", System.currentTimeMillis()))
                    require(array.length() <= 200)
                    encryptedStore.put("advanced25_skills", array.toString()).getOrThrow()
                    skillStatus.text = "Approved example encrypted and saved. Examples: ${array.length()}"
                }.onFailure { skillStatus.text = "Not saved: ${it.message}" }
            })
            addView(actionButton("FORGET ALL LEARNED SKILL EXAMPLES", "Permanent deletion of this feature's encrypted examples") {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Forget learned examples?").setMessage("All saved personal skill examples will be deleted.").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    skillStatus.text = if (encryptedStore.remove("advanced25_skills")) "All saved skill examples deleted." else "Could not delete examples."
                }.show()
            })

            val routineTrigger = edit("Routine trigger phrase", "When I say: prepare morning briefing")
            val routineAction = edit("Proposed routine action", "Prepare briefing draft and show it for review")
            val routineConsent = CheckBox(this@MainActivity).apply { text = "इस routine को enabled proposal के रूप में save करें; actions फिर भी approval माँगेंगे।"; setTextColor(0xFFE4E9F5.toInt()) }
            val routineOutput = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f }
            addView(TextView(this@MainActivity).apply { text = "24 • SMART ROUTINE AUTOMATION"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, dp(4)) })
            addView(routineTrigger); addView(routineAction); addView(routineConsent); addView(routineOutput)
            addView(actionButton("SAVE APPROVAL-GATED ROUTINE", "Routine proposal only; no background action is executed") {
                runCatching {
                    require(routineConsent.isChecked && routineTrigger.text.isNotBlank() && routineAction.text.isNotBlank())
                    val routine = `in`.shraddha.ai.future.SmartRoutineEngine.Routine("routine-${System.currentTimeMillis()}", routineTrigger.text.toString(), routineTrigger.text.toString(), routineAction.text.toString(), true, true)
                    val proposal = `in`.shraddha.ai.future.SmartRoutineEngine.propose(routine, routineTrigger.text.toString()) ?: error("Routine did not match its trigger")
                    encryptedStore.put("advanced25_routine_latest", JSONObject().put("id", proposal.routineId).put("summary", proposal.summary).put("requiresApproval", proposal.requiresApproval).toString()).getOrThrow()
                    routineOutput.text = "Routine saved. Trigger: ${routine.trigger}\\nAction proposal: ${proposal.summary}\\nFresh user approval required: YES"
                }.onFailure { routineOutput.text = "Routine not saved: ${it.message}" }
            })

            val wakePhrase = edit("Wake phrase", "Hey Shraddha")
            val wakeConsent = CheckBox(this@MainActivity).apply { text = "मैं microphone wake phrase detection के लिए स्पष्ट अनुमति देता/देती हूँ।"; setTextColor(0xFFE4E9F5.toInt()) }
            val wakeStatus = TextView(this@MainActivity).apply { setTextColor(0xFFFFC98A.toInt()); textSize = 12f }
            var wakeGate = `in`.shraddha.ai.future.WakePhraseGate("Hey Shraddha")
            addView(TextView(this@MainActivity).apply { text = "07 • WAKE PHRASE FOUNDATION"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, dp(4)) })
            addView(wakePhrase); addView(wakeConsent); addView(wakeStatus)
            addView(actionButton("ARM WAKE PHRASE STATE", "This validates consent/state only. Acoustic wake-word model is not bundled or running.") {
                runCatching {
                    require(wakeConsent.isChecked) { "Microphone consent is required." }
                    wakeGate.deactivate()
                    wakeGate = `in`.shraddha.ai.future.WakePhraseGate(wakePhrase.text.toString())
                    wakeGate.arm(true)
                    wakeStatus.text = "Consent recorded for this session. State: ${wakeGate.state}. Acoustic model integration is still required; no background wake detection is running."
                }.onFailure { wakeStatus.text = it.message ?: "Could not arm wake phrase." }
            })
            addView(actionButton("DEACTIVATE WAKE PHRASE", "Stop this local wake phrase state") {
                wakeGate.deactivate()
                wakeConsent.isChecked = false
                wakeStatus.text = "Wake phrase state deactivated. Microphone is not started by this control."
            })

            val translationSource = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("hi", "en", "mr", "es", "fr", "de")) }
            val translationTarget = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("en", "hi", "mr", "es", "fr", "de")) }
            val translationText = edit("Text to translate", "यहाँ text लिखें").apply { isSingleLine = false; minLines = 2 }
            val translationOutput = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF0C1525.toInt(), 14) }
            addView(TextView(this@MainActivity).apply { text = "12 • AI TRANSLATION"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, dp(4)) })
            addView(translationSource); addView(translationTarget); addView(translationText); addView(translationOutput)
            addView(actionButton("TRANSLATE USING CONFIGURED AI", "Provider-based text translation • live camera overlay is not yet connected") {
                runCatching { `in`.shraddha.ai.future.LiveTranslationEngine.prepare(translationText.text.toString(), translationSource.selectedItem.toString(), translationTarget.selectedItem.toString()) }
                    .onSuccess { request ->
                        val url = VoiceGatewaySettings.get(this@MainActivity).baseUrl
                        val token = VoiceGatewaySettings.get(this@MainActivity).gatewayToken
                        if (!validRelay(url)) { translationOutput.text = "पहले Settings में secure AI backend configure करें।"; return@onSuccess }
                        translationOutput.text = "Translating…"
                        securityAudit.append("translation", "requested", "source=${request.sourceLanguage}; target=${request.targetLanguage}; text_not_logged")
                        DeviceLinkManager.askChat(url, token, "auto", "Translate the following text from ${request.sourceLanguage} to ${request.targetLanguage}. Preserve meaning and formatting. Return only the translation:\\n\\n${request.text}") { result ->
                            runOnUiThread {
                                translationOutput.text = result.getOrElse { "Translation failed: ${it.message}" }
                                securityAudit.append("translation", if (result.isSuccess) "completed" else "failed", "source=${request.sourceLanguage}; target=${request.targetLanguage}; content_not_logged")
                            }
                        }
                    }.onFailure { translationOutput.text = "Translation request invalid: ${it.message}" }
            })

            addView(actionButton("START LIVE CAMERA TRANSLATION", "हर नए paired camera frame पर AI text पढ़कर चुनी भाषा में अनुवाद करेगा") {
                val frame = lastCameraJpeg
                val settings = VoiceGatewaySettings.get(this@MainActivity)
                if (frame == null) { translationOutput.text = "पहले paired phone पर Camera Sharing चालू करें।"; return@actionButton }
                if (!validRelay(settings.baseUrl)) { translationOutput.text = "पहले HTTPS AI backend configure करें।"; return@actionButton }
                liveVisualTranslationSession?.stop()
                val session = LiveVisualTranslationSession(
                    settings.baseUrl, settings.gatewayToken, visionProvider.selectedItem.toString(),
                    translationSource.selectedItem.toString(), translationTarget.selectedItem.toString(),
                    onUpdate = { translated -> runOnUiThread {
                        if (liveVisualTranslationSession != null) translationOutput.text = translated
                    } },
                    audit = { outcome, detail -> securityAudit.append("live_visual_translation", outcome, detail) }
                )
                runCatching { session.start() }.onSuccess {
                    liveVisualTranslationSession = session
                    translationOutput.text = "Live translation चालू है। Camera frame की प्रतीक्षा…"
                    session.offerFrame(frame)
                }.onFailure { translationOutput.text = "Live translation शुरू नहीं हुई: ${it.message}" }
            })
            addView(actionButton("STOP LIVE CAMERA TRANSLATION", "Live frame analysis और translation रोकें") {
                liveVisualTranslationSession?.stop()
                liveVisualTranslationSession = null
                translationOutput.text = "Live camera translation बंद है।"
            })

            val troubleCategory = Spinner(this@MainActivity).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("network", "battery", "electrical", "general")) }
            val troubleOutput = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f; setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(0xFF0C1525.toInt(), 14) }
            addView(TextView(this@MainActivity).apply { text = "13 • VISUAL TROUBLESHOOTING PLAYBOOKS"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, dp(4)) })
            addView(troubleCategory); addView(troubleOutput)
            addView(actionButton("SHOW SAFE TROUBLESHOOTING STEPS", "Safety-first guide; not a diagnosis or automatic device repair") {
                val steps = `in`.shraddha.ai.future.VisualTroubleshootingEngine.playbook(troubleCategory.selectedItem.toString())
                troubleOutput.text = steps.mapIndexed { index, step -> "${index + 1}. ${step.instruction}\\n   Expected: ${step.expectedObservation}" }.joinToString("\\n\\n")
            })

            val notificationPrefs = getSharedPreferences(`in`.shraddha.ai.future.ShraddhaNotificationListener.PREFS, MODE_PRIVATE)
            val notificationConsent = CheckBox(this@MainActivity).apply {
                text = "मैं केवल notification metadata (app, category, समय) को locally save करने की अनुमति देता/देती हूँ।"
                setTextColor(0xFFE4E9F5.toInt())
                isChecked = notificationPrefs.getBoolean(`in`.shraddha.ai.future.ShraddhaNotificationListener.KEY_ENABLED, false)
            }
            val quietHoursConsent = CheckBox(this@MainActivity).apply {
                text = "Quiet hours metadata flag: 22:00–07:00"
                setTextColor(0xFFE4E9F5.toInt())
                isChecked = notificationPrefs.getBoolean(`in`.shraddha.ai.future.ShraddhaNotificationListener.KEY_QUIET_ENABLED, false)
            }
            val notificationStatus = TextView(this@MainActivity).apply { setTextColor(0xFFE4E9F5.toInt()); textSize = 12f }
            fun refreshNotificationStatus() {
                val enabledListeners = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
                val component = android.content.ComponentName(this@MainActivity, `in`.shraddha.ai.future.ShraddhaNotificationListener::class.java)
                val access = enabledListeners.split(':').any { it.equals(component.flattenToString(), true) }
                val events = runCatching { val saved = EncryptedFeatureStore(this@MainActivity).get(`in`.shraddha.ai.future.ShraddhaNotificationListener.ENCRYPTED_EVENTS_KEY).getOrThrow(); if (saved.isBlank()) 0 else org.json.JSONArray(saved).length() }.getOrDefault(0)
                notificationStatus.text = "OS notification access: ${if (access) "GRANTED" else "NOT GRANTED"}\nMetadata capture: ${if (notificationConsent.isChecked) "ON" else "OFF"}\nStored metadata events: $events / 100\nNotification content is never read; notifications are never dismissed."
            }
            addView(TextView(this@MainActivity).apply { text = "19 • SMART NOTIFICATION MANAGER"; setTextColor(0xFFBFAEFF.toInt()); textSize = 14f; setPadding(0, dp(12), 0, dp(4)) })
            addView(notificationConsent); addView(quietHoursConsent); addView(notificationStatus)
            addView(actionButton("SAVE NOTIFICATION PRIVACY SETTINGS", "Metadata-only, local, bounded history; off by default") {
                notificationPrefs.edit()
                    .putBoolean(`in`.shraddha.ai.future.ShraddhaNotificationListener.KEY_ENABLED, notificationConsent.isChecked)
                    .putBoolean(`in`.shraddha.ai.future.ShraddhaNotificationListener.KEY_QUIET_ENABLED, quietHoursConsent.isChecked)
                    .apply()
                refreshNotificationStatus()
            })
            addView(actionButton("GRANT ANDROID NOTIFICATION ACCESS", "Android system settings • you can revoke access any time") {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            addView(actionButton("CLEAR STORED NOTIFICATION METADATA", "Permanent local deletion") {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Clear notification history?")
                    .setMessage("Only locally stored app/category/time metadata will be deleted.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                        EncryptedFeatureStore(this@MainActivity).remove(`in`.shraddha.ai.future.ShraddhaNotificationListener.ENCRYPTED_EVENTS_KEY)
                        notificationPrefs.edit().remove(`in`.shraddha.ai.future.ShraddhaNotificationListener.KEY_EVENTS).apply()
                        refreshNotificationStatus()
                    }.show()
            })
            refreshNotificationStatus()

            addView(TextView(this@MainActivity).apply {
                text = "COVERAGE NOTE: Meeting extraction, task DAG, self-review, daily briefing, encrypted timeline, approved skill examples, routine proposals, provider text translation and troubleshooting playbooks now have working controls. Wake-word acoustic detection, live camera translation, sensor-based 3D mapping, cross-device clipboard transport and resumable encrypted file transfer still require their respective model/OS/companion integrations. Notification Intelligence now has an opt-in Android listener with metadata-only local history; it never reads notification text or dismisses alerts."
                textSize = 11f; setTextColor(0xFFFFC98A.toInt()); setPadding(0, dp(12), 0, 0)
            })
        }
    }

    private fun planReviewChecks(planText: String): List<`in`.shraddha.ai.future.SelfReviewEngine.Check> {
        if (planText.isBlank() || planText.startsWith("पहले")) return emptyList()
        return listOf(
            `in`.shraddha.ai.future.SelfReviewEngine.Check("has_order", "Dependency-aware task order was generated", planText.contains("EXECUTION ORDER")),
            `in`.shraddha.ai.future.SelfReviewEngine.Check("has_estimate", "Estimated total duration is present", planText.contains("Total estimate:")),
            `in`.shraddha.ai.future.SelfReviewEngine.Check("no_execution", "Planner did not execute external actions", planText.contains("No task has been executed."))
        )
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
            addView(actionButton("COPY CREATIVE BRIEF","Copy generated text") { val text=result.text.toString(); if(text.isNotBlank()) { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("$assistantDisplayName Creator Draft",text)); Toast.makeText(this@MainActivity,"Copied",Toast.LENGTH_SHORT).show() } })
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
            addView(actionButton("EXPORT ENCRYPTED BACKUP","AES-256-GCM • passphrase protected • selected user data only") {
                val password=EditText(this@MainActivity).apply { hint="Backup passphrase (12+ characters)"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Encrypt $assistantDisplayName backup")
                    .setMessage("Use a strong passphrase and keep it safe. It cannot be recovered if lost. Selected encrypted profiles, app workflows, checkpoints, project, digital twin, knowledge graph and memory are included. API keys, pairing tokens, permissions and biometric templates are excluded.")
                    .setView(password).setNegativeButton("Cancel",null).setPositiveButton("Encrypt & Save") { _,_ ->
                        val secret=password.text.toString().toCharArray()
                        password.text?.clear()
                        if (secret.size < 12) {
                            secret.fill('\u0000')
                            status.text = "Backup passphrase must contain at least 12 characters."
                            return@setPositiveButton
                        }
                        status.text = "Capturing selected encrypted device data and preparing backup…"
                        Thread {
                            try {
                                val recovery = `in`.shraddha.ai.cloud.DeviceRecoveryBundle.capture(this@MainActivity, 1_400_000).getOrThrow()
                                val backup = JSONObject()
                                    .put("schema", "shraddha-backup-v3")
                                    .put("createdAt", System.currentTimeMillis())
                                    .put("deviceRecoveryBundle", JSONObject(recovery))
                                val encrypted = `in`.shraddha.ai.future.EncryptedBackupCodec.encrypt(backup.toString(), secret)
                                runOnUiThread {
                                    getSharedPreferences("shraddha_backup_pending",MODE_PRIVATE).edit().putString("payload",encrypted).commit()
                                    val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/json"; putExtra(Intent.EXTRA_TITLE,"shraddha-ai-encrypted-backup-${System.currentTimeMillis()}.json") }
                                    @Suppress("DEPRECATION")
                                    launchActivityForResult(intent,backupFileRequest)
                                }
                            } catch(e:Exception) {
                                runOnUiThread { status.text = "Backup preparation failed: ${e.message}" }
                            } finally {
                                secret.fill('\u0000')
                            }
                        }.start()
                    }.show()
            })
            addView(actionButton("RESTORE ENCRYPTED BACKUP","Choose a $assistantDisplayName encrypted backup file and enter its passphrase") {
                val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/json" }
                @Suppress("DEPRECATION")
                launchActivityForResult(intent,backupImportRequest)
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
            val pushConsent = CheckBox(this@MainActivity).apply {
                text = "मैं इस device पर $assistantDisplayName के non-sensitive push notifications प्राप्त करने की अनुमति देता/देती हूँ।"
                isChecked = EncryptedFeatureStore(this@MainActivity).get("firebase_push_consent").getOrDefault("false") == "true"
                setTextColor(0xFFE4E9F5.toInt())
            }
            addView(pushConsent)
            addView(actionButton("REGISTER THIS DEVICE FOR PUSH", "Firebase Cloud Messaging • encrypted token • owner-only Firestore registration") {
                if (!pushConsent.isChecked) { result.text = "पहले push notification consent दें।"; return@actionButton }
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    androidx.core.app.ActivityCompat.requestPermissions(this@MainActivity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 731)
                    result.text = "Notification permission माँगी गई है। अनुमति देने के बाद इस button को फिर दबाएँ।"
                    return@actionButton
                }
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                result.text = "FCM token प्राप्त करके इस account के साथ सुरक्षित रूप से register किया जा रहा है…"
                Thread {
                    val registered = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity)
                        .signInAndRegisterPushToken(emailValue, passwordValue, "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
                    runOnUiThread {
                        result.text = registered.fold(
                            { "इस device का FCM token encrypted local storage और owner-only Firestore device record में register हुआ। Firebase Console से test message भेजकर जाँचें।" },
                            { "Push registration failed: ${it.message}" }
                        )
                    }
                }.start()
            })
            addView(actionButton("DISABLE PUSH & REMOVE THIS DEVICE", "Revoke this device's FCM token from the signed-in account") {
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                result.text = "इस account से device push registration हटाई जा रही है…"
                Thread {
                    val revoked = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity)
                        .signInAndUnregisterPushToken(emailValue, passwordValue)
                    runOnUiThread {
                        result.text = revoked.fold(
                            { "Push token और owner-only device record हटाए गए। इस device पर $assistantDisplayName push notifications बंद हैं।" },
                            { "Push revocation failed: ${it.message}" }
                        )
                    }
                }.start()
            })
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
                text = "मैं person/child profiles, app-training workflows, task inbox, project, digital twin, knowledge graph और memory को encrypted Firebase backup/restore करने की अनुमति देता/देती हूँ। Biometric templates इसमें शामिल नहीं होंगे।"
                setTextColor(0xFFE4E9F5.toInt())
            }
            addView(bundleConsent)
            addView(actionButton("BACK UP SELECTED DEVICE MEMORY", "Encrypted person/child profiles • app training • workflows • project • memory") {
                if (!bundleConsent.isChecked) { result.text = "पहले selected-data backup consent दें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                result.text = "Local encrypted records जाँचे और recovery bundle बनाया जा रहा है…"
                Thread {
                    val captured = `in`.shraddha.ai.cloud.DeviceRecoveryBundle.capture(this@MainActivity)
                    val saved = captured.fold(
                        onSuccess = { bundle ->
                            `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity)
                                .signInAndSaveBundle(emailValue, passwordValue, bundle)
                        },
                        onFailure = { Result.failure(it) }
                    )
                    runOnUiThread {
                        result.text = saved.fold(
                            { "Encrypted device recovery backup saved. No face/voice biometric templates were uploaded." },
                            { "Backup failed safely: ${it.message}" }
                        )
                    }
                }.start()
            })
            addView(actionButton("BACK UP LARGER SNAPSHOT TO FIREBASE STORAGE", "Encrypted file upload • up to 1.4 MB selected data • last 10 revisions retained") {
                if (!bundleConsent.isChecked) { result.text = "पहले selected-data backup consent दें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                result.text = "Larger encrypted recovery file तैयार और Firebase Storage पर upload हो रही है…"
                Thread {
                    val captured = `in`.shraddha.ai.cloud.DeviceRecoveryBundle.capture(this@MainActivity, 1_400_000)
                    val saved = captured.fold(
                        onSuccess = { bundle ->
                            `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity)
                                .signInAndUploadStorageBundle(emailValue, passwordValue, bundle)
                        },
                        onFailure = { Result.failure(it) }
                    )
                    runOnUiThread {
                        result.text = saved.fold(
                            { "Encrypted Firebase Storage backup uploaded. Revision: $it. Face/voice templates and credentials are excluded." },
                            { "Storage backup failed safely: ${it.message}" }
                        )
                    }
                }.start()
            })
            addView(actionButton("RESTORE SELECTED DEVICE MEMORY", "Preview overwrite scope and confirm before restoring") {
                if (!bundleConsent.isChecked) { result.text = "पहले selected-data restore consent दें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                result.text = "Encrypted recovery bundle download और decrypt हो रहा है…"
                Thread {
                    val restored = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity)
                        .signInAndLoadBundle(emailValue, passwordValue)
                    runOnUiThread {
                        restored.onSuccess { raw ->
                            if (raw.isNullOrBlank()) {
                                result.text = "इस Firebase account में device recovery backup नहीं मिला।"
                            } else {
                                showDeviceRecoveryPreview(raw, result)
                            }
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
                                                    if (raw.isNullOrBlank()) result.text = "Selected snapshot खाली है।"
                                                    else showDeviceRecoveryPreview(raw, result)
                                                }.onFailure { result.text = "Snapshot restore failed: ${it.message}" }
                                            }
                                        }.start()
                                    }.setNegativeButton("CLOSE", null).show()
                            }
                        }
                    }
                }.start()
            })
            addView(actionButton("FIREBASE STORAGE HISTORY • RESTORE", "पिछले अधिकतम 10 encrypted file backups में से चुनें") {
                if (!bundleConsent.isChecked) { result.text = "पहले selected-data restore consent दें।"; return@actionButton }
                if (email.text.isNullOrBlank() || password.text.length < 8) { result.text = "Email और password भरें।"; return@actionButton }
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                result.text = "Firebase Storage recovery history खोजी जा रही है…"
                Thread {
                    val vault = `in`.shraddha.ai.cloud.FirebaseCloudVault(this@MainActivity)
                    val listed = vault.signInAndListStorageRevisions(emailValue, passwordValue)
                    runOnUiThread {
                        if (listed.isFailure) {
                            result.text = "Storage history failed: ${listed.exceptionOrNull()?.message}"
                        } else {
                            val revisions = listed.getOrDefault(emptyList())
                            if (revisions.isEmpty()) {
                                result.text = "इस Firebase account में Storage backups नहीं मिले।"
                            } else {
                                val labels = revisions.map { revision ->
                                    val date = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(revision.updatedAt))
                                    "$date • ${revision.sizeBytes / 1024} KB"
                                }.toTypedArray()
                                android.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Choose encrypted Storage snapshot")
                                    .setItems(labels) { _, which ->
                                        val selected = revisions[which]
                                        result.text = "Selected encrypted Storage file download और decrypt हो रही है…"
                                        Thread {
                                            val loaded = vault.signInAndLoadStorageRevision(emailValue, passwordValue, selected.id)
                                            runOnUiThread {
                                                loaded.onSuccess { raw ->
                                                    if (raw.isNullOrBlank()) result.text = "Selected Storage snapshot खाली है।"
                                                    else showDeviceRecoveryPreview(raw, result)
                                                }.onFailure { result.text = "Storage restore failed: ${it.message}" }
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

    private fun showDeviceRecoveryPreview(raw: String, output: TextView) {
        val preview = `in`.shraddha.ai.cloud.DeviceRecoveryBundle.preview(raw).getOrElse {
            output.text = "Recovery preview rejected: ${it.message}"
            return
        }
        val details = listOf(
            "Encrypted records: ${preview.recordCount}",
            "App training profiles: ${preview.appProfileCount}",
            "Workflow checkpoints: ${preview.workflowCheckpointCount}",
            "Expert projects: ${preview.expertProjectCount}",
            "Active project: ${if (preview.hasActiveProject) "included" else "empty"}",
            "Private memory: ${if (preview.hasMemory) "included" else "empty"}",
            "Knowledge graph: ${if (preview.hasKnowledgeGraph) "included" else "empty"}",
            "Face/voice biometric templates: never included"
        ).joinToString("\n")
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore device recovery v${preview.version}?")
            .setMessage(details + "\n\nThis replaces the selected local collections, trained app profiles, workflows, expert projects, project, digital twin, knowledge graph and private memory. Existing selected local data will be overwritten. Continue only if this is the intended backup.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RESTORE") { _, _ ->
                output.text = "Validating and restoring encrypted device data…"
                Thread {
                    val restored = `in`.shraddha.ai.cloud.DeviceRecoveryBundle.restore(this@MainActivity, raw)
                    runOnUiThread {
                        output.text = restored.fold(
                            { "Recovery complete: ${it.recordCount} encrypted records, ${it.appProfileCount} app profiles, ${it.workflowCheckpointCount} workflow checkpoints and selected preferences restored." },
                            { "Recovery was rolled back where possible: ${it.message}" }
                        )
                    }
                }.start()
            }.show()
    }

    private fun addKidsUniverse(root: LinearLayout) {
        val store = EncryptedFeatureStore(this)
        val ageOptions = listOf("0–6 months", "6–12 months", "1–2 years", "2–3 years", "3–4 years", "4–5 years", "5–6 years")
        val age = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ageOptions) }
        val childName = edit("Child nickname (optional)", "Only stored on this device")
        val parentName = edit("Parent / guardian name", "Optional")
        val saved = store.get("kids_profile").getOrDefault("")
        val old = runCatching { JSONObject(saved) }.getOrNull()
        if (old != null) {
            childName.setText(old.optString("child")); parentName.setText(old.optString("guardian"))
            val idx = ageOptions.indexOf(old.optString("age")); if (idx >= 0) age.setSelection(idx)
        }
        section(root, "BABY CARE & CHILD PROFILE", "0–6 वर्ष • अभिभावक-नियंत्रित, डिवाइस पर encrypted रिकॉर्ड") {
            addView(childName); addView(parentName); addView(age)
            addView(actionButton("Save Child Profile", "जानकारी encrypted local storage में रहेगी") {
                val obj = JSONObject().put("child", childName.text.toString().take(60)).put("guardian", parentName.text.toString().take(60)).put("age", age.selectedItem.toString())
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
        section(root, "PARENT SAFETY NOTES", "बच्चे की सुरक्षा और वास्तविक विकास सबसे पहले") {
            addView(TextView(this@MainActivity).apply { text = "0–2 वर्ष: यह मॉड्यूल माता-पिता के लिए care journal और real-world activity guide है; शिशु को स्क्रीन पर पढ़ाने का विकल्प नहीं। Feeding, sleep, growth और vaccination की जानकारी अभिभावक/चिकित्सक से सत्यापित करें। यह medical advice, diagnosis, cry detection या emergency alert system नहीं है। कैमरा/माइक background में निगरानी नहीं करते।"; setTextColor(0xFFFFD7A8.toInt()); textSize = 12f })
            addView(actionButton("Delete All Kids Data", "बच्चे की प्रोफाइल और journal हटाएँ") {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Delete kids data?").setMessage("यह इस फोन से kids profile और care journal हटाएगा।").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> store.remove("kids_profile"); store.remove("kids_care_log"); status.text = "Kids data इस डिवाइस से हटाया गया।" }.show()
            })
        }
    }

    private fun addPersonMemory(root: LinearLayout) {
        val store = EncryptedFeatureStore(this)
        val biometricVault = BiometricConsentVault(store)
        val personName = edit("Person name", "Example: Rahul")
        val relation = edit("Relationship / introduction", "Friend, family, colleague…")
        val notes = edit("Optional notes", "Only information you are allowed to store")
        val consent = Switch(this).apply { text = "इस profile या live capture में सामने मौजूद व्यक्ति की स्पष्ट सहमति प्राप्त है"; setTextColor(-1); textSize = 13f; setPadding(0, dp(8), 0, dp(10)) }
        var refreshProfileList: (() -> Unit)? = null
        var directoryReadFailed = false
        fun readPeople(): JSONArray {
            return try {
                val raw = store.get("person_directory").getOrThrow()
                val arr = if (raw.isBlank()) JSONArray() else JSONArray(raw)
                require(arr.length() <= 50) { "Person Memory directory limit exceeded" }
                var changed = false
                val seenIds = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val person = arr.optJSONObject(i) ?: error("Person Memory contains an invalid profile entry")
                    var id = person.optString("id")
                    if (id.isBlank()) {
                        id = java.util.UUID.randomUUID().toString().replace("-", "")
                        person.put("id", id)
                        changed = true
                    }
                    require(id.matches(Regex("[A-Za-z0-9_-]{8,64}"))) { "Person Memory contains an invalid profile ID" }
                    require(seenIds.add(id)) { "Person Memory contains duplicate profile IDs" }
                }
                if (changed) store.put("person_directory", arr.toString()).getOrThrow()
                arr
            } catch (error: Exception) {
                directoryReadFailed = true
                status.text = "Person Memory data सुरक्षित रूप से पढ़ा नहीं जा सका। मौजूदा डेटा overwrite नहीं किया गया: ${error.message}"
                JSONArray()
            }
        }
        fun people(): List<JSONObject> {
            val arr = readPeople()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optBoolean("consent", false) }
        }
        section(root, "PERSON DIRECTORY", "परिचित व्यक्तियों के consent-based profiles • encrypted, local-only") {
            addView(personName); addView(relation); addView(notes); addView(consent)
            addView(actionButton("Save Person Profile", "व्यक्ति की स्पष्ट अनुमति के बाद profile सुरक्षित करें") {
                if (directoryReadFailed) { status.text = "Person Memory data पढ़ने में समस्या है; overwrite से बचने के लिए save रोक दिया गया है।"; return@actionButton }
                val name = personName.text.toString().trim()
                if (name.isBlank() || !consent.isChecked) { status.text = "नाम और व्यक्ति की स्पष्ट सहमति आवश्यक है।"; return@actionButton }
                val arr = readPeople()
                if (arr.length() >= 50) { status.text = "Person Memory में अधिकतम 50 profiles रख सकते हैं।"; return@actionButton }
                val id = java.util.UUID.randomUUID().toString().replace("-", "")
                arr.put(JSONObject().put("id", id).put("name", name.take(80)).put("relation", relation.text.toString().take(100)).put("notes", notes.text.toString().take(500)).put("consent", true).put("consentAt", System.currentTimeMillis()).put("createdAt", System.currentTimeMillis()))
                store.put("person_directory", arr.toString()).onSuccess { status.text = "Person profile सुरक्षित रूप से सेव हुआ।"; personName.text.clear(); relation.text.clear(); notes.text.clear(); consent.isChecked = false; refreshProfileList?.invoke() }.onFailure { status.text = "Save error: ${it.message}" }
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
                            if (directoryReadFailed) {
                                status.text = "Person directory could not be read safely; withdrawal was not attempted. Resolve the storage error first."
                                return@setPositiveButton
                            }
                            val updated = JSONArray()
                            val all = readPeople()
                            for (i in 0 until all.length()) {
                                val person = all.optJSONObject(i) ?: continue
                                if (person.optString("id") != selected.optString("id")) updated.put(person)
                            }
                            if (directoryReadFailed) {
                                status.text = "Person directory became unreadable; withdrawal was not attempted."
                                return@setPositiveButton
                            }
                            biometricVault.withdraw(selected.optString("id"), updated.toString())
                                .onSuccess {
                                    status.text = "Consent withdrawn; local profile, face templates, voice templates and thresholds deleted."
                                    refreshProfileList?.invoke()
                                }
                                .onFailure { status.text = "Atomic consent withdrawal failed: ${it.message}" }
                        }.show()
                }.show()
            })
        }
        section(root, "FACE & SPEAKER RECOGNITION", "On-device SFace + ECAPA • randomized live challenge • encrypted local templates") {
            addView(TextView(this@MainActivity).apply {
                text = "SFace और ECAPA models सामान्य build में bundled और checksum-verified हैं; पहली capture पर app उन्हें private storage में install करेगा। अलग से model import करने की जरूरत नहीं है। हर enrollment से पहले व्यक्ति की स्पष्ट सहमति switch ON करनी होगी। Camera में blink + head-turn challenge और speaker के लिए on-device random phrase challenge चलेगा। Match केवल locally calibrated threshold पर होगा; Unknown default है। यह certified anti-spoofing नहीं है; photo/video replay और advanced spoof से धोखा संभव है।"
                setTextColor(0xFFB9C8E4.toInt()); textSize = 12f
            })
            val profileSpinner = Spinner(this@MainActivity)
            val profileStatus = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(0xFF9CB4D7.toInt())
                setPadding(0, dp(4), 0, dp(8))
            }
            fun updateBiometricStatus() {
                val selected = people().getOrNull(profileSpinner.selectedItemPosition)
                if (selected == null) {
                    profileStatus.text = if (directoryReadFailed) "Person Memory सुरक्षित रूप से पढ़ा नहीं जा सका।" else "पहले consented person profile बनाएँ।"
                    return
                }
                biometricVault.templateCounts(selected.optString("id"))
                    .onSuccess { counts -> profileStatus.text = "Saved samples • Face: ${counts.face}/8 • Voice: ${counts.speaker}/8" }
                    .onFailure { profileStatus.text = "Biometric template status unavailable: ${it.message}" }
            }
            fun refreshProfiles() {
                val selectedPosition = profileSpinner.selectedItemPosition
                val labels = people().map { it.optString("name") + " • " + it.optString("relation") }
                profileSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, if (labels.isEmpty()) listOf("No consented people") else labels)
                if (selectedPosition in labels.indices) profileSpinner.setSelection(selectedPosition)
                profileSpinner.post { updateBiometricStatus() }
            }
            profileSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    consent.isChecked = false
                    updateBiometricStatus()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                    consent.isChecked = false
                    profileStatus.text = "Select a consented person profile."
                }
            }
            refreshProfiles()
            refreshProfileList = { refreshProfiles(); refreshBiometricStatus?.invoke() }
            addView(TextView(this@MainActivity).apply { text = "CONSENTED PERSON"; setTextColor(0xFFB9C8E4.toInt()) })
            addView(profileSpinner)
            addView(profileStatus)
            val faceThreshold = edit("Face cosine threshold (auto-calibrated)", "").apply { keyListener = null; isFocusable = false; isFocusableInTouchMode = false }
            val speakerThreshold = edit("Speaker cosine threshold (auto-calibrated)", "").apply { keyListener = null; isFocusable = false; isFocusableInTouchMode = false }
            addView(faceThreshold); addView(speakerThreshold)
            fun loadSavedThresholds() {
                biometricVault.loadThreshold("face").onSuccess { value ->
                    faceThreshold.setText(value?.let { "%.4f".format(java.util.Locale.US, it) } ?: "")
                }.onFailure { faceThreshold.text.clear(); status.text = "Stored face threshold invalid: ${it.message}" }
                biometricVault.loadThreshold("speaker").onSuccess { value ->
                    speakerThreshold.setText(value?.let { "%.4f".format(java.util.Locale.US, it) } ?: "")
                }.onFailure { speakerThreshold.text.clear(); status.text = "Stored speaker threshold invalid: ${it.message}" }
            }
            refreshBiometricStatus = { updateBiometricStatus(); loadSavedThresholds() }
            refreshBiometricStatus?.invoke()
            addView(actionButton("CALIBRATE FACE THRESHOLD", "कम-से-कम 2 consented लोगों के 2-2 face samples से genuine/impostor score separation जाँचें") {
                faceThreshold.text.clear()
                biometricVault.calibrateThreshold("face").onSuccess { result ->
                    biometricVault.saveThreshold("face", result.threshold)
                        .onSuccess {
                            faceThreshold.setText("%.4f".format(java.util.Locale.US, result.threshold))
                            status.text = "Face threshold calibrated and saved: %.4f • genuine min %.3f • impostor max %.3f • %d/%d pairs".format(java.util.Locale.US, result.threshold, result.minimumGenuine, result.maximumImpostor, result.genuinePairs, result.impostorPairs)
                        }
                        .onFailure { faceThreshold.text.clear(); status.text = "Face threshold save failed: ${it.message}" }
                }.onFailure {
                    val cleared = biometricVault.clearThreshold("face")
                    faceThreshold.text.clear()
                    status.text = if (cleared) "Face calibration blocked: ${it.message}" else "Face calibration blocked and previous threshold could not be cleared. Do not use face matching until recalibrated."
                }
            })
            addView(actionButton("CALIBRATE SPEAKER THRESHOLD", "कम-से-कम 2 consented लोगों के 2-2 voice samples से threshold निकालें") {
                speakerThreshold.text.clear()
                biometricVault.calibrateThreshold("speaker").onSuccess { result ->
                    biometricVault.saveThreshold("speaker", result.threshold)
                        .onSuccess {
                            speakerThreshold.setText("%.4f".format(java.util.Locale.US, result.threshold))
                            status.text = "Speaker threshold calibrated and saved: %.4f • genuine min %.3f • impostor max %.3f • %d/%d pairs".format(java.util.Locale.US, result.threshold, result.minimumGenuine, result.maximumImpostor, result.genuinePairs, result.impostorPairs)
                        }
                        .onFailure { speakerThreshold.text.clear(); status.text = "Speaker threshold save failed: ${it.message}" }
                }.onFailure {
                    val cleared = biometricVault.clearThreshold("speaker")
                    speakerThreshold.text.clear()
                    status.text = if (cleared) "Speaker calibration blocked: ${it.message}" else "Speaker calibration blocked and previous threshold could not be cleared. Do not use speaker matching until recalibrated."
                }
            })
            fun launchCapture(kind: String, mode: String) {
                val availablePeople = people()
                val selected = availablePeople.getOrNull(profileSpinner.selectedItemPosition)
                if (mode == "identify" && availablePeople.isEmpty()) { status.text = "पहले कम-से-कम एक consented Person Profile enroll करें।"; return }
                if (mode != "identify" && selected == null) { status.text = "पहले dropdown से consented Person Profile चुनें।"; return }
                if (!consent.isChecked) { status.text = "इस live capture में सामने मौजूद व्यक्ति की स्पष्ट session consent की पुष्टि करें।"; return }
                val thresholdText = if (kind == "face") faceThreshold.text.toString().trim() else speakerThreshold.text.toString().trim()
                val threshold = if (mode != "enroll") thresholdText.toFloatOrNull() else Float.NaN
                if (mode != "enroll" && (threshold == null || threshold !in -1f..1f)) { status.text = "Match/identify के लिए पहले locally calibrated threshold -1 से 1 के बीच भरें।"; return }
                val request = Intent(this@MainActivity, BiometricCaptureActivity::class.java)
                    .putExtra(BiometricCaptureActivity.EXTRA_KIND, kind)
                    .putExtra(BiometricCaptureActivity.EXTRA_MODE, mode)
                    .putExtra(BiometricCaptureActivity.EXTRA_PERSON_ID, selected?.optString("id").orEmpty())
                    .putExtra(BiometricCaptureActivity.EXTRA_PERSON_NAME, selected?.optString("name").orEmpty())
                    .putExtra(BiometricCaptureActivity.EXTRA_CONSENT, true)
                    .putExtra(BiometricCaptureActivity.EXTRA_THRESHOLD, threshold ?: Float.NaN)
                consent.isChecked = false
                securityAudit.append("biometric_capture", "started", "kind=$kind; mode=$mode; explicit_session_consent=true")
                launchActivityForResult(request, if (kind == "face") faceCaptureRequest else speakerCaptureRequest)
            }
            addView(actionButton("ENROLL FACE • LIVE CAMERA", "Random blink + head-turn liveness check; camera runs only on this screen") { launchCapture("face", "enroll") })
            addView(actionButton("MATCH FACE • SELECTED PERSON", "Compare only with the selected consented profile") { launchCapture("face", "match") })
            addView(actionButton("IDENTIFY FACE • ALL CONSENTED PEOPLE", "Find the closest passing match across every separately consented face profile; Unknown if none passes") { launchCapture("face", "identify") })
            addView(actionButton("ENROLL SPEAKER • LIVE MICROPHONE", "On-device random phrase check then 5-second voice sample") { launchCapture("speaker", "enroll") })
            addView(actionButton("MATCH SPEAKER • SELECTED PERSON", "On-device random phrase check then compare only with the selected consented voiceprint") { launchCapture("speaker", "match") })
            addView(actionButton("IDENTIFY SPEAKER • ALL CONSENTED PEOPLE", "Find the closest passing match across all separately consented speaker profiles; Unknown if none passes") { launchCapture("speaker", "identify") })
            addView(actionButton("DELETE ALL BIOMETRIC TEMPLATES", "स्थानीय face और speaker templates मिटाएँ") {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Delete all biometric templates?").setMessage("यह सभी enrolled face और voice templates मिटाएगा।")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        val ok = biometricVault.clear()
                        status.text = if (ok) "All local biometric templates deleted." else "Some biometric records could not be deleted."
                        refreshBiometricStatus?.invoke()
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
        DeviceLinkManager.onE2EVerification = { peerId, fingerprint ->
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Verify paired-device encryption")
                    .setMessage("Device: " + (peerNames[peerId] ?: peerId) + "\n\nFingerprint:\n" + fingerprint + "\n\nCompare this exact code with the other device through a separate trusted channel. Only confirm if both screens show the same code. A mismatch may indicate interception.")
                    .setNegativeButton("Do not trust", null)
                    .setPositiveButton("Fingerprint matches") { _, _ ->
                        val sent = DeviceLinkManager.confirmE2EPeer(peerId, fingerprint)
                        status.text = if (sent) "Local fingerprint confirmed. Waiting for the other device to confirm too." else "Could not confirm fingerprint; reconnect and try again."
                    }.show()
            }
        }
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
                            val peer = peers.optJSONObject(i) ?: continue
                            peerNames[ids[i]] = peer.optString("name", "Device")
                            peerOnline[ids[i]] = peer.optBoolean("online")
                        }
                        if (ids.isNotEmpty()) lastPeerId = ids.first()
                        peerPicker.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("No paired devices") })
                        if (targetDevice.text.isNullOrBlank()) targetDevice.setText(lastPeerId.orEmpty())
                    }
                }
                runOnUiThread { mainOnlyMode.isChecked = msg.optString("controlMode") == "master"; publishDataPermissions() }
            } else if (msg.optString("type") == "device_revoked") {
                val revokedId = msg.optString("deviceId")
                runOnUiThread {
                    peerIds.remove(revokedId); peerNames.remove(revokedId); peerOnline.remove(revokedId)
                    if (targetDevice.text.toString() == revokedId) targetDevice.setText("")
                    if (::peerPicker.isInitialized) {
                        val labels = peerIds.map { id -> "${peerNames[id] ?: "Device"} • ${if (peerOnline[id] == true) "Online" else "Offline"}" }
                        peerPicker.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels.ifEmpty { listOf("No paired devices") })
                    }
                    status.text = "Device $revokedId revoked. Relay access removed."
                }
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
                if (pendingWindowsVoiceAction != null && msg.optString("targetDeviceId") == pendingWindowsVoiceTarget) {
                    val action = pendingWindowsVoiceAction!!
                    val target = pendingWindowsVoiceTarget
                    pendingWindowsVoiceAction = null
                    if (approved) {
                        if (DeviceLinkManager.sendAction(target, action)) runOnUiThread { status.text = "PC पर command भेजी गई। Windows की per-action confirmation और परिणाम की प्रतीक्षा है।" }
                        else runOnUiThread { status.text = "Permission मिली, लेकिन command relay पर नहीं भेजी जा सकी।" }
                    } else runOnUiThread { status.text = "Windows PC ने control request अस्वीकार की।" }
                }
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
                    if (approved) {
                        approvedShareRequestId = msg.optString("requestId")
                        approvedShareTarget = msg.optString("targetDeviceId")
                        approvedShareCategory = msg.optString("category")
                        approvedShareExpiresAt = System.currentTimeMillis() + msg.optLong("expiresInMinutes", 0L) * 60_000L
                    } else {
                        approvedShareRequestId = null
                        approvedShareTarget = null
                        approvedShareCategory = null
                        approvedShareExpiresAt = 0L
                    }
                    status.text = if (approved) "Permission approved for ${msg.optString("category")}. अब चुनी file भेज सकते हैं।" else "Data sharing request अस्वीकार हुई।"
                    if (approved) Toast.makeText(this, "इस exact request के लिए one-time permission मिली।", Toast.LENGTH_LONG).show()
                }
            } else if (msg.optString("type") == "data_share_payload") {
                runCatching {
                    val encoded = msg.getString("payload")
                    require(encoded.length <= 900_000) { "Transfer payload too large" }
                    val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                    val expectedSize = msg.getLong("sizeBytes")
                    val expectedHash = msg.getString("sha256").lowercase()
                    require(expectedSize in 1L..650_000L && bytes.size.toLong() == expectedSize) { "File size mismatch" }
                    require(expectedHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid file digest" }
                    val actualHash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    require(actualHash == expectedHash) { "File integrity verification failed" }
                    if (msg.optString("contentType") == "clipboard_text") {
                        require(msg.optString("category") == "clipboard" && bytes.size <= 80_000) { "Invalid clipboard packet" }
                        val receivedText = bytes.toString(Charsets.UTF_8)
                        require(receivedText.isNotBlank() && receivedText.length <= 20_000) { "Clipboard text limit exceeded" }
                        runOnUiThread {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Clipboard text received")
                                .setMessage("Paired device से text आया है। इसे इस फोन के clipboard में copy करना है?\n\n" + receivedText.take(800))
                                .setNegativeButton("Discard", null)
                                .setPositiveButton("Copy to Clipboard") { _, _ ->
                                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Shraddha shared text", receivedText))
                                    status.text = "User-approved clipboard text copied."
                                }.show()
                        }
                    } else {
                        val folder = java.io.File(filesDir, "shared").apply { mkdirs() }
                        val safeName = msg.optString("fileName", "shared_file").replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "shared_file" }
                        val destination = java.io.File(folder, safeName)
                        val temp = java.io.File(folder, ".${safeName}.${java.util.UUID.randomUUID()}.part")
                        try {
                            temp.outputStream().use { it.write(bytes); it.fd.sync() }
                            require(temp.renameTo(destination)) { "Could not commit verified file" }
                        } finally { if (temp.exists()) temp.delete() }
                        runOnUiThread { status.text = "Verified file received: $safeName (${bytes.size} bytes, SHA-256 OK). App storage में सुरक्षित है।" }
                    }
                }.onFailure { runOnUiThread { status.text = "File rejected: ${it.message}" } }
            }
        }
        val appContext = applicationContext
        DeviceLinkManager.onRemoteActionWithId = { fromDeviceId, actionId, action ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val prefs = appContext.getSharedPreferences("shraddha_device_link", MODE_PRIVATE)
                val allowed = prefs.getBoolean("remote_control_consent", false)
                val sessionGranted = prefs.getLong("remote_control_grant_until", 0L) > System.currentTimeMillis()
                val kind = action.optString("kind", "unknown").take(32)
                val description = when (kind) {
                    "tap" -> "Tap at (${action.optDouble("x").toInt()}, ${action.optDouble("y").toInt()})"
                    "swipe" -> "Swipe gesture"
                    "type" -> "Type text: " + action.optString("text").take(120)
                    "click_text" -> "Click visible text: " + action.optString("text").take(120)
                    "open_app" -> "Open app: " + action.optString("appName").take(80)
                    "home" -> "Go to Home screen"
                    "back" -> "Go Back"
                    "scroll" -> "Scroll current screen"
                    else -> "Unsupported action: $kind"
                }
                if (!allowed || !sessionGranted) {
                    DeviceLinkManager.sendRemoteActionResult(fromDeviceId, actionId, kind,
                        if (!allowed) "DENIED: This phone's remote-control permission is OFF."
                        else "DENIED: No active 5-minute remote-control session. Request permission first.")
                    return@post
                }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Approve remote action?")
                    .setMessage("From paired device: ${peerNames[fromDeviceId] ?: fromDeviceId}\n\nAction: $description\n\nOnly this action runs after your approval.")
                    .setNegativeButton("Deny") { _, _ ->
                        DeviceLinkManager.sendRemoteActionResult(fromDeviceId, actionId, kind, "DENIED_BY_USER: No action was executed.")
                        runCatching { `in`.shraddha.ai.future.LocalAuditTrail(appContext).append("remote_control", "action_denied", "kind=$kind; source_device_redacted") }
                    }
                    .setPositiveButton("Approve this action") { _, _ ->
                        val stillAllowed = prefs.getBoolean("remote_control_consent", false)
                        val before = ShraddhaAccessibilityService.captureVisibleScreen()
                        val executed = stillAllowed && ShraddhaAccessibilityService.applyRemoteAction(action)
                        if (!executed) {
                            DeviceLinkManager.sendRemoteActionResult(fromDeviceId, actionId, kind, "FAILED: Accessibility permission, target app, or action unavailable.")
                            runCatching { `in`.shraddha.ai.future.LocalAuditTrail(appContext).append("remote_control", "action_failed", "kind=$kind; source_device_redacted") }
                        } else {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                val after = ShraddhaAccessibilityService.captureVisibleScreen()
                                val beforeVisible = before?.elements.orEmpty().asSequence().filterNot { it.password }
                                    .map { (it.text.ifBlank { it.description }).trim() }.filter { it.isNotBlank() }.toSet()
                                val afterVisible = after?.elements.orEmpty().asSequence().filterNot { it.password }
                                    .map { (it.text.ifBlank { it.description }).trim() }.filter { it.isNotBlank() }.toSet()
                                val visible = afterVisible.take(24).joinToString(" | ").take(1800)
                                val added = (afterVisible - beforeVisible).take(12).joinToString(" | ").take(700)
                                val removed = (beforeVisible - afterVisible).take(12).joinToString(" | ").take(700)
                                val packageChanged = before != null && after != null && before.packageName != after.packageName
                                val verification = when {
                                    after == null -> "OUTCOME_UNVERIFIED: screen capture unavailable"
                                    packageChanged || added.isNotBlank() || removed.isNotBlank() -> "VISIBLE_UI_CHANGE_DETECTED"
                                    else -> "NO_VISIBLE_UI_CHANGE_DETECTED: action outcome is not proven"
                                }
                                val evidence = if (after != null) {
                                    "$verification; accessibility_dispatch_accepted=true; app=${after.packageName.take(120)}; previous_app=${before?.packageName.orEmpty().take(120)}; added_text=$added; removed_text=$removed; current_visible_text=$visible"
                                } else "OUTCOME_UNVERIFIED: screen observation unavailable; accessibility dispatch was accepted but the actual UI result is unknown."
                                DeviceLinkManager.sendRemoteActionResult(fromDeviceId, actionId, kind, evidence)
                                status.text = "Remote $kind: $verification."
                                runCatching { `in`.shraddha.ai.future.LocalAuditTrail(appContext).append("remote_control", "action_result_observed", "kind=$kind; source_device_redacted; screen_observed=${after != null}") }
                            }, 900L)
                        }
                    }
                    .setOnCancelListener { DeviceLinkManager.sendRemoteActionResult(fromDeviceId, actionId, kind, "DISMISSED: No action was executed.") }
                    .show()
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
            liveVisualTranslationSession?.offerFrame(bytes)
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
        launchActivityForResult(manager.createScreenCaptureIntent(), screenShareRequest)
    }

    private fun requestFeaturePermissions(requested: Array<String>, requestCode: Int, feature: String) {
        val missing = requested.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            status.text = "$feature permissions पहले से enabled हैं।"
            return
        }
        androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 12 || requestCode == 13) {
            val denied = permissions.filterIndexed { index, _ ->
                grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
            }
            status.text = if (denied.isEmpty()) {
                if (requestCode == 12) "Voice calling के लिए Contacts और Phone permissions enabled हैं।"
                else "Voice SMS के लिए Contacts और SMS permissions enabled हैं।"
            } else {
                "कुछ permissions नहीं मिलीं: ${denied.joinToString()}. आप इस page से दोबारा कोशिश कर सकते हैं।"
            }
            return
        }
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

    private fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
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
            securityAudit.append("biometric_capture", if (resultCode == Activity.RESULT_OK) "finished" else "cancelled", "request=${if (requestCode == faceCaptureRequest) "face" else "speaker"}; result_text_not_logged")
            refreshBiometricStatus?.invoke()
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
                check(getSharedPreferences("shraddha_backup_pending",MODE_PRIVATE).edit().remove("payload").commit()) { "Could not clear temporary encrypted backup payload" }
                `in`.shraddha.ai.future.LocalAuditTrail(this).append("backup_export","completed","encrypted user-selected backup")
                status.text="Encrypted backup export complete. Keep the passphrase and file separately."
            } catch(e:Exception) {
                getSharedPreferences("shraddha_backup_pending",MODE_PRIVATE).edit().remove("payload").commit()
                status.text="Backup export failed: ${e.message}"
            }
            return
        }
        if (requestCode == backupFileRequest) {
            getSharedPreferences("shraddha_backup_pending", MODE_PRIVATE).edit().remove("payload").commit()
            if (resultCode != Activity.RESULT_OK || data?.data == null) {
                status.text = "Backup export cancelled. Temporary encrypted payload cleared."
                return
            }
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
                            when (backup.optString("schema")) {
                                "shraddha-backup-v3" -> {
                                    val recovery = backup.optJSONObject("deviceRecoveryBundle") ?: error("Device recovery data missing")
                                    val rawRecovery = recovery.toString()
                                    val preview = `in`.shraddha.ai.cloud.DeviceRecoveryBundle.preview(rawRecovery).getOrThrow()
                                    val details = "Encrypted records: ${preview.recordCount}\nApp profiles: ${preview.appProfileCount}\nWorkflow checkpoints: ${preview.workflowCheckpointCount}\nExpert projects: ${preview.expertProjectCount}\nActive project: ${preview.hasActiveProject}\nKnowledge graph: ${preview.hasKnowledgeGraph}\nPrivate memory: ${preview.hasMemory}\nBiometric templates: excluded"
                                    android.app.AlertDialog.Builder(this@MainActivity)
                                        .setTitle("Restore encrypted backup v3?")
                                        .setMessage(details + "\n\nThis will replace the selected local records and preferences. Continue only if you trust this backup.")
                                        .setNegativeButton("CANCEL", null)
                                        .setPositiveButton("RESTORE") { _, _ ->
                                            status.text = "Validating and restoring encrypted backup…"
                                            Thread {
                                                val restored = `in`.shraddha.ai.cloud.DeviceRecoveryBundle.restore(this@MainActivity, rawRecovery)
                                                runOnUiThread {
                                                    status.text = restored.fold(
                                                        { "Encrypted backup restored: ${it.recordCount} records, ${it.appProfileCount} app profiles and ${it.workflowCheckpointCount} workflow checkpoints." },
                                                        { "Backup restore rolled back where possible: ${it.message}" }
                                                    )
                                                    if (restored.isSuccess) `in`.shraddha.ai.future.LocalAuditTrail(this@MainActivity).append("backup_restore","completed","encrypted backup v3")
                                                }
                                            }.start()
                                        }.show()
                                }
                                "shraddha-backup-v2" -> {
                                    val graph=backup.optString("knowledgeGraph","[]")
                                    JSONArray(graph)
                                    val project=backup.optString("activeProject","").take(100_000)
                                    val notes=backup.optString("localNotes","").take(8_000)
                                    val twin=backup.optJSONObject("digitalTwin") ?: JSONObject()
                                    val graphPrefs=getSharedPreferences("shraddha_knowledge_graph",MODE_PRIVATE)
                                    val projectPrefs=getSharedPreferences("shraddha_projects",MODE_PRIVATE)
                                    val twinPrefs=getSharedPreferences("shraddha_digital_twin",MODE_PRIVATE)
                                    check(graphPrefs.edit().putString("nodes",graph).commit())
                                    check(projectPrefs.edit().putString("active_project",project).commit())
                                    check(getSharedPreferences("shraddha_local_memory",MODE_PRIVATE).edit().putString("notes",notes).commit())
                                    check(twinPrefs.edit().putString("name",twin.optString("name","")).putString("language",twin.optString("language","Hindi")).putString("style",twin.optString("style","step-by-step")).commit())
                                    `in`.shraddha.ai.future.LocalAuditTrail(this).append("backup_restore","completed","legacy encrypted backup v2")
                                    status.text="Legacy encrypted backup v2 restored. It does not contain app profiles or workflow checkpoints."
                                }
                                else -> error("Unsupported backup data schema")
                            }
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
                val bytes = java.io.ByteArrayOutputStream().use { output ->
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(16 * 1024); var total = 0
                        while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                            if (total > 650_000) throw IllegalArgumentException("File exceeds 650 KB transfer limit")
                            output.write(buffer, 0, count)
                        }
                    } ?: throw IllegalArgumentException("Cannot open selected file")
                    output.toByteArray()
                }
                if (bytes.isEmpty()) { status.text = "फाइल खाली है।"; return }
                val name = queryDisplayName(uri) ?: "shared_file"
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val payload = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val requestId = approvedShareRequestId
                if (requestId.isNullOrBlank() || approvedShareTarget != target || approvedShareCategory != category || approvedShareExpiresAt <= System.currentTimeMillis()) { status.text = "इसी target और selected category के लिए नया one-time sharing approval लें।"; return }
                val sent = DeviceLinkManager.sendSharedPayload(target, category, requestId, name, mime, payload, bytes.size.toLong(), digest)
                if (sent) { approvedShareRequestId = null; approvedShareTarget = null; approvedShareCategory = null }
                status.text = if (sent) "File integrity digest सहित $target को भेजी गई। Receiver पर SHA-256 verify होगा।" else "File भेजने के लिए relay disconnected है या permission grant expire हो गया।"
            } catch (e: Exception) { status.text = "File share error: ${e.message}" }
            return
        }
        if (requestCode == windowsVoiceRequest && resultCode == Activity.RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty().trim()
            val normalized = spoken.lowercase()
            val action = when {
                normalized in listOf("वापस जाओ", "go back", "back") -> JSONObject().put("kind", "back")
                normalized in listOf("होम", "डेस्कटॉप दिखाओ", "show desktop", "go home") -> JSONObject().put("kind", "home")
                normalized in listOf("नीचे स्क्रॉल करो", "scroll down") -> JSONObject().put("kind", "scroll").put("amount", -4)
                normalized in listOf("ऊपर स्क्रॉल करो", "scroll up") -> JSONObject().put("kind", "scroll").put("amount", 4)
                normalized.startsWith("सेव पर क्लिक करो") -> JSONObject().put("kind", "click_text").put("label", "Save")
                normalized.startsWith("क्लिक करो ") -> JSONObject().put("kind", "click_text").put("label", spoken.substringAfter("क्लिक करो ").trim())
                normalized.startsWith("click ") -> JSONObject().put("kind", "click_text").put("label", spoken.substringAfter("click ").trim())
                normalized.startsWith("लिखो ") -> JSONObject().put("kind", "type").put("text", spoken.substringAfter("लिखो ").take(500))
                normalized.startsWith("type ") -> JSONObject().put("kind", "type").put("text", spoken.substringAfter("type ").take(500))
                normalized.startsWith("ऐप खोलो ") -> JSONObject().put("kind", "open_app").put("name", spoken.substringAfter("ऐप खोलो ").trim())
                normalized.startsWith("open app ") -> JSONObject().put("kind", "open_app").put("name", spoken.substringAfter("open app ").trim())
                normalized in listOf("आवाज बढ़ाओ", "volume बढ़ाओ", "volume up", "आवाज तेज करो") -> JSONObject().put("kind", "system_control").put("command", "volume_up")
                normalized in listOf("आवाज कम करो", "volume down", "आवाज घटाओ") -> JSONObject().put("kind", "system_control").put("command", "volume_down")
                normalized in listOf("कंप्यूटर लॉक करो", "पीसी लॉक करो", "lock pc", "lock computer") -> JSONObject().put("kind", "system_control").put("command", "lock")
                normalized in listOf("ब्राइटनेस बढ़ाओ", "brightness up", "screen bright करो") -> JSONObject().put("kind", "system_control").put("command", "brightness_up")
                normalized in listOf("म्यूजिक चलाओ", "play pause", "music play pause") -> JSONObject().put("kind", "media_control").put("command", "play_pause")
                normalized in listOf("अगला गाना", "next song", "next track") -> JSONObject().put("kind", "media_control").put("command", "next")
                normalized in listOf("स्क्रीनशॉट लो", "screenshot लो", "take screenshot") -> JSONObject().put("kind", "screenshot")
                normalized.startsWith("वेब पर खोजो ") -> JSONObject().put("kind", "web_search").put("query", spoken.substringAfter("वेब पर खोजो ").trim().take(160))
                normalized.startsWith("search web ") -> JSONObject().put("kind", "web_search").put("query", spoken.substringAfter("search web ").trim().take(160))
                normalized in listOf("clipboard uppercase", "क्लिपबोर्ड uppercase") -> JSONObject().put("kind", "clipboard_transform").put("operation", "uppercase")
                normalized in listOf("clipboard साफ करो", "क्लिपबोर्ड trim करो") -> JSONObject().put("kind", "clipboard_transform").put("operation", "trim")
                normalized.startsWith("code assistant ") -> JSONObject().put("kind", "code_assistant").put("prompt", spoken.substringAfter("code assistant ").trim().take(4000))
                else -> null
            }
            if (action == null) { status.text = "आवाज़ समझ नहीं आई या command allowlist में नहीं है: $spoken"; return }
            val target = targetDevice.text.toString().trim().ifBlank { lastPeerId.orEmpty() }
            if (target.isBlank()) { status.text = "पहले Windows PC चुनें।"; return }
            pendingWindowsVoiceAction = action; pendingWindowsVoiceTarget = target
            status.text = "Windows command: $spoken. PC से permission माँगी जा रही है…"
            if (!DeviceLinkManager.requestRemoteControl(target, 2)) { pendingWindowsVoiceAction = null; status.text = "Relay disconnected; command नहीं भेजा गया।" }
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
        liveVisualTranslationSession?.stop()
        liveVisualTranslationSession = null
        DeviceLinkManager.onStatus = null
        DeviceLinkManager.onPeerMessage = null
        DeviceLinkManager.onRemoteActionWithId = null
        DeviceLinkManager.onScreenFrame = null
        DeviceLinkManager.onCameraFrame = null
        Thread { LocalOfflineLlm.close() }.start()
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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN"); putExtra(RecognizerIntent.EXTRA_PROMPT, "$assistantDisplayName को अपना आदेश बोलें"); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3) }
        try { launchActivityForResult(intent, speechRequest) } catch (_: Exception) { Toast.makeText(this, "इस डिवाइस पर Speech Recognition उपलब्ध नहीं है।", Toast.LENGTH_LONG).show() }
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
private class ShraddhaOrbView(context: android.content.Context, assistantName: String) : View(context) {
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
        contentDescription = "$assistantName animated energy orb. Microphone state is shown by the voice controls."
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
