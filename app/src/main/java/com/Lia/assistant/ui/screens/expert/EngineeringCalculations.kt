package com.Lia.assistant.ui.screens.expert

data class OhmsLawResult(
    val currentFromVoltageAndResistance: Double?,
    val powerFromVoltageAndResistance: Double?,
    val resistanceFromVoltageAndCurrent: Double?,
    val powerFromVoltageAndCurrent: Double?,
    val voltageFromCurrentAndResistance: Double?
)

data class LedResistorResult(val resistanceOhms: Double, val resistorPowerWatts: Double)
data class DividerResult(val outputVolts: Double, val currentAmps: Double, val totalResistanceOhms: Double)

object EngineeringCalculations {
    fun ohmsLaw(voltage: Double?, resistance: Double?, current: Double?): OhmsLawResult? {
        if (voltage == null && resistance == null && current == null) return null
        if (listOfNotNull(voltage, resistance, current).any { !it.isFinite() || it < 0.0 }) return null
        if (resistance != null && resistance <= 0.0) return null
        val i = if (voltage != null && resistance != null) voltage / resistance else null
        val p1 = if (voltage != null && resistance != null) voltage * voltage / resistance else null
        val r = if (voltage != null && current != null && current > 0.0) voltage / current else null
        val p2 = if (voltage != null && current != null) voltage * current else null
        val v = if (current != null && resistance != null) current * resistance else null
        val outputs = listOfNotNull(i, p1, r, p2, v)
        if (outputs.any { !it.isFinite() }) return null
        return OhmsLawResult(i, p1, r, p2, v)
    }

    fun ledSeriesResistor(supplyVolts: Double, forwardVolts: Double, targetCurrentMilliAmps: Double, ledCount: Int = 1): LedResistorResult? {
        if (!supplyVolts.isFinite() || !forwardVolts.isFinite() || !targetCurrentMilliAmps.isFinite()) return null
        if (ledCount !in 1..10 || forwardVolts < 0.0 || targetCurrentMilliAmps <= 0.0 ||
            targetCurrentMilliAmps > 1000.0 || supplyVolts <= ledCount * forwardVolts) return null
        val currentAmps = targetCurrentMilliAmps / 1000.0
        val resistor = (supplyVolts - ledCount * forwardVolts) / currentAmps
        val power = (supplyVolts - ledCount * forwardVolts) * currentAmps
        if (!resistor.isFinite() || resistor <= 0.0 || !power.isFinite() || power <= 0.0) return null
        return LedResistorResult(resistor, power)
    }

    fun voltageDivider(inputVolts: Double, topOhms: Double, bottomOhms: Double): DividerResult? {
        if (!inputVolts.isFinite() || !topOhms.isFinite() || !bottomOhms.isFinite()) return null
        if (inputVolts < 0.0 || topOhms <= 0.0 || bottomOhms <= 0.0) return null
        val total = topOhms + bottomOhms
        if (!total.isFinite() || total <= 0.0) return null
        val output = inputVolts * (bottomOhms / total)
        val current = inputVolts / total
        if (!output.isFinite() || !current.isFinite()) return null
        return DividerResult(output, current, total)
    }
}
