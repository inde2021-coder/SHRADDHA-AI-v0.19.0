package `in`.shraddha.ai.future

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import `in`.shraddha.ai.brain.EncryptedFeatureStore

/**
 * Device-local audit metadata encrypted at rest with Android Keystore AES-GCM.
 * Never records passwords, tokens, screen frames, raw biometrics or message bodies.
 * If encryption fails, append fails closed and no plaintext fallback is written.
 */
class LocalAuditTrail(context: Context) {
    private val store = EncryptedFeatureStore(context.applicationContext)
    private val key = "security_audit_v2"
    private val legacyPrefs = context.applicationContext.getSharedPreferences("shraddha_audit_trail", Context.MODE_PRIVATE)

    @Synchronized
    fun append(event: String, outcome: String, detail: String = ""): Result<Unit> = runCatching {
        // Purge historical plaintext before touching encrypted records.
        check(legacyPrefs.edit().remove("events").commit()) { "Could not purge legacy plaintext audit history" }
        val arr = JSONArray(store.get(key).getOrThrow().ifBlank { "[]" })
        arr.put(JSONObject()
            .put("at", System.currentTimeMillis())
            .put("event", event.take(80))
            .put("outcome", outcome.take(40))
            .put("detail", detail.take(240)))
        while (arr.length() > 300) arr.remove(0)
        store.put(key, arr.toString()).getOrThrow()
    }

    @Synchronized
    fun exportText(): Result<String> = runCatching {
        check(legacyPrefs.edit().remove("events").commit()) { "Could not purge legacy plaintext audit history" }
        val arr = JSONArray(store.get(key).getOrThrow().ifBlank { "[]" })
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                "${it.optLong("at")} | ${it.optString("event")} | ${it.optString("outcome")} | ${it.optString("detail")}"
            }
        }.joinToString("\n")
    }

    @Synchronized
    fun clear(): Boolean {
        val legacyPurged = legacyPrefs.edit().remove("events").commit()
        return legacyPurged && store.remove(key)
    }
}
