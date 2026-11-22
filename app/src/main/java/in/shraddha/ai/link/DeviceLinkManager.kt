package `in`.shraddha.ai.link

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
    private val reconnectHandler = Handler(Looper.getMainLooper())
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
    private val nextVisualFrameSequence = ConcurrentHashMap<String, AtomicLong>()
    private val receivedVisualFrameSequence = mutableMapOf<String, Long>()
    private val receivedVisualFrameLock = Any()


    data class PairResult(val code: String, val sessionId: String, val deviceId: String, val token: String)

    fun createSession(baseUrl: String, name: String, done: (Result<PairResult>) -> Unit) = post(baseUrl, "/api/device/session", JSONObject().put("deviceName", name)) { result ->
        result.mapCatching { body -> PairResult(body.getString("pairingCode"), body.getString("sessionId"), body.getString("deviceId"), body.getString("deviceToken")) }
            .onSuccess { saveAndConnect(baseUrl, it, done) }.onFailure { done(Result.failure(it)) }
    }

    fun joinSession(baseUrl: String, name: String, code: String, done: (Result<PairResult>) -> Unit) = post(baseUrl, "/api/device/join", JSONObject().put("deviceName", name).put("pairingCode", code)) { result ->
        result.mapCatching { body -> PairResult("", body.getString("sessionId"), body.getString("deviceId"), body.getString("deviceToken")) }
            .onSuccess { saveAndConnect(baseUrl, it, done) }.onFailure { done(Result.failure(it)) }
    }

    private fun saveAndConnect(baseUrl: String, pair: PairResult, done: (Result<PairResult>) -> Unit) {
        lastBaseUrl = baseUrl; lastPair = pair
        sessionId = pair.sessionId; deviceId = pair.deviceId; deviceToken = pair.token
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
                                else -> ""
                            }
                            val aad = "$sessionId|$peerId|$deviceId|$kind|$correlation".toByteArray(Charsets.UTF_8)
                            val plain = DeviceE2ECrypto.decrypt(state.key, msg.optString("nonce"), msg.optString("ciphertext"), aad)
                            val payload = JSONObject(plain.toString(Charsets.UTF_8))
                            when (kind) {
                                "remote_action" -> {
                                    if (actionId.matches(Regex("[A-Za-z0-9_-]{12,64}")) && payload.optString("kind").isNotBlank())
                                        onRemoteActionWithId?.invoke(peerId, actionId, payload)
                                }
                                "remote_action_result" -> {
                                    onRemoteActionResult?.invoke(peerId, msg.optString("actionKind"), payload.optString("result"))
                                    onPeerMessage?.invoke(JSONObject().put("type", "remote_action_result").put("fromDeviceId", peerId)
                                        .put("actionId", actionId).put("actionKind", msg.optString("actionKind")).put("result", payload.optString("result")))
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
                                else -> onStatus?.invoke("Unsupported encrypted peer message blocked.")
                            }
                        }

                        "e2e_key_offer" -> {
                            val peerId = msg.optString("fromDeviceId")
                            val derived = e2eCrypto.derivePeerKey(msg.optString("publicKey"), deviceId.orEmpty(), peerId)
                            peerKeys[peerId] = PeerKeyState(derived.first, derived.second)
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
                            for (i in 0 until (peers?.length() ?: 0)) {
                                val peerId = peers?.optJSONObject(i)?.optString("id").orEmpty()
                                if (peerId.isNotBlank() && peerId != deviceId) sendE2EKeyOffer(peerId)
                            }
                            onStatus?.invoke("Connected • " + (peers?.length() ?: 0) + " paired device(s)")
                            onPeerMessage?.invoke(msg)
                        }
                        "device_revoked" -> {
                            val revokedId = msg.optString("deviceId")
                            peerKeys.remove(revokedId)?.key?.fill(0)
                            onPeerMessage?.invoke(msg)
                        }
                        "peer_status" -> {
                            val peerId = msg.optString("deviceId")
                            if (msg.optBoolean("online") && peerId.isNotBlank() && peerId != deviceId) {
                                sendE2EKeyOffer(peerId)
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
                    val aad = "$localSession|${envelope.sourceDeviceId}|$localDevice|visual_frame|${envelope.frameType}|${envelope.sequence}"
                        .toByteArray(Charsets.UTF_8)
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
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { onStatus?.invoke("Relay error: ${t.message ?: "connection failed"}"); if (completionDelivered.compareAndSet(false, true)) done(Result.failure(t)); scheduleReconnect(baseUrl, pair) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onStatus?.invoke("Disconnected: $reason"); if (code != 1000) scheduleReconnect(baseUrl, pair) }
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
        val body = JSONObject().put("provider", provider).put("prompt", prompt.take(12000))
        val builder = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/api/chat")
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
        val builder = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/api/ai/providers").get()
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
    ): Call {
        val builder = Request.Builder().url(baseUrl.trim().trimEnd('/') + path)
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
        val body = JSONObject().put("provider", provider).put("prompt", prompt.take(4000)).put("mode", mode)
            .put("mimeType", "image/jpeg").put("imageBase64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        val builder = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/api/vision")
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
        return socket?.send(JSONObject().put("type", "data_share_payload").put("targetDeviceId", targetDeviceId).put("category", category).put("requestId", requestId).put("fileName", fileName.take(120)).put("mimeType", mimeType.take(100)).put("contentType", contentType).put("sizeBytes", sizeBytes).put("sha256", sha256.lowercase()).put("payload", base64Payload).toString()) == true
    }
    fun sendClipboardText(targetDeviceId: String, requestId: String, text: String): Boolean {
        if (text.isBlank() || text.length > 20000) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return sendSharedPayload(targetDeviceId, "clipboard", requestId, "clipboard.txt", "text/plain", Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size.toLong(), digest, "clipboard_text")
    }
    fun startFileTransfer(targetDeviceId: String, requestId: String, transferId: String, fileName: String, sizeBytes: Long, chunkCount: Int, sha256: String): Boolean {
        if (targetDeviceId.isBlank() || requestId.isBlank() || !transferId.matches(Regex("[A-Za-z0-9_-]{12,64}")) || sizeBytes !in 1L..(20_000_000L) || chunkCount !in 1..DeviceFileTransferProtocol.MAX_CHUNKS || sha256.matches(Regex("[a-fA-F0-9]{64}")).not() || DeviceFileTransferProtocol.chunkCount(sizeBytes) != chunkCount) return false
        return socket?.send(JSONObject().put("type", "file_transfer_start").put("targetDeviceId", targetDeviceId).put("requestId", requestId).put("transferId", transferId).put("fileName", fileName.take(120)).put("sizeBytes", sizeBytes).put("chunkCount", chunkCount).put("sha256", sha256.lowercase()).toString()) == true
    }
    fun sendFileTransferChunk(targetDeviceId: String, transferId: String, index: Int, bytes: ByteArray): Boolean {
        if (transferId.isBlank() || index !in 0 until DeviceFileTransferProtocol.MAX_CHUNKS || bytes.isEmpty() || bytes.size > DeviceFileTransferProtocol.CHUNK_BYTES) return false
        val payload = JSONObject().put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP))
        return sendEncryptedPeerMessage(targetDeviceId, "file_transfer_chunk", payload, transferId, index)
    }
    fun requestFileTransferResume(sourceDeviceId: String, transferId: String): Boolean =
        socket?.send(JSONObject().put("type", "file_transfer_resume").put("targetDeviceId", sourceDeviceId).put("transferId", transferId).toString()) == true
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
    private fun sendEncryptedPeerMessage(targetDeviceId: String, kind: String, payload: JSONObject, correlationId: String, index: Int = -1, actionKind: String = ""): Boolean {
        val state = peerKeys[targetDeviceId] ?: return false
        if (!state.localConfirmed || !state.remoteConfirmed || sessionId.isNullOrBlank() || deviceId.isNullOrBlank()) {
            onStatus?.invoke("Encrypted send blocked: verify the peer fingerprint on both devices first.")
            return false
        }
        val correlation = if (kind == "file_transfer_chunk") "$correlationId:$index" else correlationId
        val aad = "$sessionId|$deviceId|$targetDeviceId|$kind|$correlation".toByteArray(Charsets.UTF_8)
        val sealed = DeviceE2ECrypto.encrypt(state.key, payload.toString().toByteArray(Charsets.UTF_8), aad)
        val message = JSONObject().put("type", "e2e_message").put("targetDeviceId", targetDeviceId)
            .put("messageKind", kind).put("nonce", sealed.nonce).put("ciphertext", sealed.ciphertext)
        if (kind == "file_transfer_chunk") message.put("transferId", correlationId).put("index", index)
        if (kind == "clipboard_payload") message.put("requestId", correlationId)
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

    fun sendChat(message: String): Boolean = socket?.send(JSONObject().put("type", "chat").put("message", message.take(2000)).toString()) == true
    fun sendTaskHandoff(targetDeviceId: String, title: String, description: String, steps: List<String>): Boolean {
        if (targetDeviceId.isBlank() || title.isBlank() || description.isBlank()) return false
        val cleanSteps = JSONArray()
        steps.take(12).forEach { cleanSteps.put(it.take(300)) }
        val task = JSONObject().put("title", title.trim().take(120))
            .put("description", description.trim().take(3000)).put("steps", cleanSteps)
        return socket?.send(JSONObject().put("type", "task_handoff").put("targetDeviceId", targetDeviceId).put("task", task).toString()) == true
    }
    fun respondTaskHandoff(fromDeviceId: String, taskId: String, accepted: Boolean, note: String = ""): Boolean =
        socket?.send(JSONObject().put("type", "task_handoff_response").put("targetDeviceId", fromDeviceId)
            .put("taskId", taskId.take(48)).put("accepted", accepted).put("note", note.take(300)).toString()) == true
    fun setControlMode(masterOnly: Boolean): Boolean = socket?.send(JSONObject().put("type", "set_control_mode").put("mode", if (masterOnly) "master" else "mutual").toString()) == true
    fun sendFrame(jpeg: ByteArray, frameType: Int = 1, targetDeviceId: String? = null): Boolean {
        if (!DeviceFrameValidation.isValidJpeg(jpeg) || frameType !in 1..2) return false
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
            val aad = "$localSession|$localDevice|$target|visual_frame|$frameType|$sequence".toByteArray(Charsets.UTF_8)
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
    fun disconnect() { reconnectHandler.removeCallbacksAndMessages(null); reconnectPending = false; reconnectAttempts = 0; peerKeys.values.forEach { it.key.fill(0) }; peerKeys.clear(); nextVisualFrameSequence.clear(); synchronized(receivedVisualFrameLock) { receivedVisualFrameSequence.clear() }; lastBaseUrl = null; lastPair = null; socket?.close(1000, "User disconnected"); socket = null; sessionId = null; deviceId = null; deviceToken = null }
}
