package `in`.shraddha.ai.biometric

import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Base64

/**
 * Consent-gated local biometric templates. Each person's payload is separately
 * encrypted by EncryptedFeatureStore/Android Keystore AES-GCM so the per-entry
 * 100 KB storage limit is respected. Raw photos/audio are never stored.
 */
class BiometricConsentVault(private val store: EncryptedFeatureStore) {
    companion object {
        private const val INDEX = "biometric_person_index_v1"
        private const val PREFIX = "biometric_person_"
        private const val MAX_PEOPLE = 50
        private const val MAX_TEMPLATES_PER_KIND = 8
        private const val CONSENT_VERSION = 1
    }

    @Synchronized
    fun enroll(personId: String, displayName: String, kind: String, embedding: FloatArray, explicitConsent: Boolean): Result<Unit> = runCatching {
        require(explicitConsent) { "व्यक्ति की स्पष्ट biometric सहमति आवश्यक है" }
        require(personId.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(kind == "face" || kind == "speaker")
        val normalized = BiometricMath.unitNormalize(embedding)
        try {
            // Read and validate the index before writing any profile data. A corrupt or
            // undecryptable index must never create an unindexed biometric template.
            val ids = readIndex()
            check(store.remove(thresholdKey(kind))) { "Could not invalidate the stale biometric threshold" }
            val key = PREFIX + personId
            val person = readPerson(personId) ?: run {
                require(ids.length() < MAX_PEOPLE) { "Person directory limit reached" }
                JSONObject().put("id", personId).put("name", displayName)
                    .put("consentVersion", CONSENT_VERSION).put("consentAt", System.currentTimeMillis())
                    .put("consent", true).put("face", JSONArray()).put("speaker", JSONArray())
            }
            require(person.optBoolean("consent")) { "Consent was withdrawn" }
            person.put("name", displayName)
            val templates = person.optJSONArray(kind) ?: JSONArray()
            require(templates.length() < MAX_TEMPLATES_PER_KIND) { "Template limit reached; remove old samples first" }
            val consentAt = System.currentTimeMillis()
            val encoded = encode(normalized)
            templates.put(JSONObject().put("dimension", normalized.size).put("vector", encoded)
                .put("createdAt", consentAt).put("consentAt", consentAt).put("consentVersion", CONSENT_VERSION))
            person.put(kind, templates)
            check(store.put(key, person.toString()).isSuccess) { "Encrypted biometric save failed" }
            if ((0 until ids.length()).none { ids.optString(it) == personId }) {
                ids.put(personId)
                check(store.put(INDEX, ids.toString()).isSuccess) { "Biometric index save failed" }
            }
        } finally {
            normalized.fill(0f)
        }
    }

    @Synchronized
    fun withdraw(personId: String): Result<Unit> = runCatching {
        require(personId.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        val old = readIndex()
        check(store.remove(thresholdKey("face")) && store.remove(thresholdKey("speaker"))) { "Could not invalidate biometric thresholds" }
        check(store.remove(PREFIX + personId)) { "Could not delete biometric templates" }
        val updated = JSONArray()
        for (i in 0 until old.length()) if (old.optString(i) != personId) updated.put(old.optString(i))
        check(store.put(INDEX, updated.toString()).isSuccess) { "Could not update biometric index" }
    }

    @Synchronized fun clear(): Boolean = runCatching {
        // Prefix removal also catches any orphaned records left by an older build.
        store.removeAllWithPrefix("biometric_")
    }.getOrDefault(false)

    @Synchronized
    fun match(kind: String, probe: FloatArray, calibratedThreshold: Float, onlyPersonId: String? = null): Match? {
        require(kind == "face" || kind == "speaker")
        require(calibratedThreshold in -1f..1f)
        val normalized = BiometricMath.unitNormalize(probe)
        try {
            var best: Match? = null
            val ids = readIndex()
            for (i in 0 until ids.length()) {
                val personId = ids.optString(i)
                if (onlyPersonId != null && personId != onlyPersonId) continue
                val person = readPerson(personId) ?: continue
                if (!person.optBoolean("consent")) continue
                val templates = person.optJSONArray(kind) ?: continue
                for (j in 0 until templates.length()) {
                    val template = templates.optJSONObject(j) ?: continue
                    if (template.optInt("dimension") != normalized.size) continue
                    val vector = decode(template.optString("vector"), normalized.size)
                    val score = try {
                        BiometricMath.cosineSimilarity(normalized, vector)
                    } finally {
                        vector.fill(0f)
                    }
                    if (best == null || score > best.similarity) best = Match(person.optString("id"), person.optString("name"), kind, score)
                }
            }
            return best?.takeIf { BiometricMath.isMatch(it.similarity, calibratedThreshold) }
        } finally {
            normalized.fill(0f)
        }
    }

    private fun readIndex(): JSONArray {
        val raw = store.get(INDEX).getOrThrow()
        if (raw.isBlank()) return JSONArray()
        val ids = JSONArray(raw)
        require(ids.length() <= MAX_PEOPLE) { "Biometric person index exceeds the supported limit" }
        val seen = mutableSetOf<String>()
        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            require(id.matches(Regex("[A-Za-z0-9_-]{8,64}"))) { "Biometric person index contains an invalid ID" }
            require(seen.add(id)) { "Biometric person index contains a duplicate ID" }
        }
        return ids
    }

    private fun readPerson(id: String): JSONObject? {
        require(id.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        val raw = store.get(PREFIX + id).getOrThrow()
        if (raw.isBlank()) return null
        return JSONObject(raw).also { person ->
            require(person.optString("id") == id) { "Biometric profile ID does not match its storage key" }
        }
    }

    private fun encode(values: FloatArray): String {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bytes.putFloat(it) }
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
    }
    private fun decode(value: String, dimension: Int): FloatArray {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size == dimension * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }
    }

