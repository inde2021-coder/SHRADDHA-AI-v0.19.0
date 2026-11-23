package com.Lia.assistant.ui.screens.expert

object EngineeringExports {
    fun openScadMountingPlate(widthMm: Double, heightMm: Double, thicknessMm: Double, holeDiameterMm: Double, edgeOffsetMm: Double): Result<String> = runCatching {
        val values = listOf(widthMm, heightMm, thicknessMm, holeDiameterMm, edgeOffsetMm)
        require(values.all { it.isFinite() && it > 0.0 }) { "All dimensions must be finite and greater than zero." }
        require(widthMm <= 500.0 && heightMm <= 500.0 && thicknessMm <= 50.0) { "Plate dimensions exceed the supported 500 x 500 x 50 mm envelope." }
        require(holeDiameterMm <= minOf(widthMm, heightMm) / 3.0) { "Hole diameter is too large for the plate." }
        require(edgeOffsetMm * 2.0 + holeDiameterMm < minOf(widthMm, heightMm)) { "Hole offset leaves no valid mounting area." }
        listOf(
            "// Shraddha AI — parametric mounting plate",
            "// Units: millimetres. Review clearances and manufacturing tolerances.",
            "width = ${fmt(widthMm)};",
            "height = ${fmt(heightMm)};",
            "thickness = ${fmt(thicknessMm)};",
            "hole_diameter = ${fmt(holeDiameterMm)};",
            "edge_offset = ${fmt(edgeOffsetMm)};",
            "difference() {",
            "  cube([width, height, thickness], center = false);",
            "  for (x = [edge_offset, width - edge_offset])",
            "    for (y = [edge_offset, height - edge_offset])",
            "      translate([x, y, -0.1])",
            "        cylinder(h = thickness + 0.2, d = hole_diameter);",
            "}"
        ).joinToString("\n") + "\n"
    }

    fun kicadPlacementCsv(boardWidthMm: Double, boardHeightMm: Double, ledCount: Int): Result<String> = runCatching {
        require(boardWidthMm.isFinite() && boardHeightMm.isFinite() && boardWidthMm in 10.0..300.0 && boardHeightMm in 10.0..300.0) {
            "PCB dimensions must be between 10 and 300 mm."
        }
        require(ledCount in 1..10) { "LED count must be between 1 and 10." }
        val rows = mutableListOf("Ref,Value,Footprint,PosX_mm,PosY_mm,Rotation,Side")
        rows += "R1,Series resistor,Resistor_THT:R_Axial_DIN0207_L6.3mm_D2.5mm_P10.16mm,${fmt(boardWidthMm / 2.0)},${fmt(boardHeightMm / 2.0)},0,top"
        for (i in 0 until ledCount) {
            val x = boardWidthMm * (i + 1.0) / (ledCount + 1.0)
            rows += "D${i + 1},LED,LED_THT:LED_D5.0mm,${fmt(x)},${fmt(boardHeightMm * 0.25)},0,top"
        }
        rows.joinToString("\n") + "\n"
    }

    private fun fmt(value: Double): String =
        java.text.DecimalFormat("0.###", java.text.DecimalFormatSymbols(java.util.Locale.US)).format(value)
}
