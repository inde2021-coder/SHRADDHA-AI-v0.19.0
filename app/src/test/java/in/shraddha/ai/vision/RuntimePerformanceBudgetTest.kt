package `in`.shraddha.ai.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePerformanceBudgetTest {
    @Test fun heapPressureIsBoundedAndInvalidSignalsFailClosed() {
        assertEquals(0.5f, RuntimePerformanceBudget.memoryPressure(50, 100), 0.001f)
        assertEquals(1f, RuntimePerformanceBudget.memoryPressure(100, 0), 0.001f)
        assertFalse(RuntimePerformanceBudget.allowLocalInference(0.95f))
        assertFalse(RuntimePerformanceBudget.allowLocalInference(Float.NaN))
        assertTrue(RuntimePerformanceBudget.allowLocalInference(0.55f))
    }

    @Test fun localFrameRateSlowsAsMemoryBatteryAndThermalPressureIncrease() {
        assertEquals(2_800L, RuntimePerformanceBudget.localFrameIntervalMs(0.4f, false, RuntimePerformanceBudget.ThermalBand.NORMAL))
        assertEquals(7_000L, RuntimePerformanceBudget.localFrameIntervalMs(0.75f, false, RuntimePerformanceBudget.ThermalBand.NORMAL))
        assertEquals(7_000L, RuntimePerformanceBudget.localFrameIntervalMs(0.4f, true, RuntimePerformanceBudget.ThermalBand.NORMAL))
        assertEquals(12_000L, RuntimePerformanceBudget.localFrameIntervalMs(0.5f, false, RuntimePerformanceBudget.ThermalBand.SEVERE))
    }

    @Test fun cloudAnalysisNeedsConsentAndHealthyDeviceBudget() {
        assertFalse(RuntimePerformanceBudget.allowCloudAnalysis(false, false, 0.3f, RuntimePerformanceBudget.ThermalBand.NORMAL))
        assertFalse(RuntimePerformanceBudget.allowCloudAnalysis(true, true, 0.3f, RuntimePerformanceBudget.ThermalBand.NORMAL))
        assertFalse(RuntimePerformanceBudget.allowCloudAnalysis(true, false, 0.8f, RuntimePerformanceBudget.ThermalBand.NORMAL))
        assertFalse(RuntimePerformanceBudget.allowCloudAnalysis(true, false, 0.3f, RuntimePerformanceBudget.ThermalBand.MODERATE))
        assertTrue(RuntimePerformanceBudget.allowCloudAnalysis(true, false, 0.3f, RuntimePerformanceBudget.ThermalBand.NORMAL))
    }
}
