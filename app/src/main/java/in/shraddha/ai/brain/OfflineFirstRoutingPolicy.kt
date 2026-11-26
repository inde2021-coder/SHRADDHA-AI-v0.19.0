package `in`.shraddha.ai.brain

/**
 * Free-first routing decision. Callers execute the selected engine and report its real result.
 * This policy never selects a paid provider and never authorizes cloud use unless the user
 * enabled free API access and an eligible free quota is available.
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
            return Decision(
                Route.LOCAL_DETERMINISTIC_SKILL,
                "A local deterministic skill can handle this task",
                false
            )
        }

        // Document and personal-memory questions must prefer the encrypted local knowledge
        // store over a generative model so that source-grounded private data stays on-device.
        val knowledgeFirst = task == TaskKind.DOCUMENT_QA || task == TaskKind.PERSONAL_MEMORY
        if (!specialOnlineTask && knowledgeFirst && state.localKnowledgeAvailable) {
            return Decision(
                Route.LOCAL_KNOWLEDGE,
                "Use locally stored, source-grounded knowledge",
                false
            )
        }

        if (!specialOnlineTask && state.localModelAvailable) {
            return Decision(
                Route.LOCAL_MODEL,
                "Prefer the on-device model to avoid API usage",
                false
            )
        }
        if (!specialOnlineTask && state.localKnowledgeAvailable) {
            return Decision(
                Route.LOCAL_KNOWLEDGE,
                "Use locally stored, source-grounded knowledge",
                false
            )
        }

        // Free API is a last resort for every task, not just live tasks. Paid providers are
        // never selected here. Cloud use requires network, explicit user opt-in, and free quota.
        if (state.networkAvailable && state.freeApiEnabledByUser && state.freeQuotaAvailable) {
            return Decision(
                Route.FREE_API,
                if (specialOnlineTask) {
                    "This task needs an online capability; use an eligible free provider only"
                } else {
                    "No suitable local capability is available; use an eligible free provider only"
                },
                true
            )
        }

        val reason = when {
            !state.networkAvailable -> "No local capability is available and the device is offline"
            !state.freeApiEnabledByUser -> "No local capability is available; online provider use is disabled"
            !state.freeQuotaAvailable -> "No local capability is available and no eligible free API quota remains"
            else -> "No suitable capability is currently available"
        }
        return Decision(Route.EXPLAIN_OFFLINE_LIMIT, reason, false)
    }
}
