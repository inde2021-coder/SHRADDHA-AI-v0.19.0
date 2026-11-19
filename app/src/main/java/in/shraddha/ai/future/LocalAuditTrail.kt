package `in`.shraddha.ai.future

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Minimal local audit trail. It stores metadata only, never passwords, tokens, screen frames or biometrics. */
class LocalAuditTrail(context:Context) {
    private val prefs=context.getSharedPreferences("shraddha_audit_trail",Context.MODE_PRIVATE)
    @Synchronized fun append(event:String, outcome:String, detail:String="") {
        val arr=runCatching { JSONArray(prefs.getString("events","[]")) }.getOrElse { JSONArray() }
        arr.put(JSONObject().put("at",System.currentTimeMillis()).put("event",event.take(80)).put("outcome",outcome.take(40)).put("detail",detail.take(240)))
        while(arr.length()>300) arr.remove(0)
        prefs.edit().putString("events",arr.toString()).apply()
    }
    fun exportText():String {
        val arr=runCatching { JSONArray(prefs.getString("events","[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { "${it.optLong("at")} | ${it.optString("event")} | ${it.optString("outcome")} | ${it.optString("detail")}" } }.joinToString("\n")
    }
    fun clear() { prefs.edit().remove("events").apply() }
}
