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
                        "connected" -> { onStatus?.invoke("Connected • ${msg.optJSONArray("devices")?.length() ?: 0} paired device(s)"); onPeerMessage?.invoke(msg) }
                        "peer_status" -> { onStatus?.invoke("${msg.optString("name", "Device")}: ${if (msg.optBoolean("online")) "online" else "offline"}"); onPeerMessage?.invoke(msg) }
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
                val frameType = frame[1 + idLength].toInt() and 0xFF
                if (frameType !in 1..2) return
                val jpegLength = frame.size - (2 + idLength)
                if (jpegLength !in 4..DeviceFrameValidation.MAX_JPEG_BYTES) return
                val jpeg = frame.copyOfRange(2 + idLength, frame.size)
                if (!DeviceFrameValidation.isValidJpeg(jpeg)) return
                if (frameType == 2) onCameraFrame?.invoke(sourceId, jpeg) else onScreenFrame?.invoke(sourceId, jpeg)
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
                    onStatus?.invoke(if (code == 4003) "This device pairing was revoked by the session owner." else "Disconnected: $reason")
                }
            }
        })
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
    fun requestDataShare(targetDeviceId: String, category: String, itemLabel: String = ""): Boolean = socket?.send(JSONObject().put("type", "data_share_request").put("targetDeviceId", targetDeviceId).put("category", category).put("itemLabel", itemLabel).put("requestId", java.util.UUID.randomUUID().toString()).toString()) == true
    fun respondDataShare(requesterDeviceId: String, category: String, approved: Boolean, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "data_share_response").put("requesterDeviceId", requesterDeviceId).put("category", category).put("approved", approved).put("minutes", minutes).toString()) == true
    fun sendSharedPayload(targetDeviceId: String, category: String, fileName: String, mimeType: String, base64Payload: String): Boolean = socket?.send(JSONObject().put("type", "data_share_payload").put("targetDeviceId", targetDeviceId).put("category", category).put("fileName", fileName.take(120)).put("mimeType", mimeType.take(100)).put("payload", base64Payload).toString()) == true
    fun sendSignal(targetDeviceId: String, signal: JSONObject): Boolean = socket?.send(JSONObject().put("type", "signal").put("targetDeviceId", targetDeviceId).put("signal", signal).toString()) == true
    fun requestRemoteControl(targetDeviceId: String, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "control_request").put("targetDeviceId", targetDeviceId).put("minutes", minutes.coerceIn(1, 5)).toString()) == true
    fun respondRemoteControl(requesterDeviceId: String, approved: Boolean, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "control_response").put("requesterDeviceId", requesterDeviceId).put("approved", approved).put("minutes", minutes.coerceIn(1, 5)).toString()) == true
    fun sendAction(targetDeviceId: String, action: JSONObject): Boolean = socket?.send(JSONObject().put("type", "remote_action").put("targetDeviceId", targetDeviceId).put("action", action).toString()) == true
    fun respondRemoteAction(actionId: String, ok: Boolean, reason: String = ""): Boolean =
        if (actionId.isBlank() || actionId.length > 64) false else socket?.send(JSONObject().put("type", "remote_action_result").put("actionId", actionId).put("ok", ok).put("reason", reason.take(240)).toString()) == true
    fun sendChat(message: String): Boolean = socket?.send(JSONObject().put("type", "chat").put("message", message.take(2000)).toString()) == true
    fun setControlMode(masterOnly: Boolean): Boolean = socket?.send(JSONObject().put("type", "set_control_mode").put("mode", if (masterOnly) "master" else "mutual").toString()) == true
    fun sendFrame(jpeg: ByteArray, frameType: Int = 1, targetDeviceId: String? = null): Boolean {
        if (!DeviceFrameValidation.isValidJpeg(jpeg) || frameType !in 1..2) return false
        val target = targetDeviceId?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        if (target.size > 64) return false
        val packet = ByteArray(2 + target.size + jpeg.size)
        packet[0] = frameType.toByte()
        packet[1] = target.size.toByte()
        if (target.isNotEmpty()) System.arraycopy(target, 0, packet, 2, target.size)
        System.arraycopy(jpeg, 0, packet, 2 + target.size, jpeg.size)
        return socket?.send(ByteString.of(*packet)) == true
    }
    /** Revokes a paired device on the relay. Only the session owner may revoke another device. */
    fun revokeRemoteDevice(targetDeviceId: String, done: (Result<Boolean>) -> Unit) {
        val sid = sessionId
        val did = deviceId
        val bearer = deviceToken
        val credentials = runCatching { credentialStore?.load() }.getOrNull()
        val baseUrl = credentials?.baseUrl
        if (sid.isNullOrBlank() || did.isNullOrBlank() || bearer.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            done(Result.failure(IllegalStateException("Connect to the paired-device relay before revoking a device.")))
            return
        }
        if (targetDeviceId.isBlank() || targetDeviceId.length > 64) {
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
                        if (revoked && targetDeviceId == did) disconnect()
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
                        if (revoked) disconnect()
                        done(Result.success(revoked))
                    }
                }
            }
        })
    }

    fun disconnect() { socket?.close(1000, "User disconnected"); socket = null; sessionId = null; deviceId = null; deviceToken = null; credentialStore?.clear() }
}
