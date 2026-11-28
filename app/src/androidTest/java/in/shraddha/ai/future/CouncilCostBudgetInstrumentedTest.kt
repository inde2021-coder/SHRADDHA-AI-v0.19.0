package `in`.shraddha.ai.future

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.Lia.assistant.voice.ProviderTokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CouncilCostBudgetInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun meteredUsageIsRecordedAndBudgetStopsTheNextCall() {
        val prefs = context.getSharedPreferences("council_cost_budget", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            CouncilCostBudget.saveSettings(
                context,
                0.002,
                """[{"provider":"GEMINI","model":"gemini-2.0-flash","inputUsdPerMillionTokens":1.0,"outputUsdPerMillionTokens":2.0}]"""
            )
            CouncilCostBudget.ensureBudgetAllowsCall(context, "GEMINI")
            CouncilCostBudget.recordUsageAndEnforce(
                context,
                ProviderTokenUsage("GEMINI", "gemini-2.0-flash", 1000, 500)
            )
            assertEquals(0.002, CouncilCostBudget.monthlySpendUsd(context), 0.00001)
            expectIllegalState { CouncilCostBudget.ensureBudgetAllowsCall(context, "GEMINI") }
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun unknownUsageAndUnmeteredFeaturesFailClosedOnlyWhenLimitEnabled() {
        val prefs = context.getSharedPreferences("council_cost_budget", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            CouncilCostBudget.saveSettings(context, 5.0, """[{"provider":"GEMINI","model":"gemini-2.0-flash","inputUsdPerMillionTokens":1.0,"outputUsdPerMillionTokens":2.0}]""")
            expectIllegalState { CouncilCostBudget.requireUsageCountersAvailable(context, "Gemini Live voice") }
            expectIllegalState { CouncilCostBudget.stopIfUsageUnknown(context, "GEMINI", "unknown") }
            expectIllegalState { CouncilCostBudget.ensureBudgetAllowsCall(context, "GEMINI") }
            CouncilCostBudget.saveSettings(context, 0.0, "[]")
            CouncilCostBudget.requireUsageCountersAvailable(context, "Gemini Live voice")
            CouncilCostBudget.ensureBudgetAllowsCall(context, "GEMINI")
            assertTrue(CouncilCostBudget.monthlySpendUsd(context) >= 0.0)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun missingProviderPriceBlocksBeforeNetworkDispatch() {
        val prefs = context.getSharedPreferences("council_cost_budget", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            CouncilCostBudget.saveSettings(context, 10.0, "[]")
            expectIllegalState { CouncilCostBudget.ensureBudgetAllowsCall(context, "OPENAI") }
            CouncilCostBudget.saveSettings(
                context, 10.0,
                """[{"provider":"OPENAI","model":"gpt-test","inputUsdPerMillionTokens":0.0,"outputUsdPerMillionTokens":0.0}]"""
            )
            CouncilCostBudget.ensureBudgetAllowsCall(context, "OPENAI")
        } finally {
            prefs.edit().clear().commit()
        }
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // Expected fail-closed behavior.
        }
    }
}
