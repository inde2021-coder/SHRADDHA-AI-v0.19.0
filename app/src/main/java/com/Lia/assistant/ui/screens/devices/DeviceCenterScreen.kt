package com.Lia.assistant.ui.screens.devices

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.speech.RecognizerIntent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.link.DeviceLinkManager
import kotlinx.coroutines.launch

private data class LinkedPeer(
    val id: String,
    val name: String,
    val online: Boolean
)

@Composable
fun DeviceCenterScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val assistantName = NovaPreferences.getString(
        context, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.FULL_NAME
    ).trim().ifBlank { AssistantBrand.FULL_NAME }

    var baseUrl by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("$assistantName Android") }
    var pairingCode by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("इस फोन का कोई live relay status अभी उपलब्ध नहीं है।") }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var peers by remember { mutableStateOf(emptyList<LinkedPeer>()) }
    var selectedPeerId by remember { mutableStateOf("") }
    var pendingFingerprint by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showClearPairing by remember { mutableStateOf(false) }
    var commandText by remember { mutableStateOf("") }

    DisposableEffect(context) {
        DeviceLinkManager.initialize(context)
        DeviceLinkManager.onStatus = { message ->
            mainHandler.post {
                status = message
                if (message.contains("Secure relay connected", ignoreCase = true) ||
                    message.startsWith("Connected •")) connected = true
                if (message.startsWith("Disconnected") ||
                    message.startsWith("Relay error") ||
                    message.contains("User disconnected", ignoreCase = true)) connected = false
            }
        }
        DeviceLinkManager.onPeerMessage = { message ->
            mainHandler.post {
                when (message.optString("type")) {
                    "connected" -> {
                        val devices = message.optJSONArray("devices")
                        val localId = DeviceLinkManager.deviceId.orEmpty()
                        peers = (0 until (devices?.length() ?: 0)).mapNotNull { index ->
                            val item = devices?.optJSONObject(index) ?: return@mapNotNull null
                            val id = item.optString("id")
                            if (id.isBlank() || id == localId) null else LinkedPeer(
                                id = id,
                                name = item.optString("name", "Paired device"),
                                online = item.optBoolean("online")
                            )
                        }
                        if (selectedPeerId !in peers.map { it.id }) selectedPeerId = peers.firstOrNull()?.id.orEmpty()
                    }
                    "device_revoked" -> {
                        val revoked = message.optString("deviceId")
                        peers = peers.filterNot { it.id == revoked }
                        if (selectedPeerId == revoked) selectedPeerId = peers.firstOrNull()?.id.orEmpty()
                    }
                    "peer_status" -> status = message.optString("name", "Device") + ": " +
                        if (message.optBoolean("online")) "online" else "offline"
                    "remote_action_result" -> status = (if (message.optBoolean("ok")) "PC action succeeded: " else "PC action failed: ") + message.optString("result").take(300)
                    else -> status = message.optString("type", "Relay message received")
                }
            }
        }
        DeviceLinkManager.onE2EVerification = { peerId, fingerprint ->
            mainHandler.post { pendingFingerprint = peerId to fingerprint }
        }
        onDispose {
            DeviceLinkManager.onStatus = null
            DeviceLinkManager.onPeerMessage = null
            DeviceLinkManager.onE2EVerification = null
        }
    }

    LaunchedEffect(Unit) {
        DeviceLinkManager.restore(context) { result ->
            mainHandler.post {
                result.onSuccess { pair ->
                    connected = true
                    pairingCode = pair.code
                    status = "Saved pairing restored. Waiting for the paired PC/device list."
                }.onFailure { error ->
                    status = error.message ?: "No saved pairing. Create a session or join with a pairing code."
                }
            }
        }
    }

    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Shraddha AI को PC command बोलें")
        }
    }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            commandText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (commandText.isBlank()) status = "आवाज़ समझ नहीं आई। फिर से बोलें या लिखें।"
        }
    }
    val selectedPeer = peers.firstOrNull { it.id == selectedPeerId }
    val peerTrusted = selectedPeerId.isNotBlank() && DeviceLinkManager.isE2ETrusted(selectedPeerId)

    Column(
        modifier = Modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NovaTopBar(
            title = "PC CONTROL CENTER",
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
            }
        )

        NovaGlassCard(cornerRadius = 22.dp, elevation = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, contentDescription = null, tint = colors.accent)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("$assistantName • PC Control", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Secure remote access • Internet से भी काम करेगा", color = colors.textSecondary, fontSize = 11.sp)
                }
            }
            Text(status, color = if (connected) Color(0xFF58D6A5) else colors.textSecondary, fontSize = 12.sp)
            Text(
                "पहले PC pair करें। PC पर Remote Control ON और हर action की local approval जरूरी है।",
                color = colors.textSecondary, fontSize = 11.sp
            )
        }

        NovaGlassCard {
            Text("PAIR OR JOIN A DEVICE", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            NovaTextField(value = baseUrl, onValueChange = { baseUrl = it.trim() }, modifier = Modifier.fillMaxWidth(), placeholder = "https://your-secure-relay.example.com")
            NovaTextField(value = deviceName, onValueChange = { deviceName = it.take(80) }, modifier = Modifier.fillMaxWidth(), placeholder = "This device name")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NovaButton(
                    text = if (busy) "Connecting…" else "Create Session",
                    onClick = {
                        if (!baseUrl.trim().startsWith("https://")) {
                            status = "केवल HTTPS relay URL स्वीकार किया जाता है।"
                        } else if (deviceName.isBlank()) {
                            status = "इस device का नाम लिखें।"
                        } else {
                            busy = true
                            DeviceLinkManager.createSession(context, baseUrl.trim(), deviceName.trim()) { result ->
                                mainHandler.post {
                                    result.onSuccess { pair ->
                                        pairingCode = pair.code
                                        connected = true
                                        status = "Session created. Pair your Windows companion with the code below."
                                    }.onFailure { status = it.message ?: "Could not create a secure session." }
                                    busy = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    type = NovaButtonType.PRIMARY
                )
                NovaButton(
                    text = "Restore",
                    onClick = {
                        busy = true
                        DeviceLinkManager.restore(context) { result ->
                            mainHandler.post {
                                result.onSuccess { pairingCode = it.code; connected = true; status = "Saved device pairing restored." }
                                    .onFailure { status = it.message ?: "No saved pairing is available." }
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    type = NovaButtonType.SECONDARY
                )
            }
            if (pairingCode.isNotBlank()) {
                Text("PAIRING CODE", color = colors.textTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(pairingCode, color = colors.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                NovaButton(
                    text = "Copy Pairing Code",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Device pairing code", pairingCode))
                        status = "Pairing code copied. Share it only with the device you intend to pair."
                    },
                    type = NovaButtonType.SECONDARY
                )
            }
            NovaTextField(value = joinCode, onValueChange = { joinCode = it.filter(Char::isLetterOrDigit).take(12) }, modifier = Modifier.fillMaxWidth(), placeholder = "Enter pairing code to join")
            NovaButton(
                text = "Join Existing Session",
                onClick = {
                    if (!baseUrl.trim().startsWith("https://") || joinCode.isBlank() || deviceName.isBlank()) {
                        status = "HTTPS relay URL, device name and pairing code are required."
                    } else {
                        busy = true
                        DeviceLinkManager.joinSession(context, baseUrl.trim(), deviceName.trim(), joinCode.trim()) { result ->
                            mainHandler.post {
                                result.onSuccess { connected = true; status = "Joined session. Waiting for peer fingerprint verification." }
                                    .onFailure { status = it.message ?: "Could not join the session." }
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                type = NovaButtonType.SECONDARY
            )
        }

        NovaGlassCard {
            Text("PAIRED DEVICES", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (peers.isEmpty()) {
                Text("No paired peer has been reported by the relay yet. Keep the companion open and refresh/reconnect.", color = colors.textSecondary, fontSize = 12.sp)
            } else {
                peers.forEach { peer ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(peer.id, color = colors.textTertiary, fontSize = 9.sp)
                            Text(if (peer.online) "ONLINE" else "OFFLINE", color = if (peer.online) Color(0xFF58D6A5) else colors.textSecondary, fontSize = 9.sp)
                        }
                        NovaButton(
                            text = if (selectedPeerId == peer.id) "Selected" else "Select",
                            onClick = { selectedPeerId = peer.id },
                            type = if (selectedPeerId == peer.id) NovaButtonType.PRIMARY else NovaButtonType.SECONDARY
                        )
                    }
                }
            }
        }

        selectedPeer?.let { peer ->
            NovaGlassCard(cornerRadius = 22.dp, elevation = 6.dp) {
                Text("CONNECTED DEVICE", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(peer.name, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (peerTrusted) "Secure link verified" else "पहले दोनों devices पर एक जैसा security fingerprint verify करें।",
                    color = if (peerTrusted) Color(0xFF58D6A5) else colors.textSecondary, fontSize = 11.sp
                )
                NovaButton(
                    text = "Request PC Control",
                    onClick = {
                        status = if (DeviceLinkManager.requestRemoteControl(peer.id, 5)) {
                            "PC पर approval दें। Approval के बाद voice/text commands काम करेंगे।"
                        } else "Request नहीं भेजा गया। Connection जाँचें।"
                    },
                    enabled = connected && peer.online && peerTrusted,
                    modifier = Modifier.fillMaxWidth()
                )
                NovaTextField(
                    value = commandText,
                    onValueChange = { commandText = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "जैसे: Open Notepad, volume 50, scroll down, type Hello"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NovaButton(
                        text = "🎙 बोलें",
                        onClick = { speechLauncher.launch(speechIntent) },
                        modifier = Modifier.weight(1f),
                        type = NovaButtonType.SECONDARY,
                        enabled = connected && peer.online && peerTrusted
                    )
                    NovaButton(
                        text = if (busy) "Sending…" else "Send command",
                        onClick = {
                            PcCommandInterpreter.parse(commandText).onSuccess { action ->
                                busy = true
                                val sent = DeviceLinkManager.sendAction(peer.id, action)
                                status = if (sent) "Command सुरक्षित रूप से भेजा गया। PC पर approval/परिणाम देखें।"
                                    else "Command नहीं भेजा गया। PC online और remote approval जाँचें।"
                                busy = false
                            }.onFailure { status = it.message ?: "यह command समझ नहीं आया।" }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = connected && peer.online && peerTrusted && !busy && commandText.isNotBlank()
                    )
                }
                Text(
                    "एक ही जगह बोलकर या लिखकर PC चलाएँ। उदाहरण: “desktop दिखाओ”, “Notepad खोलो”, “ऊपर scroll करो”, “type नमस्ते”, “volume 40”. हर control PC पर आपकी अनुमति के बाद ही चलेगा।",
                    color = colors.textSecondary, fontSize = 10.sp
                )
            }
        }

        NovaGlassCard {
            Text("SESSION SECURITY", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            NovaButton(
                text = "Disconnect (keep saved pairing)",
                onClick = {
                    DeviceLinkManager.disconnect(context, clearSavedCredentials = false)
                    connected = false
                    peers = emptyList()
                    status = "Disconnected. Encrypted pairing remains saved on this phone."
                },
                modifier = Modifier.fillMaxWidth(),
                type = NovaButtonType.SECONDARY
            )
            NovaButton(
                text = "Clear This Phone’s Saved Pairing",
                onClick = { showClearPairing = true },
                modifier = Modifier.fillMaxWidth(),
                type = NovaButtonType.TEXT
            )
        }
    }

    pendingFingerprint?.let { (peerId, fingerprint) ->
        AlertDialog(
            onDismissRequest = { pendingFingerprint = null },
            title = { Text("Verify paired device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Peer ID: $peerId")
                    Text("Fingerprint:", fontWeight = FontWeight.Bold)
                    Text(fingerprint, color = colors.accent)
                    Text("Compare this fingerprint with the paired device using a trusted channel. Only confirm if both devices display the same value.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val confirmed = DeviceLinkManager.confirmE2EPeer(peerId, fingerprint)
                    status = if (confirmed) "Local fingerprint confirmed. Waiting for peer confirmation." else "Fingerprint confirmation failed."
                    pendingFingerprint = null
                }) { Text("Confirm fingerprint") }
            },
            dismissButton = {
                TextButton(onClick = { pendingFingerprint = null }) { Text("Not now") }
            }
        )
    }


    if (showClearPairing) {
        AlertDialog(
            onDismissRequest = { showClearPairing = false },
            title = { Text("Clear this phone’s saved pairing?") },
            text = { Text("This disconnects this phone and clears its local encrypted credentials. It does not revoke other paired devices from the relay session.") },
            confirmButton = {
                TextButton(onClick = {
                    DeviceLinkManager.disconnect(context, clearSavedCredentials = true)
                    connected = false
                    peers = emptyList()
                    pairingCode = ""
                    status = "Disconnected and this phone’s saved credentials were cleared."
                    showClearPairing = false
                }) { Text("Clear local pairing") }
            },
            dismissButton = { TextButton(onClick = { showClearPairing = false }) { Text("Cancel") } }
        )
    }
}
