package `in`.shraddha.ai.operator

import java.util.UUID

enum class CommandPlanReadiness { READY_FOR_CAPABILITY_CHECK, NEEDS_CLARIFICATION, REQUIRES_APPROVAL, UNSUPPORTED }

data class PlannedCommandStep(
    val id: String,
    val sequence: Int,
    val domain: UnifiedCommandDomain,
    val action: UnifiedCommandAction,
    val commandText: String,
    val confidence: Float,
    val requiresHighImpactApproval: Boolean,
    val needsClarification: Boolean
)

data class PlannedCommand(
    val id: String,
    val originalCommand: String,
    val steps: List<PlannedCommandStep>,
    val readiness: CommandPlanReadiness,
    val explanation: String
)

/**
 * Converts a new Hindi/English/Hinglish instruction into an ordered plan.
 * It does not resolve private targets, request permissions, execute adapters or claim success.
 */
object NaturalLanguageCommandPlanner {
    private val sequenceSeparators = Regex(
        """(?i)\s*(?:;|\n|\bthen\b|\band\s+then\b|\bafter\s+that\b|\bnext\b|फिर|उसके\s+बाद|इसके\s+बाद|और\s+फिर)\s*"""
    )

    fun plan(raw: String): PlannedCommand {
        val command = raw.trim()
        if (command.isEmpty() || command.length > 4_000) {
            return PlannedCommand(UUID.randomUUID().toString(), command.take(4_000), emptyList(),
                CommandPlanReadiness.NEEDS_CLARIFICATION, "कमांड खाली है या निर्धारित सीमा से बड़ी है।")
        }
        val clauses = split(command)
        if (clauses.isEmpty() || clauses.size > MAX_STEPS) {
            return PlannedCommand(UUID.randomUUID().toString(), command, emptyList(),
                CommandPlanReadiness.NEEDS_CLARIFICATION, "एक बार में अधिकतम $MAX_STEPS क्रमिक कार्रवाइयाँ दी जा सकती हैं।")
        }

        var inheritedDomain = UnifiedCommandDomain.UNKNOWN
        val steps = clauses.mapIndexed { index, clause ->
            val intent = UnifiedCommandIntentRouter.classify(clause)
            val domain = if (intent.domain == UnifiedCommandDomain.UNKNOWN) inheritedDomain else intent.domain
            if (intent.domain != UnifiedCommandDomain.UNKNOWN) inheritedDomain = intent.domain
            val inherited = intent.domain == UnifiedCommandDomain.UNKNOWN && domain != UnifiedCommandDomain.UNKNOWN
            PlannedCommandStep(
                id = UUID.randomUUID().toString(),
                sequence = index + 1,
                domain = domain,
                action = intent.action,
                commandText = clause,
                confidence = if (inherited) (intent.confidence * 0.8f).coerceAtMost(0.72f) else intent.confidence,
                requiresHighImpactApproval = intent.requiresHighImpactApproval,
                needsClarification = intent.action == UnifiedCommandAction.UNKNOWN || domain == UnifiedCommandDomain.UNKNOWN
            )
        }

        val readiness = when {
            steps.any { it.needsClarification } -> CommandPlanReadiness.NEEDS_CLARIFICATION
            steps.any { it.requiresHighImpactApproval } -> CommandPlanReadiness.REQUIRES_APPROVAL
            else -> CommandPlanReadiness.READY_FOR_CAPABILITY_CHECK
        }
        val explanation = when (readiness) {
            CommandPlanReadiness.NEEDS_CLARIFICATION -> "कुछ चरणों में ऐप/सेवा या कार्रवाई स्पष्ट नहीं है। निष्पादन से पहले पूछना होगा।"
            CommandPlanReadiness.REQUIRES_APPROVAL -> "योजना में संवेदनशील कार्रवाई है। संबंधित चरण के लिए अलग अनुमति आवश्यक है।"
            CommandPlanReadiness.READY_FOR_CAPABILITY_CHECK -> "क्रमिक योजना बन गई है। अगला चरण उपलब्ध adapter और permissions की जाँच है।"
            CommandPlanReadiness.UNSUPPORTED -> "इस योजना के लिए कोई समर्थित adapter उपलब्ध नहीं है।"
        }
        return PlannedCommand(UUID.randomUUID().toString(), command, steps, readiness, explanation)
    }

    private fun split(command: String): List<String> =
        command.split(sequenceSeparators).map { it.trim() }.filter { it.isNotBlank() }
            .take(MAX_STEPS + 1)

    private const val MAX_STEPS = 8
}
