package `in`.shraddha.ai.future

/** Rejects internally inconsistent encrypted workflow recovery records before restoring state. */
internal object PersistedWorkflowIntegrityPolicy {
    fun isValid(
        workflowId: String,
        plannedTaskIds: List<String>,
        workflowStepIds: List<String>,
        auditWorkflowIds: List<String>,
        auditActionIds: List<String>,
        auditEvents: List<String>,
        auditDetails: List<String>,
        auditTimestamps: List<Long>
    ): Boolean {
        if (!workflowId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return false
        if (plannedTaskIds.isEmpty() || plannedTaskIds.size > 256) return false
        if (plannedTaskIds != workflowStepIds) return false
        if (plannedTaskIds.any { !it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }) return false
        if (plannedTaskIds.distinct().size != plannedTaskIds.size) return false
        if (auditWorkflowIds.isEmpty() || auditWorkflowIds.size > 2_000) return false
        if (auditWorkflowIds.any { it != workflowId }) return false
        if (auditActionIds.any { it !in plannedTaskIds }) return false
        if (auditEvents.size != auditWorkflowIds.size ||
            auditDetails.size != auditWorkflowIds.size ||
            auditTimestamps.size != auditWorkflowIds.size) return false
        if (auditEvents.any { it.isBlank() || it.length > 80 }) return false
        if (auditDetails.any { it.length > 500 }) return false
        if (auditTimestamps.any { it < 0L }) return false
        return true
    }
}
