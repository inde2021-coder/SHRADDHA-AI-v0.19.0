package `in`.shraddha.ai.operator.learning

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONObject
import java.security.MessageDigest

/** Per-package learning profiles encrypted at rest with the Android Keystore-backed feature store. */
class AppProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val encrypted = EncryptedFeatureStore(appContext)
    private val legacyPrefs = appContext.getSharedPreferences("app_learning_profiles_v1", Context.MODE_PRIVATE)

    @Synchronized
    private fun migrateLegacyProfiles() {
        val marker = encrypted.get(MIGRATION_MARKER).getOrThrow()
        if (marker == "complete") {
            if (legacyPrefs.all.isNotEmpty()) {
                check(legacyPrefs.edit().clear().commit()) { "Could not remove migrated plaintext app profiles" }
            }
            return
        }
        val migrated = linkedMapOf<String, String>()
        legacyPrefs.all.values.forEach { rawValue ->
            val raw = rawValue as? String ?: error("Legacy app profile contains an unsupported value")
            val profile = AppTrainingProfile.fromJson(JSONObject(raw))
            require(profile.packageName.isNotBlank()) { "Legacy app profile has no package name" }
            migrated[key(profile.packageName)] = profile.toJson().toString()
        }
        migrated[MIGRATION_MARKER] = "complete"
        encrypted.putAll(migrated).getOrThrow()
        check(legacyPrefs.edit().clear().commit()) { "Encrypted profiles saved, but legacy plaintext cleanup failed" }
    }

    @Synchronized
    fun get(packageName: String): AppTrainingProfile? {
        require(packageName.isNotBlank())
        migrateLegacyProfiles()
        val raw = encrypted.get(key(packageName)).getOrThrow()
        return raw.takeIf { it.isNotBlank() }?.let { AppTrainingProfile.fromJson(JSONObject(it)) }
    }

    @Synchronized
    fun save(profile: AppTrainingProfile): Boolean = runCatching {
        require(profile.packageName.isNotBlank())
        migrateLegacyProfiles()
        encrypted.put(key(profile.packageName), profile.toJson().toString()).getOrThrow()
    }.isSuccess

    @Synchronized
    fun upsertAnchor(packageName: String, displayName: String, anchor: SemanticAnchor): AppTrainingProfile {
        val old = get(packageName) ?: AppTrainingProfile(packageName, displayName)
        val merged = old.anchors.filterNot { it.role.equals(anchor.role, true) && it.label.equals(anchor.label, true) } + anchor
        return old.copy(displayName = displayName, updatedAt = System.currentTimeMillis(), anchors = merged).also {
            check(save(it)) { "Encrypted app profile save failed" }
        }
    }

    @Synchronized
    fun saveWorkflow(packageName: String, displayName: String, name: String, steps: List<LearnedStep>): AppTrainingProfile {
        require(name.isNotBlank() && steps.isNotEmpty() && steps.size <= 100)
        val old = get(packageName) ?: AppTrainingProfile(packageName, displayName)
        return old.copy(updatedAt = System.currentTimeMillis(), workflows = old.workflows + (name to steps.take(100))).also {
            check(save(it)) { "Encrypted workflow save failed" }
        }
    }

    @Synchronized
    fun all(): List<AppTrainingProfile> {
        migrateLegacyProfiles()
        return encrypted.getAllWithPrefixes(setOf(PROFILE_PREFIX)).getOrThrow().values
            .map { AppTrainingProfile.fromJson(JSONObject(it)) }
            .sortedBy { it.displayName.lowercase() }
    }

    @Synchronized
    fun delete(packageName: String) {
        migrateLegacyProfiles()
        check(encrypted.remove(key(packageName))) { "Encrypted app profile delete failed" }
    }

    private fun key(packageName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(packageName.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return PROFILE_PREFIX + digest
    }

    companion object {
        private const val PROFILE_PREFIX = "app_profile_"
        private const val MIGRATION_MARKER = "app_profiles_migrated_v1"
    }
}
