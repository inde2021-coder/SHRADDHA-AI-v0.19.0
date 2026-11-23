package `in`.shraddha.ai.social

import android.content.Context
import com.Lia.assistant.data.VoiceGatewaySettings
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object SocialPublishingClient {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()

    suspend fun publish(context: Context, platform: String, text: String, mediaKind: String? = null, mediaUrl: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("platform", platform).put("text", text).put("preview", text).put("confirmed", true)
            if (platform == "instagram") body.put("mediaKind", mediaKind).put("mediaUrl", mediaUrl)
            request(context, "/api/social/publish", body)
        }

    suspend fun schedule(
        context: Context, platform: String, text: String, scheduledAt: String,
        idempotencyKey: String = UUID.randomUUID().toString().replace("-", ""),
        mediaKind: String? = null, mediaUrl: String? = null
    ): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in with Firebase before scheduling posts.")
        val idToken = user.getIdToken(false).await().token ?: error("Could not obtain Firebase user token.")
        return withContext(Dispatchers.IO) {
            request(context, "/api/social/schedule", JSONObject()
                .put("platform", platform).put("text", text).put("preview", text).put("confirmed", true)
                .put("scheduledAt", scheduledAt).put("idempotencyKey", idempotencyKey)
                .apply { if (platform == "instagram") { put("mediaKind", mediaKind); put("mediaUrl", mediaUrl) } }, idToken)
        }
    }

    suspend fun list(context: Context): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in with Firebase to view scheduled posts.")
        val idToken = user.getIdToken(false).await().token ?: error("Could not obtain Firebase user token.")
        return withContext(Dispatchers.IO) {
            request(context, "/api/social/schedule/list", JSONObject(), idToken, methodGet = true)
        }
    }

    suspend fun cancel(context: Context, id: String): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in with Firebase before cancelling scheduled posts.")
        val idToken = user.getIdToken(false).await().token ?: error("Could not obtain Firebase user token.")
        return withContext(Dispatchers.IO) {
            request(context, "/api/social/schedule/cancel", JSONObject().put("id", id), idToken)
        }
    }

    private fun request(context: Context, path: String, payload: JSONObject, firebaseIdToken: String? = null, methodGet: Boolean = false): JSONObject {
        val config = VoiceGatewaySettings.get(context)
        require(config.readError == null) { config.readError ?: "Gateway settings could not be read." }
        require(config.baseUrl.startsWith("https://") && config.gatewayToken.isNotBlank()) {
            "Configure the secure HTTPS gateway and token in Settings."
        }
        val builder = Request.Builder().url(config.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer " + config.gatewayToken)
        if (methodGet) builder.get() else builder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!firebaseIdToken.isNullOrBlank()) builder.header("X-Firebase-ID-Token", firebaseIdToken)
        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { error("Social gateway returned invalid JSON.") }
            if (!response.isSuccessful) error(json.optString("error").ifBlank { "Social request failed (HTTP ${response.code})." })
            return json
        }
    }
}
