package `in`.shraddha.ai.link

import android.content.Context
import android.util.Base64
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Authenticated relay client. Deploy behind HTTPS/WSS; never use an untrusted relay. */
object DeviceLinkManager {
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    // Bound Visual Lens calls: a stalled HTTP response must not keep the camera analysis gate locked for OkHttp's default 10-minute read timeout.
    private val visionClient = buildVisionHttpClient(client)
    private val reconnectHandler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null
    @Volatile private var lastBaseUrl: String? = null
    @Volatile private var lastPair: PairResult? = null
    @Volatile private var reconnectAttempts = 0
    @Volatile private var reconnectPending = false

    @Volatile private var socket: WebSocket? = null
    @Volatile var sessionId: String? = null; private set
    @Volatile var deviceId: String? = null; private set
    @Volatile private var deviceToken: String? = null
    @Volatile var onStatus: ((String) -> Unit)? = null
    @Volatile var onRemoteAction: ((String, JSONObject) -> Unit)? = null
    @Volatile var onRemoteActionWithId: ((String, String, JSONObject) -> Unit)? = null
    @Volatile var onScreenFrame: ((String, ByteArray) -> Unit)? = null
    @Volatile var onCameraFrame: ((String, ByteArray) -> Unit)? = null
    @Volatile var onPeerMessage: ((JSONObject) -> Unit)? = null
    @Volatile var onFileTransferMessage: ((JSONObject) -> Unit)? = null
    @Volatile var onRemoteActionResult: ((String, String, String) -> Unit)? = null

    @Volatile var onE2EVerification: ((String, String) -> Unit)? = null
    private val e2eCrypto by lazy { DeviceE2ECrypto.create() }
    private data class PeerKeyState(val key: ByteArray, val fingerprint: String, @Volatile var localConfirmed: Boolean = false, @Volatile var remoteConfirmed: Boolean = false)
    private val peerKeys = ConcurrentHashMap<String, PeerKeyState>()

    data class PairedDeviceSnapshot(val id: String, val name: String, val online: Boolean, val platform: String)
    private val pairedDeviceSnapshots = ConcurrentHashMap<String, PairedDeviceSnapshot>()
    fun pairedDevices(): List<PairedDeviceSnapshot> = pairedDeviceSnapshots.values.sortedBy { it.name.lowercase() }

    private val nextVisualFrameSequence = ConcurrentHashMap<String, AtomicLong>()
    private val receivedVisualFrameSequence = mutableMapOf<String, Long>()
    private val receivedVisualFrameLock = Any()


    data class PairResult(val code: String, val sessionId: String, val deviceId: String, val token: String)

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun createSession(context: Context, baseUrl: String, name: String, done: (Result<PairResult>) -> Unit) {
        initialize(context)
        createSession(baseUrl, name, done)
    }

    fun joinSession(context: Context, baseUrl: String, name: String, code: String, done: (Result<PairResult>) -> Unit) {
        initialize(context)
        joinSession(baseUrl, name, code, done)
    }

    fun restore(context: Context, done: (Result<PairResult>) -> Unit) {
        initialize(context)
        val credentials = DeviceCredentialStore(context.applicationContext).loadResult()
            .getOrElse { error ->
                done(Result.failure(IllegalStateException(
                    "Saved device pairing could not be decrypted. The encrypted record was preserved; re-pair only if recovery is not possible. ${error.localizedMessage ?: "Keystore unavailable"}",
                    error
                )))
                return
            }
        if (credentials == null) {
            done(Result.failure(IllegalStateException("No saved device pairing was found on this phone.")))
            return
        }
        val pair = PairResult("", credentials.sessionId, credentials.deviceId, credentials.token)
        saveAndConnect(credentials.baseUrl, pair, done)
    }

