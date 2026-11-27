package `in`.shraddha.ai.future

/**
 * Keeps encrypted audit records within the same bounds enforced by recovery validation.
 * The newest evidence is retained; old entries are evicted instead of making future recovery fail.
 */
internal object WorkflowAuditPolicy {
    const val MAX_EVENTS = 2_000
    const val MAX_EVENT_LENGTH = 80
    const val MAX_DETAIL_LENGTH = 500

    fun append(
        existing: List<ShraddhaIntegrationHub.AuditEvent>,
        next: ShraddhaIntegrationHub.AuditEvent
    ): List<ShraddhaIntegrationHub.AuditEvent> {
        require(next.workflowId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid audit workflow ID." }
        require(next.actionId == null || next.actionId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "Invalid audit action ID."
        }
        require(next.event.isNotBlank()) { "Audit event name cannot be blank." }
        require(next.timestamp >= 0L) { "Audit timestamp cannot be negative." }
        val bounded = next.copy(
            event = next.event.take(MAX_EVENT_LENGTH),
            detail = next.detail.take(MAX_DETAIL_LENGTH)
        )
        return (existing + bounded).takeLast(MAX_EVENTS)
    }
}
