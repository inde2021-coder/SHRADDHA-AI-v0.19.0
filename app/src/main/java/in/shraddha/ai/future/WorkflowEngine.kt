package `in`.shraddha.ai.future

/** Deterministic workflow lifecycle. External actions must be supplied by a permission-aware executor. */
enum class WorkflowStatus {
    DRAFT, READY, WAITING_FOR_APPROVAL, RUNNING, VERIFYING, COMPLETED, PAUSED, FAILED, CANCELLED
}

data class WorkflowStep(
    val id: String,
    val title: String,
    val risk: Risk,
    val requiresApproval: Boolean = false
)

enum class Risk { READ_ONLY, REVERSIBLE, SENSITIVE, IRREVERSIBLE }

data class WorkflowSnapshot(
    val id: String,
    val title: String,
    val status: WorkflowStatus,
    val currentStep: Int,
    val steps: List<WorkflowStep>,
    val message: String
)

/**
 * State-only engine: it never performs Android/app actions itself.
 * A host executor must check permissions again immediately before each action.
 */
class WorkflowEngine {
    fun start(s: WorkflowSnapshot): WorkflowSnapshot {
        validate(s)
        require(s.status == WorkflowStatus.DRAFT || s.status == WorkflowStatus.READY || s.status == WorkflowStatus.PAUSED) {
            "Workflow cannot start from ${s.status}"
        }
        return enterStep(s)
    }

    fun resume(s: WorkflowSnapshot): WorkflowSnapshot {
        validate(s)
        require(s.status == WorkflowStatus.PAUSED) { "Only a paused workflow can resume" }
        return enterStep(s)
    }

    fun approve(s: WorkflowSnapshot, accepted: Boolean): WorkflowSnapshot {
        validate(s)
        require(s.status == WorkflowStatus.WAITING_FOR_APPROVAL) { "Workflow is not waiting for approval" }
        return if (accepted) {
            s.copy(status = WorkflowStatus.RUNNING, message = "Approval granted for current step")
        } else {
            s.copy(status = WorkflowStatus.PAUSED, message = "User declined; no action executed")
        }
    }

    fun pause(s: WorkflowSnapshot, reason: String = "Paused by user"): WorkflowSnapshot {
        validate(s)
        require(s.status == WorkflowStatus.RUNNING || s.status == WorkflowStatus.WAITING_FOR_APPROVAL) {
            "Only an active workflow can be paused"
        }
        return s.copy(status = WorkflowStatus.PAUSED, message = reason.take(240))
    }

    fun cancel(s: WorkflowSnapshot): WorkflowSnapshot {
        validate(s)
        require(s.status !in setOf(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.CANCELLED)) {
            "Workflow is already terminal"
        }
        return s.copy(status = WorkflowStatus.CANCELLED, message = "Cancelled; no further steps will run")
    }

    fun markExecuted(s: WorkflowSnapshot): WorkflowSnapshot {
        validate(s)
        require(s.status == WorkflowStatus.RUNNING) { "Workflow step is not approved to execute" }
        return s.copy(status = WorkflowStatus.VERIFYING, message = "Action sent; waiting for independent verification")
    }

    fun markVerified(s: WorkflowSnapshot, success: Boolean): WorkflowSnapshot {
        validate(s)
        require(s.status == WorkflowStatus.VERIFYING) { "Workflow step is not awaiting verification" }
        if (!success) return s.copy(status = WorkflowStatus.FAILED, message = "Verification failed; stopped safely")

        val next = s.currentStep + 1
        if (next >= s.steps.size) return s.copy(status = WorkflowStatus.COMPLETED, message = "All steps verified")
        return enterStep(s.copy(currentStep = next))
    }

    private fun enterStep(s: WorkflowSnapshot): WorkflowSnapshot {
        val step = s.steps[s.currentStep]
        val approval = step.requiresApproval || step.risk == Risk.SENSITIVE || step.risk == Risk.IRREVERSIBLE
        return s.copy(
            status = if (approval) WorkflowStatus.WAITING_FOR_APPROVAL else WorkflowStatus.RUNNING,
            message = if (approval) "Approval required: ${step.title}" else "Ready: ${step.title}"
        )
    }

    private fun validate(s: WorkflowSnapshot) {
        require(s.id.isNotBlank()) { "Workflow ID is required" }
        require(s.title.isNotBlank()) { "Workflow title is required" }
        require(s.steps.isNotEmpty()) { "Workflow must contain at least one step" }
        require(s.currentStep in s.steps.indices) { "Current step is outside the workflow" }
        require(s.steps.all { it.id.isNotBlank() && it.title.isNotBlank() }) { "Every step needs an ID and title" }
        require(s.steps.map { it.id }.distinct().size == s.steps.size) { "Workflow step IDs must be unique" }
    }
    fun markFailed(s: WorkflowSnapshot, reason: String): WorkflowSnapshot {
        require(s.status == WorkflowStatus.RUNNING || s.status == WorkflowStatus.VERIFYING)
        return s.copy(status = WorkflowStatus.FAILED, message = reason.take(240))
    }
}

