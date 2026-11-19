package `in`.shraddha.ai.biometric

import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

data class BiometricMatch(val personId: String, val name: String, val similarity: Float)

/** Device-local encrypted template vault. Explicit consent is required at every enrollment call. */
class BiometricConsentVault(private val store: EncryptedFeatureStore) {
    companion object { private const val KEY = "people_biometric_templates_v1" }

    @Synchronized
    fun enroll(personId: String, name: String, kind: String, embedding: FloatArray, consent: Boolean): Result<Unit> = runCatching {
        require(consent) { "Explicit enrollment consent is required." }
        require(personId.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "Invalid person ID." }
        require(name.isNotBlank() && name.length <= 100) { "Invalid display name." }
        require(kind == "face" || kind == "speaker") { "Unsupported biometric type." }
        require(embedding.isNotEmpty() && embedding.size <= 8192 && embedding.all { it.isFinite() }) { "Invalid biometric embedding." }
        val root = readRoot()
        val items = root.optJSONArray("templates") ?: JSONArray()
        val entry = JSONObject().put("personId", personId).put("name", name).put("kind", kind)
            .put("embedding", JSONArray().also { array -> embedding.forEach { array.put(it.toDouble()) } })
        val next = JSONArray()
        for (i in 0 until items.length()) {
            val old = items.optJSONObject(i) ?: continue
            if (old.optString("personId") != personId || old.optString("kind") != kind) next.put(old)
        }
        next.put(entry)
        root.put("templates", next)
        store.put(KEY, root.toString()).getOrThrow()
    }

    @Synchronized
    fun match(kind: String, embedding: FloatArray, threshold: Float, onlyPersonId: String? = null): BiometricMatch? {
        require(kind == "face" || kind == "speaker")
        require(threshold.isFinite() && threshold in 0f..1f)
        require(embedding.isNotEmpty() && embedding.all { it.isFinite() })
        val templates = readRoot().optJSONArray("templates") ?: return null
        var best: BiometricMatch? = null
        for (i in 0 until templates.length()) {
            val item = templates.optJSONObject(i) ?: continue
            if (item.optString("kind") != kind) continue
            val id = item.optString("personId")
            if (onlyPersonId != null && id != onlyPersonId) continue
            val values = item.optJSONArray("embedding") ?: continue
            if (values.length() != embedding.size) continue
            val candidate = FloatArray(values.length()) { values.optDouble(it).toFloat() }
            val similarity = cosine(embedding, candidate) ?: continue
            if (similarity >= threshold && (best == null || similarity > best!!.similarity)) {
                best = BiometricMatch(id, item.optString("name"), similarity)
            }
        }
        return best
    }

    @Synchronized
    fun deletePerson(personId: String): Result<Unit> = runCatching {
        val root = readRoot()
        val old = root.optJSONArray("templates") ?: JSONArray()
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("personId") != personId) next.put(item)
        }
        root.put("templates", next)
        store.put(KEY, root.toString()).getOrThrow()
    }

    private fun readRoot(): JSONObject {
        val raw = store.get(KEY).getOrThrow()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float? {
        if (a.size != b.size) return null
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        if (aa <= 1e-12 || bb <= 1e-12) return null
        return (dot / (sqrt(aa) * sqrt(bb))).toFloat().coerceIn(-1f, 1f)
    }
}
