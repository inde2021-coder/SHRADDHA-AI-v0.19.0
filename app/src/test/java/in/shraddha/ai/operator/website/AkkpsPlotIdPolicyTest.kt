package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsPlotIdPolicyTest {
    @Test fun acceptsBoundedOpaquePlotIds() {
        assertTrue(AkkpsPlotIdPolicy.isValid("PLOT_2026-17"))
        assertTrue(AkkpsPlotIdPolicy.isValid("  plot-1  "))
        assertTrue(AkkpsPlotIdPolicy.isValid("A".repeat(80)))
    }

    @Test fun rejectsBlankLongOrPathLikePlotIds() {
        listOf("", "   ", "A".repeat(81), "../plot", "plot/1", "plot 1", "plot?x=1").forEach {
            assertFalse("$it must be rejected", AkkpsPlotIdPolicy.isValid(it))
        }
    }
}
