package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
