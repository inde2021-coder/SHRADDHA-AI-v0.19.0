package `in`.shraddha.ai.brain

/**
 * Free-first routing decision. This is a pure policy layer; callers still execute the selected
 * engine and must report its real result. It never selects a paid provider.
 */
object OfflineFirstRoutingPolicy {
    enum class TaskKind {
        GENERAL_CHAT, LEARNING, MATH_OR_CONVERSION, PERSONAL_MEMORY, DOCUMENT_QA,
        LIVE_INFORMATION, EXTERNAL_SERVICE_ACTION, CLOUD_ONLY_SPECIALIST
    }

    enum class Route {
        LOCAL_DETERMINISTIC_SKILL, LOCAL_MODEL, LOCAL_KNOWLEDGE,
        FREE_API, EXPLAIN_OFFLINE_LIMIT
    }

    data class Availability(
        val deterministicSkillAvailable: Boolean,
        val localModelAvailable: Boolean,
        val localKnowledgeAvailable: Boolean,
        val networkAvailable: Boolean,
        val freeApiEnabledByUser: Boolean,
        val freeQuotaAvailable: Boolean
    )

    data class Decision(
        val route: Route,
        val reason: String,
        val maySendUserContentToCloud: Boolean
    )

    fun choose(task: TaskKind, state: Availability): Decision {
        val specialOnlineTask = task == TaskKind.LIVE_INFORMATION ||
            task == TaskKind.EXTERNAL_SERVICE_ACTION ||
            task == TaskKind.CLOUD_ONLY_SPECIALIST

        if (!specialOnlineTask && state.deterministicSkillAvailable) {
            return Decision(Route.LOCAL_DETERMINISTIC_SKILL, "A local deterministic skill can handle this task", false)
        }
        if (!specialOnlineTask && state.localModelAvailable) {
            return Decision(Route.LOCAL_MODEL, "Prefer the on-device model to avoid API usage", false)
        }
        if (!specialOnlineTask && state.localKnowledgeAvailable) {
            return Decision(Route.LOCAL_KNOWLEDGE, "Use locally stored, source-grounded knowledge", false)
        }

        if (specialOnlineTask && state.networkAvailable &&
            state.freeApiEnabledByUser && state.freeQuotaAvailable) {
            return Decision(Route.FREE_API, "This task needs an online capability; use an eligible free provider only", true)
        }

        val reason = when {
            specialOnlineTask && !state.networkAvailable -> "This task needs an internet connection"
            specialOnlineTask && !state.freeApiEnabledByUser -> "Online provider use is disabled by the user"
            specialOnlineTask && !state.freeQuotaAvailable -> "No eligible free API quota is available"
            else -> "No suitable local capability is currently available"
        }
        return Decision(Route.EXPLAIN_OFFLINE_LIMIT, reason, false)
    }
}
