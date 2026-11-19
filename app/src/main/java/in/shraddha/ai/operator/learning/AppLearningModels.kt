package `in`.shraddha.ai.operator.learning

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SemanticAnchor(
    val role: String,
    val label: String,
    val resourceId: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val parentLabel: String = "",
    val packageName: String = "",
    val confidence: Float = 0.0f
) {
    fun toJson() = JSONObject().apply {
        put("role", role); put("label", label); put("resourceId", resourceId)
        put("contentDescription", contentDescription); put("className", className)
        put("parentLabel", parentLabel); put("packageName", packageName); put("confidence", confidence.toDouble())
    }
    companion object {
        fun fromJson(o: JSONObject) = SemanticAnchor(o.optString("role"), o.optString("label"), o.optString("resourceId"), o.optString("contentDescription"), o.optString("className"), o.optString("parentLabel"), o.optString("packageName"), o.optDouble("confidence").toFloat())
    }
}

data class LearnedStep(
    val id: String = UUID.randomUUID().toString(),
    val action: String,
    val targetRole: String = "",
    val targetLabel: String = "",
    val inputTemplate: String = "",
    val expectedText: String = "",
    val destructive: Boolean = false
) {
    fun toJson() = JSONObject().apply { put("id", id); put("action", action); put("targetRole", targetRole); put("targetLabel", targetLabel); put("inputTemplate", inputTemplate); put("expectedText", expectedText); put("destructive", destructive) }
    companion object { fun fromJson(o: JSONObject) = LearnedStep(o.optString("id"), o.optString("action"), o.optString("targetRole"), o.optString("targetLabel"), o.optString("inputTemplate"), o.optString("expectedText"), o.optBoolean("destructive")) }
}

data class AppTrainingProfile(
    val packageName: String,
    val displayName: String,
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val anchors: List<SemanticAnchor> = emptyList(),
    val workflows: Map<String, List<LearnedStep>> = emptyMap(),
    val notes: String = ""
) {
    fun toJson(): JSONObject {
        val a = JSONArray(); anchors.forEach { a.put(it.toJson()) }
        val w = JSONObject(); workflows.forEach { (name, steps) -> val s = JSONArray(); steps.forEach { s.put(it.toJson()) }; w.put(name, s) }
        return JSONObject().put("packageName", packageName).put("displayName", displayName).put("version", version).put("updatedAt", updatedAt).put("anchors", a).put("workflows", w).put("notes", notes)
    }
    companion object {
        fun fromJson(o: JSONObject): AppTrainingProfile {
            val anchors = mutableListOf<SemanticAnchor>(); val aa = o.optJSONArray("anchors") ?: JSONArray()
            for (i in 0 until aa.length()) aa.optJSONObject(i)?.let { anchors.add(SemanticAnchor.fromJson(it)) }
            val workflows = linkedMapOf<String, List<LearnedStep>>(); val ww = o.optJSONObject("workflows") ?: JSONObject(); val keys = ww.keys()
            while (keys.hasNext()) { val key = keys.next(); val arr = ww.optJSONArray(key) ?: JSONArray(); val steps = mutableListOf<LearnedStep>(); for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { steps.add(LearnedStep.fromJson(it)) }; workflows[key] = steps }
            return AppTrainingProfile(o.optString("packageName"), o.optString("displayName"), o.optInt("version", 1), o.optLong("updatedAt"), anchors, workflows, o.optString("notes"))
        }
    }
}
