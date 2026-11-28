package `in`.shraddha.ai.plugins

import android.content.Context
import com.Lia.assistant.data.VoiceGatewaySettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PluginGatewayClient {
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    fun verify(context: Context, manifest: JSONObject): JSONObject = post(context, "/api/plugins/verify", manifest)

    fun authorize(context: Context, manifest: JSONObject, actionIndex: Int): JSONObject =
        post(context, "/api/plugins/execute", JSONObject().put("manifest", manifest)
            .put("actionIndex", actionIndex).put("confirmed", true))

    private fun post(context: Context, path: String, payload: JSONObject): JSONObject {
        // Plugin verification and execution use the cloud AI gateway and may transmit
        // plugin manifests or action data; both require the same explicit online consent.
        `in`.shraddha.ai.future.OnlineAiConsent.requireAllowed(context)
        val config = VoiceGatewaySettings.get(context)
        require(config.readError == null) { config.readError ?: "Gateway settings could not be read." }
        require(config.baseUrl.startsWith("https://") && config.gatewayToken.isNotBlank()) {
            "Configure the secure HTTPS gateway and token in Settings first."
        }
        val request = Request.Builder().url(config.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer " + config.gatewayToken)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        `in`.shraddha.ai.future.OnlineAiConsent.requireAllowed(context)
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { error("Gateway returned invalid JSON.") }
            if (!response.isSuccessful) error(json.optString("error").ifBlank { "Plugin gateway failed (HTTP ${response.code})." })
            return json
        }
    }
}
