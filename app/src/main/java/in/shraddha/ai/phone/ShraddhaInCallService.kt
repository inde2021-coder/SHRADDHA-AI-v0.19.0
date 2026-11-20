package `in`.shraddha.ai.phone

import android.content.Context
import android.telecom.Call
import android.telecom.InCallService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShraddhaInCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                saveEvent(call, state)
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        saveEvent(call, call.state)

        val prefs = getSharedPreferences("phone_agent", Context.MODE_PRIVATE)
        if (call.state == Call.STATE_RINGING &&
            prefs.getBoolean(PhoneAgentActivity.KEY_AUTO_ANSWER, false)) {
            // Answer control only. Android does not grant this service cellular-call audio.
            call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
        }
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        saveEvent(call, call.state)
        super.onCallRemoved(call)
    }

    private fun saveEvent(call: Call, state: Int) {
        val handle = call.details?.handle?.schemeSpecificPart?.takeLast(4)?.let { "••••$it" } ?: "Unknown caller"
        val stateText = when (state) {
            Call.STATE_RINGING -> "Ringing"
            Call.STATE_DIALING -> "Dialing"
            Call.STATE_ACTIVE -> "Active"
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTED -> "Ended"
            Call.STATE_CONNECTING -> "Connecting"
            else -> "Call state $state"
        }
        val time = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        getSharedPreferences("phone_agent", Context.MODE_PRIVATE).edit()
            .putString(PhoneAgentActivity.KEY_LAST_CALL, "$stateText • $handle • $time")
            .apply()
    }
}
