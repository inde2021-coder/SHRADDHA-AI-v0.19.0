package `in`.shraddha.ai.future

/** Deterministic workflow lifecycle. External actions must be supplied by a permission-aware executor. */
enum class WorkflowStatus { DRAFT, READY, WAITING_FOR_APPROVAL, RUNNING, VERIFYING, COMPLETED, PAUSED, FAILED, CANCELLED }
data class WorkflowStep(val id:String, val title:String, val risk:Risk, val requiresApproval:Boolean = false)
enum class Risk { READ_ONLY, REVERSIBLE, SENSITIVE, IRREVERSIBLE }
data class WorkflowSnapshot(val id:String, val title:String, val status:WorkflowStatus, val currentStep:Int, val steps:List<WorkflowStep>, val message:String)
class WorkflowEngine {
    fun start(s:WorkflowSnapshot):WorkflowSnapshot {
        require(s.steps.isNotEmpty()) { "Workflow must contain at least one step" }
        require(s.status == WorkflowStatus.DRAFT || s.status == WorkflowStatus.READY || s.status == WorkflowStatus.PAUSED)
        val step=s.steps[s.currentStep.coerceIn(0,s.steps.lastIndex)]
        return s.copy(status=if(step.requiresApproval || step.risk==Risk.SENSITIVE || step.risk==Risk.IRREVERSIBLE) WorkflowStatus.WAITING_FOR_APPROVAL else WorkflowStatus.RUNNING, message="Ready: ${step.title}")
    }
    fun approve(s:WorkflowSnapshot, accepted:Boolean):WorkflowSnapshot {
        require(s.status==WorkflowStatus.WAITING_FOR_APPROVAL)
        return if(accepted) s.copy(status=WorkflowStatus.RUNNING,message="Approval granted for current step") else s.copy(status=WorkflowStatus.PAUSED,message="User declined; no action executed")
    }
    fun markExecuted(s:WorkflowSnapshot):WorkflowSnapshot { require(s.status==WorkflowStatus.RUNNING); return s.copy(status=WorkflowStatus.VERIFYING,message="Action sent; waiting for independent verification") }
    fun markVerified(s:WorkflowSnapshot, success:Boolean):WorkflowSnapshot {
        require(s.status==WorkflowStatus.VERIFYING)
        if(!success) return s.copy(status=WorkflowStatus.FAILED,message="Verification failed; stopped safely")
        val next=s.currentStep+1
        if(next>=s.steps.size) return s.copy(status=WorkflowStatus.COMPLETED,message="All steps verified")
        val step=s.steps[next]
        return s.copy(currentStep=next,status=if(step.requiresApproval || step.risk==Risk.SENSITIVE || step.risk==Risk.IRREVERSIBLE) WorkflowStatus.WAITING_FOR_APPROVAL else WorkflowStatus.RUNNING,message="Next: ${step.title}")
    }
}
