package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class AgentCouncilExecutionTest {
    @Test fun executesAllFiveRealRolePassesInOrderAndReportsVerifierOutput() {
        val calls = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val result = AgentCouncil.execute(
            goal = "Build a safe task automation workflow",
            language = "Hindi",
            provider = "GEMINI",
            executor = { role, system, prompt ->
                assertTrue(system.contains("Never claim"))
                assertTrue(prompt.contains("USER GOAL"))
                calls += role.name
                "Output from ${role.name}"
            },
            onProgress = { role, index, total ->
                assertEquals(5, total)
                progress += "$index:$role"
            }
        ).getOrThrow()

        assertEquals(listOf("Planner", "Researcher", "Specialist", "Critic", "Verifier"), calls)
        assertEquals(calls, result.stages.map { it.role })
        assertEquals("Output from Verifier", result.finalAnswer)
        assertEquals("GEMINI", result.provider)
        assertTrue(result.completed)
        assertEquals(listOf("1:Planner", "2:Researcher", "3:Specialist", "4:Critic", "5:Verifier"), progress)
    }

    @Test fun stopsAtFirstProviderFailureAndDoesNotClaimCompletion() {
        val calls = mutableListOf<String>()
        val result = AgentCouncil.execute(
            goal = "Research and verify",
            provider = "GROQ",
            executor = { role, _, _ ->
                calls += role.name
                if (role.name == "Researcher") error("provider unavailable")
                "Planner result"
            }
        )

        assertTrue(result.isFailure)
        assertEquals(listOf("Planner", "Researcher"), calls)
    }

    @Test fun configuredProvidersAreAssignedAcrossRolesInStableRoundRobinOrder() {
        val roles = AgentCouncil.plan("Test multi-provider routing").roles
        val assignments = AgentCouncil.assignProviders(roles, listOf("GEMINI", "OPENAI", "GROQ"))
        assertEquals(
            listOf("GEMINI", "OPENAI", "GROQ", "GEMINI", "OPENAI"),
            roles.map { assignments.getValue(it.name) }
        )
    }

    @Test fun providerRoutingRejectsUnknownOrEmptyProviderSets() {
        val roles = AgentCouncil.plan("Test invalid routing").roles
        try {
            AgentCouncil.assignProviders(roles, emptyList())
            throw AssertionError("Empty provider set must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            AgentCouncil.assignProviders(roles, listOf("UNSUPPORTED"))
            throw AssertionError("Unknown provider must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun cancellationStopsBeforeTheNextRole() {
        var keepRunning = true
        val calls = mutableListOf<String>()
        val result = AgentCouncil.execute(
            goal = "Long running work",
            provider = "OPENAI",
            executor = { role, _, _ ->
                calls += role.name
                keepRunning = false
                "Partial planner output"
            },
            shouldContinue = { keepRunning }
        )
        assertTrue(result.isFailure)
        assertEquals(listOf("Planner"), calls)
    }


    @Test fun executeParallelRunsFoundationAndSpecialistsConcurrentlyThenCriticAndVerifier() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val result = AgentCouncil.executeParallel(
            goal = "Design and verify a secure multi-step automation",
            language = "Hindi",
            providerNames = listOf("GEMINI", "OPENAI", "GROQ"),
            specialistCount = 3,
            executor = { role, provider, _, prompt ->
                assertTrue(provider in listOf("GEMINI", "OPENAI", "GROQ"))
                if (role.name.startsWith("Specialist")) {
                    assertTrue(prompt.contains("Planner"))
                    assertTrue(prompt.contains("Researcher"))
                }
                val running = active.incrementAndGet()
                peak.updateAndGet { old -> maxOf(old, running) }
                delay(60)
                calls.add(role.name)
                active.decrementAndGet()
                "Verified stage output: " + role.name
            }
        ).getOrThrow()

        assertTrue("Planner and Researcher must overlap", peak.get() >= 2)
        assertEquals(7, result.stages.size)
        assertEquals("Critic", result.stages[result.stages.size - 2].role)
        assertEquals("Verifier", result.stages.last().role)
        assertTrue(result.stages.take(2).map { it.role }.containsAll(listOf("Planner", "Researcher")))
        assertEquals("Verified stage output: Verifier", result.finalAnswer)
        val firstSpecialist = calls.indexOfFirst { it.startsWith("Specialist") }
        assertTrue(firstSpecialist >= 2)
        assertTrue(calls.indexOf("Critic") > calls.indexOfLast { it.startsWith("Specialist") })
        assertTrue(calls.indexOf("Verifier") > calls.indexOf("Critic"))
    }

    @Test fun executeParallelEnforcesCallBudgetsAndPersistsEveryCompletedStageCallback() = runBlocking {
        val evidence = Collections.synchronizedList(mutableListOf<String>())
        val result = AgentCouncil.executeParallel(
            goal = "Build a verifiable provider workflow",
            providerNames = listOf("GEMINI", "OPENAI"),
            specialistCount = 1,
            executor = { role, provider, _, _ ->
                "$provider output for ${role.name} " + "x".repeat(1400)
            },
            onStageCompleted = { role, provider, output ->
                evidence.add("$role:$provider:${output.length}")
            },
            budget = CouncilExecutionBudget(
                maxProviderCalls = 5,
                maxCallsPerProvider = 3,
                maxDurationMs = 30_000,
                maxOutputCharactersPerStage = 1_000
            )
        ).getOrThrow()

        assertEquals(5, evidence.size)
        assertEquals(5, result.providerUsage.sumOf { it.calls })
        assertTrue(result.providerUsage.all { it.calls <= 3 })
        assertTrue(result.stages.all { it.output.length <= 1_000 })
        assertTrue(result.elapsedMs in 0..30_000)
    }

    @Test fun executeParallelRejectsBudgetsThatCannotCoverRequiredStages() = runBlocking {
        val result = runCatching {
            AgentCouncil.executeParallel(
                goal = "Do not partially execute a task with an impossible budget",
                providerNames = listOf("GEMINI"),
                specialistCount = 1,
                executor = { _, _, _, _ -> "unused" },
                budget = CouncilExecutionBudget(maxProviderCalls = 4)
            )
        }
        assertTrue(result.isFailure)
    }

    @Test fun executeParallelRejectsInvalidSpecialistCountAndProviderList() = runBlocking {
        val invalidCount = runCatching {
            AgentCouncil.executeParallel("Goal", providerNames = listOf("GEMINI"), specialistCount = 0, executor = { _, _, _, _ -> "ok" })
        }
        assertTrue(invalidCount.isFailure)
        val invalidProvider = runCatching {
            AgentCouncil.executeParallel("Goal", providerNames = listOf("UNKNOWN"), specialistCount = 1, executor = { _, _, _, _ -> "ok" })
        }
        assertTrue(invalidProvider.isFailure)
    }

    @Test fun rejectsBlankGoalsAndBoundsTheStoredGoal() {
        assertTrue(AgentCouncil.execute(
            goal = " ",
            provider = "GEMINI",
            executor = { _, _, _ -> "unused" }
        ).isFailure)
        val plan = AgentCouncil.plan("x".repeat(5000))
        assertEquals(4000, plan.goal.length)
        assertFalse(plan.guardrails.isEmpty())
    }
}
