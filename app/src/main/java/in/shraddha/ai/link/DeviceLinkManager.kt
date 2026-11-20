package `in`.shraddha.ai.link

import android.util.Base64
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

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
    @Volatile var onRemoteActionResult: ((String, String, String) -> Unit)? = null

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
                    when (msg.optString("type")) {
                        "connected" -> { onStatus?.invoke("Connected • ${msg.optJSONArray("devices")?.length() ?: 0} paired device(s)"); onPeerMessage?.invoke(msg) }
                        "peer_status" -> { onStatus?.invoke("${msg.optString("name", "Device")}: ${if (msg.optBoolean("online")) "online" else "offline"}"); onPeerMessage?.invoke(msg) }
                        "control_mode" -> onPeerMessage?.invoke(msg)
                        "remote_action" -> { val sender = msg.optString("fromDeviceId"); val actionId = msg.optString("actionId"); val action = msg.optJSONObject("action") ?: JSONObject(); if (actionId.matches(Regex("[A-Za-z0-9_-]{12,64}")) && msg.optBoolean("requiresPerActionApproval")) onRemoteActionWithId?.invoke(sender, actionId, action) else onStatus?.invoke("Unverified remote action blocked: missing approval metadata") }
                        "remote_action_result" -> {
                            onRemoteActionResult?.invoke(msg.optString("fromDeviceId"), msg.optString("actionKind"), msg.optString("result"))
                            onPeerMessage?.invoke(msg)
                        }
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
    fun sendSharedPayload(targetDeviceId: String, category: String, requestId: String, fileName: String, mimeType: String, base64Payload: String, sizeBytes: Long, sha256: String, contentType: String = "file"): Boolean { if (requestId.isBlank() || sizeBytes !in 1L..650_000L || !sha256.matches(Regex("[a-fA-F0-9]{64}")) || base64Payload.length > 900_000 || contentType !in setOf("file", "clipboard_text")) return false; return socket?.send(JSONObject().put("type", "data_share_payload").put("targetDeviceId", targetDeviceId).put("category", category).put("requestId", requestId).put("fileName", fileName.take(120)).put("mimeType", mimeType.take(100)).put("contentType", contentType).put("sizeBytes", sizeBytes).put("sha256", sha256.lowercase()).put("payload", base64Payload).toString()) == true }
    fun sendClipboardText(targetDeviceId: String, requestId: String, text: String): Boolean {
        if (text.isBlank() || text.length > 20000) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return sendSharedPayload(targetDeviceId, "clipboard", requestId, "clipboard.txt", "text/plain", Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size.toLong(), digest, "clipboard_text")
    }
    fun startFileTransfer(targetDeviceId: String, requestId: String, transferId: String, fileName: String, sizeBytes: Long, chunkCount: Int, sha256: String): Boolean {
        if (targetDeviceId.isBlank() || requestId.isBlank() || !transferId.matches(Regex("[A-Za-z0-9_-]{12,64}")) || sizeBytes !in 1L..(100L * 1024 * 1024) || chunkCount !in 1..2200 || sha256.matches(Regex("[a-fA-F0-9]{64}")).not() || chunkCount != ((sizeBytes + 49151) / 49152).toInt()) return false
        return socket?.send(JSONObject().put("type", "file_transfer_start").put("targetDeviceId", targetDeviceId).put("requestId", requestId).put("transferId", transferId).put("fileName", fileName.take(120)).put("sizeBytes", sizeBytes).put("chunkCount", chunkCount).put("sha256", sha256.lowercase()).toString()) == true
    }
    fun sendFileTransferChunk(targetDeviceId: String, transferId: String, index: Int, bytes: ByteArray): Boolean {
        if (transferId.isBlank() || index !in 0..2199 || bytes.isEmpty() || bytes.size > 49152) return false
        return socket?.send(JSONObject().put("type", "file_transfer_chunk").put("targetDeviceId", targetDeviceId).put("transferId", transferId).put("index", index).put("payload", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString()) == true
    }
    fun requestFileTransferResume(sourceDeviceId: String, transferId: String): Boolean =
        socket?.send(JSONObject().put("type", "file_transfer_resume").put("targetDeviceId", sourceDeviceId).put("transferId", transferId).toString()) == true
    fun acknowledgeFileTransfer(targetDeviceId: String, transferId: String, index: Int, complete: Boolean = false, ok: Boolean = true): Boolean =
        socket?.send(JSONObject().put("type", "file_transfer_ack").put("targetDeviceId", targetDeviceId).put("transferId", transferId).put("index", index).put("complete", complete).put("ok", ok).toString()) == true
    fun cancelFileTransfer(targetDeviceId: String, transferId: String, reason: String = "cancelled"): Boolean =
        socket?.send(JSONObject().put("type", "file_transfer_cancel").put("targetDeviceId", targetDeviceId).put("transferId", transferId).put("reason", reason.take(120)).toString()) == true

    fun sendSignal(targetDeviceId: String, signal: JSONObject): Boolean = socket?.send(JSONObject().put("type", "signal").put("targetDeviceId", targetDeviceId).put("signal", signal).toString()) == true
    fun requestRemoteControl(targetDeviceId: String, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "control_request").put("targetDeviceId", targetDeviceId).put("minutes", minutes.coerceIn(1, 5)).toString()) == true
    fun respondRemoteControl(requesterDeviceId: String, approved: Boolean, minutes: Int = 5): Boolean = socket?.send(JSONObject().put("type", "control_response").put("requesterDeviceId", requesterDeviceId).put("approved", approved).put("minutes", minutes.coerceIn(1, 5)).toString()) == true
    fun sendAction(targetDeviceId: String, action: JSONObject): Boolean {
        val actionId = java.util.UUID.randomUUID().toString().replace("-", "")
        return socket?.send(JSONObject().put("type", "remote_action").put("targetDeviceId", targetDeviceId).put("actionId", actionId).put("action", action).toString()) == true
    }
    fun sendRemoteActionResult(targetDeviceId: String, actionId: String, actionKind: String, result: String): Boolean {
        if (!actionId.matches(Regex("[A-Za-z0-9_-]{12,64}"))) return false
        return socket?.send(JSONObject().put("type", "remote_action_result").put("targetDeviceId", targetDeviceId).put("actionId", actionId).put("actionKind", actionKind.take(32)).put("result", result.take(1000)).toString()) == true
    }
    fun revokePairedDevice(targetDeviceId: String): Boolean = socket?.send(JSONObject().put("type", "revoke_device").put("targetDeviceId", targetDeviceId).toString()) == true
    fun leavePairedSession(): Boolean = socket?.send(JSONObject().put("type", "leave_session").toString()) == true
    fun sendRemoteActionResult(targetDeviceId: String, actionKind: String, result: String): Boolean =
        socket?.send(JSONObject().put("type", "remote_action_result").put("targetDeviceId", targetDeviceId)
            .put("actionKind", actionKind.take(32)).put("result", result.take(1000)).toString()) == true
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
    fun disconnect() { reconnectHandler.removeCallbacksAndMessages(null); reconnectPending = false; reconnectAttempts = 0; lastBaseUrl = null; lastPair = null; socket?.close(1000, "User disconnected"); socket = null; sessionId = null; deviceId = null; deviceToken = null }
}
