package `in`.shraddha.ai.future

/** Local-first project planner. It tracks work; it does not execute external actions. */
enum class MilestoneState { TODO, IN_PROGRESS, BLOCKED, DONE }
data class ProjectMilestone(val id:String, val title:String, val state:MilestoneState = MilestoneState.TODO, val dependsOn:List<String> = emptyList(), val note:String = "")
data class ProjectPlan(val id:String, val title:String, val milestones:List<ProjectMilestone>, val createdAt:Long = System.currentTimeMillis())
object ProjectWorkspace {
    fun create(title:String, milestoneTitles:List<String>):ProjectPlan {
        require(title.isNotBlank()) { "Project title is required" }
        val clean=milestoneTitles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(clean.isNotEmpty()) { "Add at least one milestone" }
        return ProjectPlan("p-${System.currentTimeMillis()}",title.trim(),clean.mapIndexed { i,s -> ProjectMilestone("m${i+1}",s) })
    }
    fun update(plan:ProjectPlan, milestoneId:String, state:MilestoneState, note:String = ""):ProjectPlan {
        require(plan.milestones.any { it.id==milestoneId }) { "Milestone not found" }
        val target=plan.milestones.first { it.id==milestoneId }
        require(state!=MilestoneState.DONE || target.dependsOn.all { id -> plan.milestones.any { it.id==id && it.state==MilestoneState.DONE } }) { "Complete dependencies first" }
        return plan.copy(milestones=plan.milestones.map { if(it.id==milestoneId) it.copy(state=state,note=note.take(500)) else it })
    }
    fun progress(plan:ProjectPlan):Int = if(plan.milestones.isEmpty()) 0 else (plan.milestones.count { it.state==MilestoneState.DONE }*100/plan.milestones.size)
}
