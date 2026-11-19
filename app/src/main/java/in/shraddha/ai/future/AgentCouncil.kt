package `in`.shraddha.ai.future

/** Creates a transparent multi-role work plan. It does not claim to call remote AI agents. */
data class CouncilRole(val name:String, val responsibility:String, val output:String)
data class CouncilPlan(val goal:String, val roles:List<CouncilRole>, val guardrails:List<String>)
object AgentCouncil {
    fun plan(goal:String):CouncilPlan {
        require(goal.isNotBlank()) { "Describe the goal first" }
        return CouncilPlan(goal.trim(), listOf(
            CouncilRole("Planner","Goal ko small, ordered tasks me todna","Milestones, dependencies, acceptance criteria"),
            CouncilRole("Researcher","Reliable information aur sources collect karna","Evidence notes with source links"),
            CouncilRole("Specialist","Domain-specific solution taiyar karna","Draft solution and assumptions"),
            CouncilRole("Critic","Errors, risks aur missing cases dhoondhna","Risk list and corrections"),
            CouncilRole("Verifier","Output ko requirements ke against check karna","Pass/fail checklist and unresolved items")
        ), listOf("External AI agents are not contacted by this local planner.","No message, payment, deletion or device action without explicit approval.","High-risk advice must be checked by a qualified professional."))
    }
}
