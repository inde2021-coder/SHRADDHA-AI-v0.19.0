package `in`.shraddha.ai.phone

import android.app.role.RoleManager
import android.Manifest
import android.content.Context
import android.net.Uri
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

class PhoneAgentActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("phone_agent", Context.MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var history: TextView
    private lateinit var numberInput: EditText
    private val roleRequest = 901
    private val callPermissionRequest = 902

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(0xFF080D18.toInt())
        }
        root.addView(TextView(this).apply {
            text = "SHRADDHA AI  /  PHONE AGENT"
            textSize = 20f
            setTextColor(-1)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Call controls, caller events and shareable call notes."
            textSize = 14f
            setTextColor(0xFFB8C5DD.toInt())
            setPadding(0, 18, 0, 18)
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF70E0C1.toInt())
            setPadding(0, 12, 0, 20)
        }
        root.addView(status)
        numberInput = EditText(this).apply {
            hint = "Phone number"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setTextColor(-1)
            setHintTextColor(0xFF8291AA.toInt())
        }
        root.addView(numberInput)
        root.addView(actionButton("Call number") { placeCall() })
        root.addView(actionButton("Set Shraddha as default phone app") { requestDialerRole() })
        root.addView(actionButton("Refresh phone status") { refreshStatus() })
        root.addView(TextView(this).apply {
            text = "CALL HISTORY"
            textSize = 12f
            setTextColor(0xFFBDA8FF.toInt())
            setPadding(0, 22, 0, 8)
        })
        history = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE4E9F5.toInt())
            setPadding(14, 14, 14, 14)
            setBackgroundColor(0xFF111B2D.toInt())
        }
        root.addView(history)
        root.addView(actionButton("Share call summary") { shareCallSummary() })
        root.addView(TextView(this).apply {
            text = "IMPORTANT: Normal SIM-call audio is not exposed to third-party InCallService apps on standard Android. Shraddha can provide call controls and history here, but live AI listening and speaking requires a supported carrier/OEM integration or a VoIP/SIP calling service. Auto-answer is intentionally unavailable until that voice path is implemented and tested, so calls are never silently answered without an AI response."
            textSize = 12f
            setTextColor(0xFFFFC78A.toInt())
            setPadding(0, 20, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
        handleDialIntent(intent)
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    private fun handleDialIntent(source: Intent?) {
        val number = source?.data?.schemeSpecificPart ?: source?.getStringExtra(TelecomManager.EXTRA_PHONE_NUMBER)
        if (!number.isNullOrBlank()) numberInput.setText(number)
    }

    private fun placeCall() {
        val number = numberInput.text.toString().trim()
        if (number.isBlank()) { numberInput.error = "Enter a phone number"; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), callPermissionRequest)
            return
        }
        runCatching {
            val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
        }.onFailure { status.text = "Call could not be started: " + it.message }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == callPermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) placeCall()
        else if (requestCode == callPermissionRequest) status.text = "Phone-call permission was not granted."
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun requestDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager.isRoleAvailable(RoleManager.ROLE_DIALER) && !manager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_DIALER), roleRequest)
            } else refreshStatus()
        } else {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            startActivityForResult(intent, roleRequest)
        }
    }

    @Deprecated("Compatibility with minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == roleRequest) refreshStatus()
    }

    private fun refreshStatus() {
        val manager = getSystemService(TELECOM_SERVICE) as TelecomManager
        status.text = "Default phone app: ${if (manager.defaultDialerPackage == packageName) "YES" else "NO"}"
        history.text = prefs.getString(ShraddhaInCallService.KEY_CALL_HISTORY, null)
            ?.takeIf { it.isNotBlank() } ?: "No call events recorded yet."
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
    }
}
