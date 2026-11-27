package `in`.shraddha.ai.operator

import org.json.JSONObject
import org.json.JSONTokener

/** A single action proposed from the currently visible UI, not an arbitrary device script. */
data class UniversalVisibleStep(val action: OperatorActionType, val target: String = "", val value: String = "", val expectedVisibleText: String = "")

/** Validates model output against the live accessibility snapshot before it can enter the Main Brain queue. */
object UniversalVisibleTaskPlanner {
    data class Result(val steps: List<UniversalVisibleStep> = emptyList(), val error: String? = null)

    fun parseAndValidate(raw: String, userCommand: String, snapshot: ScreenSnapshot): Result {
        if (raw.length !in 1..20_000) return Result(error = "AI plan size invalid है।")
        if (userCommand.isBlank() || userCommand.length > 3000) return Result(error = "User command invalid है।")
        val root = runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull()
            ?: return Result(error = "AI ने valid JSON plan नहीं दिया।")
        val array = root.optJSONArray("steps") ?: return Result(error = "Plan में steps नहीं मिले।")
        if (array.length() !in 1..8) return Result(error = "एक plan में 1 से 8 steps ही स्वीकार हैं।")
        val steps = mutableListOf<UniversalVisibleStep>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return Result(error = "Step $index का format गलत है।")
            val action = runCatching { OperatorActionType.valueOf(item.optString("action").uppercase()) }.getOrNull()
                ?: return Result(error = "Step $index में unsupported action है।")
            val target = item.optString("target").trim()
            val value = item.optString("value")
            val expected = item.optString("expectedVisibleText").trim()
            if (target.length > 120 || value.length > 2000 || expected.length > 120) return Result(error = "Step $index की सीमा पार हुई।")
            if (expected.isNotBlank() && snapshot.elements.any { element ->
                    element.text.contains(expected, true) || element.description.contains(expected, true)
                }) return Result(error = "Step $index का expected result पहले से स्क्रीन पर है; verification अनुमानित नहीं होगा।")
            if (action == OperatorActionType.CLICK_TEXT && isHighImpactTarget(target)) {
                return Result(error = "भुगतान, भेजने, प्रकाशित करने, हटाने या Submit करने वाले बटन universal planner से नहीं चलेंगे।")
            }
            when (action) {
                OperatorActionType.OPEN_APP -> {
                    if (target.isBlank() || !containsMention(userCommand, target)) return Result(error = "ऐप का नाम user command में स्पष्ट नहीं है।")
                }
                OperatorActionType.CLICK_TEXT -> {
                    val matches = snapshot.elements.filter { e ->
                        e.clickable && !e.password && listOf(e.text, e.description)
                            .any { it.isNotBlank() && it.trim().equals(target, true) }
                    }
                    if (target.isBlank() || matches.size != 1) return Result(error = "Step $index का clickable target स्क्रीन पर exact और unique नहीं है।")
                    if (value.isNotEmpty()) return Result(error = "Click step में अतिरिक्त value स्वीकार नहीं है।")
                }
                OperatorActionType.TYPE_TEXT -> {
                    val matches = snapshot.elements.filter { e ->
                        e.editable && !e.password && listOf(e.id, e.text, e.description, e.hintText)
                            .any { it.isNotBlank() && it.trim().equals(target, true) }
                    }
                    if (target.isBlank() || matches.size != 1) return Result(error = "Step $index का editable field exact और unique नहीं है।")
                    if (value.isBlank() || !userCommand.contains(value, ignoreCase = true))
                        return Result(error = "Step $index का typed value user command में नहीं मिला; अनुमानित data रोका गया।")
                }
                OperatorActionType.BACK, OperatorActionType.HOME, OperatorActionType.SCROLL_FORWARD -> {
                    if (target.isNotBlank() || value.isNotBlank()) return Result(error = "Navigation step में target/value नहीं होना चाहिए।")
                }
            }
            steps += UniversalVisibleStep(action, target, value, expected)
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

    private fun containsMention(command: String, target: String): Boolean =
        target.isNotBlank() && command.contains(target, ignoreCase = true)
}
