package com.Lia.assistant.action

import android.content.Context
import com.Lia.assistant.data.VoiceGatewaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Server-side Twilio relay. Provider credentials never leave the backend. */
object ProviderMessageClient {
    data class Receipt(val provider: String, val messageId: String, val status: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun send(
        context: Context,
        channel: String,
        recipientE164: String,
        exactMessage: String,
        requestId: String = UUID.randomUUID().toString().replace("-", "")
    ): Result<Receipt> = withContext(Dispatchers.IO) {
        runCatching {
            require(channel == "sms" || channel == "whatsapp") { "Unsupported message channel." }
            require(Regex("^\\+[1-9]\\d{7,14}$").matches(recipientE164)) {
                "Recipient must be an international E.164 phone number."
            }
            require(exactMessage.isNotBlank() && exactMessage.length <= 4000) { "Message length is invalid." }
            require(Regex("^[A-Za-z0-9_-]{16,80}$").matches(requestId)) { "Message request ID is invalid." }
            val config = VoiceGatewaySettings.get(context.applicationContext)
            require(config.readError == null) { config.readError ?: "Gateway settings could not be read." }
            require(config.baseUrl.isNotBlank() && config.gatewayToken.isNotBlank()) {
                "Configure the HTTPS Voice Gateway to use provider-based messaging."
            }
            val payload = JSONObject()
                .put("channel", channel)
                .put("to", recipientE164)
                .put("text", exactMessage)
                .put("preview", exactMessage)
                .put("confirmed", true)
                .put("requestId", requestId)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/api/messages/send")
                .header("Authorization", "Bearer ${config.gatewayToken}")
                .post(payload)
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val body = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                if (!response.isSuccessful || !body.optBoolean("ok")) {
                    throw IllegalStateException(body.optString("error").ifBlank { "Provider send failed (HTTP ${response.code})." })
                }
                require(body.optString("recipient") == recipientE164 && body.optString("channel") == channel) {
                    "Provider receipt does not match the approved recipient and channel."
                }
                Receipt(
                    provider = body.optString("provider"),
                    messageId = body.optString("messageId"),
                    status = body.optString("providerStatus")
                )
            }
        }
    }
}
