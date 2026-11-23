package com.Lia.assistant.ui.screens.expert

import org.junit.Assert.*
import org.junit.Test

class EngineeringCalculationsTest {
    @Test fun calculatesOhmsLawAndPower() {
        val result = EngineeringCalculations.ohmsLaw(12.0, 1000.0, 0.02)!!
        assertEquals(0.012, result.currentFromVoltageAndResistance!!, 1e-9)
        assertEquals(0.144, result.powerFromVoltageAndResistance!!, 1e-9)
        assertEquals(600.0, result.resistanceFromVoltageAndCurrent!!, 1e-9)
        assertEquals(0.24, result.powerFromVoltageAndCurrent!!, 1e-9)
        assertEquals(20.0, result.voltageFromCurrentAndResistance!!, 1e-9)
    }

    @Test fun rejectsNonFiniteNegativeAndZeroResistanceInputs() {
        assertNull(EngineeringCalculations.ohmsLaw(Double.NaN, 100.0, null))
        assertNull(EngineeringCalculations.ohmsLaw(-1.0, 100.0, null))
        assertNull(EngineeringCalculations.ohmsLaw(12.0, 0.0, null))
        assertNull(EngineeringCalculations.ohmsLaw(Double.MAX_VALUE, 1e-300, null))
    }

    @Test fun calculatesLedResistorForSeriesString() {
        val result = EngineeringCalculations.ledSeriesResistor(12.0, 2.0, 20.0, 3)!!
        assertEquals(300.0, result.resistanceOhms, 1e-9)
        assertEquals(0.12, result.resistorPowerWatts, 1e-9)
        assertNull(EngineeringCalculations.ledSeriesResistor(6.0, 2.0, 20.0, 3))
        assertNull(EngineeringCalculations.ledSeriesResistor(12.0, 2.0, 0.0, 1))
        assertNull(EngineeringCalculations.ledSeriesResistor(12.0, 2.0, 20.0, 11))
    }

    @Test fun calculatesDividerWithoutOverflowAndRejectsInvalidValues() {
        val result = EngineeringCalculations.voltageDivider(12.0, 10_000.0, 10_000.0)!!
        assertEquals(6.0, result.outputVolts, 1e-9)
        assertEquals(0.0006, result.currentAmps, 1e-9)
        assertNull(EngineeringCalculations.voltageDivider(12.0, Double.MAX_VALUE, Double.MAX_VALUE))
        assertNull(EngineeringCalculations.voltageDivider(12.0, 0.0, 1000.0))
    }
}
