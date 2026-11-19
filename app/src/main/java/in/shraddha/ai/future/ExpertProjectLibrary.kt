package `in`.shraddha.ai.future

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Encrypted, ordered project library with one-time migration from the old plaintext StringSet. */
class ExpertProjectLibrary(context: Context) {
    data class Entry(
        val id: String,
        val title: String,
        val content: String,
        val savedAt: Long,
        val workspace: String,
        val task: String,
        val provider: String
    ) {
        fun toJson() = JSONObject()
            .put("id", id).put("title", title).put("content", content)
            .put("savedAt", savedAt).put("workspace", workspace)
            .put("task", task).put("provider", provider)

        companion object {
            fun fromJson(json: JSONObject): Entry {
                val id = json.optString("id")
                val title = json.optString("title")
                val content = json.optString("content")
                val savedAt = json.optLong("savedAt", 0L)
                require(id.matches(Regex("[A-Za-z0-9_-]{8,64}")))
                require(title.isNotBlank() && title.length <= 160)
                require(content.isNotBlank() && content.length <= 12_000)
                require(savedAt > 0)
                return Entry(
                    id, title, content, savedAt,
                    json.optString("workspace").take(80),
                    json.optString("task").take(80),
                    json.optString("provider").take(40)
                )
            }
        }
    }

    private val store = EncryptedFeatureStore(context.applicationContext)
    private val legacy = context.applicationContext.getSharedPreferences("shraddha_expert_library", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): Result<List<Entry>> = runCatching {
        migrateLegacy()
        parse(store.get(KEY).getOrThrow())
    }

    @Synchronized
    fun save(title: String, content: String, workspace: String, task: String, provider: String): Result<List<Entry>> = runCatching {
        require(title.isNotBlank() && content.isNotBlank())
        val existing = load().getOrThrow()
        val newEntry = Entry(
            UUID.randomUUID().toString().replace("-", ""),
            title.trim().take(160),
            content.trim().take(12_000),
            System.currentTimeMillis(),
            workspace.take(80), task.take(80), provider.take(40)
        )
        val entries = (listOf(newEntry) + existing).take(MAX_ITEMS).toMutableList()
        var encoded = encode(entries)
        while (encoded.length > MAX_JSON_CHARS && entries.size > 1) {
            entries.removeAt(entries.lastIndex)
            encoded = encode(entries)
        }
        require(encoded.length <= MAX_JSON_CHARS) { "Project output is too large for the encrypted local library" }
        store.put(KEY, encoded).getOrThrow()
        entries
    }

    @Synchronized
    fun delete(id: String): Result<List<Entry>> = runCatching {
        require(id.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        val entries = load().getOrThrow().filterNot { it.id == id }
        store.put(KEY, encode(entries)).getOrThrow()
        entries
    }

    private fun migrateLegacy() {
        if (store.get(MIGRATION_KEY).getOrThrow() == "complete") {
            if (legacy.all.isNotEmpty()) check(legacy.edit().clear().commit()) { "Could not clear migrated plaintext project library" }
            return
        }
        val oldEntries = legacy.getStringSet("items", emptySet()).orEmpty()
            .map { raw ->
                val json = JSONObject(raw)
                Entry(
                    json.optString("id").ifBlank { UUID.randomUUID().toString().replace("-", "") },
                    json.optString("title"),
                    json.optString("content"),
                    json.optLong("savedAt", System.currentTimeMillis()),
                    json.optString("workspace"),
                    json.optString("task"),
                    json.optString("provider")
                ).also {
                    require(it.title.isNotBlank() && it.content.isNotBlank())
                }
            }
            .sortedByDescending { it.savedAt }
            .take(MAX_ITEMS)
        val encoded = encode(oldEntries)
        require(encoded.length <= MAX_JSON_CHARS) { "Legacy project library is too large to migrate safely" }
        store.putAll(mapOf(KEY to encoded, MIGRATION_KEY to "complete")).getOrThrow()
        check(legacy.edit().clear().commit()) { "Encrypted project library saved, but plaintext cleanup failed" }
    }

    private fun parse(raw: String): List<Entry> {
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        require(array.length() <= MAX_ITEMS) { "Project library exceeds the item limit" }
        return (0 until array.length()).map { Entry.fromJson(array.getJSONObject(it)) }
            .sortedByDescending { it.savedAt }
    }

    private fun encode(entries: List<Entry>): String =
        JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString()

    companion object {
        private const val KEY = "expert_project_library"
        // Deliberately does not share the project-key prefix, so this marker is not exported.
        private const val MIGRATION_KEY = "expert_library_migrated_v1"
        private const val MAX_ITEMS = 20
        private const val MAX_JSON_CHARS = 90_000
    }
}
