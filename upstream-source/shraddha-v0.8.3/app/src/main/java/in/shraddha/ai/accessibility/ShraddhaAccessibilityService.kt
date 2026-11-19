package `in`.shraddha.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.graphics.Path
import android.os.Build
import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class ShraddhaAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Screen content is not uploaded or logged. */ }
    override fun onInterrupt() { }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    private fun setFocusedText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        // Never interact with password fields or nodes marked sensitive.
        if (focused.isPassword || !focused.isEditable || focused.isVisibleToUser.not()) return false
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })
    }

    private fun performRemoteTap(xNorm: Float, yNorm: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val metrics = resources.displayMetrics
        val x = (xNorm.coerceIn(0f, 1f) * metrics.widthPixels)
        val y = (yNorm.coerceIn(0f, 1f) * metrics.heightPixels)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performRemoteSwipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(x1.coerceIn(0f, 1f) * metrics.widthPixels, y1.coerceIn(0f, 1f) * metrics.heightPixels)
            lineTo(x2.coerceIn(0f, 1f) * metrics.widthPixels, y2.coerceIn(0f, 1f) * metrics.heightPixels)
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        return dispatchGesture(gesture, null, null)
    }


    private fun openInstalledApp(query: String): Boolean {
        val wanted = query.trim()
        if (wanted.isBlank()) return false
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(launcher, 0)
        val match = matches.firstOrNull { it.loadLabel(packageManager).toString().equals(wanted, ignoreCase = true) }
            ?: matches.firstOrNull { it.loadLabel(packageManager).toString().contains(wanted, ignoreCase = true) }
            ?: return false
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        return try { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); true } catch (_: Exception) { false }
    }

    private fun clickVisibleText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(label)
        val node = matches.firstOrNull { it.isVisibleToUser && it.isClickable }
            ?: matches.firstOrNull { it.isVisibleToUser }?.let { candidate ->
                var parent = candidate.parent
                while (parent != null && !parent.isClickable) parent = parent.parent
                parent
            }
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    companion object {
        @Volatile private var active: ShraddhaAccessibilityService? = null
        fun fillFocusedField(text: String): Boolean = active?.setFocusedText(text) ?: false
        fun clickVisibleText(label: String): Boolean = active?.clickVisibleText(label) ?: false
        fun remoteTap(x: Float, y: Float): Boolean = active?.performRemoteTap(x, y) ?: false
        fun remoteSwipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean = active?.performRemoteSwipe(x1, y1, x2, y2) ?: false
        fun applyRemoteAction(action: JSONObject): Boolean {
            val service = active ?: return false
            return when (action.optString("kind")) {
                "tap" -> service.performRemoteTap(action.optDouble("x").toFloat(), action.optDouble("y").toFloat())
                "swipe" -> service.performRemoteSwipe(action.optDouble("x1").toFloat(), action.optDouble("y1").toFloat(), action.optDouble("x2").toFloat(), action.optDouble("y2").toFloat())
                "type" -> service.setFocusedText(action.optString("text").take(2000))
                "click_text" -> service.clickVisibleText(action.optString("text").take(120))
                "open_app" -> service.openInstalledApp(action.optString("appName").take(80))
                "home" -> service.performGlobalAction(GLOBAL_ACTION_HOME)
                "back" -> service.performGlobalAction(GLOBAL_ACTION_BACK)
                "scroll" -> service.rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                else -> false
            }
        }
        fun scrollForward(): Boolean = active?.rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        fun goBack(): Boolean = active?.performGlobalAction(GLOBAL_ACTION_BACK) == true
    }
}
