package `in`.shraddha.ai.operator.website

/** Stores only user-approved plot geometry, encrypted at rest with the Android Keystore. */
class AkkpsBoundaryStore(context: android.content.Context) {
    private val encrypted = `in`.shraddha.ai.brain.EncryptedFeatureStore(context.applicationContext)

    fun save(plotId: String, boundary: AkkpsBoundary) {
        encrypted.put(key(plotId), AkkpsBoundaryGeometry.toJson(boundary)).getOrThrow()
    }

    fun load(plotId: String): AkkpsBoundary? {
        val raw = encrypted.get(key(plotId)).getOrThrow()
        if (raw.isBlank()) return null
        return AkkpsBoundaryGeometry.fromJson(raw)
    }

    fun delete(plotId: String) {
        check(encrypted.remove(key(plotId))) { "Boundary local delete failed." }
    }

    private fun key(plotId: String): String {
        val id = plotId.trim()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
            "Plot ID में केवल अक्षर, अंक, _ और - मान्य हैं।"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return "akkps_boundary_$digest"
    }
}
