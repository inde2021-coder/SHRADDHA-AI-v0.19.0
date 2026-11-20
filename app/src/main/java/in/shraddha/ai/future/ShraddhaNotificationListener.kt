package `in`.shraddha.ai.future

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import java.util.Calendar

/**
 * Opt-in, metadata-only notification observer.
 * Never reads notification title/text/bigText, never dismisses notifications,
 * and stores only package/category/channel/time/ongoing metadata.
 */
class ShraddhaNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !preferences().getBoolean(KEY_ENABLED, false)) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val record = JSONObject()
            .put("package", sbn.packageName.take(160))
            .put("category", (n.category ?: "uncategorized").take(60))
            .put("channel", (if (android.os.Build.VERSION.SDK_INT >= 26) n.channelId else "").take(100))
            .put("postedAt", sbn.postTime)
            .put("ongoing", sbn.isOngoing)
            .put("quietHours", isQuietHours())
        synchronized(LOCK) {
            val prefs = preferences()
            // Remove any legacy plaintext before attempting encrypted storage.
            prefs.edit().remove(KEY_EVENTS).commit()
            val store = EncryptedFeatureStore(this)
            // Android Keystore AES-GCM; never fall back to plaintext if encryption fails.
            val items = runCatching {
                val saved = store.get(ENCRYPTED_EVENTS_KEY).getOrThrow()
                if (saved.isBlank()) JSONArray() else JSONArray(saved)
            }.getOrElse { return }
            items.put(record)
            while (items.length() > MAX_EVENTS) items.remove(0)
            if (store.put(ENCRYPTED_EVENTS_KEY, items.toString()).isFailure) return
            // Purge legacy plaintext history from older app versions.
            prefs.edit().remove(KEY_EVENTS).commit()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Deliberately no content capture and no notification manipulation.
    }

    private fun preferences() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun isQuietHours(): Boolean {
        val prefs = preferences()
        if (!prefs.getBoolean(KEY_QUIET_ENABLED, false)) return false
        val start = prefs.getInt(KEY_QUIET_START, 22 * 60).coerceIn(0, 1439)
        val end = prefs.getInt(KEY_QUIET_END, 7 * 60).coerceIn(0, 1439)
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        return if (start == end) false else if (start < end) now in start until end else now >= start || now < end
    }

    companion object {
        const val PREFS = "shraddha_notification_preferences"
        const val KEY_ENABLED = "metadata_capture_enabled"
        /** Legacy plaintext key retained only for cleanup of older installs. */
        const val KEY_EVENTS = "metadata_events"
        const val ENCRYPTED_EVENTS_KEY = "notification_history_v1"
        const val KEY_QUIET_ENABLED = "quiet_hours_enabled"
        const val KEY_QUIET_START = "quiet_hours_start_minute"
        const val KEY_QUIET_END = "quiet_hours_end_minute"
        const val MAX_EVENTS = 100
        private val LOCK = Any()
    }
}
