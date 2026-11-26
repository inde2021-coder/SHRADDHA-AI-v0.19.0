package `in`.shraddha.ai.vision

/** Conservative memory/thermal budget for continuous on-device vision workloads. */
object RuntimePerformanceBudget {
    enum class ThermalBand { NORMAL, MODERATE, SEVERE, CRITICAL }

    fun memoryPressure(usedBytes: Long, maxBytes: Long): Float {
        if (maxBytes <= 0L || usedBytes < 0L) return 1f
        return (usedBytes.toDouble() / maxBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    fun allowLocalInference(memoryPressure: Float, thermal: ThermalBand = ThermalBand.NORMAL): Boolean {
        if (!memoryPressure.isFinite() || memoryPressure >= 0.90f) return false
        return thermal != ThermalBand.CRITICAL
    }

    fun localFrameIntervalMs(
        memoryPressure: Float,
        batterySaver: Boolean,
        thermal: ThermalBand
    ): Long = when {
        !memoryPressure.isFinite() || memoryPressure >= 0.85f || thermal == ThermalBand.SEVERE || thermal == ThermalBand.CRITICAL -> 12_000L
        batterySaver || memoryPressure >= 0.72f || thermal == ThermalBand.MODERATE -> 7_000L
        else -> 2_800L
    }

    fun allowCloudAnalysis(
        userConsent: Boolean,
        batterySaver: Boolean,
        memoryPressure: Float,
        thermal: ThermalBand
    ): Boolean = userConsent && !batterySaver &&
        memoryPressure.isFinite() && memoryPressure < 0.72f &&
        thermal == ThermalBand.NORMAL
}
