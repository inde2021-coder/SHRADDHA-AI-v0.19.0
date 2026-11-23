package com.Lia.assistant.ui.screens.expert

import org.junit.Assert.*
import org.junit.Test

class CircuitDcSolverTest {
    @Test fun solvesResistiveVoltageDivider() {
        val result = CircuitDcSolver.solve("""
            V1 VIN 0 DC 12
            R1 VIN VOUT 10k
            R2 VOUT GND 10k
            .op
            .end
        """.trimIndent()).getOrThrow()
        assertEquals(12.0, result.nodeVoltages.getValue("VIN"), 1e-8)
        assertEquals(6.0, result.nodeVoltages.getValue("VOUT"), 1e-8)
        assertEquals(-0.0006, result.voltageSourceCurrents.getValue("V1"), 1e-8)
    }

    @Test fun solvesCurrentSourceThroughResistor() {
        val result = CircuitDcSolver.solve("I1 0 OUT DC 2m\nR1 OUT 0 1k").getOrThrow()
        assertEquals(2.0, result.nodeVoltages.getValue("OUT"), 1e-8)
    }

    @Test fun supportsMultipleVoltageSourcesAndMegaSuffix() {
        val result = CircuitDcSolver.solve("V1 A 0 5\nV2 B 0 3\nR1 A B 1meg\nR2 B 0 1meg").getOrThrow()
        assertEquals(5.0, result.nodeVoltages.getValue("A"), 1e-8)
        assertEquals(3.0, result.nodeVoltages.getValue("B"), 1e-8)
    }

    @Test fun rejectsUnsupportedElementsBadValuesAndDuplicateNames() {
        assertTrue(CircuitDcSolver.solve("C1 A 0 1u").isFailure)
        assertTrue(CircuitDcSolver.solve("R1 A 0 -1").isFailure)
        assertTrue(CircuitDcSolver.solve("R1 A 0 1k\nR1 A 0 2k").isFailure)
        assertTrue(CircuitDcSolver.solve("V1 A 0 AC 2").isFailure)
    }

    @Test fun rejectsFloatingOrSingularCircuits() {
        assertTrue(CircuitDcSolver.solve("R1 A B 1k").isFailure)
        assertTrue(CircuitDcSolver.solve("V1 A B 5").isFailure)
    }

    @Test fun rejectsOversizedNetlistsAndInvalidNodes() {
        assertTrue(CircuitDcSolver.solve("R1 node-with-dash 0 1k").isFailure)
        assertTrue(CircuitDcSolver.solve((1..257).joinToString("\n") { "R$it N$it 0 1k" }).isFailure)
    }
}
