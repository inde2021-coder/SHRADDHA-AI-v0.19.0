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
import android.graphics.Rect
import `in`.shraddha.ai.operator.ScreenElement
import `in`.shraddha.ai.operator.ScreenSnapshot

class ShraddhaAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Screen content is not uploaded or logged. */ }
    override fun onInterrupt() { }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    private fun isSensitiveNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val labels = listOfNotNull(
            node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString(),
            node.viewIdResourceName, node.parent?.contentDescription?.toString()
        ).joinToString(" ").lowercase()
        return Regex("""(?i)password|passcode|one[ -]?time|otp|pin|cvv|security code|verification code|\\bupi\\b|पासवर्ड|ओटीपी|पिन|गुप्त कोड|सत्यापन कोड""").containsMatchIn(labels)
    }

    private fun setFocusedText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        // Never enter secrets or interact with password/OTP/PIN fields.
        if (isSensitiveNode(focused) || !focused.isEditable || !focused.isVisibleToUser) return false
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value.take(4000))
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
        val wanted = label.trim()
        if (wanted.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(wanted)
            .filter { it.isVisibleToUser && !isSensitiveNode(it) }
        val exact = matches.filter {
            it.text?.toString()?.trim().equals(wanted, true) ||
                it.contentDescription?.toString()?.trim().equals(wanted, true)
        }
        val candidates = (if (exact.isNotEmpty()) exact else matches).mapNotNull { candidate ->
            var node: AccessibilityNodeInfo? = candidate
            while (node != null && !node.isClickable) node = node.parent
            node
        }.distinctBy { it.viewIdResourceName.orEmpty() + ":" + it.text?.toString().orEmpty() + ":" + it.contentDescription?.toString().orEmpty() }
        // Ambiguous labels are refused; the agent must inspect the screen and ask which one.
        if (candidates.size != 1) return false
        return candidates.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickByResourceId(resourceId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!resourceId.contains(":id/")) return false
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(resourceId) }.getOrDefault(emptyList())
        val node = nodes.firstOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
            ?: nodes.firstOrNull { it.isVisibleToUser && it.isEnabled }
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun selectFileInPicker(fileName: String): Boolean {
        if (fileName.isBlank()) return false
        if (clickVisibleText(fileName)) return true
        // Common Android DocumentsUI search flow. Only interacts with a visible search control.
        val openedSearch = clickVisibleText("Search") || clickVisibleText("Search files") || clickVisibleText("Search in")
        if (!openedSearch) return false
        if (!setFocusedText(fileName.take(240))) return false
        try { Thread.sleep(650) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        return clickVisibleText(fileName)
    }

    private fun fillByResourceId(resourceId: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!resourceId.contains(":id/")) return false
        val node = runCatching { root.findAccessibilityNodeInfosByViewId(resourceId) }.getOrDefault(emptyList()).firstOrNull { it.isVisibleToUser && it.isEditable && !isSensitiveNode(it) } ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value.take(4000))
        })
    }

    companion object {
        @Volatile private var active: ShraddhaAccessibilityService? = null
        fun fillFocusedField(text: String): Boolean = active?.setFocusedText(text) ?: false
        fun openApp(name: String): Boolean = active?.openInstalledApp(name) ?: false
        fun goHome(): Boolean = active?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        fun captureVisibleScreen(): ScreenSnapshot? {
            val service = active ?: return null
            val root = service.rootInActiveWindow ?: return null
            val found = mutableListOf<ScreenElement>()
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 180) {
                val node = queue.removeFirst(); count++
                val rect = Rect(); node.getBoundsInScreen(rect)
                if (node.isVisibleToUser && (node.text != null || node.contentDescription != null || node.isClickable || node.isEditable)) {
                    val sensitive = service.isSensitiveNode(node)
                    found.add(ScreenElement(
                        id = node.viewIdResourceName ?: "node_$count",
                        text = if (sensitive) "" else node.text?.toString()?.take(300).orEmpty(),
                        description = if (sensitive) "संवेदनशील फ़ील्ड छिपाया गया" else node.contentDescription?.toString()?.take(300).orEmpty(),
                        className = node.className?.toString().orEmpty(), bounds = Rect(rect),
                        clickable = node.isClickable, editable = node.isEditable,
                        scrollable = node.isScrollable, password = sensitive, packageName = node.packageName?.toString().orEmpty(),
                        hintText = if (sensitive) "" else node.hintText?.toString().orEmpty()
                    ))
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            return ScreenSnapshot(
                packageName = root.packageName?.toString().orEmpty(),
                activityName = root.className?.toString().orEmpty(),
                capturedAt = System.currentTimeMillis(), elements = found
            )
        }
        fun clickVisibleText(label: String): Boolean = active?.clickVisibleText(label) ?: false
        fun clickByResourceId(resourceId: String): Boolean = active?.clickByResourceId(resourceId) ?: false
        fun fillByResourceId(resourceId: String, value: String): Boolean = active?.fillByResourceId(resourceId, value) ?: false
        fun selectFileInPicker(fileName: String): Boolean = active?.selectFileInPicker(fileName) ?: false
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
        fun scrollBackward(): Boolean = active?.rootInActiveWindow?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        fun goBack(): Boolean = active?.performGlobalAction(GLOBAL_ACTION_BACK) == true
    }
}
