package `in`.shraddha.ai.phone

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.os.OutcomeReceiver
import android.telecom.InCallService
import android.telecom.VideoProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShraddhaInCallService : InCallService() {
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeCall = call
        service = this
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                activeCall = call
                saveEvent(call, state)
                sendBroadcast(Intent(ACTION_CALL_STATE).setPackage(packageName))
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        saveEvent(call, currentCallState(call))
        sendBroadcast(Intent(ACTION_CALL_STATE).setPackage(packageName))

        if (currentCallState(call) == Call.STATE_RINGING || currentCallState(call) == Call.STATE_DIALING || currentCallState(call) == Call.STATE_CONNECTING) {
            val screen = Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { startActivity(screen) }
        }
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        availableCallEndpoints = availableEndpoints.toList()
        super.onAvailableCallEndpointsChanged(availableEndpoints)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        sendBroadcast(Intent(ACTION_CALL_STATE).setPackage(packageName))
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        saveEvent(call, Call.STATE_DISCONNECTED)
        if (activeCall === call) { activeCall = null; isMuted = false }
        sendBroadcast(Intent(ACTION_CALL_STATE).setPackage(packageName))
        super.onCallRemoved(call)
    }

    private fun saveEvent(call: Call, state: Int) {
        val handle = call.details?.handle?.schemeSpecificPart?.takeLast(4)?.let { "••••$it" } ?: "Unknown caller"
        val stateText = when (state) {
            Call.STATE_RINGING -> "Ringing"
            Call.STATE_DIALING -> "Dialing"
            Call.STATE_ACTIVE -> "Answered"
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTED -> "Ended"
            Call.STATE_CONNECTING -> "Connecting"
            else -> "Call state $state"
        }
        val time = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val prefs = getSharedPreferences("phone_agent", Context.MODE_PRIVATE)
        val entry = "$stateText • $handle • $time"
        prefs.edit().putString(PhoneAgentActivity.KEY_LAST_CALL, entry).apply()
        val old = prefs.getString(KEY_CALL_HISTORY, "").orEmpty().lines().filter { it.isNotBlank() }
        val updated = (listOf(entry) + old).distinct().take(50)
        prefs.edit().putString(KEY_CALL_HISTORY, updated.joinToString("\n")).apply()
    }

    companion object {
        const val ACTION_CALL_STATE = "in.shraddha.ai.phone.CALL_STATE"
        const val KEY_CALL_HISTORY = "call_history"
        @Volatile var activeCall: Call? = null
            private set
        @Volatile var service: ShraddhaInCallService? = null
            private set
        @Volatile var isMuted: Boolean = false
            private set
        @Volatile var availableCallEndpoints: List<CallEndpoint> = emptyList()
            private set

        fun currentCallState(call: Call): Int =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                call.details?.state ?: Call.STATE_DISCONNECTED
            } else {
                legacyCallState(call)
            }

        @Suppress("DEPRECATION")
        private fun legacyCallState(call: Call): Int = call.state

        fun toggleMute() {
            isMuted = !isMuted
            service?.setMuted(isMuted)
        }

        fun toggleSpeaker() {
            val current = service ?: return
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val currentType = current.currentCallEndpoint.endpointType
                val targetType = if (currentType == CallEndpoint.TYPE_SPEAKER) CallEndpoint.TYPE_EARPIECE else CallEndpoint.TYPE_SPEAKER
                val target = availableCallEndpoints.firstOrNull { it.endpointType == targetType } ?: return
                current.requestCallEndpointChange(target, current.mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
                    override fun onResult(result: Void?) = Unit
                    override fun onError(error: CallEndpointException) = Unit
                })
            } else {
                toggleSpeakerLegacy(current)
            }
        }

        @Suppress("DEPRECATION")
        private fun toggleSpeakerLegacy(current: ShraddhaInCallService) {
            val audio = current.callAudioState
            val route = if (audio?.route == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
            current.setAudioRoute(route)
        }
    }

    override fun onDestroy() {
        callbacks.toMap().forEach { (call, callback) ->
            runCatching { call.unregisterCallback(callback) }
        }
        callbacks.clear()
        if (service === this) {
            service = null
            activeCall = null
            isMuted = false
            availableCallEndpoints = emptyList()
        }
        sendBroadcast(Intent(ACTION_CALL_STATE).setPackage(packageName))
        super.onDestroy()
    }
}
