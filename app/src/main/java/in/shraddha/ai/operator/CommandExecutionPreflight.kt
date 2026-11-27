package `in`.shraddha.ai.operator

data class CommandStepPreflight(
    val step: PlannedCommandStep,
    val capability: CapabilityResolution
)

enum class PreflightState { READY, NEEDS_CLARIFICATION, NEEDS_APPROVAL, NEEDS_PERMISSION, UNSUPPORTED, AMBIGUOUS, BLOCKED }

data class CommandPreflightResult(
    val planId: String,
    val state: PreflightState,
    val steps: List<CommandStepPreflight>,
    val explanation: String
)

/**
 * Performs an all-steps preflight before an executor may begin side effects.
 * It does not execute anything and never downgrades a partially supported plan to success.
 */
class CommandExecutionPreflight(private val registry: CommandCapabilityRegistry) {
    fun inspect(plan: PlannedCommand): CommandPreflightResult {
        if (plan.steps.isEmpty()) return result(plan, PreflightState.NEEDS_CLARIFICATION, emptyList(),
            "कोई स्पष्ट कार्रवाई पहचान में नहीं आई।")
        if (plan.steps.any { it.needsClarification }) return result(plan, PreflightState.NEEDS_CLARIFICATION,
            plan.steps.map { CommandStepPreflight(it, CapabilityResolution.Unsupported(it.domain, it.action)) },
            "पहले अस्पष्ट चरणों की जानकारी चाहिए; कोई कार्रवाई शुरू नहीं हुई।")
        if (plan.steps.any { it.requiresHighImpactApproval }) return result(plan, PreflightState.NEEDS_APPROVAL,
            plan.steps.map { CommandStepPreflight(it, CapabilityResolution.Unsupported(it.domain, it.action)) },
            "योजना में उच्च-प्रभाव वाली कार्रवाई है; अलग अनुमति चाहिए।")

        val resolved = plan.steps.map { step ->
            CommandStepPreflight(step, registry.resolve(step.domain, step.action))
        }
        val state = when {
            resolved.any { it.capability is CapabilityResolution.Ambiguous } -> PreflightState.AMBIGUOUS
            resolved.any { it.capability is CapabilityResolution.Blocked } -> PreflightState.BLOCKED
            resolved.any { it.capability is CapabilityResolution.Unsupported } -> PreflightState.UNSUPPORTED
            resolved.any { it.capability is CapabilityResolution.PermissionRequired } -> PreflightState.NEEDS_PERMISSION
            else -> PreflightState.READY
        }
        val message = when (state) {
            PreflightState.READY -> "सभी चरणों के लिए adapter उपलब्ध हैं। अब अलग execution/approval policy जाँची जा सकती है।"
            PreflightState.NEEDS_PERMISSION -> "कम से कम एक चरण के लिए Android/Windows या account permission चाहिए।"
            PreflightState.UNSUPPORTED -> "कम से कम एक चरण के लिए adapter उपलब्ध नहीं है; आंशिक निष्पादन नहीं होगा।"
            PreflightState.AMBIGUOUS -> "एक से अधिक adapter मिले; सही adapter स्पष्ट किए बिना निष्पादन नहीं होगा।"
            PreflightState.BLOCKED -> "कम से कम एक adapter बंद या blocked है; निष्पादन शुरू नहीं होगा।"
            else -> "योजना अभी निष्पादन के लिए तैयार नहीं है।"
        }
        return result(plan, state, resolved, message)
    }

    private fun result(
        plan: PlannedCommand,
        state: PreflightState,
        steps: List<CommandStepPreflight>,
        explanation: String
    ) = CommandPreflightResult(plan.id, state, steps, explanation)
}
