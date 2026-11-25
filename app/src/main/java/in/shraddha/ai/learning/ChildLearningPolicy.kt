package `in`.shraddha.ai.learning

/**
 * Age-aware tutoring flow policy. Content and answer correctness come from the
 * approved curriculum/AI tutor; this class only selects the next teaching step.
 */
object ChildLearningPolicy {
    enum class Step { ASK, HINT, SIMPLE_EXPLANATION, WORKED_EXAMPLE, RETRY, ENCOURAGE_AND_REVIEW }
    enum class AgeBand { PRESCHOOL_3_TO_5, EARLY_PRIMARY_6_TO_8, PRIMARY_9_TO_10 }

    data class Learner(val ageYears: Int, val guardianModeEnabled: Boolean) {
        fun ageBand(): AgeBand? = when (ageYears) {
            in 3..5 -> AgeBand.PRESCHOOL_3_TO_5
            in 6..8 -> AgeBand.EARLY_PRIMARY_6_TO_8
            in 9..10 -> AgeBand.PRIMARY_9_TO_10
            else -> null
        }
    }

    data class Turn(
        val learner: Learner,
        val attemptsOnCurrentConcept: Int,
        val answeredCorrectly: Boolean?,
        val needsAccessibilitySupport: Boolean = false
    )

    data class TeachingPlan(
        val ageBand: AgeBand,
        val nextStep: Step,
        val maxQuestionWords: Int,
        val useConcreteExamples: Boolean,
        val suggestAdultSupport: Boolean
    )

    fun next(turn: Turn): TeachingPlan? {
        val band = turn.learner.ageBand() ?: return null
        if (!turn.learner.guardianModeEnabled || turn.attemptsOnCurrentConcept < 0) return null

        val step = when {
            turn.answeredCorrectly == true -> Step.ENCOURAGE_AND_REVIEW
            turn.answeredCorrectly == null && turn.attemptsOnCurrentConcept == 0 -> Step.ASK
            turn.attemptsOnCurrentConcept == 1 -> Step.HINT
            turn.attemptsOnCurrentConcept == 2 -> Step.SIMPLE_EXPLANATION
            turn.attemptsOnCurrentConcept == 3 -> Step.WORKED_EXAMPLE
            else -> Step.RETRY
        }
        val words = when (band) {
            AgeBand.PRESCHOOL_3_TO_5 -> 8
            AgeBand.EARLY_PRIMARY_6_TO_8 -> 14
            AgeBand.PRIMARY_9_TO_10 -> 22
        }
        return TeachingPlan(
            ageBand = band,
            nextStep = step,
            maxQuestionWords = words,
            useConcreteExamples = band != AgeBand.PRIMARY_9_TO_10 || turn.needsAccessibilitySupport,
            suggestAdultSupport = turn.attemptsOnCurrentConcept >= 4 && turn.answeredCorrectly != true
        )
    }
}
