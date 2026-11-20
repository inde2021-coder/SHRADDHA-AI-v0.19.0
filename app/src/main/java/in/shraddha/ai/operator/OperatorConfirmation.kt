package \`in\`.shraddha.ai.operator

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A short-lived, single-use approval bound to the exact operation the user saw.
 * It cannot be reused for a different target/value or replayed after 30 seconds.
 */
class OperatorConfirmation private constructor(
    private val fingerprint: String,
    private val issuedAtElapsedMs: Long,
    private val nonce: String
) {
    private val consumed = AtomicBoolean(false)

    internal fun consume(step: OperatorStep, nowElapsedMs: Long): Boolean {
        val age = nowElapsedMs - issuedAtElapsedMs
        if (age !in 0..MAX_AGE_MS) return false
        if (fingerprint != fingerprint(step)) return false
        if (nonce.isBlank()) return false
        return consumed.compareAndSet(false, true)
    }

    companion object {
        private const val MAX_AGE_MS = 30_000L

        /** Call only from the positive button handler after a visible user approval dialog. */
        fun afterExplicitUserApproval(
            step: OperatorStep,
            nowElapsedMs: Long = System.nanoTime() / 1_000_000L
        ): OperatorConfirmation = OperatorConfirmation(
            fingerprint(step), nowElapsedMs, UUID.randomUUID().toString()
        )

        private fun fingerprint(step: OperatorStep): String {
            val canonical = listOf(
                step.action.name, step.target, step.value,
                step.expectedVisibleText, step.requiresConfirmation.toString()
            ).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
