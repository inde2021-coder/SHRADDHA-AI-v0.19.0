package `in`.shraddha.ai.operator

import android.content.Context
import `in`.shraddha.ai.accessibility.ShraddhaAccessibilityService
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Encrypted write-ahead journal for typed Main Brain Operator steps.
 * It stores no typed input value and never authorizes replay by itself.
 */
data class OperatorActionJournalEntry(
    val workflowId: String,
    val stepId: String,
    val action: String,
    val packageName: String,
    val beforeFingerprint: String,
    val expectedVisibleText: String,
    val expectedTextAbsentBefore: Boolean,
    val state: String,
    val updatedAt: Long = System.currentTimeMillis()
)

class OperatorActionJournal(context: Context) {
    private val encrypted = EncryptedFeatureStore(context.applicationContext)

    fun begin(workflowId: String, action: PlannedOperatorAction): Boolean {
        val before = ShraddhaAccessibilityService.captureVisibleScreen() ?: return false
        val expected = action.step.expectedVisibleText.trim().take(160)
        val absentBefore = expected.isNotBlank() && before.elements.none { element ->
            element.text.contains(expected, true) || element.description.contains(expected, true)
        }
        val entry = OperatorActionJournalEntry(
            workflowId = workflowId,
            stepId = action.id,
            action = action.step.action.name,
            packageName = before.packageName,
            beforeFingerprint = fingerprint(before),
            expectedVisibleText = expected,
            expectedTextAbsentBefore = absentBefore,
            state = "EXECUTING"
        )
        return save(entry)
    }

    /** Marks the action only after OperatorEngine independently verifies the resulting UI. */
    fun finish(workflowId: String, action: PlannedOperatorAction, verified: Boolean): Boolean {
        val existing = get(workflowId) ?: return false
        if (existing.stepId != action.id || existing.state != "EXECUTING") return false
        return save(existing.copy(
            state = if (verified) "VERIFIED" else "FAILED",
            updatedAt = System.currentTimeMillis()
        ))
    }

    fun get(workflowId: String): OperatorActionJournalEntry? =
        encrypted.get(key(workflowId)).getOrNull()?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                OperatorActionJournalEntry(
                    workflowId = json.getString("workflowId"),
                    stepId = json.getString("stepId"),
                    action = json.getString("action"),
                    packageName = json.getString("packageName"),
                    beforeFingerprint = json.getString("beforeFingerprint"),
                    expectedVisibleText = json.optString("expectedVisibleText"),
                    expectedTextAbsentBefore = json.optBoolean("expectedTextAbsentBefore"),
                    state = json.getString("state"),
                    updatedAt = json.optLong("updatedAt")
                )
            }.getOrNull()
        }

    /**
     * A process-death step is considered complete only with a newly visible, explicit
     * postcondition on the same app and a changed screen. All other cases need human review.
     */
    fun reconcileInterrupted(workflowId: String): Recovery {
        val entry = get(workflowId) ?: return Recovery.NO_JOURNAL
        if (entry.state == "VERIFIED" || entry.state == "RECOVERED_VERIFIED") return Recovery.VERIFIED_COMPLETE
        if (entry.state != "EXECUTING") return Recovery.NOT_INTERRUPTED
        val current = ShraddhaAccessibilityService.captureVisibleScreen()
            ?: return Recovery.REVIEW_REQUIRED
        val decision = assess(
            entry.packageName,
            entry.beforeFingerprint,
            entry.expectedVisibleText,
            entry.expectedTextAbsentBefore,
            current
        )
        return if (decision == Recovery.VERIFIED_COMPLETE) {
            if (save(entry.copy(state = "RECOVERED_VERIFIED", updatedAt = System.currentTimeMillis()))) {
                Recovery.VERIFIED_COMPLETE
            } else Recovery.REVIEW_REQUIRED
        } else Recovery.REVIEW_REQUIRED
    }

    fun clear(workflowId: String): Boolean = encrypted.remove(key(workflowId))

    private fun save(entry: OperatorActionJournalEntry): Boolean {
        val json = JSONObject()
            .put("workflowId", entry.workflowId)
            .put("stepId", entry.stepId)
            .put("action", entry.action)
            .put("packageName", entry.packageName)
            .put("beforeFingerprint", entry.beforeFingerprint)
            .put("expectedVisibleText", entry.expectedVisibleText.take(160))
            .put("expectedTextAbsentBefore", entry.expectedTextAbsentBefore)
            .put("state", entry.state)
            .put("updatedAt", entry.updatedAt)
        return encrypted.put(key(entry.workflowId), json.toString()).isSuccess
    }

    private fun key(workflowId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(workflowId.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return "operator_action_journal_$digest"
    }

    private fun fingerprint(snapshot: ScreenSnapshot): String {
        val canonical = buildString {
            append(snapshot.packageName).append('|').append(snapshot.activityName)
            snapshot.elements.forEach { element ->
                append('|').append(element.id).append(':').append(element.text).append(':')
                    .append(element.description).append(':').append(element.bounds.flattenToString())
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    enum class Recovery { VERIFIED_COMPLETE, REVIEW_REQUIRED, NO_JOURNAL, NOT_INTERRUPTED }

    companion object {
        internal fun assess(
            expectedPackage: String,
            beforeFingerprint: String,
            expectedText: String,
            expectedTextAbsentBefore: Boolean,
            current: ScreenSnapshot
        ): Recovery {
            val expected = expectedText.trim()
            if (expectedPackage.isBlank() || current.packageName != expectedPackage ||
                beforeFingerprint.isBlank() || fingerprintForTest(current) == beforeFingerprint ||
                expected.isBlank() || !expectedTextAbsentBefore) return Recovery.REVIEW_REQUIRED
            val visible = current.elements.any {
                it.text.contains(expected, true) || it.description.contains(expected, true)
            }
            return if (visible) Recovery.VERIFIED_COMPLETE else Recovery.REVIEW_REQUIRED
        }

        internal fun fingerprintForTest(snapshot: ScreenSnapshot): String {
            val canonical = buildString {
                append(snapshot.packageName).append('|').append(snapshot.activityName)
                snapshot.elements.forEach { element ->
                    append('|').append(element.id).append(':').append(element.text).append(':')
                        .append(element.description).append(':').append(element.bounds.flattenToString())
                }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
