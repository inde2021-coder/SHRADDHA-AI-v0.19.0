package com.Lia.assistant.ui.screens.devices

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import `in`.shraddha.ai.link.DeviceFileTransferReceiver
import `in`.shraddha.ai.link.DeviceFileTransferSender
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
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
    var pcFilePath by remember { mutableStateOf("") }
    var pcFileQuery by remember { mutableStateOf("") }
    var lastPcResult by remember { mutableStateOf("") }
    var lastPcResultOk by remember { mutableStateOf(false) }
    var pendingShareRequest by remember { mutableStateOf<JSONObject?>(null) }
    var pendingIncomingFile by remember { mutableStateOf<DeviceFileTransferReceiver.Manifest?>(null) }
    var incomingTransferStatus by remember { mutableStateOf("") }
    var receivedFiles by remember { mutableStateOf(DeviceFileTransferReceiver.listReceived(context)) }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var outgoingFile by remember { mutableStateOf(DeviceFileTransferSender.pending(File(context.filesDir, "OutgoingTransfers")).firstOrNull()) }
    var outgoingProgress by remember { mutableStateOf(outgoingFile?.sentChunks ?: 0) }
    var outgoingSending by remember { mutableStateOf(false) }
    val exportFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val source = pendingExportFile
        pendingExportFile = null
        if (uri != null && source != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
                    ?: error("Downloads destination could not be opened.")
            }.onSuccess { incomingTransferStatus = "फ़ाइल Downloads में export हो गई: ${source.name}" }
                .onFailure { incomingTransferStatus = "Export असफल: ${it.message ?: "unknown error"}" }
        }
    }

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
                    "data_share_request" -> {
                        if (message.optString("category") == "documents" &&
                            message.optString("requestId").matches(Regex("[A-Za-z0-9_-]{12,64}"))) {
                            pendingShareRequest = JSONObject(message.toString())
                        }
                    }
                    "remote_action_result" -> {
    lastPcResultOk = message.optBoolean("ok")
    lastPcResult = message.optString("result").take(12000)
    status = if (lastPcResultOk) "PC command completed. Full result is shown below."
        else "PC command failed. Details are shown below."
}
                    else -> status = message.optString("type", "Relay message received")
                }
            }
        }
        DeviceLinkManager.onFileTransferMessage = { message ->
            mainHandler.post {
                when (message.optString("type")) {
                    "file_transfer_ack" -> {
                        val item = outgoingFile
                        val peerId = message.optString("fromDeviceId")
                        if (item != null && peerId == selectedPeerId && message.optString("transferId") == item.transferId) {
                            val index = message.optInt("index", -2)
                            if (index == -1 && message.optBoolean("ok") && !message.optBoolean("complete")) {
                                scope.launch {
                                    outgoingSending = true
                                    for (chunkIndex in 0 until item.chunkCount) {
                                        val bytes = withContext(Dispatchers.IO) { DeviceFileTransferSender.readChunk(item, chunkIndex) }
                                        val sent = DeviceLinkManager.sendFileTransferChunk(peerId, item.transferId, chunkIndex, bytes)
                                        bytes.fill(0)
                                        if (!sent) { incomingTransferStatus = "Chunk भेजना असफल।"; outgoingSending = false; return@launch }
                                        outgoingProgress = chunkIndex + 1
                                        outgoingFile = withContext(Dispatchers.IO) { DeviceFileTransferSender.updateProgress(item, outgoingProgress) }
                                    }
                                    incomingTransferStatus = "सभी chunks भेजे गए; PC verification की प्रतीक्षा…"
                                    outgoingSending = false
                                }
                            } else if (message.optBoolean("complete") && message.optBoolean("ok")) {
                                DeviceFileTransferSender.cleanup(item)
                                outgoingFile = null
                                outgoingSending = false
                                incomingTransferStatus = "फ़ाइल PC पर verify होकर save हो गई।"
                            } else if (!message.optBoolean("ok")) {
                                incomingTransferStatus = "PC ने transfer रोक दिया; retry आवश्यक है।"
                                outgoingSending = false
                            }
                        }
                    }
                    "file_transfer_resume" -> {
                        val item = outgoingFile
                        val peerId = message.optString("fromDeviceId")
                        val indexes = message.optJSONArray("missingIndexes")
                        if (item != null && peerId == selectedPeerId && message.optString("transferId") == item.transferId && indexes != null) {
                            scope.launch {
                                outgoingSending = true
                                for (position in 0 until indexes.length()) {
                                    val chunkIndex = indexes.optInt(position, -1)
                                    if (chunkIndex !in 0 until item.chunkCount) continue
                                    val bytes = withContext(Dispatchers.IO) { DeviceFileTransferSender.readChunk(item, chunkIndex) }
                                    val sent = DeviceLinkManager.sendFileTransferChunk(peerId, item.transferId, chunkIndex, bytes)
                                    bytes.fill(0)
                                    if (!sent) { incomingTransferStatus = "Retry chunk send failed."; outgoingSending = false; return@launch }
                                    outgoingFile = withContext(Dispatchers.IO) { DeviceFileTransferSender.updateProgress(item, maxOf(item.sentChunks, chunkIndex + 1)) }
                                }
                                incomingTransferStatus = "Missing chunks resent; waiting for PC verification…"
                                outgoingSending = false
                            }
                        }
                    }
                    "file_transfer_start" -> {
                        val peerId = message.optString("fromDeviceId")
                        val manifest = DeviceFileTransferReceiver.parseManifest(peerId, message)
                        if (manifest != null && DeviceLinkManager.isE2ETrusted(peerId)) {
                            pendingIncomingFile = manifest
                            incomingTransferStatus = ""
                        } else {
                            DeviceLinkManager.acknowledgeFileTransfer(peerId, message.optString("transferId"), -1, ok = false)
                            incomingTransferStatus = "अमान्य manifest या unverified sender; transfer रोक दिया गया।"
                        }
                    }
                    "file_transfer_chunk" -> {
                        val peerId = message.optString("fromDeviceId")
                        val result = DeviceFileTransferReceiver.receiveChunk(context, peerId, message)
                        DeviceLinkManager.acknowledgeFileTransfer(peerId, message.optString("transferId"), result.index,
                            complete = result.complete, ok = result.ok)
                        incomingTransferStatus = when {
                            result.complete -> { receivedFiles = DeviceFileTransferReceiver.listReceived(context); "फ़ाइल verify होकर सुरक्षित रूप से save हो गई: ${result.savedPath}" }
                            !result.ok -> result.error ?: "फ़ाइल chunk reject हुआ।"
                            else -> "फ़ाइल प्राप्त हो रही है… chunk ${result.index + 1}"
                        }
                    }
                    "file_transfer_cancel" -> {
                        val transferId = message.optString("transferId")
                        val item = outgoingFile
                        if (item != null && item.transferId == transferId) {
                            DeviceFileTransferSender.cleanup(item)
                            outgoingFile = null
                            outgoingSending = false
                            outgoingProgress = 0
                            incomingTransferStatus = "PC cancelled the outgoing transfer."
                        } else {
                            DeviceFileTransferReceiver.reject(transferId)
                            incomingTransferStatus = "PC cancelled the incoming transfer."
                        }
                    }
                }
            }
        }
        DeviceLinkManager.onE2EVerification = { peerId, fingerprint ->
            mainHandler.post { pendingFingerprint = peerId to fingerprint }
        }
        onDispose {
            DeviceLinkManager.onStatus = null
            DeviceLinkManager.onPeerMessage = null
            DeviceLinkManager.onFileTransferMessage = null
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
    LaunchedEffect(outgoingFile?.peerId, peers) {
        val pendingPeer = outgoingFile?.peerId
        if (selectedPeerId.isBlank() && !pendingPeer.isNullOrBlank() && peers.any { it.id == pendingPeer }) selectedPeerId = pendingPeer
    }
    val sendFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val peer = selectedPeerId
        if (uri != null && peer.isNotBlank() && peerTrusted) {
            scope.launch {
                outgoingSending = true
                incomingTransferStatus = "चुनी गई फ़ाइल तैयार हो रही है…"
                val prepared = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        DeviceFileTransferSender.prepare(context.contentResolver, uri, File(context.filesDir, "OutgoingTransfers"), name)
                    }
                }
                prepared.onSuccess { staged ->
                    val item = withContext(Dispatchers.IO) { DeviceFileTransferSender.bindPeer(staged, peer) }
                    val started = DeviceLinkManager.startFileTransfer(peer, java.util.UUID.randomUUID().toString().replace("-", ""),
                        item.transferId, item.fileName, item.sizeBytes, item.chunkCount, item.sha256)
                    if (started) { outgoingFile = item; outgoingProgress = item.sentChunks; incomingTransferStatus = "PC पर save location चुनने की प्रतीक्षा…" }
                    else { DeviceFileTransferSender.cleanup(item); incomingTransferStatus = "PC transfer शुरू नहीं हो सका।"; outgoingSending = false }
                }.onFailure { incomingTransferStatus = "फ़ाइल तैयार नहीं हुई: ${it.message ?: "unknown error"}"; outgoingSending = false }
            }
        } else if (uri != null) incomingTransferStatus = "पहले trusted PC चुनें।"
    }


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
                                lastPcResult = ""
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
                    "एक ही जगह बोलकर या लिखकर PC चलाएँ। उदाहरण: “desktop दिखाओ”, “Notepad खोलो”, “ऊपर scroll करो”, “type नमस्ते”, “volume 40”, “फ़ाइल खोजो invoice”, “फ़ाइल खोलो Documents/report.pdf”. हर control PC पर आपकी अनुमति के बाद ही चलेगा।",
                    color = colors.textSecondary, fontSize = 10.sp
                )
                Text(
                    "PC FILES • केवल आपके Windows user folder के अंदर",
                    color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
                NovaTextField(
                    value = pcFilePath,
                    onValueChange = { pcFilePath = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Folder/file path, जैसे Documents या Documents/notes.txt"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovaButton(
                        text = "List files",
                        onClick = {
                            lastPcResult = ""
                            status = if (DeviceLinkManager.requestPcFileList(peer.id, pcFilePath)) {
                                "PC folder list request भेजी गई। Windows पर exact action approve करें।"
                            } else "File list request नहीं भेजी गई। Secure link और PC approval जाँचें।"
                        },
                        modifier = Modifier.weight(1f),
                        type = NovaButtonType.SECONDARY,
                        enabled = connected && peer.online && peerTrusted
                    )
                    NovaButton(
                        text = "Preview text",
                        onClick = {
                            lastPcResult = ""
                            status = if (DeviceLinkManager.requestPcTextPreview(peer.id, pcFilePath)) {
                                "Text preview request भेजी गई। Windows पर exact action approve करें।"
                            } else "Preview के लिए relative file path लिखें और secure link जाँचें।"
                        },
                        modifier = Modifier.weight(1f),
                        type = NovaButtonType.SECONDARY,
                        enabled = connected && peer.online && peerTrusted && pcFilePath.isNotBlank()
                    )
                }
                NovaButton(
                    text = "Open file / folder on PC",
                    onClick = {
                        lastPcResult = ""
                        status = if (DeviceLinkManager.requestPcFileOpen(peer.id, pcFilePath)) {
                            "PC पर file/folder open request भेजी गई। Windows पर exact path approve करें।"
                        } else "एक सुरक्षित relative file/folder path लिखें।"
                    },
                    enabled = connected && peer.online && peerTrusted && pcFilePath.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    type = NovaButtonType.SECONDARY
                )
                Text(
                    "सुरक्षा: hidden/credential folders, parent paths, executable files और unknown formats block हैं। हर open action Windows PC पर अलग से approve होगी।",
                    color = colors.textSecondary, fontSize = 10.sp
                )
                NovaTextField(
                    value = pcFileQuery,
                    onValueChange = { pcFileQuery = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Filename खोजें, जैसे invoice या बिल"
                )
                NovaButton(
                    text = "Search PC files",
                    onClick = {
                        lastPcResult = ""
                        status = if (DeviceLinkManager.requestPcFileSearch(peer.id, pcFileQuery, pcFilePath)) {
                            "Filename search request भेजी गई। Windows पर exact search approve करें।"
                        } else "कम से कम 2 अक्षरों का filename search लिखें।"
                    },
                    enabled = connected && peer.online && peerTrusted && pcFileQuery.trim().length >= 2,
                    modifier = Modifier.fillMaxWidth(),
                    type = NovaButtonType.SECONDARY
                )
            }
        }

        NovaGlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("RECEIVED FILES", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                TextButton(onClick = { receivedFiles = DeviceFileTransferReceiver.listReceived(context) }) { Text("Refresh") }
            }
            if (receivedFiles.isEmpty()) {
                Text("अभी कोई received file नहीं है। PC से सुरक्षित transfer पूरा होने पर फ़ाइल यहाँ दिखेगी।", color = colors.textSecondary, fontSize = 12.sp)
            } else {
                receivedFiles.forEach { file ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(file.name, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${file.length() / 1024} KB • ${DeviceFileTransferReceiver.mimeType(file)}", color = colors.textSecondary, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            NovaButton(text = "Open", onClick = {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.lia.fileprovider", file)
                                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DeviceFileTransferReceiver.mimeType(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(intent)
                                }.onFailure { incomingTransferStatus = "फ़ाइल खोलने के लिए compatible app नहीं मिला: ${it.message ?: "unknown error"}" }
                            }, modifier = Modifier.weight(1f), type = NovaButtonType.SECONDARY)
                            NovaButton(text = "Share", onClick = {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.lia.fileprovider", file)
                                    val intent = Intent(Intent.ACTION_SEND).setType(DeviceFileTransferReceiver.mimeType(file)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(Intent.createChooser(intent, "Share with…"))
                                }.onFailure { incomingTransferStatus = "Share शुरू नहीं हो सका: ${it.message ?: "unknown error"}" }
                            }, modifier = Modifier.weight(1f), type = NovaButtonType.SECONDARY)
                            NovaButton(text = "Downloads", onClick = { pendingExportFile = file; exportFileLauncher.launch(file.name) }, modifier = Modifier.weight(1f), type = NovaButtonType.SECONDARY)
                        }
                    }
                }
            }
        }
        if (incomingTransferStatus.isNotBlank()) {
            NovaGlassCard {
                Text("FILE TRANSFER STATUS", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(incomingTransferStatus, color = colors.textPrimary, fontSize = 12.sp)
            }
        }
        if (lastPcResult.isNotBlank()) {
            NovaGlassCard {
                Text(
                    if (lastPcResultOk) "PC RESULT" else "PC ERROR",
                    color = if (lastPcResultOk) Color(0xFF58D6A5) else Color(0xFFFF8A80),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                SelectionContainer {
                    Text(lastPcResult, color = colors.textPrimary, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovaButton(
                        text = "Copy full result",
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Shraddha PC result", lastPcResult))
                            status = "पूरा PC result clipboard में copy हो गया।"
                        },
                        modifier = Modifier.weight(1f),
                        type = NovaButtonType.SECONDARY
                    )
                    NovaButton(
                        text = "Clear",
                        onClick = { lastPcResult = "" },
                        modifier = Modifier.weight(1f),
                        type = NovaButtonType.TEXT
                    )
                }
            }
        }
        if (incomingTransferStatus.isNotBlank()) {
            NovaGlassCard {
                Text("PC → PHONE FILE TRANSFER", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(incomingTransferStatus, color = colors.textPrimary, fontSize = 12.sp)
            }
        }
        NovaGlassCard {
            Text("PHONE TO PC FILE TRANSFER", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Send a document to the trusted PC. The PC user must approve its save location. Maximum 100 MB.", color = colors.textSecondary, fontSize = 11.sp)
            NovaButton(text = "Choose file and send to PC", onClick = { sendFilePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(), enabled = connected && selectedPeer?.online == true && selectedPeerId.isNotBlank() && peerTrusted && !outgoingSending,
                type = NovaButtonType.PRIMARY)
            outgoingFile?.let { item ->
                Text("${item.fileName} • ${item.sizeBytes / 1024} KB • ${outgoingProgress}/${item.chunkCount} chunks", color = colors.textPrimary, fontSize = 11.sp)
                NovaButton(text = "Resume / Retry", onClick = {
                    if (item.peerId.isBlank() || item.peerId != selectedPeerId || !DeviceLinkManager.isE2ETrusted(item.peerId)) {
                        incomingTransferStatus = "इस pending transfer के लिए वही trusted PC चुनें।"
                    } else {
                    val requested = DeviceLinkManager.requestFileTransferResume(selectedPeerId, item.transferId, item.chunkCount, emptyList())
                    incomingTransferStatus = if (requested) "PC से missing chunks की सूची माँगी गई…" else "Resume request नहीं भेजी जा सकी।"
                    }
                }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
                NovaButton(text = "Cancel transfer", onClick = {
                    DeviceLinkManager.cancelFileTransfer(selectedPeerId, item.transferId, "cancelled_by_phone")
                    DeviceFileTransferSender.cleanup(item)
                    outgoingFile = null
                    outgoingSending = false
                    outgoingProgress = 0
                    incomingTransferStatus = "Phone transfer cancelled."
                }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
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

    pendingShareRequest?.let { request ->
        val peerId = request.optString("fromDeviceId")
        val requestId = request.optString("requestId")
        AlertDialog(
            onDismissRequest = { pendingShareRequest = null },
            title = { Text("PC से फ़ाइल भेजने की अनुमति") },
            text = { Text("Paired PC ($peerId) यह फ़ाइल भेजना चाहता है: ${request.optString("itemLabel", "document")}। केवल तभी अनुमति दें जब आपने स्वयं transfer शुरू किया हो।") },
            confirmButton = {
                TextButton(onClick = {
                    val sent = DeviceLinkManager.respondDataShare(peerId, "documents", requestId, true)
                    incomingTransferStatus = if (sent) "अनुमति भेजी गई। अब आने वाली फ़ाइल का manifest स्वीकार करें।" else "अनुमति भेजी नहीं जा सकी।"
                    pendingShareRequest = null
                }) { Text("अनुमति दें") }
            },
            dismissButton = {
                TextButton(onClick = {
                    DeviceLinkManager.respondDataShare(peerId, "documents", requestId, false)
                    pendingShareRequest = null
                }) { Text("मना करें") }
            }
        )
    }

    pendingIncomingFile?.let { manifest ->
        AlertDialog(
            onDismissRequest = {
                DeviceLinkManager.acknowledgeFileTransfer(manifest.peerId, manifest.transferId, -1, ok = false)
                pendingIncomingFile = null
            },
            title = { Text("फ़ाइल प्राप्त करें?") },
            text = { Text("PC: ${manifest.peerId}\nफ़ाइल: ${manifest.fileName}\nआकार: ${manifest.sizeBytes / 1024} KB\n\nफ़ाइल पहले app-private ReceivedFiles folder में save होगी। SHA-256 जाँच सफल होने पर ही final file बनेगी।") },
            confirmButton = {
                TextButton(onClick = {
                    val accepted = DeviceFileTransferReceiver.accept(context, manifest)
                    val acknowledged = accepted && DeviceLinkManager.acknowledgeFileTransfer(manifest.peerId, manifest.transferId, -1, ok = true)
                    if (!acknowledged) {
                        DeviceFileTransferReceiver.reject(manifest.transferId)
                        DeviceLinkManager.acknowledgeFileTransfer(manifest.peerId, manifest.transferId, -1, ok = false)
                        incomingTransferStatus = "फ़ाइल receive शुरू नहीं हो सकी।"
                    } else incomingTransferStatus = "फ़ाइल receive शुरू हो गई है…"
                    pendingIncomingFile = null
                }) { Text("फ़ाइल स्वीकार करें") }
            },
            dismissButton = {
                TextButton(onClick = {
                    DeviceLinkManager.acknowledgeFileTransfer(manifest.peerId, manifest.transferId, -1, ok = false)
                    pendingIncomingFile = null
                    incomingTransferStatus = "फ़ाइल transfer अस्वीकार किया।"
                }) { Text("अस्वीकार करें") }
            }
        )
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
