package `in`.shraddha.ai.phone

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class PhoneAgentActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("phone_agent", Context.MODE_PRIVATE) }
    private lateinit var status: TextView
    private val roleRequest = 901

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(0xFF080D18.toInt())
        }
        root.addView(TextView(this).apply {
            text = "SHRADDHA AI  /  PHONE AGENT"
            textSize = 20f; setTextColor(-1); setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Call control foundation • Hindi / English AI conversation requires a supported carrier/VoIP audio bridge."
            textSize = 14f; setTextColor(0xFFB8C5DD.toInt()); setPadding(0, 18, 0, 18)
        })
        status = TextView(this).apply { textSize = 13f; setTextColor(0xFF70E0C1.toInt()); setPadding(0, 12, 0, 20) }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Set as default phone app"
            setOnClickListener { requestDialerRole() }
        })
        root.addView(Switch(this).apply {
            text = "Auto-answer incoming calls"
            setTextColor(-1)
            isChecked = prefs.getBoolean(KEY_AUTO_ANSWER, false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(KEY_AUTO_ANSWER, checked).apply() }
        })
        root.addView(TextView(this).apply {
            text = "Safety: auto-answer is OFF by default. Turn it on only when you want Shraddha to answer. Android does not expose ordinary cellular-call audio to third-party apps, so this setting does not make the AI speak/listen to the caller."
            textSize = 12f; setTextColor(0xFFFFC78A.toInt()); setPadding(0, 16, 0, 16)
        })
        root.addView(Button(this).apply {
            text = "Share latest call note on WhatsApp"
            setOnClickListener { shareLatestCallNote() }
        })
        root.addView(Button(this).apply {
            text = "Refresh call status"
            setOnClickListener { refreshStatus() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun requestDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), roleRequest)
            } else refreshStatus()
        } else {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            startActivityForResult(intent, roleRequest)
        }
    }

    @Deprecated("Legacy activity result API is retained for minSdk 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == roleRequest) refreshStatus()
    }

    private fun refreshStatus() {
        val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
        val isDefault = tm.defaultDialerPackage == packageName
        val last = prefs.getString(KEY_LAST_CALL, "No call event recorded yet")
        status.text = "Default phone app: ${if (isDefault) "YES" else "NO"}\nAuto-answer: ${if (prefs.getBoolean(KEY_AUTO_ANSWER, false)) "ON" else "OFF"}\nLatest call: $last"
    }

    private fun shareLatestCallNote() {
        val note = prefs.getString(KEY_LAST_CALL, null)
            ?: "Shraddha AI: No incoming-call event has been recorded yet."
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Shraddha AI call update:\n$note")
            setPackage("com.whatsapp")
        }
        try { startActivity(send) }
        catch (_: Exception) {
            val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, note)
            }, "Send call note")
            startActivity(chooser)
        }
    }

    companion object {
        const val KEY_AUTO_ANSWER = "auto_answer"
        const val KEY_LAST_CALL = "last_call_note"
    }
}