    private fun normalizedRelayBaseUrl(raw: String): String {
        val url = raw.trim().trimEnd('/')
        require(url.length in 10..500 && !url.contains(" ")) { "Relay URL is invalid." }
        val uri = java.net.URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null) { "A secure HTTPS relay URL is required." }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Relay URL must not contain credentials, query parameters or fragments."
        }
        return url
    }

    fun createSession(baseUrl: String, name: String, done: (Result<PairResult>) -> Unit) {
        val safeBaseUrl = runCatching { normalizedRelayBaseUrl(baseUrl) }
            .getOrElse { done(Result.failure(it)); return }
        post(safeBaseUrl, "/api/device/session", JSONObject().put("deviceName", name)) { result ->
            result.mapCatching { body ->
                PairResult(body.getString("pairingCode"), body.getString("sessionId"), body.getString("deviceId"), body.getString("deviceToken"))
            }.onSuccess { saveAndConnect(safeBaseUrl, it, done) }
                .onFailure { done(Result.failure(it)) }
        }
    }

    fun joinSession(baseUrl: String, name: String, code: String, done: (Result<PairResult>) -> Unit) {
        val safeBaseUrl = runCatching { normalizedRelayBaseUrl(baseUrl) }
            .getOrElse { done(Result.failure(it)); return }
        post(safeBaseUrl, "/api/device/join", JSONObject().put("deviceName", name).put("pairingCode", code)) { result ->
            result.mapCatching { body ->
                PairResult("", body.getString("sessionId"), body.getString("deviceId"), body.getString("deviceToken"))
            }.onSuccess { saveAndConnect(safeBaseUrl, it, done) }
                .onFailure { done(Result.failure(it)) }
        }
    }

    private fun saveAndConnect(baseUrl: String, pair: PairResult, done: (Result<PairResult>) -> Unit) {
        val app = appContext
        if (app != null) {
            val persisted = runCatching {
                DeviceCredentialStore(app).save(pair.sessionId, pair.deviceId, pair.token, baseUrl)
            }
            if (persisted.isFailure) {
                done(Result.failure(persisted.exceptionOrNull() ?: IllegalStateException("Could not save device credentials securely.")))
                return
            }
        }
        if (sessionId == pair.sessionId && deviceId == pair.deviceId && socket != null) {
            done(Result.success(pair))
            return
        }
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectPending = false
        socket?.close(1000, "Replacing device session connection")
        socket = null
        lastBaseUrl = baseUrl
        lastPair = pair
        sessionId = pair.sessionId
        deviceId = pair.deviceId
        deviceToken = pair.token
        val completionDelivered = AtomicBoolean(false)
        val wsBase = baseUrl.trim().trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val url = "$wsBase/ws?sessionId=${pair.sessionId}&deviceId=${pair.deviceId}"
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${pair.token}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { reconnectAttempts = 0; reconnectPending = false; onStatus?.invoke("Secure relay connected"); if (completionDelivered.compareAndSet(false, true)) done(Result.success(pair)) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if ((msg.optString("type").startsWith("file_transfer_") && msg.optString("type") != "file_transfer_chunk") || msg.optString("type") == "data_share_result" || msg.optString("type") == "connected") onFileTransferMessage?.invoke(msg)
                    when (msg.optString("type")) {
                        "e2e_message" -> {
                            val peerId = msg.optString("fromDeviceId")
                            val kind = msg.optString("messageKind")
                            val state = peerKeys[peerId]
                            if (state == null || !state.localConfirmed || !state.remoteConfirmed) {
                                onStatus?.invoke("Encrypted message blocked: peer fingerprint is not mutually verified.")
                                return
                            }
                            val actionId = msg.optString("actionId")
                            val transferId = msg.optString("transferId")
                            val index = msg.optInt("index", -1)
                            val correlation = when (kind) {
                                "remote_action", "remote_action_result" -> actionId
                                "file_transfer_chunk" -> "$transferId:$index"
                                "clipboard_payload" -> msg.optString("requestId")
                                "data_share_payload" -> msg.optString("requestId") + "|" + msg.optString("category")
                                "task_handoff", "task_handoff_response" -> msg.optString("requestId")
                                else -> ""
                            }
                            val aad = "$sessionId|$peerId|$deviceId|$kind|$correlation".toByteArray(Charsets.UTF_8)
                            val plain = try {
                                DeviceE2ECrypto.decrypt(state.key, msg.optString("nonce"), msg.optString("ciphertext"), aad)
                            } finally {
                                aad.fill(0)
                            }
                            val payload = try {
                                JSONObject(plain.toString(Charsets.UTF_8))
                            } finally {
                                plain.fill(0)
                            }
                            when (kind) {
                                "remote_action" -> {
                                    if (actionId.matches(Regex("[A-Za-z0-9_-]{12,64}")) && payload.optString("kind").isNotBlank())
                                        onRemoteActionWithId?.invoke(peerId, actionId, payload)
                                }
                                "remote_action_result" -> {
                                    onRemoteActionResult?.invoke(peerId, msg.optString("actionKind"), payload.optString("result"))
                                    onPeerMessage?.invoke(JSONObject().put("type", "remote_action_result").put("fromDeviceId", peerId)
                                        .put("actionId", actionId).put("actionKind", msg.optString("actionKind")).put("ok", payload.optBoolean("ok")).put("result", payload.optString("result")))
                                }
                                "file_transfer_chunk" -> {
                                    payload.put("type", "file_transfer_chunk").put("fromDeviceId", peerId)
                                        .put("transferId", transferId).put("index", index)
                                    onFileTransferMessage?.invoke(payload)
                                }
                                "clipboard_payload" -> {
                                    payload.put("type", "data_share_payload").put("fromDeviceId", peerId)
                                        .put("category", "clipboard").put("requestId", msg.optString("requestId"))
                                    onPeerMessage?.invoke(payload)
                                }
                                "data_share_payload" -> {
                                    payload.put("type", "data_share_payload").put("fromDeviceId", peerId)
                                        .put("category", msg.optString("category")).put("requestId", msg.optString("requestId"))
                                    onPeerMessage?.invoke(payload)
                                }
                                "task_handoff" -> {
                                    payload.put("type", "task_handoff").put("fromDeviceId", peerId)
                                        .put("fromDeviceName", msg.optString("fromDeviceName"))
                                    onPeerMessage?.invoke(payload)
                                }
                                "task_handoff_response" -> {
                                    payload.put("type", "task_handoff_response").put("fromDeviceId", peerId)
                                        .put("fromDeviceName", msg.optString("fromDeviceName"))
                                        .put("taskId", msg.optString("requestId"))
                                    onPeerMessage?.invoke(payload)
                                }
                                else -> onStatus?.invoke("Unsupported encrypted peer message blocked.")
                            }
                        }

                        "e2e_key_offer" -> {
                            val peerId = msg.optString("fromDeviceId")
                            val derived = e2eCrypto.derivePeerKey(msg.optString("publicKey"), sessionId.orEmpty(), deviceId.orEmpty(), peerId)
                            peerKeys.put(peerId, PeerKeyState(derived.first, derived.second))?.key?.fill(0)
                            clearVisualFrameState(peerId)
                            onE2EVerification?.invoke(peerId, derived.second)
                        }
                        "e2e_key_confirm" -> {
                            val peerId = msg.optString("fromDeviceId")
                            val state = peerKeys[peerId]
                            if (state != null && state.fingerprint == msg.optString("fingerprint")) {
                                state.remoteConfirmed = true
                                onStatus?.invoke("Peer fingerprint confirmed: " + peerId)
                            } else onStatus?.invoke("E2E fingerprint mismatch. Do not trust this peer.")
                        }


                        "connected" -> {
                            val peers = msg.optJSONArray("devices")
                            pairedDeviceSnapshots.clear()
                            for (i in 0 until (peers?.length() ?: 0)) {
                                val peer = peers?.optJSONObject(i) ?: continue
                                val peerId = peer.optString("id").trim()
                                if (peerId.isNotBlank() && peerId != deviceId) {
                                    pairedDeviceSnapshots[peerId] = PairedDeviceSnapshot(
                                        peerId, peer.optString("name", "Paired device").take(80),
                                        peer.optBoolean("online", true), peer.optString("platform", peer.optString("os", "unknown")).take(40)
                                    )
                                    sendE2EKeyOffer(peerId)
                                }
                            }
                            onStatus?.invoke("Connected • " + (peers?.length() ?: 0) + " paired device(s)")
                            onPeerMessage?.invoke(msg)
                        }
                        "device_revoked" -> {
                            val revokedId = msg.optString("deviceId")
                            peerKeys.remove(revokedId)?.key?.fill(0)
                            pairedDeviceSnapshots.remove(revokedId)
                            clearVisualFrameState(revokedId)
                            if (revokedId.isNotBlank() && revokedId == deviceId) {
                                appContext?.let { runCatching { DeviceCredentialStore(it).clear() } }
                                onStatus?.invoke("This phone's device pairing was revoked by the relay.")
                                disconnect(clearSavedCredentials = false)
                                return
                            }
                            onPeerMessage?.invoke(msg)
                        }
                        "peer_status" -> {
                            val peerId = msg.optString("deviceId")
                            if (peerId.isNotBlank() && peerId != deviceId) {
                                val previous = pairedDeviceSnapshots[peerId]
                                pairedDeviceSnapshots[peerId] = PairedDeviceSnapshot(
                                    peerId, msg.optString("name", previous?.name ?: "Paired device").take(80),
                                    msg.optBoolean("online"), msg.optString("platform", previous?.platform ?: "unknown").take(40)
                                )
                            }
                            if (msg.optBoolean("online") && peerId.isNotBlank() && peerId != deviceId) {
                                sendE2EKeyOffer(peerId)
                            } else if (peerId.isNotBlank() && peerId != deviceId) {
                                // A disconnect is a key-lifecycle boundary. Do not retain a stale
                                // peer key or replay window for a future reconnect.
                                peerKeys.remove(peerId)?.key?.fill(0)
                                clearVisualFrameState(peerId)
                            }
                            onStatus?.invoke("${msg.optString("name", "Device")}: ${if (msg.optBoolean("online")) "online" else "offline"}")
                            onPeerMessage?.invoke(msg)
                        }
                        "control_mode" -> onPeerMessage?.invoke(msg)
                        "remote_action" -> onStatus?.invoke("Blocked plaintext remote action. End-to-end encryption is mandatory.")
                        "remote_action_result" -> onStatus?.invoke("Blocked plaintext remote action result. End-to-end encryption is mandatory.")
                        "file_transfer_chunk" -> onStatus?.invoke("Blocked plaintext file chunk. End-to-end encryption is mandatory.")
                        else -> onPeerMessage?.invoke(msg)
                    }
                } catch (_: Exception) { onStatus?.invoke("Received an unreadable relay message") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val envelope = DeviceFrameValidation.decodeFromRelay(bytes.toByteArray()) ?: run {
                    onStatus?.invoke("Rejected malformed encrypted visual frame.")
                    return
                }
                val state = peerKeys[envelope.sourceDeviceId]
                if (state == null || !state.localConfirmed || !state.remoteConfirmed) {
                    onStatus?.invoke("Visual frame blocked: peer fingerprint is not mutually verified.")
                    return
                }
                val localSession = sessionId ?: return
                val localDevice = deviceId ?: return
                val sequenceKey = envelope.sourceDeviceId + ":" + envelope.frameType
                val jpeg = try {
                    val aad = DeviceE2ECrypto.visualFrameAad(localSession, envelope.sourceDeviceId, localDevice, envelope.frameType, envelope.sequence)
                    synchronized(receivedVisualFrameLock) {
                        if (envelope.sequence <= (receivedVisualFrameSequence[sequenceKey] ?: 0L)) return
                        val plain = DeviceE2ECrypto.decrypt(
                            state.key,
                            java.util.Base64.getEncoder().withoutPadding().encodeToString(envelope.nonce),
                            java.util.Base64.getEncoder().withoutPadding().encodeToString(envelope.ciphertext),
                            aad
                        )
                        if (!DeviceFrameValidation.isValidJpeg(plain)) {
                            plain.fill(0)
                            onStatus?.invoke("Rejected decrypted visual data that is not a bounded JPEG.")
                            return
                        }
                        receivedVisualFrameSequence[sequenceKey] = envelope.sequence
                        plain
                    }
                } catch (_: Exception) {
                    onStatus?.invoke("Visual frame authentication failed; encrypted frame discarded.")
                    return
                }
                if (envelope.frameType == 2) onCameraFrame?.invoke(envelope.sourceDeviceId, jpeg)
                else onScreenFrame?.invoke(envelope.sourceDeviceId, jpeg)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) {
                    socket = null
                    clearAllPeerE2EState()
                }
                onStatus?.invoke("Relay error: ${t.message ?: "connection failed"}")
                if (completionDelivered.compareAndSet(false, true)) done(Result.failure(t))
                scheduleReconnect(baseUrl, pair)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) {
                    socket = null
                    clearAllPeerE2EState()
                }
                onStatus?.invoke("Disconnected: $reason")
                if (code != 1000) scheduleReconnect(baseUrl, pair)
            }
        })
    }

    private fun scheduleReconnect(baseUrl: String, pair: PairResult) {
        if (sessionId != pair.sessionId || reconnectPending) return
        reconnectPending = true
        val delaySeconds = (1 shl reconnectAttempts.coerceIn(0, 5)).coerceAtMost(60)
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(6)
        onStatus?.invoke("Reconnecting securely in ${delaySeconds}s…")
        reconnectHandler.postDelayed({
            reconnectPending = false
            if (sessionId == pair.sessionId && lastPair?.deviceId == pair.deviceId) {
                onStatus?.invoke("Restoring paired device connection…")
                saveAndConnect(baseUrl, pair) { result -> if (result.isFailure) onStatus?.invoke("Reconnect attempt failed; retry scheduled") }
            }
        }, delaySeconds * 1000L)
    }
    private fun post(base: String, path: String, body: JSONObject, callback: (Result<JSONObject>) -> Unit) {
        val url = base.trim().trimEnd('/') + path
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use { val raw = it.body?.string().orEmpty(); if (!it.isSuccessful) callback(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}")))) else callback(runCatching { JSONObject(raw) }) }
            }
        })
    }

    fun askChat(baseUrl: String, gatewayToken: String, provider: String, prompt: String, done: (Result<String>) -> Unit) {
        // This endpoint may carry a bearer token. Reuse the pairing URL policy so a
        // misconfigured HTTP URL can never leak gateway credentials over cleartext.
        val safeBaseUrl = runCatching { normalizedRelayBaseUrl(baseUrl) }
            .getOrElse { done(Result.failure(it)); return }
        val normalizedProvider = provider.trim().uppercase()
        if (normalizedProvider !in setOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")) {
            done(Result.failure(IllegalArgumentException("Unsupported AI provider.")))
            return
        }
        if (prompt.isBlank()) {
            done(Result.failure(IllegalArgumentException("Prompt cannot be empty.")))
            return
        }
        val body = JSONObject().put("provider", normalizedProvider).put("prompt", prompt.take(12000))
        val builder = Request.Builder().url("$safeBaseUrl/api/chat")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        visionClient.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}"))))
                    else done(runCatching { JSONObject(raw).let { body ->
                        val actual = body.optString("provider", provider)
                        val route = if (body.optString("routing") == "automatic_failover") "Auto route → $actual" else "Provider → $actual"
                        "$route\n\n${body.getString("answer")}"
                    } })
                }
            }
        })
    }

    fun publishSocialPost(
        baseUrl: String,
        gatewayToken: String,
        platform: String,
        exactText: String,
        userConfirmed: Boolean,
        confirmedPlatform: String,
        confirmedText: String,
        onCall: ((Call) -> Unit)? = null,
        done: (Result<String>) -> Unit
    ): Call? {
        val normalizedPlatform = platform.trim().lowercase()
        if (!userConfirmed || !confirmedPlatform.trim().equals(normalizedPlatform, true) || confirmedText != exactText) {
            done(Result.failure(IllegalStateException("Exact social post preview approval is required")))
            return null
        }
        val characterLength = if (normalizedPlatform == "bluesky") exactText.codePointCount(0, exactText.length) else exactText.length
        val limit = when (normalizedPlatform) {
            "bluesky" -> 300
            "linkedin" -> 3000
            "telegram", "mastodon" -> 5000
            else -> 0
        }
        if (limit == 0 || exactText.isBlank() || characterLength > limit) {
            done(Result.failure(IllegalArgumentException("Unsupported platform or post length (limit: ${if (limit == 0) 0 else limit} characters)")))
            return null
        }
        val body = JSONObject().put("platform", normalizedPlatform).put("text", exactText)
            .put("preview", exactText).put("confirmed", true)
        return postAuthenticated(baseUrl, "/api/social/publish", gatewayToken, body, done, onCall)
    }

    fun providerStatus(baseUrl: String, gatewayToken: String, done: (Result<String>) -> Unit) {
        val safeBaseUrl = runCatching { normalizedRelayBaseUrl(baseUrl) }
            .getOrElse { done(Result.failure(it)); return }
        val builder = Request.Builder().url("$safeBaseUrl/api/ai/providers").get()
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

    private fun postAuthenticated(
        baseUrl: String,
        path: String,
        gatewayToken: String,
        body: JSONObject,
        done: (Result<String>) -> Unit,
        onCall: ((Call) -> Unit)? = null
    ): Call? {
        val safeBaseUrl = runCatching { normalizedRelayBaseUrl(baseUrl) }
            .getOrElse { done(Result.failure(it)); return null }
        val builder = Request.Builder().url("$safeBaseUrl$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        val call = client.newCall(builder.build())
        onCall?.invoke(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) done(Result.failure(IllegalStateException(
                        runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}")
                    ))) else done(Result.success(raw))
                }
            }
        })
        return call
    }

    fun analyzeScreen(baseUrl: String, gatewayToken: String, provider: String, prompt: String, jpeg: ByteArray, mode: String = "general", done: (Result<String>) -> Unit) {
        val safeBaseUrl = runCatching { normalizedRelayBaseUrl(baseUrl) }
            .getOrElse { done(Result.failure(it)); return }
        // The vision gateway currently supports Gemini, OpenAI and Anthropic,
        // plus automatic failover. Route other configured chat providers through
        // auto rather than silently sending an unsupported provider name.
        val normalizedProvider = when (provider.trim().uppercase()) {
            "GEMINI" -> "gemini"
            "OPENAI" -> "openai"
            "CLAUDE", "ANTHROPIC" -> "anthropic"
            "AUTO", "GROQ", "PERPLEXITY" -> "auto"
            else -> "auto"
        }
        if (prompt.isBlank() || jpeg.isEmpty()) {
            done(Result.failure(IllegalArgumentException("A prompt and image are required.")))
            return
        }
        if (jpeg.size > 2_500_000) {
            done(Result.failure(IllegalArgumentException("Image is too large for a secure paired-device request (2.5 MB maximum).")))
            return
        }
        val body = JSONObject().put("provider", normalizedProvider).put("prompt", prompt.take(4000)).put("mode", mode)
            .put("mimeType", "image/jpeg").put("imageBase64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        val builder = Request.Builder().url("$safeBaseUrl/api/vision")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}"))))
                    else done(runCatching { JSONObject(raw).let { body ->
                        val actual = body.optString("provider", provider)
                        val route = if (body.optString("routing") == "automatic_failover") "Auto vision route → $actual" else "Vision provider → $actual"
                        "$route\n\n${body.getString("answer")}"
                    } })
                }
            }
        })
    }

    fun updateSharePermissions(permissions: JSONObject): Boolean = socket?.send(JSONObject().put("type", "permission_update").put("permissions", permissions).toString()) == true
    fun requestDataShare(targetDeviceId: String, category: String, requestId: String, itemLabel: String = ""): Boolean = socket?.send(JSONObject().put("type", "data_share_request").put("targetDeviceId", targetDeviceId).put("category", category).put("itemLabel", itemLabel).put("requestId", requestId).toString()) == true
    fun respondDataShare(requesterDeviceId: String, category: String, requestId: String, approved: Boolean, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "data_share_response").put("requesterDeviceId", requesterDeviceId).put("category", category).put("requestId", requestId).put("approved", approved).put("minutes", minutes).toString()) == true
    fun sendSharedPayload(targetDeviceId: String, category: String, requestId: String, fileName: String, mimeType: String, base64Payload: String, sizeBytes: Long, sha256: String, contentType: String = "file"): Boolean {
        if (requestId.isBlank() || sizeBytes !in 1L..650_000L || !sha256.matches(Regex("[a-fA-F0-9]{64}")) || base64Payload.length > 900_000 || contentType !in setOf("file", "clipboard_text")) return false
        val payload = JSONObject().put("fileName", fileName.take(120)).put("mimeType", mimeType.take(100))
            .put("contentType", contentType).put("sizeBytes", sizeBytes).put("sha256", sha256.lowercase()).put("payload", base64Payload)
        if (category == "clipboard" && contentType == "clipboard_text")
            return sendEncryptedPeerMessage(targetDeviceId, "clipboard_payload", payload, requestId)
        return sendEncryptedPeerMessage(targetDeviceId, "data_share_payload", payload, requestId, category = category)
    }
    fun sendClipboardText(targetDeviceId: String, requestId: String, text: String): Boolean {
        if (text.isBlank() || text.length > 20000) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            sendSharedPayload(
                targetDeviceId, "clipboard", requestId, "clipboard.txt", "text/plain",
                Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size.toLong(), digest, "clipboard_text"
            )
        } finally {
            bytes.fill(0)
        }
    }
    fun startFileTransfer(targetDeviceId: String, requestId: String, transferId: String, fileName: String, sizeBytes: Long, chunkCount: Int, sha256: String): Boolean {
        if (targetDeviceId.isBlank() || requestId.isBlank() || !transferId.matches(Regex("[A-Za-z0-9_-]{12,64}")) || sizeBytes !in 1L..DeviceFileTransferProtocol.MAX_FILE_BYTES || chunkCount !in 1..DeviceFileTransferProtocol.MAX_CHUNKS || sha256.matches(Regex("[a-fA-F0-9]{64}")).not() || DeviceFileTransferProtocol.chunkCount(sizeBytes) != chunkCount) return false
        return socket?.send(JSONObject().put("type", "file_transfer_start").put("targetDeviceId", targetDeviceId).put("requestId", requestId).put("transferId", transferId).put("fileName", fileName.take(120)).put("sizeBytes", sizeBytes).put("chunkCount", chunkCount).put("sha256", sha256.lowercase()).toString()) == true
    }
    fun sendFileTransferChunk(targetDeviceId: String, transferId: String, index: Int, bytes: ByteArray): Boolean {
        if (transferId.isBlank() || index !in 0 until DeviceFileTransferProtocol.MAX_CHUNKS || bytes.isEmpty() || bytes.size > DeviceFileTransferProtocol.CHUNK_BYTES) return false
        val payload = JSONObject().put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP))
        return sendEncryptedPeerMessage(targetDeviceId, "file_transfer_chunk", payload, transferId, index)
    }
    fun requestFileTransferResume(sourceDeviceId: String, transferId: String, chunkCount: Int, missingIndexes: List<Int>): Boolean {
        if (sourceDeviceId.isBlank() || !transferId.matches(Regex("[A-Za-z0-9_-]{12,64}")) ||
            chunkCount !in 1..DeviceFileTransferProtocol.MAX_CHUNKS || missingIndexes.size > chunkCount ||
            missingIndexes.size > DeviceFileTransferProtocol.MAX_CHUNKS ||
            missingIndexes.any { it !in 0 until chunkCount } || missingIndexes.distinct().size != missingIndexes.size) return false
        val indexes = JSONArray()
        missingIndexes.forEach(indexes::put)
        return socket?.send(JSONObject().put("type", "file_transfer_resume")
            .put("targetDeviceId", sourceDeviceId).put("transferId", transferId)
            .put("chunkCount", chunkCount).put("missingIndexes", indexes).toString()) == true
    }
    fun acknowledgeFileTransfer(targetDeviceId: String, transferId: String, index: Int, complete: Boolean = false, ok: Boolean = true): Boolean =
        socket?.send(JSONObject().put("type", "file_transfer_ack").put("targetDeviceId", targetDeviceId).put("transferId", transferId).put("index", index).put("complete", complete).put("ok", ok).toString()) == true
    fun cancelFileTransfer(targetDeviceId: String, transferId: String, reason: String = "cancelled"): Boolean =
        socket?.send(JSONObject().put("type", "file_transfer_cancel").put("targetDeviceId", targetDeviceId).put("transferId", transferId).put("reason", reason.take(120)).toString()) == true


    private fun sendE2EKeyOffer(targetDeviceId: String): Boolean =
        socket?.send(JSONObject().put("type", "e2e_key_offer").put("targetDeviceId", targetDeviceId).put("publicKey", e2eCrypto.publicKeyBase64()).toString()) == true

    fun confirmE2EPeer(peerId: String, fingerprint: String): Boolean {
        val state = peerKeys[peerId] ?: return false
        if (state.fingerprint != fingerprint) return false
        state.localConfirmed = true
        return socket?.send(JSONObject().put("type", "e2e_key_confirm").put("targetDeviceId", peerId).put("fingerprint", fingerprint).toString()) == true
    }

    private fun clearVisualFrameState(peerId: String) {
        nextVisualFrameSequence.keys.removeAll { it.startsWith(peerId + ":") }
        synchronized(receivedVisualFrameLock) {
            receivedVisualFrameSequence.keys.removeAll { it.startsWith(peerId + ":") }
        }
    }

    /** Clear all ephemeral peer keys and replay windows at a transport boundary. */
    private fun clearAllPeerE2EState() {
        peerKeys.values.forEach { it.key.fill(0) }
        peerKeys.clear()
        nextVisualFrameSequence.clear()
        synchronized(receivedVisualFrameLock) { receivedVisualFrameSequence.clear() }
    }

    fun isE2ETrusted(peerId: String): Boolean =
        peerKeys[peerId]?.let { it.localConfirmed && it.remoteConfirmed } == true

    fun sendSignal(targetDeviceId: String, signal: JSONObject): Boolean = socket?.send(JSONObject().put("type", "signal").put("targetDeviceId", targetDeviceId).put("signal", signal).toString()) == true
    fun requestRemoteControl(targetDeviceId: String, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "control_request").put("targetDeviceId", targetDeviceId).put("minutes", minutes.coerceIn(1, 5)).toString()) == true
    fun respondRemoteControl(requesterDeviceId: String, approved: Boolean, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "control_response").put("requesterDeviceId", requesterDeviceId).put("approved", approved).put("minutes", minutes.coerceIn(1, 5)).toString()) == true
    fun sendAction(targetDeviceId: String, action: JSONObject): Boolean {
        val actionId = java.util.UUID.randomUUID().toString().replace("-", "")
        return sendEncryptedPeerMessage(targetDeviceId, "remote_action", action, actionId, -1)
    }
    fun sendRemoteActionResult(targetDeviceId: String, actionId: String, actionKind: String, result: String): Boolean {
        if (!actionId.matches(Regex("[A-Za-z0-9_-]{12,64}"))) return false
        return sendEncryptedPeerMessage(targetDeviceId, "remote_action_result", JSONObject().put("result", result.take(14000)), actionId, -1, actionKind.take(32))
    }
    private fun sendEncryptedPeerMessage(targetDeviceId: String, kind: String, payload: JSONObject, correlationId: String, index: Int = -1, actionKind: String = "", category: String? = null): Boolean {
        val state = peerKeys[targetDeviceId] ?: return false
        if (!state.localConfirmed || !state.remoteConfirmed || sessionId.isNullOrBlank() || deviceId.isNullOrBlank()) {
            onStatus?.invoke("Encrypted send blocked: verify the peer fingerprint on both devices first.")
            return false
        }
        val correlation = when (kind) {
            "file_transfer_chunk" -> "$correlationId:$index"
            "data_share_payload" -> correlationId + "|" + category.orEmpty()
            else -> correlationId
        }
        val aad = "$sessionId|$deviceId|$targetDeviceId|$kind|$correlation".toByteArray(Charsets.UTF_8)
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        val sealed = try {
            DeviceE2ECrypto.encrypt(state.key, plain, aad)
        } finally {
            plain.fill(0)
            aad.fill(0)
        }
        val message = JSONObject().put("type", "e2e_message").put("targetDeviceId", targetDeviceId)
            .put("messageKind", kind).put("nonce", sealed.nonce).put("ciphertext", sealed.ciphertext)
        if (kind == "file_transfer_chunk") message.put("transferId", correlationId).put("index", index)
        if (kind == "clipboard_payload" || kind == "task_handoff" || kind == "task_handoff_response") message.put("requestId", correlationId)
        if (kind == "data_share_payload") message.put("requestId", correlationId).put("category", category.orEmpty())
        if (kind == "remote_action" || kind == "remote_action_result") message.put("actionId", correlationId).put("actionKind", actionKind.ifBlank { payload.optString("kind", "unknown") })
        return socket?.send(message.toString()) == true
    }
    fun revokePairedDevice(targetDeviceId: String): Boolean = socket?.send(JSONObject().put("type", "revoke_device").put("targetDeviceId", targetDeviceId).toString()) == true
    fun leavePairedSession(): Boolean = socket?.send(JSONObject().put("type", "leave_session").toString()) == true
    @Deprecated("An actionId and mutually verified E2E peer key are required")
    fun sendRemoteActionResult(targetDeviceId: String, actionKind: String, result: String): Boolean = false
    fun requestPcOcr(targetDeviceId: String): Boolean =
        sendAction(targetDeviceId, JSONObject().put("kind", "ocr"))

    fun requestPcFileList(targetDeviceId: String, relativePath: String = ""): Boolean =
        sendAction(targetDeviceId, JSONObject().put("kind", "file_list").put("path", relativePath.take(500)))

    fun requestPcTextPreview(targetDeviceId: String, relativePath: String): Boolean =
        if (relativePath.isBlank()) false else sendAction(targetDeviceId,
            JSONObject().put("kind", "file_read").put("path", relativePath.take(500)))

    @Deprecated("Plaintext peer chat is disabled; use a mutually verified E2E peer channel.")
    fun sendChat(message: String): Boolean = false
    fun sendTaskHandoff(targetDeviceId: String, title: String, description: String, steps: List<String>): Boolean {
        if (targetDeviceId.isBlank() || title.isBlank() || description.isBlank()) return false
        val taskId = java.util.UUID.randomUUID().toString().replace("-", "")
        val cleanSteps = JSONArray()
        steps.take(12).forEach { cleanSteps.put(it.take(300)) }
        val task = JSONObject().put("id", taskId).put("title", title.trim().take(120))
            .put("description", description.trim().take(3000)).put("steps", cleanSteps)
        val payload = JSONObject().put("task", task).put("expiresAt", System.currentTimeMillis() + 15 * 60_000L)
        return sendEncryptedPeerMessage(targetDeviceId, "task_handoff", payload, taskId)
    }
    fun respondTaskHandoff(fromDeviceId: String, taskId: String, accepted: Boolean, note: String = ""): Boolean {
        if (!taskId.matches(Regex("[A-Za-z0-9_-]{12,64}"))) return false
        return sendEncryptedPeerMessage(fromDeviceId, "task_handoff_response",
            JSONObject().put("accepted", accepted).put("note", note.take(300)), taskId)
    }
    fun setControlMode(masterOnly: Boolean): Boolean = socket?.send(JSONObject().put("type", "set_control_mode").put("mode", if (masterOnly) "master" else "mutual").toString()) == true
    fun sendFrame(jpeg: ByteArray, frameType: Int = 1, targetDeviceId: String? = null): Boolean {
        if (jpeg.size !in 1..2_500_000 || !DeviceFrameValidation.isValidJpeg(jpeg) || frameType !in 1..2) return false
        val target = targetDeviceId?.trim().orEmpty()
        if (target.isBlank()) return false
        val state = peerKeys[target]
        if (state == null || !state.localConfirmed || !state.remoteConfirmed) {
            onStatus?.invoke("Visual sharing blocked: verify the target device fingerprint on both devices first.")
            return false
        }
        val localSession = sessionId ?: return false
        val localDevice = deviceId ?: return false
        val sequenceKey = target + ":" + frameType
        val sequence = nextVisualFrameSequence.computeIfAbsent(sequenceKey) { AtomicLong(0L) }.incrementAndGet()
        if (sequence <= 0L) return false
        return try {
            val aad = DeviceE2ECrypto.visualFrameAad(localSession, localDevice, target, frameType, sequence)
            val sealed = DeviceE2ECrypto.encrypt(state.key, jpeg, aad)
            val nonce = java.util.Base64.getDecoder().decode(sealed.nonce)
            val ciphertext = java.util.Base64.getDecoder().decode(sealed.ciphertext)
            val packet = DeviceFrameValidation.encodeForRelay(frameType, target, sequence, nonce, ciphertext)
            socket?.send(ByteString.of(*packet)) == true
        } catch (_: Exception) {
            onStatus?.invoke("Could not encrypt visual frame; frame was not sent.")
            false
        }
    }
    fun disconnect(context: Context, clearSavedCredentials: Boolean = false) {
        initialize(context)
        disconnect(clearSavedCredentials)
    }

    fun disconnect(clearSavedCredentials: Boolean = false) {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectPending = false
        reconnectAttempts = 0
        clearAllPeerE2EState()
        pairedDeviceSnapshots.clear()
        if (clearSavedCredentials) appContext?.let { runCatching { DeviceCredentialStore(it).clear() } }
        lastBaseUrl = null
        lastPair = null
        socket?.close(1000, "User disconnected")
        socket = null
        sessionId = null
        deviceId = null
        deviceToken = null
    }
}
