package `in`.shraddha.ai.brain

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** HTTPS-only, allowlisted Home Assistant REST adapter. No arbitrary service or script execution. */
class HomeAssistantGateway(context: Context) {
    data class Config(val baseUrl: String, val token: String)
    data class Entity(val entityId: String, val name: String, val state: String, val domain: String)

    private val store = EncryptedFeatureStore(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun saveConfig(baseUrl: String, token: String): Result<Unit> = runCatching {
        val normalized = normalizeBaseUrl(baseUrl)
        require(token.length in 16..4_096 && token.none { it.isISOControl() }) { "A valid long-lived access token is required." }
        store.put(CONFIG_KEY, JSONObject().put("baseUrl", normalized).put("token", token).toString()).getOrThrow()
    }

    fun loadConfig(): Result<Config?> = runCatching {
        val raw = store.get(CONFIG_KEY).getOrThrow()
        if (raw.isBlank()) return@runCatching null
        val json = JSONObject(raw)
        Config(normalizeBaseUrl(json.getString("baseUrl")), json.getString("token").also {
            require(it.length in 16..4_096) { "Saved Home Assistant token is malformed." }
        })
    }

    fun clearConfig(): Result<Unit> = runCatching {
        check(store.remove(CONFIG_KEY)) { "Could not clear encrypted Home Assistant settings." }
    }

    fun listEntities(): Result<List<Entity>> = runCatching {
        val config = loadConfig().getOrThrow() ?: error("Configure Home Assistant first.")
        val request = request(config, "/api/states").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Home Assistant returned HTTP ${response.code}." }
            val array = JSONArray(body)
            require(array.length() <= 5_000) { "Home Assistant returned too many entities." }
            (0 until array.length()).mapNotNull { i ->
                val item = array.getJSONObject(i)
                val entityId = item.optString("entity_id")
                val domain = entityId.substringBefore('.', "")
                if (domain !in ALLOWED_DOMAINS || !ENTITY_ID.matches(entityId)) return@mapNotNull null
                val attrs = item.optJSONObject("attributes") ?: JSONObject()
                Entity(entityId, attrs.optString("friendly_name").ifBlank { entityId }.take(100),
                    item.optString("state").take(80), domain)
            }.sortedBy { it.name.lowercase() }
        }
    }

    /** Every call is a specific allowlisted service and requires a fresh explicit confirmation. */
    fun callService(
        confirmed: Boolean,
        entityId: String,
        service: String,
        temperatureCelsius: Double? = null
    ): Result<String> = runCatching {
        require(confirmed) { "Explicit confirmation is required before controlling a smart-home device." }
        require(ENTITY_ID.matches(entityId)) { "Invalid Home Assistant entity ID." }
        val domain = entityId.substringBefore('.')
        val allowed = ALLOWED_SERVICES[domain] ?: error("This Home Assistant domain is not supported.")
        require(service in allowed) { "This Home Assistant service is not allowlisted." }
        val data = JSONObject().put("entity_id", entityId)
        if (domain == "climate" && service == "set_temperature") {
            require(temperatureCelsius != null && temperatureCelsius.isFinite() && temperatureCelsius in 16.0..30.0) {
                "Climate temperature must be between 16°C and 30°C."
            }
            data.put("temperature", temperatureCelsius)
        } else {
            require(temperatureCelsius == null) { "Temperature is valid only for climate.set_temperature." }
        }
        val config = loadConfig().getOrThrow() ?: error("Configure Home Assistant first.")
        val request = request(config, "/api/services/$domain/$service")
            .post(data.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Home Assistant returned HTTP ${response.code}: ${body.take(300)}" }
            "Home Assistant accepted $domain.$service for $entityId."
        }
    }

    private fun request(config: Config, path: String): Request.Builder =
        Request.Builder().url(config.baseUrl + path)
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", "application/json")

    companion object {
        private const val CONFIG_KEY = "home_assistant_config_v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ENTITY_ID = Regex("^[a-z_]{2,24}\\.[a-z0-9_]{1,64}$")
        private val ALLOWED_DOMAINS = setOf("light", "switch", "fan", "climate", "cover")
        private val ALLOWED_SERVICES = mapOf(
            "light" to setOf("turn_on", "turn_off", "toggle"),
            "switch" to setOf("turn_on", "turn_off", "toggle"),
            "fan" to setOf("turn_on", "turn_off", "toggle"),
            "climate" to setOf("set_temperature"),
            "cover" to setOf("open_cover", "close_cover", "stop_cover")
        )

        fun normalizeBaseUrl(value: String): String {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) {
                "Home Assistant must use HTTPS; insecure HTTP is blocked."
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Credentials, query strings and fragments are not allowed in the server URL."
            }
            require(uri.path.isNullOrBlank() || uri.path == "/") {
                "Use the Home Assistant server root URL, not a custom API path."
            }
            return "https://" + uri.host + (if (uri.port != -1) ":${uri.port}" else "")
        }
    }
}
