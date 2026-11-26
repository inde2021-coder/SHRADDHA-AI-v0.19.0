package `in`.shraddha.ai.brain

import org.junit.Assert.*
import org.junit.Test

class OfflineFirstRoutingPolicyTest {
    private fun state(
        skill: Boolean = false,
        model: Boolean = false,
        knowledge: Boolean = false,
        network: Boolean = true,
        apiEnabled: Boolean = true,
        quota: Boolean = true
    ) = OfflineFirstRoutingPolicy.Availability(skill, model, knowledge, network, apiEnabled, quota)

    @Test fun deterministicLocalSkillWinsBeforeEverything() {
        val result = OfflineFirstRoutingPolicy.choose(
            OfflineFirstRoutingPolicy.TaskKind.GENERAL_CHAT,
            state(skill = true, model = true, knowledge = true)
        )
        assertEquals(OfflineFirstRoutingPolicy.Route.LOCAL_DETERMINISTIC_SKILL, result.route)
        assertFalse(result.maySendUserContentToCloud)
    }

    @Test fun localModelHandlesOrdinaryQuestionsWithoutApi() {
        val result = OfflineFirstRoutingPolicy.choose(
            OfflineFirstRoutingPolicy.TaskKind.LEARNING,
            state(model = true, network = true, apiEnabled = true, quota = true)
        )
        assertEquals(OfflineFirstRoutingPolicy.Route.LOCAL_MODEL, result.route)
        assertFalse(result.maySendUserContentToCloud)
    }

    @Test fun localKnowledgeIsUsedBeforeCloud() {
        val result = OfflineFirstRoutingPolicy.choose(
            OfflineFirstRoutingPolicy.TaskKind.DOCUMENT_QA,
            state(knowledge = true)
        )
        assertEquals(OfflineFirstRoutingPolicy.Route.LOCAL_KNOWLEDGE, result.route)
    }

    @Test fun liveInformationMayUseFreeApiOnlyWhenEnabledAndAvailable() {
        val result = OfflineFirstRoutingPolicy.choose(
            OfflineFirstRoutingPolicy.TaskKind.LIVE_INFORMATION,
            state()
        )
        assertEquals(OfflineFirstRoutingPolicy.Route.FREE_API, result.route)
        assertTrue(result.maySendUserContentToCloud)
    }

    @Test fun noNetworkNeverFallsBackToPaidApi() {
        val result = OfflineFirstRoutingPolicy.choose(
            OfflineFirstRoutingPolicy.TaskKind.CLOUD_ONLY_SPECIALIST,
            state(network = false)
        )
        assertEquals(OfflineFirstRoutingPolicy.Route.EXPLAIN_OFFLINE_LIMIT, result.route)
        assertFalse(result.maySendUserContentToCloud)
    }

    @Test fun disabledFreeApiDoesNotCallProvider() {
        val result = OfflineFirstRoutingPolicy.choose(
            OfflineFirstRoutingPolicy.TaskKind.LIVE_INFORMATION,
            state(apiEnabled = false)
        )
        assertEquals(OfflineFirstRoutingPolicy.Route.EXPLAIN_OFFLINE_LIMIT, result.route)
    }

    @Test fun exhaustedFreeQuotaDoesNotCallProvider() {
        val result = OfflineFirstRoutingPolicy.choose(
            OfflineFirstRoutingPolicy.TaskKind.LIVE_INFORMATION,
            state(quota = false)
        )
        assertEquals(OfflineFirstRoutingPolicy.Route.EXPLAIN_OFFLINE_LIMIT, result.route)
    }
}