    private fun thresholdKey(kind: String): String = "biometric_threshold_${kind}_v1"

    @Synchronized
    fun saveThreshold(kind: String, threshold: Float): Result<Unit> = runCatching {
        require(kind == "face" || kind == "speaker")
        require(threshold.isFinite() && threshold in -1f..1f)
        check(store.put(thresholdKey(kind), threshold.toString()).isSuccess) { "Could not save calibrated threshold" }
    }

    @Synchronized
    fun clearThreshold(kind: String): Boolean {
        require(kind == "face" || kind == "speaker")
        return store.remove(thresholdKey(kind))
    }

    @Synchronized
    fun loadThreshold(kind: String): Result<Float?> = runCatching {
        require(kind == "face" || kind == "speaker")
        val raw = store.get(thresholdKey(kind)).getOrThrow()
        if (raw.isBlank()) null else raw.toFloat().also {
            require(it.isFinite() && it in -1f..1f) { "Stored biometric threshold is invalid" }
        }
    }

    data class TemplateCounts(val face: Int, val speaker: Int)

    @Synchronized
    fun templateCounts(personId: String): Result<TemplateCounts> = runCatching {
        require(personId.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        val ids = readIndex()
        if ((0 until ids.length()).none { ids.optString(it) == personId }) return@runCatching TemplateCounts(0, 0)
        val person = readPerson(personId) ?: return@runCatching TemplateCounts(0, 0)
        if (!person.optBoolean("consent")) return@runCatching TemplateCounts(0, 0)
        TemplateCounts(
            person.optJSONArray("face")?.length() ?: 0,
            person.optJSONArray("speaker")?.length() ?: 0
        )
    }

    data class CalibrationResult(val threshold: Float, val minimumGenuine: Float, val maximumImpostor: Float, val genuinePairs: Int, val impostorPairs: Int)

    @Synchronized
    fun calibrateThreshold(kind: String): Result<CalibrationResult> = runCatching {
        require(kind == "face" || kind == "speaker")
        val groups = mutableListOf<Pair<String, List<FloatArray>>>()
        try {
            val ids = readIndex()
            for (i in 0 until ids.length()) {
                val person = readPerson(ids.optString(i)) ?: continue
                if (!person.optBoolean("consent")) continue
                val templates = person.optJSONArray(kind) ?: continue
                val vectors = mutableListOf<FloatArray>()
                try {
                    for (index in 0 until templates.length()) {
                        val item = templates.getJSONObject(index)
                        val dimension = item.getInt("dimension")
                        vectors.add(decode(item.getString("vector"), dimension))
                    }
                } catch (error: Exception) {
                    vectors.forEach { it.fill(0f) }
                    throw error
                }
                if (vectors.size >= 2) groups.add(person.optString("id") to vectors)
                else vectors.forEach { it.fill(0f) }
            }
            require(groups.size >= 2) { "कम-से-कम 2 अलग consented लोगों के 2-2 samples जरूरी हैं" }
            val genuine = mutableListOf<Float>()
            val impostor = mutableListOf<Float>()
            for ((_, vectors) in groups) for (a in vectors.indices) for (b in a + 1 until vectors.size) {
                if (vectors[a].size == vectors[b].size) genuine.add(BiometricMath.cosineSimilarity(vectors[a], vectors[b]))
            }
            for (a in groups.indices) for (b in a + 1 until groups.size) {
                for (left in groups[a].second) for (right in groups[b].second) {
                    if (left.size == right.size) impostor.add(BiometricMath.cosineSimilarity(left, right))
                }
            }
            require(genuine.isNotEmpty() && impostor.isNotEmpty()) { "Calibration samples are insufficient" }
            val calibrated = BiometricMath.calibrateThreshold(genuine, impostor)
            CalibrationResult(calibrated.threshold, calibrated.minimumGenuine, calibrated.maximumImpostor, genuine.size, impostor.size)
        } finally {
            groups.forEach { (_, vectors) -> vectors.forEach { it.fill(0f) } }
        }
    }

    data class Match(val personId: String, val name: String, val kind: String, val similarity: Float)
}