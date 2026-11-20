package `in`.shraddha.ai.link

import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Authenticated relay client. Deploy behind HTTPS/WSS; never use an untrusted relay. */
object DeviceLinkManager {
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile var sessionId: String? = null; private set
    @Volatile var deviceId: String? = null; private set
    @Volatile private var deviceToken: String? = null
    @Volatile var onStatus: ((String) -> Unit)? = null
    @Volatile var onRemoteAction: ((String, JSONObject) -> Unit)? = null
    @Volatile var onScreenFrame: ((String, ByteArray) -> Unit)? = null
    @Volatile var onCameraFrame: ((String, ByteArray) -> Unit)? = null
    @Volatile var onPeerMessage: ((JSONObject) -> Unit)? = null

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
        val parsed = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        if (parsed == null || parsed.scheme != "https" || parsed.host.isBlank()) {
            done(Result.failure(IllegalArgumentException("Remote device pairing requires a valid HTTPS relay URL. Plain HTTP is blocked.")))
            return
        }
        require(pair.sessionId.isNotBlank() && pair.deviceId.isNotBlank() && pair.token.length >= 24) {
            "Relay returned an invalid session or device credential."
        }
        sessionId = pair.sessionId; deviceId = pair.deviceId; deviceToken = pair.token
        val url = parsed.newBuilder().scheme("wss").addPathSegments("ws")
            .addQueryParameter("sessionId", pair.sessionId)
            .addQueryParameter("deviceId", pair.deviceId).build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${pair.token}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { onStatus?.invoke("Secure relay connected"); done(Result.success(pair)) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "connected" -> { onStatus?.invoke("Connected • ${msg.optJSONArray("devices")?.length() ?: 0} paired device(s)"); onPeerMessage?.invoke(msg) }
                        "peer_status" -> { onStatus?.invoke("${msg.optString("name", "Device")}: ${if (msg.optBoolean("online")) "online" else "offline"}"); onPeerMessage?.invoke(msg) }
                        "control_mode" -> onPeerMessage?.invoke(msg)
                        "remote_action" -> onRemoteAction?.invoke(msg.optString("fromDeviceId"), msg.optJSONObject("action") ?: JSONObject())
                        else -> onPeerMessage?.invoke(msg)
                    }
                } catch (_: Exception) { onStatus?.invoke("Received an unreadable relay message") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = bytes.toByteArray()
                if (frame.isEmpty()) return
                val idLength = frame[0].toInt() and 0xFF
                if (idLength <= 0 || frame.size <= 2 + idLength) return
                val sourceId = frame.copyOfRange(1, 1 + idLength).toString(Charsets.UTF_8)
                val frameType = frame[1 + idLength].toInt() and 0xFF
                val jpeg = frame.copyOfRange(2 + idLength, frame.size)
                if (frameType == 2) onCameraFrame?.invoke(sourceId, jpeg) else onScreenFrame?.invoke(sourceId, jpeg)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { onStatus?.invoke("Relay error: ${t.message ?: "connection failed"}"); done(Result.failure(t)) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onStatus?.invoke("Disconnected: $reason") }
        })
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
                    else done(runCatching { JSONObject(raw).getString("answer") })
                }
            }
        })
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

    private fun postAuthenticated(baseUrl: String, path: String, gatewayToken: String, body: JSONObject, done: (Result<String>) -> Unit) {
        val builder = Request.Builder().url(baseUrl.trim().trimEnd('/') + path).post(body.toString().toRequestBody("application/json".toMediaType()))
        if (gatewayToken.isNotBlank()) builder.header("Authorization", "Bearer $gatewayToken")
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { done(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use { val raw = it.body?.string().orEmpty(); if (!it.isSuccessful) done(Result.failure(IllegalStateException(runCatching { JSONObject(raw).optString("error", "HTTP ${it.code}") }.getOrDefault("HTTP ${it.code}")))) else done(Result.success(raw)) }
            }
        })
    }

    fun analyzeScreen(baseUrl: String, gatewayToken: String, provider: String, prompt: String, jpeg: ByteArray, done: (Result<String>) -> Unit) {
        val body = JSONObject().put("provider", provider).put("prompt", prompt.take(4000))
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
    fun sendChat(message: String): Boolean = socket?.send(JSONObject().put("type", "chat").put("message", message.take(2000)).toString()) == true
    fun setControlMode(masterOnly: Boolean): Boolean = socket?.send(JSONObject().put("type", "set_control_mode").put("mode", if (masterOnly) "master" else "mutual").toString()) == true
    fun sendFrame(jpeg: ByteArray, frameType: Int = 1, targetDeviceId: String? = null): Boolean {
        if (jpeg.size > 2_500_000 || frameType !in 1..2) return false
        val target = targetDeviceId?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        if (target.size > 255) return false
        val packet = ByteArray(2 + target.size + jpeg.size)
        packet[0] = frameType.toByte()
        packet[1] = target.size.toByte()
        if (target.isNotEmpty()) System.arraycopy(target, 0, packet, 2, target.size)
        System.arraycopy(jpeg, 0, packet, 2 + target.size, jpeg.size)
        return socket?.send(ByteString.of(*packet)) == true
    }
    fun disconnect() { socket?.close(1000, "User disconnected"); socket = null; sessionId = null; deviceId = null; deviceToken = null }
}
