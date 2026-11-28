package `in`.shraddha.ai.operator.website

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores a bounded, non-record-bearing inventory of the currently visible AKKPS Runner page.
 * Input values, farmer rows, cookies, authentication state and query strings are never persisted.
 */
object AkkpsPageInventory {
    private const val PREFS = "akkps_runner_page_inventory"
    private const val KEY_LAST = "last_safe_inventory"
    private const val MAX_JSON_CHARS = 12_000

    fun save(context: Context, page: JSONObject) {
        val safe = JSONObject()
            .put("title", page.optString("title").take(120))
            .put("path", safePath(page.optString("path")))
            .put("capturedAt", System.currentTimeMillis())
            .put("modules", safeModules(page.optJSONArray("modules")))
            .put("actions", safeActions(page.optJSONArray("actions")))
            .put("forms", safeForms(page.optJSONArray("forms")))
            .put("tables", safeTables(page.optJSONArray("tables")))
            .put("fieldLabels", safeFieldLabels(page.optJSONArray("fields")))
        val encoded = safe.toString().takeIf { it.length <= MAX_JSON_CHARS } ?: JSONObject()
            .put("title", safe.optString("title"))
            .put("path", safe.optString("path"))
            .put("capturedAt", safe.optLong("capturedAt"))
            .put("truncated", true)
            .toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST, encoded).apply()
    }

    fun last(context: Context): JSONObject? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)
            ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    fun summary(page: JSONObject): String {
        val modules = page.optJSONArray("modules")?.length() ?: 0
        val actions = page.optJSONArray("actions")?.length() ?: 0
        val forms = page.optJSONArray("forms")?.length() ?: 0
        val tables = page.optJSONArray("tables")?.length() ?: 0
        val fields = page.optJSONArray("fieldLabels") ?: page.optJSONArray("fields") ?: JSONArray()
        val required = (0 until fields.length()).count { fields.optJSONObject(it)?.optBoolean("required") == true }
        return "AKKPS पेज: ${page.optString("title").take(80)}\n" +
            "Modules: $modules • Actions: $actions • Forms: $forms • Tables: $tables\n" +
            "Visible fields: ${fields.length()} • Required: $required"
    }

    private fun safeModules(source: JSONArray?): JSONArray = JSONArray().also { out ->
        for (i in 0 until minOf(source?.length() ?: 0, 30)) {
            val item = source?.optJSONObject(i) ?: continue
            val label = item.optString("label").trim().take(80)
            val path = safePath(item.optString("path"))
            if (label.isNotBlank() && path.isNotBlank()) {
                out.put(JSONObject().put("label", label).put("path", path).put("active", item.optBoolean("active")))
            }
        }
    }

    private fun safeActions(source: JSONArray?): JSONArray = JSONArray().also { out ->
        val actionPatterns = listOf(
            Regex("(?i)^(create|add|new|register|बनाएं|बनाओ|नया|नई|जोड़ें|जोड़ो)(?:\\s|$|[:：])") to "Create",
            Regex("(?i)^(edit|update|संपादित|बदलें|बदलना)(?:\\s|$|[:：])") to "Edit",
            Regex("(?i)^(save|submit|सहेजें|जमा|सबमिट)(?:\\s|$|[:：])") to "Submit",
            Regex("(?i)^(search|find|filter|खोज|ढूंढ|ढूँढ)(?:\\s|$|[:：])") to "Search",
            Regex("(?i)^(view|open|details|देखें|खोलें)(?:\\s|$|[:：])") to "View",
            Regex("(?i)^(upload|attach|अपलोड|संलग्न)(?:\\s|$|[:：])") to "Upload",
            Regex("(?i)^(delete|remove|हटाएं|मिटाएं|डिलीट)(?:\\s|$|[:：])") to "Delete",
            Regex("(?i)^(sync|सिंक)(?:\\s|$|[:：])") to "Sync",
            Regex("(?i)^(cancel|back|रद्द|वापस)(?:\\s|$|[:：])") to "Cancel"
        )
        for (i in 0 until minOf(source?.length() ?: 0, 40)) {
            val item = source?.optJSONObject(i) ?: continue
            val rawLabel = item.optString("label").trim().take(80)
            val safeLabel = actionPatterns.firstOrNull { it.first.containsMatchIn(rawLabel) }?.second ?: continue
            out.put(JSONObject().put("label", safeLabel)
                .put("type", item.optString("type").take(20)).put("disabled", item.optBoolean("disabled")))
        }
    }

    private fun safeForms(source: JSONArray?): JSONArray = JSONArray().also { out ->
        for (i in 0 until minOf(source?.length() ?: 0, 20)) {
            val item = source?.optJSONObject(i) ?: continue
            out.put(JSONObject().put("index", item.optInt("index"))
                .put("method", item.optString("method").take(8))
                .put("path", safePath(item.optString("path")))
                .put("fieldCount", item.optInt("fieldCount").coerceIn(0, 200)))
        }
    }

    private fun safeTables(source: JSONArray?): JSONArray = JSONArray().also { out ->
        for (i in 0 until minOf(source?.length() ?: 0, 20)) {
            val item = source?.optJSONObject(i) ?: continue
            val headers = JSONArray()
            val sourceHeaders = item.optJSONArray("headers") ?: JSONArray()
            for (j in 0 until sourceHeaders.length()) {
                val value = sourceHeaders.optString(j).trim().take(80)
                if (value.isNotBlank()) headers.put(value)
            }
            out.put(JSONObject().put("index", item.optInt("index"))
                .put("headers", headers).put("rowCount", item.optInt("rowCount").coerceIn(0, 100_000)))
        }
    }

    private fun safeFieldLabels(source: JSONArray?): JSONArray = JSONArray().also { out ->
        for (i in 0 until minOf(source?.length() ?: 0, 80)) {
            val item = source?.optJSONObject(i) ?: continue
            val label = listOf("label", "name", "id", "placeholder", "aria")
                .map { item.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(100)
            if (label.isNotBlank()) out.put(JSONObject().put("label", label)
                .put("required", item.optBoolean("required")).put("tag", item.optString("tag").take(12))
                .put("type", item.optString("type").take(20)))
        }
    }

    private fun safePath(raw: String): String {
        val path = raw.substringBefore('?').substringBefore('#').take(300)
        if (!path.startsWith("/") || path.startsWith("//")) return ""
        val segments = path.split('/')
        return segments.mapIndexed { index, segment ->
            val previous = segments.getOrNull(index - 1)?.lowercase().orEmpty()
            when {
                segment.isBlank() -> ""
                previous in setOf("farmers", "plots", "awd-reports") && segment != "new" -> ":id"
                segment.matches(Regex("[0-9]{3,}")) -> ":id"
                segment.matches(Regex("(?i)[0-9a-f]{8}-[0-9a-f-]{27,}")) -> ":id"
                else -> segment.take(60)
            }
        }.joinToString("/")
    }
}
