package `in`.shraddha.ai.operator

import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** A single action proposed from the current UI; future targets are re-resolved at dispatch time. */
data class UniversalVisibleStep(
    val action: OperatorActionType,
    val target: String = "",
    val value: String = "",
    val expectedVisibleText: String = "",
    val explicitlyAuthorizedByUser: Boolean = false,
    val requiresRuntimeTargetResolution: Boolean = false
)

/** Validates model output against observed UI evidence and the literal user command. */
object UniversalVisibleTaskPlanner {
    data class Result(val steps: List<UniversalVisibleStep> = emptyList(), val error: String? = null)

    fun parseAndValidate(raw: String, userCommand: String, snapshot: ScreenSnapshot): Result {
        if (raw.length !in 1..20_000) return Result(error = "AI plan size invalid है।")
        if (userCommand.isBlank() || userCommand.length > 3000) return Result(error = "User command invalid है।")
        val tokener = JSONTokener(raw)
        val root = runCatching { tokener.nextValue() as? JSONObject }.getOrNull()
            ?: return Result(error = "AI ने valid JSON plan नहीं दिया।")
        if (runCatching { tokener.nextClean() != '\u0000' }.getOrDefault(true)) {
            return Result(error = "AI plan में JSON के बाद अतिरिक्त सामग्री मिली।")
        }
        val array = root.optJSONArray("steps") ?: return Result(error = "Plan में steps नहीं मिले।")
        if (array.length() !in 1..8) return Result(error = "एक plan में 1 से 8 steps ही स्वीकार हैं।")

        val steps = mutableListOf<UniversalVisibleStep>()
        var screenMayHaveChanged = false
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return Result(error = "Step $index का format गलत है।")
            val action = runCatching { OperatorActionType.valueOf(item.optString("action").uppercase(Locale.ROOT)) }.getOrNull()
                ?: return Result(error = "Step $index में unsupported action है।")
            val target = item.optString("target").trim()
            val value = item.optString("value")
            val expected = item.optString("expectedVisibleText").trim()
            if (target.length > 120 || value.length > 2000 || expected.length > 120) {
                return Result(error = "Step $index की सीमा पार हुई।")
            }
            if (!screenMayHaveChanged && expected.isNotBlank() && snapshot.elements.any { element ->
                    element.text.contains(expected, true) || element.description.contains(expected, true)
                }) return Result(error = "Step $index का expected result पहले से स्क्रीन पर है; verification अनुमानित नहीं होगा।")

            var explicitlyAuthorized = false
            var deferredTarget = false
            when (action) {
                OperatorActionType.OPEN_APP -> {
                    if (target.isBlank() || !containsMention(userCommand, target)) {
                        return Result(error = "ऐप का नाम user command में स्पष्ट नहीं है।")
                    }
                    if (value.isNotEmpty()) return Result(error = "OPEN_APP step में अतिरिक्त value स्वीकार नहीं है।")
                }
                OperatorActionType.CLICK_TEXT -> {
                    val matches = snapshot.elements.filter { e ->
                        e.clickable && !e.password && listOf(e.text, e.description)
                            .any { it.isNotBlank() && it.trim().equals(target, true) }
                    }
                    if (target.isBlank()) return Result(error = "Step $index का clickable target खाली है।")
                    if (matches.size != 1 && !screenMayHaveChanged) {
                        return Result(error = "Step $index का clickable target स्क्रीन पर exact और unique नहीं है।")
                    }
                    // After any prior action that can change the screen, even a target that
                    // existed in the old snapshot must be re-resolved from the live UI.
                    deferredTarget = screenMayHaveChanged || matches.size != 1
                    if (value.isNotEmpty()) return Result(error = "Click step में अतिरिक्त value स्वीकार नहीं है।")
                    if (isHighImpactTarget(target)) {
                        explicitlyAuthorized = isExplicitSendSequence(userCommand, target, expected, steps)
                        if (!explicitlyAuthorized) {
                            return Result(error = "भेजने/हटाने/भुगतान/प्रकाशित/Submit जैसे final actions के लिए यह plan पर्याप्त explicit नहीं है।")
                        }
                    }
                }
                OperatorActionType.SELECT_FILE -> {
                    if (target.isBlank() || !containsMention(userCommand, target)) {
                        return Result(error = "चुनी जाने वाली file का exact नाम user command में स्पष्ट नहीं है।")
                    }
                    if (value.isNotBlank()) return Result(error = "SELECT_FILE step में अतिरिक्त value स्वीकार नहीं है।")
                    if (expected.isBlank() || !expected.equals(target, true)) {
                        return Result(error = "चुनी गई file का exact नाम post-selection verification में देना आवश्यक है।")
                    }
                    deferredTarget = screenMayHaveChanged
                }
                OperatorActionType.TYPE_TEXT -> {
                    val matches = snapshot.elements.filter { e ->
                        e.editable && !e.password && listOf(e.id, e.text, e.description, e.hintText)
                            .any { it.isNotBlank() && it.trim().equals(target, true) }
                    }
                    if (target.isBlank()) return Result(error = "Step $index का editable field target खाली है।")
                    if (matches.size != 1 && !screenMayHaveChanged) {
                        return Result(error = "Step $index का editable field exact और unique नहीं है।")
                    }
                    // Never reuse a stale editable target after a screen transition.
                    deferredTarget = screenMayHaveChanged || matches.size != 1
                    if (value.isBlank() || !userCommand.contains(value, ignoreCase = true)) {
                        return Result(error = "Step $index का typed value user command में नहीं मिला; अनुमानित data रोका गया।")
                    }
                }
                OperatorActionType.BACK, OperatorActionType.HOME, OperatorActionType.SCROLL_FORWARD, OperatorActionType.SCROLL_BACKWARD -> {
                    if (target.isNotBlank() || value.isNotBlank()) {
                        return Result(error = "Navigation step में target/value नहीं होना चाहिए।")
                    }
                }
            }
            steps += UniversalVisibleStep(
                action = action,
                target = target,
                value = value,
                expectedVisibleText = expected,
                explicitlyAuthorizedByUser = explicitlyAuthorized,
                requiresRuntimeTargetResolution = deferredTarget
            )
            if (action in setOf(
                    OperatorActionType.OPEN_APP, OperatorActionType.CLICK_TEXT,
                    OperatorActionType.BACK, OperatorActionType.SCROLL_FORWARD, OperatorActionType.SCROLL_BACKWARD, OperatorActionType.SELECT_FILE
                )) screenMayHaveChanged = true
        }
        return Result(steps = steps)
    }

    private fun isHighImpactTarget(value: String): Boolean {
        val normalized = value.lowercase().trim()
        val blocked = listOf(
            "delete", "remove", "send", "pay", "transfer", "purchase", "buy",
            "publish", "post", "submit", "confirm", "place order",
            "हटाओ", "मिटाओ", "भुगतान", "पैसे भेज", "खरीद", "पोस्ट करो", "भेजो", "पुष्टि करो", "जमा करो", "सबमिट"
        )
        return blocked.any { normalized.contains(it) }
    }

    private fun isExplicitSendSequence(
        command: String,
        sendTarget: String,
        expected: String,
        previous: List<UniversalVisibleStep>
    ): Boolean {
        val sendLabels = setOf("send", "send message", "भेजें", "भेजो", "सेंड")
        if (sendTarget.lowercase().trim() !in sendLabels) return false
        val explicitVerb = listOf("send", "भेजो", "भेजना", "भेजें", "सेंड").any {
            command.contains(it, ignoreCase = true)
        }
        if (!explicitVerb || expected.isBlank()) return false
        val messageWasTyped = previous.any {
            it.action == OperatorActionType.TYPE_TEXT && it.value.isNotBlank() &&
                it.value == expected && command.contains(it.value, ignoreCase = true)
        }
        val navigationLabels = setOf("chats", "search", "messages", "contacts", "खोज", "चैट", "संदेश")
        val explicitRecipientWasSelected = previous.any {
            it.action == OperatorActionType.CLICK_TEXT &&
                it.target.lowercase().trim() !in navigationLabels &&
                containsMention(command, it.target)
        }
        return messageWasTyped && explicitRecipientWasSelected
    }

    private fun containsMention(command: String, target: String): Boolean =
        target.isNotBlank() && command.contains(target, ignoreCase = true)
}
