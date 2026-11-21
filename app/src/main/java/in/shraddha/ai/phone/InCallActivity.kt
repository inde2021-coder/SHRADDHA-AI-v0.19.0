package `in`.shraddha.ai.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class InCallActivity : AppCompatActivity() {
    private lateinit var stateLabel: TextView
    private lateinit var callerLabel: TextView

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderState()
            if (ShraddhaInCallService.activeCall == null) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(0xFF080D18.toInt())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            setBackgroundColor(0xFF080D18.toInt())
        }
        root.addView(TextView(this).apply {
            text = "SHRADDHA AI  •  PHONE"
            textSize = 13f
            setTextColor(0xFFBDA8FF.toInt())
        })
        callerLabel = TextView(this).apply {
            textSize = 25f
            setTextColor(-1)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 10)
        }
        root.addView(callerLabel)
        stateLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF9FB0CA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        root.addView(stateLabel)
        root.addView(action("Answer call") { ShraddhaInCallService.activeCall?.answer(VideoProfile.STATE_AUDIO_ONLY) })
        root.addView(action("Decline / End call") { ShraddhaInCallService.activeCall?.disconnect(); finish() })
        root.addView(action("Mute / Unmute") {
            if (ShraddhaInCallService.activeCall != null) {
                ShraddhaInCallService.toggleMute()
                stateLabel.text = if (ShraddhaInCallService.isMuted) "Microphone muted" else "Microphone on"
            }
        })
        root.addView(action("Speaker on / off") { ShraddhaInCallService.toggleSpeaker() })
        root.addView(TextView(this).apply {
            text = "AI voice conversation is not active for normal SIM calls. This screen provides call controls only; it will not pretend to hear or answer the caller."
            textSize = 12f
            setTextColor(0xFFFFC78A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 26, 0, 0)
        })
        setContentView(root)
        val filter = IntentFilter(ShraddhaInCallService.ACTION_CALL_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        renderState()
    }

    private fun action(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun renderState() {
        val call = ShraddhaInCallService.activeCall
        if (call == null) {
            callerLabel.text = "No active call"
            stateLabel.text = "Call ended"
            finish()
            return
        }
        val number = call.details?.handle?.schemeSpecificPart
        callerLabel.text = number?.takeLast(4)?.let { "Caller ••••$it" } ?: "Unknown caller"
        val callState = ShraddhaInCallService.currentCallState(call)
        stateLabel.text = when (callState) {
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "Connecting"
            Call.STATE_ACTIVE -> "Call connected"
            Call.STATE_HOLDING -> "Call on hold"
            else -> "Call status: $callState"
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
    }
}
