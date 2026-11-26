package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CouncilCostEstimatorTest {
    @Test
    fun providerBudgetAllowsCallsWhenNoCeilingIsConfigured() {
        assertNull(CouncilCostBudget.requestBlockReason(0.0, 500.0, false, "GEMINI"))
    }

    @Test
    fun providerBudgetBlocksAtOrAboveTheConfiguredCeiling() {
        assertTrue(CouncilCostBudget.requestBlockReason(5.0, 5.0, false, "GEMINI")!!.contains("budget reached"))
        assertTrue(CouncilCostBudget.requestBlockReason(5.0, 6.0, false, "GEMINI")!!.contains("budget reached"))
    }

    @Test
    fun providerBudgetBlocksWhenPreviousUsageIsUnknown() {
        assertTrue(CouncilCostBudget.requestBlockReason(5.0, 1.0, true, "OPENAI")!!.contains("unverified"))
    }

    @Test
    fun providerBudgetAllowsKnownUsageBelowCeiling() {
        assertNull(CouncilCostBudget.requestBlockReason(5.0, 4.99, false, "GROQ"))
    }

    @Test
    fun estimatesInputAndOutputTokensUsingExplicitModelRates() {
        val estimate = CouncilCostEstimator.estimate(
            usage = listOf(CouncilCostEstimator.TokenUsage("OPENAI", "gpt-example", 2_000_000, 500_000)),
            prices = listOf(CouncilCostEstimator.ModelPrice("openai", "GPT-EXAMPLE", 2.0, 8.0)),
            budgetUsd = 10.0
        )
        assertEquals(8.0, estimate.totalUsd!!, 0.0000001)
        assertEquals(8.0, estimate.knownSubtotalUsd, 0.0000001)
        assertTrue(estimate.withinBudget!!)
        assertTrue(estimate.unpricedUsage.isEmpty())
    }

    @Test
    fun missingModelPriceNeverLooksLikeZeroCost() {
        val estimate = CouncilCostEstimator.estimate(
            usage = listOf(CouncilCostEstimator.TokenUsage("CLAUDE", "unknown-model", 10_000, 2_000)),
            prices = emptyList(),
            budgetUsd = 0.0
        )
        assertNull(estimate.totalUsd)
        assertNull(estimate.withinBudget)
        assertEquals(1, estimate.unpricedUsage.size)
        assertEquals(0.0, estimate.knownSubtotalUsd, 0.0)
    }

    @Test
    fun mixedUsageReportsOnlyKnownSubtotalAndWithholdsTotal() {
        val estimate = CouncilCostEstimator.estimate(
            usage = listOf(
                CouncilCostEstimator.TokenUsage("GEMINI", "model-a", 1_000_000, 0),
                CouncilCostEstimator.TokenUsage("GROQ", "model-b", 1_000_000, 1_000_000)
            ),
            prices = listOf(CouncilCostEstimator.ModelPrice("GEMINI", "model-a", 0.5, 1.0)),
            budgetUsd = 1.0
        )
        assertNull(estimate.totalUsd)
        assertNull(estimate.withinBudget)
        assertEquals(0.5, estimate.knownSubtotalUsd, 0.0000001)
        assertEquals(1, estimate.unpricedUsage.size)
    }

    @Test
    fun overBudgetIsExplicit() {
        val estimate = CouncilCostEstimator.estimate(
            usage = listOf(CouncilCostEstimator.TokenUsage("GROQ", "model", 0, 1_000_000)),
            prices = listOf(CouncilCostEstimator.ModelPrice("GROQ", "model", 0.0, 3.0)),
            budgetUsd = 2.99
        )
        assertFalse(estimate.withinBudget!!)
        assertEquals(3.0, estimate.totalUsd!!, 0.0000001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicatePriceEntriesAreRejected() {
        val price = CouncilCostEstimator.ModelPrice("GEMINI", "model", 1.0, 1.0)
        CouncilCostEstimator.estimate(emptyList(), listOf(price, price))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTokenCountsAreRejected() {
        CouncilCostEstimator.estimate(
            listOf(CouncilCostEstimator.TokenUsage("GEMINI", "model", -1, 0)),
            emptyList()
        )
    }
}
