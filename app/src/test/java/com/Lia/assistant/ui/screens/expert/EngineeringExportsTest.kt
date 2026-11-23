package com.Lia.assistant.ui.screens.expert

import org.junit.Assert.*
import org.junit.Test

class EngineeringExportsTest {
    @Test fun generatesParametricOpenScadMountingPlate() {
        val source = EngineeringExports.openScadMountingPlate(100.0, 60.0, 3.0, 4.0, 8.0).getOrThrow()
        assertTrue(source.contains("width = 100"))
        assertTrue(source.contains("cylinder"))
        assertTrue(source.contains("hole_diameter = 4"))
    }

    @Test fun rejectsUnsafeOrImpossibleCadDimensions() {
        assertTrue(EngineeringExports.openScadMountingPlate(Double.NaN, 60.0, 3.0, 4.0, 8.0).isFailure)
        assertTrue(EngineeringExports.openScadMountingPlate(10.0, 10.0, 3.0, 4.0, 5.0).isFailure)
        assertTrue(EngineeringExports.openScadMountingPlate(900.0, 60.0, 3.0, 4.0, 8.0).isFailure)
    }

    @Test fun exportsBoundedKicadPlacementCsv() {
        val csv = EngineeringExports.kicadPlacementCsv(80.0, 50.0, 3).getOrThrow()
        assertTrue(csv.contains("Resistor_THT"))
        assertTrue(csv.contains("D3,LED"))
        assertEquals(5, csv.lines().filter { it.isNotBlank() }.size)
        assertTrue(EngineeringExports.kicadPlacementCsv(5.0, 50.0, 3).isFailure)
    }
}
