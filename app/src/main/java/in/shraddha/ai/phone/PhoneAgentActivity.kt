package `in`.shraddha.ai.phone

import android.annotation.SuppressLint

import android.app.AlertDialog
import android.app.role.RoleManager
import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.Lia.assistant.data.VoiceGatewaySettings
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PhoneAgentActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("phone_agent", Context.MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var history: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var edesyHistory: TextView
    private var tts: TextToSpeech? = null
    private val contactsPermissionRequest = 904
    private val callPermissionRequest = 902
    private var pendingContactQuery: String? = null
    private var pendingDialNumber: String? = null
    private lateinit var pendingDialButton: Button
    private val voiceCallResultLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = if (result.resultCode == RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
        if (!spoken.isNullOrBlank()) handleVoiceCallResult(spoken)
        else voiceStatus.text = "Voice command नहीं मिला। दोबारा बोलें।"
    }
    private val dialerRoleResultLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { refreshStatus() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panel(title: String, subtitle: String = ""): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF172C43.toInt(), 0xFF0B1B2D.toInt())
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF31536B.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(13) }
            addView(TextView(this@PhoneAgentActivity).apply {
                text = title; textSize = 15f; setTextColor(-1)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            if (subtitle.isNotBlank()) addView(TextView(this@PhoneAgentActivity).apply {
                text = subtitle; textSize = 11f; setTextColor(0xFF9CBBCB.toInt())
                setPadding(0, dp(4), 0, dp(11))
            })
        }

    private fun addActionPair(
        parent: LinearLayout,
        firstLabel: String,
        first: () -> Unit,
        secondLabel: String,
        second: () -> Unit
    ) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(actionButton(firstLabel, first), LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(6) })
        line.addView(actionButton(secondLabel, second), LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(6) })
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val assistantName = NovaPreferences.getString(
            this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME
        ).trim().ifBlank { AssistantBrand.NAME }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(22))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF050B14.toInt(), 0xFF0A1B2A.toInt(), 0xFF07111D.toInt())
            )
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(17), dp(15), dp(17))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF245A78.toInt(), 0xFF17365A.toInt(), 0xFF14233F.toInt())
            ).apply { cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0xFF5BBDD0.toInt()) }
            elevation = dp(7).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        }
        hero.addView(TextView(this).apply {
            text = "☎"; textSize = 29f; gravity = android.view.Gravity.CENTER
            setTextColor(-1)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x443DE0E2); setStroke(dp(1), 0x8878E8E8.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(13) }
        })
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PhoneAgentActivity).apply {
                text = "$assistantName"; textSize = 20f; setTextColor(-1)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@PhoneAgentActivity).apply {
                text = "AI PHONE AGENT"; textSize = 10f; letterSpacing = 0.16f
                setTextColor(0xFF75E5E8.toInt())
            })
            addView(TextView(this@PhoneAgentActivity).apply {
                text = "Voice-first calling • Call notes • Caller events"
                textSize = 11f; setTextColor(0xFFD0E7F0.toInt())
                setPadding(0, dp(5), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(hero)

        val statusPanel = panel("PHONE STATUS", "यह डिवाइस की वास्तविक Android phone-role स्थिति दिखाता है")
        status = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF80E6C1.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF091927.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF28556A.toInt())
            }
        }
        statusPanel.addView(status)
        addActionPair(statusPanel, "↻ Refresh status", { refreshStatus() }, "Set default phone app", { requestDialerRole() })
        root.addView(statusPanel)

        val voicePanel = panel("VOICE CALL COMMAND", "किसी Contact का नाम बोलें — कॉल से पहले पहचान और अनुमति जाँची जाएगी")
        voiceStatus = TextView(this).apply {
            text = "उदाहरण: ‘श्रद्धा, अमित को फोन लगाओ’"
            textSize = 13f; setTextColor(0xFFD9EDF3.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF091927.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF28556A.toInt())
            }
        }
        voicePanel.addView(voiceStatus)
        voicePanel.addView(actionButton("🎙  बोलकर Contact खोजें", { startVoiceCall() }))
        pendingDialButton = actionButton("Review and place dialed number") {
            pendingDialNumber?.let { number ->
                pendingDialNumber = null
                pendingDialButton.visibility = android.view.View.GONE
                placeCall(number)
            }
        }.apply { visibility = android.view.View.GONE }
        voicePanel.addView(pendingDialButton)
        root.addView(voicePanel)

        val historyPanel = panel("CALL JOURNAL", "फोन पर रिकॉर्ड हुए कॉल इवेंट और शेयर करने योग्य नोट्स")
        history = TextView(this).apply {
            textSize = 12f; setTextColor(0xFFE3F1F5.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF091927.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF28556A.toInt())
            }
        }
        historyPanel.addView(history)
        historyPanel.addView(actionButton("Share call summary", { shareCallSummary() }))
        root.addView(historyPanel)

        val aiPanel = panel("CLOUD AI CALLS", "Edesy inbound AI call history — backend configuration आवश्यक")
        edesyHistory = TextView(this).apply {
            text = "Edesy status अभी जाँचा नहीं गया है।"
            textSize = 12f; setTextColor(0xFFE3F1F5.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF091927.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF28556A.toInt())
            }
        }
        aiPanel.addView(edesyHistory)
        aiPanel.addView(actionButton("↻ Refresh Edesy AI call history", { refreshEdesyHistory() }))
        root.addView(aiPanel)

        val safety = panel("CALL SAFETY", "SIM कॉल का ऑडियो Android ऐप सीधे intercept नहीं कर सकता")
        safety.addView(TextView(this).apply {
            text = "पहले Edesy DID पर direct test call, दो-तरफ़ा audio और hang-up जाँचें। उसके बाद ही carrier की no-answer forwarding सेट करें। Unconditional forwarding बंद रखें। Contact match अस्पष्ट होने पर कॉल नहीं लगाई जाएगी।"
            textSize = 12f; setTextColor(0xFFFFD09B.toInt())
        })
        root.addView(safety)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            addView(root)
        })
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) tts?.language = Locale("hi", "IN")
        }
        handleDialIntent(intent)
        handlePendingContactIntent(intent)
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
        handlePendingContactIntent(intent)
    }

    private fun handlePendingContactIntent(source: Intent?) {
        val query = source?.getStringExtra(EXTRA_PENDING_CONTACT_QUERY)?.trim().orEmpty()
        if (query.isBlank()) return
        pendingContactQuery = query
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            pendingContactQuery = null
            handleVoiceCallResult(query)
        } else {
            voiceStatus.text = "कॉल से पहले Contacts permission जरूरी है। अनुमति दें।"
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), contactsPermissionRequest)
        }
    }

    private fun handleDialIntent(source: Intent?) {
        val number = source?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
        if (!number.isNullOrBlank()) {
            // ACTION_DIAL displays a number; it is not authorization to place a call.
            pendingDialNumber = number
            voiceStatus.text = "Dialed number: $number — review it, then tap the call button."
            pendingDialButton.text = "📞  Confirm call to $number"
            pendingDialButton.visibility = android.view.View.VISIBLE
        }
    }

    private fun startVoiceCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), contactsPermissionRequest)
            return
        }
        launchCallRecognition()
    }

    private fun launchCallRecognition() {
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "किसे फोन करना है? नाम बोलें, जैसे अमित को फोन लगाओ")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        runCatching { voiceCallResultLauncher.launch(speech) }
            .onFailure { voiceStatus.text = "इस फोन पर Speech Recognition उपलब्ध नहीं है: ${it.message}" }
    }

    private fun normalizeContactText(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun findContactNumbers(spoken: String): List<Pair<String, String>> {
        val cleaned = spoken
            .replace(Regex("(?i)\\b(shraddha|please|call|phone|lagao|laga do|milao|ko|se)\\b"), " ")
            .replace(Regex("(श्रद्धा|प्लीज|कृपया|फोन लगाओ|फोन लगा दो|कॉल करो|कॉल लगाओ|को फोन|को कॉल|को|से)"), " ")
            .trim()
        val query = normalizeContactText(cleaned)
        if (query.isBlank()) return emptyList()
        val matches = mutableListOf<Pair<String, String>>()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC")?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty()
                val number = cursor.getString(numberIndex).orEmpty()
                val normalizedName = normalizeContactText(name)
                if (name.isNotBlank() && number.isNotBlank() &&
                    (normalizedName == query)) {
                    matches.add(name to number)
                }
            }
        }
        return matches.distinctBy { it.second }
    }

    private fun handleVoiceCallResult(spoken: String) {
        val matches = runCatching { findContactNumbers(spoken) }.getOrElse {
            voiceStatus.text = "Contacts पढ़ने में समस्या: ${it.message}"
            return
        }
        when (matches.size) {
            0 -> {
                voiceStatus.text = "‘$spoken’ नाम से कोई Contact नहीं मिला। Contact का नाम फिर बोलें।"
                tts?.speak("इस नाम का contact नहीं मिला। कृपया नाम दोबारा बोलें।", TextToSpeech.QUEUE_FLUSH, null, "contact-not-found")
            }
            1 -> {
                val (name, number) = matches.first()
                val safeNumber = number.trim().replace(Regex("[\\s().-]"), "")
                if (!safeNumber.matches(Regex("^\\+?[0-9]{3,20}$"))) {
                    voiceStatus.text = "$name मिला, लेकिन उसका फोन नंबर मान्य नहीं है। कॉल नहीं लगाई गई।"
                    return
                }
                val masked = safeNumber.takeLast(4).padStart(safeNumber.length, '•')
                voiceStatus.text = "$name मिला। कॉल लगाने से पहले पुष्टि आवश्यक है।"
                tts?.speak("$name मिला है। क्या इस contact को कॉल लगाऊँ?", TextToSpeech.QUEUE_FLUSH, null, "confirm-contact-call")
                AlertDialog.Builder(this)
                    .setTitle("कॉल की पुष्टि करें")
                    .setMessage("$name\\n$masked\\n\\nक्या अभी कॉल लगानी है?")
                    .setPositiveButton("कॉल लगाएँ") { _, _ -> placeCall(safeNumber) }
                    .setNegativeButton("रद्द करें") { _, _ ->
                        voiceStatus.text = "कॉल रद्द कर दी गई।"
                        tts?.speak("कॉल रद्द कर दी गई।", TextToSpeech.QUEUE_FLUSH, null, "call-cancelled")
                    }
                    .show()
            }
            else -> {
                val names = matches.take(5).joinToString(", ") { it.first }
                voiceStatus.text = "एक से अधिक Contact मिले: $names। अभी कॉल नहीं लगाई; सही पूरा नाम बोलें।"
                tts?.speak("एक से अधिक contact मिले हैं। कृपया पूरा नाम बोलें।", TextToSpeech.QUEUE_FLUSH, null, "ambiguous-contact")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun placeCall(number: String) {
        val safeNumber = number.trim().replace(Regex("[\\s().-]"), "")
        if (!safeNumber.matches(Regex("^\\+?[0-9]{3,20}$"))) {
            voiceStatus.text = "फोन नंबर मान्य नहीं है; कॉल नहीं लगाई गई।"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), callPermissionRequest)
            return
        }
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        // SIM enumeration requires READ_PHONE_STATE. If it is not granted, let Android use its default SIM without prompting for a broader permission.
        val canReadPhoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val accounts = if (canReadPhoneState) runCatching { telecom.callCapablePhoneAccounts }.getOrDefault(emptyList()) else emptyList()
        when (accounts.size) {
            0 -> launchCallWithAccount(safeNumber, null)
            1 -> launchCallWithAccount(safeNumber, accounts.first())
            else -> {
                val labels = accounts.mapIndexed { index, handle ->
                    val account = telecom.getPhoneAccount(handle)
                    val label = account?.label?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${index + 1}"
                    val numberHint = account?.address?.schemeSpecificPart?.takeLast(4)
                        ?.takeIf { it.isNotBlank() }?.let { " ••••$it" }.orEmpty()
                    "$label$numberHint"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("किस SIM से कॉल लगानी है?")
                    .setItems(labels) { _, which ->
                        accounts.getOrNull(which)?.let { launchCallWithAccount(safeNumber, it) }
                    }
                    .setNegativeButton("रद्द करें", null)
                    .show()
            }
        }
    }

    private fun launchCallWithAccount(number: String, account: android.telecom.PhoneAccountHandle?) {
        runCatching {
            val extras = Bundle()
            if (account != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            voiceStatus.text = "चुनी गई SIM से कॉल शुरू करने का अनुरोध भेजा गया।"
        }.onFailure {
            voiceStatus.text = "कॉल शुरू नहीं हो सकी: ${it.message}"
            status.text = voiceStatus.text
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == callPermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            voiceStatus.text = "Call permission मिली। फिर से voice command दें।"
        } else if (requestCode == callPermissionRequest) status.text = "Phone-call permission was not granted."
        if (requestCode == contactsPermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val pending = pendingContactQuery
            pendingContactQuery = null
            if (!pending.isNullOrBlank()) handleVoiceCallResult(pending) else launchCallRecognition()
        } else if (requestCode == contactsPermissionRequest) {
            pendingContactQuery = null
            voiceStatus.text = "Contacts permission के बिना नाम खोजकर कॉल नहीं हो सकती।"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        setTextColor(0xFFE4FAFF.toInt())
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF24516A.toInt(), 0xFF17354D.toInt())
        ).apply { cornerRadius = dp(15).toFloat(); setStroke(dp(1), 0xFF4B8BA2.toInt()) }
        elevation = dp(3).toFloat()
        setPadding(dp(8), dp(6), dp(8), dp(6))
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) }
        setOnClickListener { action() }
    }

    private fun requestDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager.isRoleAvailable(RoleManager.ROLE_DIALER) && !manager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                dialerRoleResultLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            } else refreshStatus()
        } else {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            dialerRoleResultLauncher.launch(intent)
        }
    }

    private fun refreshStatus() {
        val manager = getSystemService(TELECOM_SERVICE) as TelecomManager
        status.text = "Default phone app: ${if (manager.defaultDialerPackage == packageName) "YES" else "NO"}"
        history.text = prefs.getString(ShraddhaInCallService.KEY_CALL_HISTORY, null)
            ?.takeIf { it.isNotBlank() } ?: "No call events recorded yet."
    }

    private fun refreshEdesyHistory() {
        edesyHistory.text = "Edesy AI call history is loading…"
        Thread {
            val config = VoiceGatewaySettings.get(this)
            if (config.baseUrl.isBlank() || config.gatewayToken.isBlank()) {
                runOnUiThread { edesyHistory.text = "पहले Settings में secure HTTPS Backend URL और Gateway Token configure करें।" }
                return@Thread
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            fun getJson(path: String): JSONObject {
                val request = Request.Builder()
                    .url(config.baseUrl.trimEnd('/') + path)
                    .header("Authorization", "Bearer ${config.gatewayToken}")
                    .get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val error = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                        throw IllegalStateException(error.ifBlank { "HTTP ${response.code}" })
                    }
                    return JSONObject(body)
                }
            }
            val message = runCatching {
                val readiness = getJson("/api/telephony/edesy/status")
                if (!readiness.optBoolean("configured")) {
                    "Backend connected, लेकिन Edesy stream token या OpenAI API key server पर configured नहीं है."
                } else {
                    val calls = getJson("/api/telephony/edesy/calls").optJSONArray("calls")
                    if (calls == null || calls.length() == 0) {
                        "Edesy configured है। अभी कोई forwarded AI call record नहीं मिला."
                    } else {
                        (0 until calls.length()).mapNotNull { index ->
                            calls.optJSONObject(index)?.let { call ->
                                val caller = call.optString("callerName").ifBlank { call.optString("caller", "Unknown caller") }
                                val started = call.optString("startedAt")
                                val statusText = call.optString("status")
                                val summary = call.optString("summary").ifBlank {
                                    when (call.optString("summaryStatus")) {
                                        "disabled" -> "Summary disabled (transcript consent/configuration required)."
                                        "pending" -> "Summary is being prepared."
                                        "failed" -> "Summary unavailable."
                                        else -> "No summary available."
                                    }
                                }
                                "• $caller\n  $started • $statusText\n  $summary"
                            }
                        }.joinToString("\n\n").ifBlank { "No readable call records." }
                    }
                }
            }.getOrElse { "Edesy status/history could not be loaded: ${it.message ?: "network error"}" }
            runOnUiThread { if (!isFinishing && !isDestroyed) edesyHistory.text = message }
        }.start()
    }

    private fun shareCallSummary() {
        val note = prefs.getString(ShraddhaInCallService.KEY_CALL_HISTORY, null)
            ?.takeIf { it.isNotBlank() } ?: "Shraddha AI: No call events recorded yet."
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Shraddha AI call summary")
            putExtra(Intent.EXTRA_TEXT, "Shraddha AI call summary:\n$note")
        }
        startActivity(Intent.createChooser(intent, "Share call summary"))
    }

    companion object {
        const val KEY_LAST_CALL = "last_call_note"
        const val EXTRA_PENDING_CONTACT_QUERY = "extra_pending_contact_query"
    }
}
