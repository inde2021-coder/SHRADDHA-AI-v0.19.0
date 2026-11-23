package `in`.shraddha.ai.plugins

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONObject
import java.util.UUID

data class AuthorizedPluginAction(
    val pluginId: String,
    val pluginName: String,
    val actionIndex: Int,
    val capabilities: Set<String>,
    val type: String,
    val title: String,
    val body: String
)

object PluginActionPolicy {
    fun parse(response: JSONObject): AuthorizedPluginAction {
        require(response.optBoolean("ok") && response.optBoolean("verified")) { "Plugin action was not verified by the trusted gateway." }
        require(response.optString("execution") == "client-local-allowlisted" && !response.optBoolean("codeExecution", true)) {
            "Executable plugin content is not permitted."
        }
        require(response.optBoolean("requiresLocalExecution")) { "Plugin response is not a local action authorization." }
        val pluginId = response.optString("pluginId")
        require(pluginId.matches(Regex("[a-z][a-z0-9._-]{2,63}"))) { "Plugin identity is invalid." }
        val capabilitiesJson = response.optJSONArray("capabilities") ?: error("Plugin capabilities are missing.")
        val capabilities = (0 until capabilitiesJson.length()).map { capabilitiesJson.getString(it) }.toSet()
        val action = response.optJSONObject("action") ?: error("Authorized action is missing.")
        val type = action.optString("type")
        require(type == "create_note" || type == "show_notification") { "Plugin action is not allowlisted." }
        val title = action.optString("title")
        val body = action.optString("body")
        when (type) {
            "create_note" -> {
                require("create_note" in capabilities) { "Plugin did not declare create_note capability." }
                require(title.isNotBlank() && title.length <= 120 && body.length <= 4000) { "Plugin note content is invalid." }
            }
            "show_notification" -> {
                require("show_notification" in capabilities) { "Plugin did not declare show_notification capability." }
                require(title.isNotBlank() && title.length <= 80 && body.length <= 240) { "Plugin notification content is invalid." }
            }
        }
        val index = response.optInt("actionIndex", -1)
        require(index >= 0) { "Plugin action index is invalid." }
        return AuthorizedPluginAction(pluginId, response.optString("pluginName"), index, capabilities, type, title, body)
    }
}

/** Executes only the two built-in local handlers; never loads plugin code, DEX, scripts or URLs. */
object DeclarativePluginRuntime {
    fun execute(context: Context, action: AuthorizedPluginAction, userConfirmed: Boolean): Result<String> = runCatching {
        require(userConfirmed) { "Explicit confirmation is required before running a plugin action." }
        when (action.type) {
            "create_note" -> {
                val key = "plugin_note_" + UUID.randomUUID().toString().replace("-", "")
                val record = JSONObject().put("pluginId", action.pluginId)
                    .put("title", action.title).put("body", action.body)
                    .put("createdAt", System.currentTimeMillis()).toString()
                EncryptedFeatureStore(context.applicationContext).put(key, record).getOrThrow()
                "Saved encrypted note: ${action.title}"
            }
            "show_notification" -> {
                require(NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    "Notifications are disabled. Enable notifications in Android settings first."
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    require(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        "Notification permission is not granted."
                    }
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= 26) {
                    manager.createNotificationChannel(NotificationChannel("shraddha_plugin_actions", "Approved plugin actions", NotificationManager.IMPORTANCE_DEFAULT))
                }
                NotificationManagerCompat.from(context).notify(
                    (action.pluginId + action.actionIndex).hashCode(),
                    NotificationCompat.Builder(context, "shraddha_plugin_actions")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(action.title).setContentText(action.body)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(action.body))
                        .setAutoCancel(true).build()
                )
                "Notification displayed: ${action.title}"
            }
            else -> error("Plugin action is not allowlisted.")
        }
    }
}
