package `in`.shraddha.ai.operator.website

import org.json.JSONArray
import org.json.JSONObject

data class AkkpsBoundaryDraft(
    val points: List<AkkpsGeoPoint>,
    val bestAccuracyMeters: Float?,
    val updatedAtEpochMillis: Long
)

object AkkpsBoundaryDraftCodec {
    fun encode(points: List<AkkpsGeoPoint>, bestAccuracyMeters: Float?, updatedAtEpochMillis: Long): String {
        validatePoints(points)
        require(bestAccuracyMeters == null || (bestAccuracyMeters.isFinite() && bestAccuracyMeters in 0f..1_000f))
        require(updatedAtEpochMillis > 0L && updatedAtEpochMillis <= System.currentTimeMillis() + 60_000L)
        return JSONObject().put("version", 1)
            .put("updatedAt", updatedAtEpochMillis)
            .put("points", JSONArray().apply {
                points.forEach { put(JSONObject().put("lat", it.latitude).put("lon", it.longitude)) }
            })
            .apply { bestAccuracyMeters?.let { put("bestAccuracyMeters", it.toDouble()) } }
            .toString()
    }

    fun decode(raw: String): AkkpsBoundaryDraft {
        require(raw.length in 1..200_000) { "GPS draft data size is invalid." }
        val json = JSONObject(raw)
        require(json.optInt("version") == 1) { "GPS draft version is unsupported." }
        val array = json.getJSONArray("points")
        require(array.length() in 1..AkkpsBoundaryGeometry.MAX_POINTS) { "GPS draft point count is invalid." }
        val points = (0 until array.length()).map { index ->
            val point = array.getJSONObject(index)
            AkkpsGeoPoint(point.getDouble("lat"), point.getDouble("lon"))
        }
        validatePoints(points)
        val accuracy = if (json.has("bestAccuracyMeters") && !json.isNull("bestAccuracyMeters"))
            json.getDouble("bestAccuracyMeters").toFloat() else null
        require(accuracy == null || (accuracy.isFinite() && accuracy in 0f..1_000f)) {
            "GPS draft accuracy is invalid."
        }
        val updatedAt = json.getLong("updatedAt")
        require(updatedAt > 0L && updatedAt <= System.currentTimeMillis() + 60_000L) {
            "GPS draft timestamp is invalid."
        }
        return AkkpsBoundaryDraft(points, accuracy, updatedAt)
    }

    private fun validatePoints(points: List<AkkpsGeoPoint>) {
        require(points.size in 1..AkkpsBoundaryGeometry.MAX_POINTS) { "GPS draft point count is invalid." }
        require(points.all {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }) { "GPS draft contains invalid coordinates." }
        require(points.zipWithNext().none { (a, b) -> a == b }) {
            "GPS draft contains duplicate adjacent points."
        }
    }
}

/** Encrypted local storage for an unfinished GPS walk; never treated as a saved boundary. */
class AkkpsBoundaryDraftStore(context: android.content.Context) {
    private val encrypted = `in`.shraddha.ai.brain.EncryptedFeatureStore(context.applicationContext)

    fun save(plotId: String, points: List<AkkpsGeoPoint>, bestAccuracyMeters: Float?) {
        val payload = AkkpsBoundaryDraftCodec.encode(points, bestAccuracyMeters, System.currentTimeMillis())
        encrypted.put(key(plotId), payload).getOrThrow()
    }

    fun load(plotId: String): AkkpsBoundaryDraft? {
        val raw = encrypted.get(key(plotId)).getOrThrow()
        if (raw.isBlank()) return null
        return AkkpsBoundaryDraftCodec.decode(raw)
    }

    fun delete(plotId: String) {
        val storageKey = key(plotId)
        val existing = encrypted.get(storageKey).getOrThrow()
        if (existing.isNotBlank()) {
            check(encrypted.remove(storageKey)) { "GPS draft local delete failed." }
        }
    }

    private fun key(plotId: String): String {
        val id = plotId.trim()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
            "Plot ID में केवल अक्षर, अंक, _ और - मान्य हैं।"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return "akkps_boundary_draft_$digest"
    }
}
