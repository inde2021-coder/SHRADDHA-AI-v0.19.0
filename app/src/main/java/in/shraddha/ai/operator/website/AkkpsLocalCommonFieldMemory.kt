package `in`.shraddha.ai.operator.website

import android.content.Context
import org.json.JSONObject

/**
 * Device-local memory for stable AKKPS administrative defaults.
 * Deliberately excludes farmer identity, phone, plot IDs, survey numbers and measurements.
 */
internal object AkkpsLocalCommonFieldMemory {
    private const val PREFS = "akkps_local_common_fields"
    private const val KEY_VALUES = "verified_defaults"

    private val allowedKeys = setOf(
        "district", "जिला",
        "block", "विकासखंड", "विकास खंड",
        "tehsil", "तहसील"
    )

    fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun isReusableCommonField(label: String): Boolean =
        normalize(label) in allowedKeys

    fun rememberVerified(context: Context, values: List<Pair<String, String>>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = runCatching { JSONObject(prefs.getString(KEY_VALUES, "{}").orEmpty()) }
            .getOrElse { JSONObject() }
        values.forEach { (field, value) ->
            val key = normalize(field)
            val cleanValue = value.trim()
            if (key in allowedKeys && cleanValue.isNotBlank() && cleanValue.length <= 100) {
                saved.put(key, cleanValue)
            }
        }
        prefs.edit().putString(KEY_VALUES, saved.toString()).apply()
    }

    fun snapshot(context: Context): JSONObject {
        val saved = runCatching {
            JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VALUES, "{}").orEmpty())
        }.getOrElse { JSONObject() }
        val safe = JSONObject()
        saved.keys().forEach { key ->
            val normalized = normalize(key)
            val value = saved.optString(key).trim()
            if (normalized in allowedKeys && value.isNotBlank() && value.length <= 100) {
                safe.put(normalized, value)
            }
        }
        return safe
    }
}
