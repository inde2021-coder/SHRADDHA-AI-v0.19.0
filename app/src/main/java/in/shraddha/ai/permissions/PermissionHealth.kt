package `in`.shraddha.ai.permissions

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService
import `in`.shraddha.ai.keyboard.ShraddhaKeyboardService
import `in`.shraddha.ai.future.ShraddhaNotificationListener

/** Read-only snapshot of Android access. It never requests or changes a permission. */
data class PermissionHealthItem(val id: String, val title: String, val enabled: Boolean, val detail: String)

object PermissionHealth {
    fun collect(context: Context): List<PermissionHealthItem> {
        fun runtime(id: String, title: String, permission: String, unavailableBefore: Int? = null): PermissionHealthItem {
            if (unavailableBefore != null && Build.VERSION.SDK_INT < unavailableBefore) return PermissionHealthItem(id, title, true, "इस Android version पर अलग runtime permission जरूरी नहीं")
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            return PermissionHealthItem(id, title, granted, if (granted) "चालू" else "बंद — Android Settings में अनुमति दें")
        }
        fun special(id: String, title: String, enabled: Boolean, action: String) = PermissionHealthItem(id, title, enabled, if (enabled) "चालू" else "बंद — " + action)
        val resolver = context.contentResolver
        val accessibility = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val accessibilityId = ComponentName(context, ShraddhaAccessibilityService::class.java).flattenToString()
        val keyboards = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
        val keyboardId = ComponentName(context, ShraddhaKeyboardService::class.java).flattenToString()
        val listeners = Settings.Secure.getString(resolver, "enabled_notification_listeners").orEmpty()
        val listenerId = ComponentName(context, ShraddhaNotificationListener::class.java).flattenToString()
        return listOf(
            runtime("microphone", "माइक्रोफोन", Manifest.permission.RECORD_AUDIO),
            runtime("camera", "कैमरा", Manifest.permission.CAMERA),
            runtime("contacts", "Contacts", Manifest.permission.READ_CONTACTS),
            runtime("phone", "Phone calling", Manifest.permission.CALL_PHONE),
            runtime("sms", "SMS sending", Manifest.permission.SEND_SMS),
            runtime("notifications", "Notifications", Manifest.permission.POST_NOTIFICATIONS, 33),
            special("overlay", "Floating AI Orb", Settings.canDrawOverlays(context), "Display over other apps खोलें"),
            special("accessibility", "App control", accessibility.contains(accessibilityId, true), "Accessibility settings खोलें"),
            special("keyboard", "Shraddha keyboard", keyboards.contains(keyboardId, true), "On-screen keyboard settings खोलें"),
            special("notification_listener", "Notification reading", listeners.contains(listenerId, true), "Notification access settings खोलें")
        )
    }
}
