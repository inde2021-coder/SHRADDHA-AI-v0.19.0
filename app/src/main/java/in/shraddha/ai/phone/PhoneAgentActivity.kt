package `in`.shraddha.ai.phone

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val assistantName = NovaPreferences.getString(
            this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME
        ).trim().ifBlank { AssistantBrand.NAME }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF07131D.toInt())
        }
        root.addView(TextView(this).apply {
            text = "$assistantName  /  PHONE AGENT"
            textSize = 20f
            setTextColor(-1)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Call controls, caller events and shareable call notes."
            textSize = 14f
            setTextColor(0xFFB6CDD2.toInt())
            setPadding(0, 18, 0, 18)
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF73E3D2.toInt())
            setPadding(0, 12, 0, 20)
        }
        root.addView(status)
        voiceStatus = TextView(this).apply {
            text = "बोलें: ‘श्रद्धा, अमित को फोन लगाओ’"
            textSize = 14f
            setTextColor(0xFFB8C5DD.toInt())
            setPadding(0, 18, 0, 18)
        }
        root.addView(voiceStatus)
        root.addView(actionButton("🎙  बोलकर Contact खोजें और Call करें") { startVoiceCall() })
        pendingDialButton = actionButton("Review and place dialed number") {
            pendingDialNumber?.let { number ->
                pendingDialNumber = null
                pendingDialButton.visibility = android.view.View.GONE
                placeCall(number)
            }
        }.apply { visibility = android.view.View.GONE }
        root.addView(pendingDialButton)
        root.addView(actionButton("Set Shraddha as default phone app") { requestDialerRole() })
        root.addView(actionButton("Refresh phone status") { refreshStatus() })
        root.addView(TextView(this).apply {
            text = "CALL HISTORY"
            textSize = 12f
            setTextColor(0xFF73DDE7.toInt())
            setPadding(0, 22, 0, 8)
        })
        history = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE6F5F7.toInt())
            setPadding(14, 14, 14, 14)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF102A36.toInt()); cornerRadius = 18f; setStroke(1, 0xFF2D8490.toInt())
            }
        }
        root.addView(history)
        root.addView(actionButton("Share phone call summary") { shareCallSummary() })
        root.addView(TextView(this).apply {
            text = "EDESY AI CALL HISTORY"
            textSize = 12f
            setTextColor(0xFFBDA8FF.toInt())
            setPadding(0, 24, 0, 8)
        })
        edesyHistory = TextView(this).apply {
            text = "Edesy status not checked yet."
            textSize = 13f
            setTextColor(0xFFE4E9F5.toInt())
            setPadding(14, 14, 14, 14)
            setBackgroundColor(0xFF111B2D.toInt())
        }
        root.addView(edesyHistory)
        root.addView(actionButton("Refresh Edesy AI call history") { refreshEdesyHistory() })
        root.addView(TextView(this).apply {
            text = "IMPORTANT: Android InCallService cannot capture normal SIM-call audio. For the selected flow, the caller dials your existing SIM, the handset rings first, and the mobile operator forwards only unanswered calls to your configured Edesy DID. Shraddha AI can speak only after Edesy routing, OpenAI Live credentials and two-way audio are verified. Do not enable forwarding before a successful real test call."
            textSize = 12f
            setTextColor(0xFFFFC78A.toInt())
            setPadding(0, 20, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
        tts = TextToSpeech(this) { result -> if (result == TextToSpeech.SUCCESS) tts?.language = Locale("hi", "IN") }
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
                    (normalizedName == query || normalizedName.contains(query) || query.contains(normalizedName))) {
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
                voiceStatus.text = "$name मिला। कॉल शुरू की जा रही है।"
                tts?.speak("$name को कॉल लगा रही हूँ।", TextToSpeech.QUEUE_FLUSH, null, "calling-contact")
                placeCall(number)
            }
            else -> {
                val names = matches.take(5).joinToString(", ") { it.first }
                voiceStatus.text = "एक से अधिक Contact मिले: $names। अभी कॉल नहीं लगाई; सही पूरा नाम बोलें।"
                tts?.speak("एक से अधिक contact मिले हैं। कृपया पूरा नाम बोलें।", TextToSpeech.QUEUE_FLUSH, null, "ambiguous-contact")
            }
        }
    }

    private fun placeCall(number: String) {
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), callPermissionRequest)
            return
        }
        runCatching {
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
        }.onFailure {
            voiceStatus.text = "कॉल शुरू नहीं हो सकी: " + it.message
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
        setTextColor(0xFF04161C.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF38C7D2.toInt()); cornerRadius = 18f; setStroke(1, 0xFF8AF1F1.toInt())
        }
        elevation = 7f
        setPadding(18, 12, 18, 12)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
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
