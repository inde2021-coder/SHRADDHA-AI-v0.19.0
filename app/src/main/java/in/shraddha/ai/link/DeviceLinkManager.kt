package `in`.shraddha.ai.link

import android.content.Context
import android.util.Base64
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Authenticated relay client. Deploy behind HTTPS/WSS; never use an untrusted relay. */
object DeviceLinkManager {
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    @Volatile private var credentialStore: DeviceCredentialStore? = null

    /** Must be called once from Application.onCreate before pairing is used. */
    fun initialize(context: Context) {
        credentialStore = DeviceCredentialStore(context.applicationContext)
    }

    /** True when a Keystore-encrypted pairing can be restored by an explicit reconnect flow. */
    fun hasSavedPairing(): Boolean = runCatching { credentialStore?.load() != null }.getOrDefault(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile var sessionId: String? = null; private set
    @Volatile var deviceId: String? = null; private set
    @Volatile private var deviceToken: String? = null
    @Volatile var onStatus: ((String) -> Unit)? = null
    @Volatile var onRemoteAction: ((String, String, JSONObject) -> Unit)? = null
    @Volatile var onRemoteActionResult: ((String, String, Boolean, String) -> Unit)? = null
    @Volatile var onScreenFrame: ((String, ByteArray) -> Unit)? = null
    @Volatile var onCameraFrame: ((String, ByteArray) -> Unit)? = null
    @Volatile var onPeerMessage: ((JSONObject) -> Unit)? = null
    @Volatile var onFileTransferMessage: ((JSONObject) -> Unit)? = null
    @Volatile var onE2EVerification: ((String, String) -> Unit)? = null
    private val e2eCrypto by lazy { DeviceE2ECrypto.create() }
    private data class PeerKeyState(val key: ByteArray, val fingerprint: String, @Volatile var localConfirmed: Boolean = false, @Volatile var remoteConfirmed: Boolean = false, val sendSequence: AtomicLong = AtomicLong(0), @Volatile var lastScreenSequence: Long = -1, @Volatile var lastCameraSequence: Long = -1)
    private val peerKeys = ConcurrentHashMap<String, PeerKeyState>()
    private val pendingActionPeers = ConcurrentHashMap<String, String>()

    data class PairResult(val code: String, val sessionId: String, val deviceId: String, val token: String)

    /** Reconnects only with a previously paired credential restored from Android Keystore storage. */
    fun reconnectSaved(done: (Result<PairResult>) -> Unit) {
        val credentials = runCatching { credentialStore?.load() }.getOrNull()
        if (credentials == null) {
            done(Result.failure(IllegalStateException("No valid saved device pairing is available. Pair this device again.")))
            return
        }
        val pair = PairResult("", credentials.sessionId, credentials.deviceId, credentials.token)
        saveAndConnect(credentials.baseUrl, pair, done)
    }

    fun createSession(baseUrl: String, name: String, done: (Result<PairResult>) -> Unit) = post(baseUrl, "/api/device/session", JSONObject().put("deviceName", name)) { result ->
        result.mapCatching { body -> PairResult(body.getString("pairingCode"), body.getString("sessionId"), body.getString("deviceId"), body.getString("deviceToken")) }
            .onSuccess { saveAndConnect(baseUrl, it, done) }.onFailure { done(Result.failure(it)) }
    }

    fun joinSession(baseUrl: String, name: String, code: String, done: (Result<PairResult>) -> Unit) = post(baseUrl, "/api/device/join", JSONObject().put("deviceName", name).put("pairingCode", code)) { result ->
        result.mapCatching { body -> PairResult("", body.getString("sessionId"), body.getString("deviceId"), body.getString("deviceToken")) }
            .onSuccess { saveAndConnect(baseUrl, it, done) }.onFailure { done(Result.failure(it)) }
    }

    private fun saveAndConnect(baseUrl: String, pair: PairResult, done: (Result<PairResult>) -> Unit) {
        val parsed = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        if (parsed == null || parsed.scheme != "https" || parsed.host.isBlank()) {
            done(Result.failure(IllegalArgumentException("Remote device pairing requires a valid HTTPS relay URL. Plain HTTP is blocked.")))
            return
        }
        if (pair.sessionId.isBlank() || pair.deviceId.isBlank() || pair.token.length < 24) {
            done(Result.failure(IllegalArgumentException("Relay returned an invalid session or device credential.")))
            return
        }
        val store = credentialStore
        if (store == null) {
            done(Result.failure(IllegalStateException("DeviceLinkManager must be initialized by the Application before pairing.")))
            return
        }
        // Detach the previous socket before closing it. Its asynchronous callbacks
        // must already see that they are stale, even if they race the new connect call.
        val previousSocket = socket
        socket = null
        previousSocket?.close(1000, "Superseded by a newer connection")
        sessionId = pair.sessionId; deviceId = pair.deviceId; deviceToken = pair.token
        val completionDelivered = AtomicBoolean(false)
        val url = parsed.newBuilder().scheme("wss").addPathSegments("ws")
            .addQueryParameter("sessionId", pair.sessionId)
            .addQueryParameter("deviceId", pair.deviceId).build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${pair.token}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) {
                    webSocket.close(1000, "Superseded connection")
                    return
                }
                try {
                    store.save(pair.sessionId, pair.deviceId, pair.token, parsed.toString())
                    onStatus?.invoke("Secure relay connected")
                    if (completionDelivered.compareAndSet(false, true)) done(Result.success(pair))
                } catch (error: Exception) {
                    onStatus?.invoke("Could not securely save device credentials")
                    webSocket.close(1000, "Credential storage failed")
                    if (completionDelivered.compareAndSet(false, true)) {
                        done(Result.failure(IllegalStateException("Could not securely save device credentials: ${error.message ?: "Keystore error"}")))
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "connected" -> { val peers = msg.optJSONArray("devices"); for (i in 0 until (peers?.length() ?: 0)) { val peerId = peers?.optJSONObject(i)?.optString("id").orEmpty(); if (peerId.isNotBlank() && peerId != deviceId) sendE2EKeyOffer(peerId) }; onStatus?.invoke("Connected • ${peers?.length() ?: 0} paired device(s)"); onPeerMessage?.invoke(msg); onFileTransferMessage?.invoke(msg) }
                        "peer_status" -> { val peerId = msg.optString("deviceId"); if (msg.optBoolean("revoked")) peerKeys.remove(peerId)?.key?.fill(0) else if (msg.optBoolean("online") && peerId.isNotBlank() && peerId != deviceId) sendE2EKeyOffer(peerId); onStatus?.invoke(if (msg.optBoolean("revoked")) "${msg.optString("name", "Device")}: pairing revoked" else "${msg.optString("name", "Device")}: ${if (msg.optBoolean("online")) "online" else "offline"}"); onPeerMessage?.invoke(msg) }
                        "e2e_key_offer" -> { val peerId = msg.optString("fromDeviceId"); val sid = sessionId.orEmpty(); val localId = deviceId.orEmpty(); if (peerId.isNotBlank() && sid.isNotBlank() && localId.isNotBlank()) { runCatching { e2eCrypto.derivePeerKey(msg.optString("publicKey"), sid, localId, peerId) }.onSuccess { (key, fingerprint) -> peerKeys.put(peerId, PeerKeyState(key, fingerprint))?.key?.fill(0); onE2EVerification?.invoke(peerId, fingerprint) }.onFailure { onStatus?.invoke("Peer E2E key offer rejected: ${it.message ?: "invalid key"}") } } }
                        "e2e_key_confirm" -> { val peerId = msg.optString("fromDeviceId"); val state = peerKeys[peerId]; if (state != null && state.fingerprint == msg.optString("fingerprint")) { state.remoteConfirmed = true; onStatus?.invoke(if (state.localConfirmed) "End-to-end peer verified: $peerId" else "Peer confirmed fingerprint; local confirmation is still required.") } else onStatus?.invoke("E2E fingerprint mismatch. Do not trust this peer.") }
                        "e2e_message" -> handleE2EMessage(msg)
                        "file_transfer_start", "file_transfer_ack", "file_transfer_resume", "file_transfer_cancel" -> onFileTransferMessage?.invoke(msg)
                        "data_share_result" -> { onPeerMessage?.invoke(msg); onFileTransferMessage?.invoke(msg) }
                        "remote_action" -> onStatus?.invoke("Blocked plaintext remote action. End-to-end encryption is required.")
                        "remote_action_result" -> onStatus?.invoke("Blocked plaintext remote action result. End-to-end encryption is required.")
                        "control_mode" -> onPeerMessage?.invoke(msg)
                        "remote_action" -> onRemoteAction?.invoke(msg.optString("fromDeviceId"), msg.optString("actionId"), msg.optJSONObject("action") ?: JSONObject())
                        "remote_action_result" -> onRemoteActionResult?.invoke(msg.optString("targetDeviceId"), msg.optString("actionId"), msg.optBoolean("ok"), msg.optString("reason"))
                        else -> onPeerMessage?.invoke(msg)
                    }
                } catch (_: Exception) { onStatus?.invoke("Received an unreadable relay message") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (socket !== webSocket) return
                val frame = bytes.toByteArray()
                if (frame.isEmpty()) return
                val idLength = frame[0].toInt() and 0xFF
                if (idLength !in 1..64 || frame.size <= 2 + idLength) return
                val sourceId = frame.copyOfRange(1, 1 + idLength).toString(Charsets.UTF_8)
                val encryptedType = frame[1 + idLength].toInt() and 0xFF
                if (encryptedType !in 0x81..0x82) return // Plaintext visual frames are rejected.
                val frameType = encryptedType and 0x7F
                val headerOffset = 2 + idLength
                if (frame.size < headerOffset + 8 + 12 + 16) return
                val state = peerKeys[sourceId] ?: return
                if (!state.localConfirmed || !state.remoteConfirmed) return
                val sequence = ByteBuffer.wrap(frame, headerOffset + 1, 8).order(ByteOrder.BIG_ENDIAN).long
                if (sequence < 0) return
                synchronized(state) {
                    val previous = if (frameType == 1) state.lastScreenSequence else state.lastCameraSequence
                    if (sequence <= previous) return
                    val nonce = frame.copyOfRange(headerOffset + 9, headerOffset + 21)
                    val ciphertext = frame.copyOfRange(headerOffset + 21, frame.size)
                    val aad = "${sessionId.orEmpty()}|$sourceId|${deviceId.orEmpty()}|$frameType|$sequence".toByteArray(Charsets.UTF_8)
                    val plain = runCatching {
                        DeviceE2ECrypto.decrypt(
                            state.key,
                            java.util.Base64.getEncoder().withoutPadding().encodeToString(nonce),
                            java.util.Base64.getEncoder().withoutPadding().encodeToString(ciphertext),
                            aad
                        )
                    }.getOrNull() ?: return
                    if (!DeviceFrameValidation.isValidJpeg(plain)) { plain.fill(0); return }
                    if (frameType == 1) state.lastScreenSequence = sequence else state.lastCameraSequence = sequence
                    if (frameType == 2) onCameraFrame?.invoke(sourceId, plain) else onScreenFrame?.invoke(sourceId, plain)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val rejectedCredential = response?.code == 401 || response?.code == 403
                val wasActive = socket === webSocket
                if (wasActive) {
                    if (rejectedCredential) {
                        // Only the active socket may invalidate the currently saved pairing.
                        // A late 401 from a superseded socket must not erase newer credentials.
                        runCatching { credentialStore?.clear() }
                    }
                    sessionId = null; deviceId = null; deviceToken = null; socket = null
                    clearE2EKeys()
                    onStatus?.invoke(
                        if (rejectedCredential) "Pairing expired or revoked. Pair this device again."
                        else "Relay error: ${t.message ?: "connection failed"}"
                    )
                    if (completionDelivered.compareAndSet(false, true)) done(Result.failure(t))
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) {
                    if (code == 4003) runCatching { credentialStore?.clear() }
                    sessionId = null; deviceId = null; deviceToken = null; socket = null
                    clearE2EKeys()
                    onStatus?.invoke(if (code == 4003) "This device pairing was revoked by the session owner." else "Disconnected: $reason")
                }
            }
        })
    }

    private fun sendE2EKeyOffer(targetDeviceId: String): Boolean = socket?.send(JSONObject().put("type", "e2e_key_offer").put("targetDeviceId", targetDeviceId).put("publicKey", e2eCrypto.publicKeyBase64()).toString()) == true

    fun confirmE2EPeer(peerId: String, fingerprint: String): Boolean {
        val state = peerKeys[peerId] ?: return false
        if (state.fingerprint != fingerprint) return false
        val sent = socket?.send(JSONObject().put("type", "e2e_key_confirm").put("targetDeviceId", peerId).put("fingerprint", fingerprint).toString()) == true
        if (sent) state.localConfirmed = true
        return sent
    }

    fun rejectE2EPeer(peerId: String) { peerKeys.remove(peerId)?.key?.fill(0) }

    fun isE2ETrusted(peerId: String): Boolean = peerKeys[peerId]?.let { it.localConfirmed && it.remoteConfirmed } == true

    private fun sendEncryptedPeerMessage(targetDeviceId: String, kind: String, payload: JSONObject, actionId: String, actionKind: String = ""): Boolean {
        val state = peerKeys[targetDeviceId] ?: return false
        val sid = sessionId ?: return false
        val source = deviceId ?: return false
        if (!state.localConfirmed || !state.remoteConfirmed || !actionId.matches(Regex("[A-Za-z0-9_-]{12,64}"))) {
            onStatus?.invoke("Encrypted action blocked: mutually verify the peer fingerprint first.")
            return false
        }
        val aad = "$sid|$source|$targetDeviceId|$kind|$actionId".toByteArray(Charsets.UTF_8)
        val sealed = runCatching { DeviceE2ECrypto.encrypt(state.key, payload.toString().toByteArray(Charsets.UTF_8), aad) }.getOrNull() ?: return false
        val message = JSONObject().put("type", "e2e_message").put("targetDeviceId", targetDeviceId)
            .put("messageKind", kind).put("actionId", actionId).put("actionKind", actionKind.take(32))
            .put("nonce", sealed.nonce).put("ciphertext", sealed.ciphertext)
        return socket?.send(message.toString()) == true
    }

    private fun handleE2EMessage(msg: JSONObject) {
        val peerId = msg.optString("fromDeviceId")
        val kind = msg.optString("messageKind")
        val actionId = msg.optString("actionId")
        val state = peerKeys[peerId] ?: return
        val sid = sessionId ?: return
        val local = deviceId ?: return
        if (!state.localConfirmed || !state.remoteConfirmed || kind !in setOf("remote_action", "remote_action_result", "file_transfer_chunk") ||
            !actionId.matches(Regex("[A-Za-z0-9_-]{12,64}"))) return
        val transferId = msg.optString("transferId")
        val transferIndex = msg.optInt("index", -1)
        val correlation = if (kind == "file_transfer_chunk") "$transferId:$transferIndex" else actionId
        if (kind == "file_transfer_chunk" && (transferId != actionId || transferIndex !in 0..2199)) return
        val aad = "$sid|$peerId|$local|$kind|$correlation".toByteArray(Charsets.UTF_8)
        val plain = runCatching { DeviceE2ECrypto.decrypt(state.key, msg.optString("nonce"), msg.optString("ciphertext"), aad) }.getOrNull() ?: return
        try {
            val payload = JSONObject(plain.toString(Charsets.UTF_8))
            if (kind == "file_transfer_chunk") {
                payload.put("type", "file_transfer_chunk").put("fromDeviceId", peerId)
                    .put("transferId", transferId).put("index", transferIndex)
                onFileTransferMessage?.invoke(payload)
            } else if (kind == "remote_action") {
                val action = payload.optJSONObject("action") ?: payload
                if (action.optString("kind").isBlank()) return
                pendingActionPeers[actionId] = peerId
                onRemoteAction?.invoke(peerId, actionId, action)
            } else {
                onRemoteActionResult?.invoke(peerId, actionId, payload.optBoolean("ok"), payload.optString("reason").take(240))
            }
        } finally { plain.fill(0) }
    }

    fun startFileTransfer(targetDeviceId: String, requestId: String, transferId: String, fileName: String, sizeBytes: Long, chunkCount: Int, sha256: String): Boolean {
        if (targetDeviceId.isBlank() || !requestId.matches(Regex("^[A-Za-z0-9_-]{12,64}$")) ||
            !transferId.matches(Regex("^[A-Za-z0-9_-]{12,64}$")) || sizeBytes !in 1L..(100L * 1024 * 1024) ||
            chunkCount !in 1..2200 || !sha256.matches(Regex("^[a-fA-F0-9]{64}$")) ||
            chunkCount != ((sizeBytes + 49151) / 49152).toInt()) return false
        return socket?.send(JSONObject().put("type", "file_transfer_start").put("targetDeviceId", targetDeviceId)
            .put("requestId", requestId).put("transferId", transferId).put("fileName", fileName.take(120))
            .put("sizeBytes", sizeBytes).put("chunkCount", chunkCount).put("sha256", sha256.lowercase()).toString()) == true
    }

    fun sendFileTransferChunk(targetDeviceId: String, transferId: String, index: Int, bytes: ByteArray): Boolean {
        if (!transferId.matches(Regex("^[A-Za-z0-9_-]{12,64}$")) || index !in 0..2199 || bytes.isEmpty() || bytes.size > 49152) return false
        val state = peerKeys[targetDeviceId] ?: return false
        val sid = sessionId ?: return false
        val source = deviceId ?: return false
        if (!state.localConfirmed || !state.remoteConfirmed) return false
        val correlation = "$transferId:$index"
        val aad = "$sid|$source|$targetDeviceId|file_transfer_chunk|$correlation".toByteArray(Charsets.UTF_8)
        val payload = JSONObject().put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val sealed = runCatching { DeviceE2ECrypto.encrypt(state.key, payload.toString().toByteArray(Charsets.UTF_8), aad) }.getOrNull() ?: return false
        return socket?.send(JSONObject().put("type", "e2e_message").put("targetDeviceId", targetDeviceId)
            .put("messageKind", "file_transfer_chunk").put("actionId", transferId).put("transferId", transferId)
            .put("index", index).put("nonce", sealed.nonce).put("ciphertext", sealed.ciphertext).toString()) == true
    }

    fun requestFileTransferResume(sourceDeviceId: String, transferId: String): Boolean =
        socket?.send(JSONObject().put("type", "file_transfer_resume").put("targetDeviceId", sourceDeviceId).put("transferId", transferId).toString()) == true

    fun acknowledgeFileTransfer(targetDeviceId: String, transferId: String, index: Int, complete: Boolean = false, ok: Boolean = true): Boolean =
        socket?.send(JSONObject().put("type", "file_transfer_ack").put("targetDeviceId", targetDeviceId)
            .put("transferId", transferId).put("index", index).put("complete", complete).put("ok", ok).toString()) == true

    fun cancelFileTransfer(targetDeviceId: String, transferId: String, reason: String = "cancelled"): Boolean =
        socket?.send(JSONObject().put("type", "file_transfer_cancel").put("targetDeviceId", targetDeviceId)
            .put("transferId", transferId).put("reason", reason.take(120)).toString()) == true

    fun sendAction(targetDeviceId: String, action: JSONObject): Boolean {
        val actionId = java.util.UUID.randomUUID().toString().replace("-", "")
        return sendEncryptedPeerMessage(targetDeviceId, "remote_action", JSONObject().put("action", action), actionId, action.optString("kind"))
    }

    fun respondRemoteAction(actionId: String, ok: Boolean, reason: String = ""): Boolean {
        val peerId = pendingActionPeers[actionId] ?: return false
        val sent = sendEncryptedPeerMessage(peerId, "remote_action_result",
            JSONObject().put("ok", ok).put("reason", reason.take(240)), actionId)
        if (sent) pendingActionPeers.remove(actionId, peerId)
        return sent
    }

    private fun clearE2EKeys() {
        peerKeys.values.forEach { it.key.fill(0) }
        peerKeys.clear()
        pendingActionPeers.clear()
    }

    private fun secureApiUrl(baseUrl: String, path: String): String? {
        val parsed = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host.isBlank()) return null
        return parsed.newBuilder().addPathSegments(path.trimStart('/')).build().toString()
    }

    private fun <T> rejectInsecure(done: (Result<T>) -> Unit) {
        done(Result.failure(IllegalArgumentException("AI gateway requires a valid HTTPS URL. Plain HTTP is blocked.")))
    }

    private fun post(base: String, path: String, body: JSONObject, callback: (Result<JSONObject>) -> Unit) {
        val parsed = base.trim().trimEnd('/').toHttpUrlOrNull()
        if (parsed == null || parsed.scheme != "https" || parsed.host.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Pairing and remote-device APIs require HTTPS. Plain HTTP is blocked.")))
            return
        }
        val url = parsed.newBuilder().addPathSegments(path.trimStart('/')).build()
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use { val raw = it.body?.string().orEmpty(); if (!it.isSuccessful) callback(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}")))) else callback(runCatching { JSONObject(raw) }) }
            }
        })
    }

    fun askChat(baseUrl: String, gatewayToken: String, provider: String, prompt: String, done: (Result<String>) -> Unit) {
        val endpoint = secureApiUrl(baseUrl, "/api/chat")
        if (endpoint == null) { rejectInsecure(done); return }
        val body = JSONObject().put("provider", provider).put("prompt", prompt.take(12000))
        val builder = Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}"))))
                    else done(runCatching { JSONObject(raw).getString("answer") })
                }
            }
        })
    }

    fun providerStatus(baseUrl: String, gatewayToken: String, done: (Result<String>) -> Unit) {
        val endpoint = secureApiUrl(baseUrl, "/api/ai/providers")
        if (endpoint == null) { rejectInsecure(done); return }
        val builder = Request.Builder().url(endpoint).get()
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) { response.use { val raw = it.body?.string().orEmpty(); if (!it.isSuccessful) done(Result.failure(IllegalStateException("HTTP ${it.code}"))) else done(Result.success(raw)) } }
        })
    }

    fun summarize(baseUrl: String, gatewayToken: String, provider: String, text: String, done: (Result<String>) -> Unit) {
        postAuthenticated(baseUrl, "/api/ai/summarize", gatewayToken, JSONObject().put("provider", provider).put("text", text.take(18000)), done)
    }

    fun planTask(baseUrl: String, gatewayToken: String, provider: String, goal: String, done: (Result<String>) -> Unit) {
        postAuthenticated(baseUrl, "/api/agent/plan", gatewayToken, JSONObject().put("provider", provider).put("goal", goal.take(3000)), done)
    }

    fun studioWork(baseUrl: String, gatewayToken: String, provider: String, workspace: String, task: String, request: String, done: (Result<String>) -> Unit) {
        postAuthenticated(baseUrl, "/api/studio/work", gatewayToken,
            JSONObject().put("provider", provider).put("workspace", workspace).put("task", task).put("request", request.take(14000)), done)
    }

    fun expertAssist(baseUrl: String, gatewayToken: String, provider: String, domain: String, mode: String, question: String, done: (Result<String>) -> Unit) {
        postAuthenticated(baseUrl, "/api/expert/assist", gatewayToken,
            JSONObject().put("provider", provider).put("domain", domain).put("mode", mode).put("question", question.take(16000)), done)
    }

    fun compareProviders(baseUrl: String, gatewayToken: String, providers: List<String>, prompt: String, done: (Result<String>) -> Unit) {
        postAuthenticated(baseUrl, "/api/ai/compare", gatewayToken, JSONObject().put("providers", org.json.JSONArray(providers.distinct().take(3))).put("prompt", prompt.take(4000)), done)
    }

    fun telephonyStatus(baseUrl: String, gatewayToken: String, done: (Result<JSONObject>) -> Unit) =
        getAuthenticated(baseUrl, "/api/telephony/edesy/status", gatewayToken, done)

    fun telephonyCalls(baseUrl: String, gatewayToken: String, done: (Result<JSONObject>) -> Unit) =
        getAuthenticated(baseUrl, "/api/telephony/edesy/calls", gatewayToken, done)

    fun telephonyPreflight(
        baseUrl: String,
        gatewayToken: String,
        sampleRate: Int = 16000,
        seconds: Int = 2,
        done: (Result<JSONObject>) -> Unit
    ) {
        if ((sampleRate != 8000 && sampleRate != 16000) || seconds !in 1..5) {
            done(Result.failure(IllegalArgumentException("Preflight sample rate or duration is invalid.")))
            return
        }
        val endpoint = secureApiUrl(baseUrl, "/api/telephony/edesy/preflight")
        if (endpoint == null) {
            done(Result.failure(IllegalArgumentException("Preflight requires a valid HTTPS backend URL.")))
            return
        }
        val payload = JSONObject().put("sample_rate", sampleRate).put("seconds", seconds)
        val builder = Request.Builder().url(endpoint)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}"))))
                    } else {
                        done(runCatching { JSONObject(raw) })
                    }
                }
            }
        })
    }

    private fun getAuthenticated(baseUrl: String, path: String, gatewayToken: String, done: (Result<JSONObject>) -> Unit) {
        val endpoint = secureApiUrl(baseUrl, path)
        if (endpoint == null) {
            done(Result.failure(IllegalArgumentException("Telephony status requires a valid HTTPS backend URL.")))
            return
        }
        val builder = Request.Builder().url(endpoint).get()
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}"))))
                    } else {
                        done(runCatching { JSONObject(raw) })
                    }
                }
            }
        })
    }

    private fun postAuthenticated(baseUrl: String, path: String, gatewayToken: String, body: JSONObject, done: (Result<String>) -> Unit) {
        val endpoint = secureApiUrl(baseUrl, path)
        if (endpoint == null) { rejectInsecure(done); return }
        val builder = Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType()))
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use { val raw = it.body?.string().orEmpty(); if (!it.isSuccessful) done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}")))) else done(Result.success(raw)) }
            }
        })
    }

    fun analyzeScreen(baseUrl: String, gatewayToken: String, provider: String, prompt: String, jpeg: ByteArray, done: (Result<String>) -> Unit) {
        if (jpeg.isEmpty() || jpeg.size > 2_500_000) {
            done(Result.failure(IllegalArgumentException("Vision image must be between 1 byte and 2.5 MB.")))
            return
        }
        val endpoint = secureApiUrl(baseUrl, "/api/vision")
        if (endpoint == null) { rejectInsecure(done); return }
        val body = JSONObject().put("provider", provider).put("prompt", prompt.take(4000))
            .put("mimeType", "image/jpeg").put("imageBase64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        val builder = Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}"))))
                    else done(runCatching { JSONObject(raw).getString("answer") })
                }
            }
        })
    }

    fun updateSharePermissions(permissions: JSONObject): Boolean = socket?.send(JSONObject().put("type", "permission_update").put("permissions", permissions).toString()) == true
    fun requestDataShare(targetDeviceId: String, category: String, itemLabel: String = "", requestId: String = java.util.UUID.randomUUID().toString()): Boolean =
        requestId.matches(Regex("^[A-Za-z0-9_-]{12,64}$")) &&
            socket?.send(JSONObject().put("type", "data_share_request").put("targetDeviceId", targetDeviceId)
                .put("category", category).put("itemLabel", itemLabel).put("requestId", requestId).toString()) == true
    fun respondDataShare(requesterDeviceId: String, category: String, approved: Boolean, minutes: Int = 5, requestId: String = ""): Boolean =
        requestId.matches(Regex("^[A-Za-z0-9_-]{12,64}$")) &&
            socket?.send(JSONObject().put("type", "data_share_response").put("requesterDeviceId", requesterDeviceId)
                .put("category", category).put("requestId", requestId).put("approved", approved).put("minutes", minutes).toString()) == true
    fun sendSharedPayload(targetDeviceId: String, category: String, fileName: String, mimeType: String, base64Payload: String): Boolean = socket?.send(JSONObject().put("type", "data_share_payload").put("targetDeviceId", targetDeviceId).put("category", category).put("fileName", fileName.take(120)).put("mimeType", mimeType.take(100)).put("payload", base64Payload).toString()) == true
    fun sendSignal(targetDeviceId: String, signal: JSONObject): Boolean = socket?.send(JSONObject().put("type", "signal").put("targetDeviceId", targetDeviceId).put("signal", signal).toString()) == true
    fun requestRemoteControl(targetDeviceId: String, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "control_request").put("targetDeviceId", targetDeviceId).put("minutes", minutes.coerceIn(1, 5)).toString()) == true
    fun respondRemoteControl(requesterDeviceId: String, approved: Boolean, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "control_response").put("requesterDeviceId", requesterDeviceId).put("approved", approved).put("minutes", minutes.coerceIn(1, 5)).toString()) == true
    fun sendAction(targetDeviceId: String, action: JSONObject): Boolean = socket?.send(JSONObject().put("type", "remote_action").put("targetDeviceId", targetDeviceId).put("action", action).toString()) == true
    fun respondRemoteAction(actionId: String, ok: Boolean, reason: String = ""): Boolean =
        if (actionId.isBlank() || actionId.length > 64) false else socket?.send(JSONObject().put("type", "remote_action_result").put("actionId", actionId).put("ok", ok).put("reason", reason.take(240)).toString()) == true
    fun sendChat(message: String): Boolean = socket?.send(JSONObject().put("type", "chat").put("message", message.take(2000)).toString()) == true
    fun setControlMode(masterOnly: Boolean): Boolean = socket?.send(JSONObject().put("type", "set_control_mode").put("mode", if (masterOnly) "master" else "mutual").toString()) == true
    /** Immediately revoke every remote-control grant issued by this phone. */
    fun revokeAllRemoteControlGrants(): Boolean =
        socket?.send(JSONObject().put("type", "control_revoke").toString()) == true

    /** Revoke one requester's remote-control grant on this phone. */
    fun revokeRemoteControlGrant(requesterDeviceId: String): Boolean {
        if (!requesterDeviceId.matches(Regex("^[a-fA-F0-9]{24}$"))) return false
        return socket?.send(JSONObject().put("type", "control_revoke").put("requesterDeviceId", requesterDeviceId).toString()) == true
    }
    fun sendFrame(jpeg: ByteArray, frameType: Int = 1, targetDeviceId: String? = null): Boolean {
        if (!DeviceFrameValidation.isValidJpeg(jpeg) || frameType !in 1..2) return false
        val sid = sessionId ?: return false
        val sourceId = deviceId ?: return false
        val targets = if (targetDeviceId != null) listOf(targetDeviceId) else peerKeys.keys.toList()
        var sentAny = false
        for (targetId in targets) {
            val state = peerKeys[targetId] ?: continue
            if (!state.localConfirmed || !state.remoteConfirmed) continue
            val target = targetId.toByteArray(Charsets.UTF_8)
            if (target.isEmpty() || target.size > 64) continue
            val sequence = state.sendSequence.getAndIncrement()
            if (sequence < 0) continue
            val aad = "$sid|$sourceId|$targetId|$frameType|$sequence".toByteArray(Charsets.UTF_8)
            val sealed = runCatching { DeviceE2ECrypto.encrypt(state.key, jpeg, aad) }.getOrNull() ?: continue
            val nonce = java.util.Base64.getDecoder().decode(sealed.nonce)
            val ciphertext = java.util.Base64.getDecoder().decode(sealed.ciphertext)
            val packet = ByteBuffer.allocate(2 + target.size + 8 + nonce.size + ciphertext.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put((frameType or 0x80).toByte()).put(target.size.toByte()).put(target)
                .putLong(sequence).put(nonce).put(ciphertext).array()
            if (socket?.send(ByteString.of(*packet)) == true) sentAny = true
            nonce.fill(0); ciphertext.fill(0)
        }
        return sentAny
    }
    /** Revokes a paired device on the relay. Only the session owner may revoke another device. */
    fun revokeRemoteDevice(targetDeviceId: String, done: (Result<Boolean>) -> Unit) {
        val credentials = runCatching { credentialStore?.load() }.getOrNull()
        val sid = sessionId ?: credentials?.sessionId
        val did = deviceId ?: credentials?.deviceId
        val bearer = deviceToken ?: credentials?.token
        val baseUrl = credentials?.baseUrl
        if (sid.isNullOrBlank() || did.isNullOrBlank() || bearer.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            done(Result.failure(IllegalStateException("Connect to the paired-device relay before revoking a device.")))
            return
        }
        if (!Regex("^[a-f0-9]{24}$").matches(targetDeviceId)) {
            done(Result.failure(IllegalArgumentException("Invalid target device ID.")))
            return
        }
        val endpoint = secureApiUrl(baseUrl, "/api/device/revoke")
        if (endpoint == null) {
            done(Result.failure(IllegalArgumentException("Device revocation requires a valid HTTPS relay URL.")))
            return
        }
        val body = JSONObject().put("sessionId", sid).put("deviceId", did).put("targetDeviceId", targetDeviceId)
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $bearer")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}"))))
                    } else {
                        val revoked = runCatching { JSONObject(raw).optBoolean("ok") }.getOrDefault(false)
                        if (revoked && targetDeviceId == did) disconnect(clearSavedPairing = true)
                        done(Result.success(revoked))
                    }
                }
            }
        })
    }

    /** Revokes this device's local pairing and, for an owner device, the entire relay session. */
    fun revokeCurrentPairing(done: (Result<Boolean>) -> Unit) {
        val activeId = deviceId
        if (!activeId.isNullOrBlank()) {
            revokeRemoteDevice(activeId, done)
            return
        }
        // A disconnected phone can still revoke its server-side token using the
        // Keystore-protected saved credential. Never report remote revocation when
        // only a local credential deletion was possible.
        val saved = runCatching { credentialStore?.load() }.getOrNull()
        if (saved == null) {
            done(Result.failure(IllegalStateException("No saved pairing is available. The relay cannot be contacted to confirm revocation.")))
            return
        }
        val endpoint = secureApiUrl(saved.baseUrl, "/api/device/revoke")
        if (endpoint == null) {
            done(Result.failure(IllegalArgumentException("Saved relay URL is not a valid HTTPS endpoint.")))
            return
        }
        val body = JSONObject().put("sessionId", saved.sessionId)
            .put("deviceId", saved.deviceId).put("targetDeviceId", saved.deviceId)
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer ${saved.token}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}"))))
                    } else {
                        val revoked = runCatching { JSONObject(raw).optBoolean("ok") }.getOrDefault(false)
                        if (revoked) disconnect(clearSavedPairing = true)
                        done(Result.success(revoked))
                    }
                }
            }
        })
    }

    /**
     * Closes the live relay connection. Keep the encrypted pairing by default so
     * reconnectSaved() works after a temporary/manual disconnect. A confirmed
     * server-side revocation must explicitly clear the local credential.
     */
    fun disconnect(clearSavedPairing: Boolean = false) {
        val active = socket
        socket = null
        active?.close(1000, "User disconnected")
        sessionId = null
        deviceId = null
        deviceToken = null
        clearE2EKeys()
        if (clearSavedPairing) credentialStore?.clear()
    }
}
