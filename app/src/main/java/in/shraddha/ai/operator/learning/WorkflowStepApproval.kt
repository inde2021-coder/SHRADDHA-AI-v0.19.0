package `in`.shraddha.ai.operator.learning

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time approval for one exact learned-workflow step.
 * A bare step index is never an authorization credential.
 */
class WorkflowStepApproval private constructor(
    private val fingerprint: String,
    private val issuedAtElapsedMs: Long,
    private val nonce: String
) {
    private val consumed = AtomicBoolean(false)

    internal fun consume(
        packageName: String,
        workflowName: String,
        index: Int,
        action: String,
        target: String,
        resolvedInput: String,
        expectedText: String,
        nowElapsedMs: Long
    ): Boolean {
        val age = nowElapsedMs - issuedAtElapsedMs
        if (age !in 0..MAX_AGE_MS || nonce.isBlank()) return false
        if (fingerprint != fingerprint(packageName, workflowName, index, action, target, resolvedInput, expectedText)) return false
        return consumed.compareAndSet(false, true)
    }

    companion object {
        private const val MAX_AGE_MS = 30_000L

        internal fun afterExplicitUserApproval(
            packageName: String,
            workflowName: String,
            index: Int,
            action: String,
            target: String,
            resolvedInput: String,
            expectedText: String,
            nowElapsedMs: Long = System.nanoTime() / 1_000_000L
        ) = WorkflowStepApproval(
            fingerprint(packageName, workflowName, index, action, target, resolvedInput, expectedText),
            nowElapsedMs,
            UUID.randomUUID().toString()
        )

        private fun fingerprint(
            packageName: String,
            workflowName: String,
            index: Int,
            action: String,
            target: String,
            resolvedInput: String,
            expectedText: String
        ): String {
            val canonical = listOf(packageName, workflowName, index.toString(), action, target, resolvedInput, expectedText)
                .joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
