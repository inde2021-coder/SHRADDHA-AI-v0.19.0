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
import android.graphics.Bitmap
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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
        // Sensitive labels can be siblings of the EditText inside TextInputLayout.
        // Inspect a bounded ancestor chain and each ancestor's direct children.
        val nearbyLabels = mutableListOf<String>()
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val ancestor = current ?: return@repeat
            if (ancestor.isPassword) return true
            nearbyLabels += listOfNotNull(
                ancestor.text?.toString(), ancestor.contentDescription?.toString(),
                ancestor.hintText?.toString(), ancestor.viewIdResourceName
            )
            for (index in 0 until ancestor.childCount) {
                val sibling = ancestor.getChild(index) ?: continue
                nearbyLabels += listOfNotNull(
                    sibling.text?.toString(), sibling.contentDescription?.toString(),
                    sibling.hintText?.toString(), sibling.viewIdResourceName
                )
            }
            current = ancestor.parent
        }
        return SensitiveFieldDetector.isSensitive(false, nearbyLabels)
    }

    private fun captureOcrTargetsInternal(done: (Result<List<ScreenOcrEngine.OcrLine>>) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            done(Result.failure(IllegalStateException("On-device OCR screenshot capture requires Android 11 or newer")))
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    if (bitmap == null) {
                        buffer.close()
                        done(Result.failure(IllegalStateException("Android screenshot buffer unavailable")))
                        return
                    }
                    ScreenOcrEngine.recognize(bitmap) { result ->
                        bitmap.recycle()
                        buffer.close()
                        done(result)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    done(Result.failure(IllegalStateException("Android refused screen OCR capture (code $errorCode). Secure screens may block capture.")))
                }
            })
        } catch (error: Exception) {
            done(Result.failure(error))
        }
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

    private fun blocksSensitiveGesture(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val root = rootInActiveWindow ?: return true
        val sensitive = mutableListOf<SensitiveBounds>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && isSensitiveNode(node)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) sensitive += SensitiveBounds(rect.left, rect.top, rect.right, rect.bottom)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        if (queue.isNotEmpty()) return true // Node limit reached: do not risk an uninspected sensitive field.
        return SensitiveScreenGuard.blocksGesture(sensitive, x1, y1, x2, y2)
    }

    private fun performRemoteTap(xNorm: Float, yNorm: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val metrics = resources.displayMetrics
        val x = (xNorm.coerceIn(0f, 1f) * metrics.widthPixels)
        val y = (yNorm.coerceIn(0f, 1f) * metrics.heightPixels)
        if (blocksSensitiveGesture(x, y, x, y)) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performRemoteSwipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val metrics = resources.displayMetrics
        val startX = x1.coerceIn(0f, 1f) * metrics.widthPixels
        val startY = y1.coerceIn(0f, 1f) * metrics.heightPixels
        val endX = x2.coerceIn(0f, 1f) * metrics.widthPixels
        val endY = y2.coerceIn(0f, 1f) * metrics.heightPixels
        if (blocksSensitiveGesture(startX, startY, endX, endY)) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        return dispatchGesture(gesture, null, null)
    }


    private fun openInstalledApp(query: String): Boolean {
        val wanted = query.trim()
        if (wanted.isBlank()) return false
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(launcher, 0)
        val exactPackages = matches.filter {
            it.loadLabel(packageManager).toString().trim().equals(wanted, ignoreCase = true)
        }.map { it.activityInfo.packageName }.distinct()
        val selectedPackage = when {
            exactPackages.size == 1 -> exactPackages.single()
            exactPackages.size > 1 -> return false
            else -> {
                val partialPackages = matches.filter {
                    it.loadLabel(packageManager).toString().contains(wanted, ignoreCase = true)
                }.map { it.activityInfo.packageName }.distinct()
                if (partialPackages.size != 1) return false
                partialPackages.single()
            }
        }
        val launch = packageManager.getLaunchIntentForPackage(selectedPackage) ?: return false
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
        }.distinctBy { node ->
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            listOf(node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
                node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
                bounds.left, bounds.top, bounds.right, bounds.bottom).joinToString(":")
        }
        // Ambiguous labels are refused; the agent must inspect the screen and ask which one.
        if (candidates.size != 1) return false
        return candidates.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickByResourceId(resourceId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!resourceId.contains(":id/")) return false
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(resourceId) }.getOrDefault(emptyList())
        val candidates = nodes.filter { it.isVisibleToUser && it.isEnabled && it.isClickable }
        // A repeated resource ID is not enough to identify a unique control.
        if (candidates.size != 1) return false
        return candidates.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)
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

    /**
     * Fill only one explicitly identified visible field. A blank or ambiguous target
     * is refused; this prevents dictation from landing in an unrelated form field.
     */
    private fun fillEditableField(target: String, value: String): Boolean {
        val wanted = target.trim()
        if (wanted.isBlank() || value.length > 4000) return false
        val root = rootInActiveWindow ?: return false
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && node.isEditable && !isSensitiveNode(node)) {
                val labels = listOf(
                    node.viewIdResourceName.orEmpty(),
                    node.hintText?.toString().orEmpty(),
                    node.text?.toString().orEmpty(),
                    node.contentDescription?.toString().orEmpty()
                )
                if (labels.any { it.isNotBlank() && it.trim().equals(wanted, ignoreCase = true) }) {
                    candidates += node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        if (candidates.size != 1) return false
        return candidates.single().performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
        )
    }

    private fun fillByResourceId(resourceId: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!resourceId.contains(":id/")) return false
        val candidates = runCatching { root.findAccessibilityNodeInfosByViewId(resourceId) }
            .getOrDefault(emptyList())
            .filter { it.isVisibleToUser && it.isEditable && !isSensitiveNode(it) }
        // Never type into the first of several fields sharing the same resource ID.
        if (candidates.size != 1) return false
        return candidates.single().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value.take(4000))
        })
    }

    companion object {
        @Volatile private var active: ShraddhaAccessibilityService? = null
        suspend fun captureOcrTargets(): List<ScreenOcrEngine.OcrLine>? = suspendCancellableCoroutine { continuation ->
            val service = active
            if (service == null) {
                continuation.resume(null)
            } else {
                service.captureOcrTargetsInternal { result ->
                    if (continuation.isActive) continuation.resume(result.getOrNull())
                }
            }
        }
        suspend fun captureOcrText(): String? =
            captureOcrTargets()?.filterNot { it.sensitive }?.joinToString("\n") { it.text }
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
        fun fillEditableField(target: String, value: String): Boolean = active?.fillEditableField(target, value) ?: false
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
