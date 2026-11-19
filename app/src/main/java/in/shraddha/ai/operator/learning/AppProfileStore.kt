package `in`.shraddha.ai.operator.learning

import android.content.Context
import org.json.JSONObject

/** Per-package learning store. Values are semantic anchors and user-approved workflow recipes, not screen recordings. */
class AppProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_learning_profiles_v1", Context.MODE_PRIVATE)
    @Synchronized fun get(packageName: String): AppTrainingProfile? = prefs.getString(key(packageName), null)?.let { runCatching { AppTrainingProfile.fromJson(JSONObject(it)) }.getOrNull() }
    @Synchronized fun save(profile: AppTrainingProfile): Boolean = prefs.edit().putString(key(profile.packageName), profile.toJson().toString()).commit()
    @Synchronized fun upsertAnchor(packageName: String, displayName: String, anchor: SemanticAnchor): AppTrainingProfile {
        val old = get(packageName) ?: AppTrainingProfile(packageName, displayName)
        val merged = old.anchors.filterNot { it.role.equals(anchor.role, true) && it.label.equals(anchor.label, true) } + anchor
        return old.copy(displayName = displayName, updatedAt = System.currentTimeMillis(), anchors = merged).also(::save)
    }
    @Synchronized fun saveWorkflow(packageName: String, displayName: String, name: String, steps: List<LearnedStep>): AppTrainingProfile {
        require(name.isNotBlank() && steps.isNotEmpty() && steps.size <= 100)
        val old = get(packageName) ?: AppTrainingProfile(packageName, displayName)
        return old.copy(updatedAt = System.currentTimeMillis(), workflows = old.workflows + (name to steps.take(100))).also(::save)
    }
    fun all(): List<AppTrainingProfile> = prefs.all.values.mapNotNull { raw -> (raw as? String)?.let { runCatching { AppTrainingProfile.fromJson(JSONObject(it)) }.getOrNull() } }
    fun delete(packageName: String) { prefs.edit().remove(key(packageName)).apply() }
    private fun key(packageName: String): String = "app_" + android.util.Base64.encodeToString(packageName.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
}
