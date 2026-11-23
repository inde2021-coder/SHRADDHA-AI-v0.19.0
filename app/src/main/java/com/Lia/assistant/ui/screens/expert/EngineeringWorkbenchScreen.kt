package com.Lia.assistant.ui.screens.expert

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import java.text.DecimalFormat

@Composable
fun EngineeringWorkbenchScreen(navController: NavController) {
    val colors = LocalNovaColors.current
    var voltage by remember { mutableStateOf("12") }
    var resistance by remember { mutableStateOf("1000") }
    var current by remember { mutableStateOf("0.02") }
    var ledSupply by remember { mutableStateOf("12") }
    var ledForward by remember { mutableStateOf("2") }
    var ledCurrent by remember { mutableStateOf("20") }
    var dividerSupply by remember { mutableStateOf("12") }
    var topResistor by remember { mutableStateOf("10000") }
    var bottomResistor by remember { mutableStateOf("10000") }
    var result by remember { mutableStateOf("Enter values to calculate. Results are estimates, not certified designs.") }
    var projectName by remember { mutableStateOf("LED Series Circuit") }
    var seriesLedCount by remember { mutableStateOf("1") }
    var netlist by remember { mutableStateOf("") }
    var circuitNetlist by remember { mutableStateOf("V1 VIN 0 DC 12\\nR1 VIN VOUT 10k\\nR2 VOUT 0 10k\\n.op\\n.end") }
    var boardWidthMm by remember { mutableStateOf("80") }
    var boardHeightMm by remember { mutableStateOf("50") }
    var plateThicknessMm by remember { mutableStateOf("3") }
    var holeDiameterMm by remember { mutableStateOf("4") }
    var holeOffsetMm by remember { mutableStateOf("8") }
    var pendingNetlist by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    val fmt = remember { DecimalFormat("0.####") }
    val context = LocalContext.current
    val netlistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingNetlist) }
                ?: error("Could not open netlist export destination.")
        }.onSuccess { result = "SPICE netlist exported. Run it with an external SPICE simulator after reviewing the generic diode model." }
            .onFailure { result = it.localizedMessage ?: "Netlist export failed." }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportText) }
                ?: error("Could not open export destination.")
        }.onSuccess { result = "Engineering worksheet CSV exported." }
            .onFailure { result = it.localizedMessage ?: "CSV export failed." }
    }
    Column(Modifier.fillMaxSize().nightSky().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NovaTopBar(title = "ENGINEERING WORKBENCH", navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary) }
        })
        NovaGlassCard {
            Text("OHM'S LAW • V = I × R", color = colors.accent)
            OutlinedTextField(value = voltage, onValueChange = { voltage = it.take(20) }, label = { Text("Voltage (V)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = resistance, onValueChange = { resistance = it.take(20) }, label = { Text("Resistance (Ω)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = current, onValueChange = { current = it.take(20) }, label = { Text("Current (A)") }, modifier = Modifier.fillMaxWidth())
            NovaButton(text = "CALCULATE V / I / R / POWER", onClick = {
                val v=voltage.toDoubleOrNull(); val r=resistance.toDoubleOrNull(); val i=current.toDoubleOrNull()
                val calculated = EngineeringCalculations.ohmsLaw(v, r, i)
                result = if (calculated == null) {
                    "Enter finite, non-negative values; resistance must be greater than zero and calculations must remain in range."
                } else {
                    buildList {
                        calculated.currentFromVoltageAndResistance?.let { add("Current from V/R = ${fmt.format(it)} A") }
                        calculated.powerFromVoltageAndResistance?.let { add("Power from V²/R = ${fmt.format(it)} W") }
                        if (v != null && i != null && i == 0.0) add("Resistance from V/I = undefined at 0 A")
                        else calculated.resistanceFromVoltageAndCurrent?.let { add("Resistance from V/I = ${fmt.format(it)} Ω") }
                        calculated.powerFromVoltageAndCurrent?.let { add("Power from V×I = ${fmt.format(it)} W") }
                        calculated.voltageFromCurrentAndResistance?.let { add("Voltage from I×R = ${fmt.format(it)} V") }
                    }.joinToString("\n").ifBlank { "Enter at least two values to calculate." }
                }
            }, modifier = Modifier.fillMaxWidth())
        }
        NovaGlassCard {
            Text("LED SERIES RESISTOR", color = colors.accent)
            OutlinedTextField(value = ledSupply, onValueChange = { ledSupply=it.take(20) }, label = { Text("Supply voltage (V)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = ledForward, onValueChange = { ledForward=it.take(20) }, label = { Text("LED forward voltage (V)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = ledCurrent, onValueChange = { ledCurrent=it.take(20) }, label = { Text("Target current (mA)") }, modifier = Modifier.fillMaxWidth())
            NovaButton(text = "CALCULATE LED RESISTOR", onClick = {
                val vs=ledSupply.toDoubleOrNull(); val vf=ledForward.toDoubleOrNull(); val ma=ledCurrent.toDoubleOrNull()
                val calculation = if (vs != null && vf != null && ma != null) EngineeringCalculations.ledSeriesResistor(vs, vf, ma) else null
                result = if (calculation != null) {
                    "Required resistance: ${fmt.format(calculation.resistanceOhms)} Ω\nMinimum resistor dissipation: ${fmt.format(calculation.resistorPowerWatts)} W\nChoose the next higher standard resistance and a suitably derated power rating."
                } else "Supply must exceed LED forward voltage; current must be between 0 and 1000 mA, and all values must be finite."
            }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
        }
        NovaGlassCard {
            Text("RESISTIVE VOLTAGE DIVIDER", color = colors.accent)
            OutlinedTextField(value = dividerSupply, onValueChange = { dividerSupply=it.take(20) }, label = { Text("Input voltage (V)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = topResistor, onValueChange = { topResistor=it.take(20) }, label = { Text("Top resistor (Ω)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = bottomResistor, onValueChange = { bottomResistor=it.take(20) }, label = { Text("Bottom resistor (Ω)") }, modifier = Modifier.fillMaxWidth())
            NovaButton(text = "CALCULATE DIVIDER", onClick = {
                val vin=dividerSupply.toDoubleOrNull(); val r1=topResistor.toDoubleOrNull(); val r2=bottomResistor.toDoubleOrNull()
                val calculation = if (vin != null && r1 != null && r2 != null) EngineeringCalculations.voltageDivider(vin, r1, r2) else null
                result = if (calculation != null) {
                    "Unloaded output: ${fmt.format(calculation.outputVolts)} V\nDivider current: ${fmt.format(calculation.currentAmps)} A\nTotal resistance: ${fmt.format(calculation.totalResistanceOhms)} Ω\nLoaded output will be lower; include load impedance in a real design."
                } else "Enter finite input voltage and positive resistor values; totals must remain in numeric range."
            }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
        }
        NovaGlassCard {
            Text("LOCAL DC CIRCUIT SIMULATOR", color = colors.accent)
            Text("Supported: resistors (R), DC voltage sources (V), DC current sources (I), ground node 0. This is a bounded DC operating-point solver, not transient/AC SPICE.", color = colors.textSecondary)
            OutlinedTextField(value = circuitNetlist, onValueChange = { circuitNetlist = it.take(32_000) }, label = { Text("SPICE-compatible DC netlist") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
            NovaButton(text = "RUN LOCAL DC SIMULATION", onClick = {
                result = CircuitDcSolver.solve(circuitNetlist).fold(
                    onSuccess = { solved ->
                        "DC operating point\\n" + solved.nodeVoltages.entries.joinToString("\\n") { (node, volts) -> "V($node) = ${fmt.format(volts)} V" } +
                            if (solved.voltageSourceCurrents.isEmpty()) "" else "\\n\\nVoltage source currents\\n" +
                                solved.voltageSourceCurrents.entries.joinToString("\\n") { (name, amps) -> "I($name) = ${fmt.format(amps)} A" }
                    },
                    onFailure = { "Simulation failed: ${it.message ?: "invalid circuit"}" }
                )
            }, modifier = Modifier.fillMaxWidth())
            NovaButton(text = "EXPORT SIMULATION NETLIST", onClick = {
                pendingNetlist = circuitNetlist
                netlistLauncher.launch("shraddha-dc-circuit.cir")
            }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
        }
        NovaGlassCard {
            Text("CIRCUIT PROJECT • SPICE NETLIST", color = colors.accent)
            OutlinedTextField(value = projectName, onValueChange = { projectName = it.take(80) }, label = { Text("Project name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = seriesLedCount, onValueChange = { seriesLedCount = it.filter(Char::isDigit).take(2) }, label = { Text("Series LED count (1–10)") }, modifier = Modifier.fillMaxWidth())
            NovaButton(text = "GENERATE SPICE NETLIST", onClick = {
                val vs=ledSupply.toDoubleOrNull(); val vf=ledForward.toDoubleOrNull(); val ma=ledCurrent.toDoubleOrNull(); val count=seriesLedCount.toIntOrNull()
                if (vs==null || vf==null || ma==null || count==null || count !in 1..10 || vf<0 || ma<=0 || vs<=count*vf) {
                    result = "Cannot generate: supply must exceed total LED forward voltage and current/count must be valid."
                } else {
                    val calculation = EngineeringCalculations.ledSeriesResistor(vs, vf, ma, count)
                    if (calculation == null) {
                        result = "Cannot generate: the calculated values are outside the supported numeric range."
                        return@NovaButton
                    }
                    val r=calculation.resistanceOhms
                    val lines=mutableListOf("* ${projectName.replace(Regex("[^A-Za-z0-9 _-]"), "").take(60)}")
                    lines += "V1 VCC 0 DC $vs"
                    lines += "R1 VCC N1 ${fmt.format(r)}"
                    for (index in 1..count) lines += "D$index N$index ${if(index==count) "0" else "N${index+1}"} DLED"
                    lines += ".model DLED D(Is=1e-14 N=2)"
                    lines += ".op"
                    lines += ".end"
                    netlist = lines.joinToString("\n")
                    pendingNetlist = netlist
                    result = "Netlist generated for $count series LED(s). Calculated resistor: ${fmt.format(r)} Ω. Generic diode model is illustrative."
                }
            }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
            if (netlist.isNotBlank()) {
                Text(netlist, color = colors.textSecondary)
                NovaButton(text = "EXPORT .CIR NETLIST", onClick = { netlistLauncher.launch(projectName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "circuit" } + ".cir") }, modifier = Modifier.fillMaxWidth())
                NovaButton(text = "EXPORT CIRCUIT BOM CSV", onClick = {
                    val vs=ledSupply.toDoubleOrNull() ?: 0.0
                    val vf=ledForward.toDoubleOrNull() ?: 0.0
                    val ma=ledCurrent.toDoubleOrNull() ?: 0.0
                    val count=seriesLedCount.toIntOrNull() ?: 0
                    if (count !in 1..10 || vs<=count*vf || ma<=0) {
                        result = "Generate a valid LED netlist before exporting the BOM."
                    } else {
                        val calculation = EngineeringCalculations.ledSeriesResistor(vs, vf, ma, count)
                        if (calculation == null) {
                            result = "BOM export refused because the calculated values are outside the supported numeric range."
                            return@NovaButton
                        }
                        val ohms=calculation.resistanceOhms
                        exportText = "Reference,Component,Quantity,Value / Rating,Notes\nR1,Resistor,1,${fmt.format(ohms)} ohm,Select next higher standard value and derated power rating\nD1..D${count},LED,${count},Generic LED,Confirm forward voltage and current from actual datasheet\nV1,DC supply,1,${vs} V,Use regulated supply with suitable protection\n"
                        exportLauncher.launch(projectName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "circuit" } + "-bom.csv")
                    }
                }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
            }
        }
        NovaGlassCard {
            Text("PARAMETRIC CAD • PCB EXPORT", color = colors.accent)
            Text("Generate an editable OpenSCAD mounting plate and a KiCad-compatible component placement CSV. Review dimensions, footprints and clearances before fabrication.", color = colors.textSecondary)
            OutlinedTextField(value = boardWidthMm, onValueChange = { boardWidthMm = it.take(12) }, label = { Text("Board / plate width (mm)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = boardHeightMm, onValueChange = { boardHeightMm = it.take(12) }, label = { Text("Board / plate height (mm)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = plateThicknessMm, onValueChange = { plateThicknessMm = it.take(12) }, label = { Text("Plate thickness (mm)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = holeDiameterMm, onValueChange = { holeDiameterMm = it.take(12) }, label = { Text("Mounting hole diameter (mm)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = holeOffsetMm, onValueChange = { holeOffsetMm = it.take(12) }, label = { Text("Hole centre offset (mm)") }, modifier = Modifier.fillMaxWidth())
            NovaButton(text = "EXPORT OPENSCAD .SCAD", onClick = {
                val values = listOf(boardWidthMm, boardHeightMm, plateThicknessMm, holeDiameterMm, holeOffsetMm).map { it.toDoubleOrNull() }
                if (values.any { it == null }) {
                    result = "Enter valid numeric CAD dimensions."
                } else {
                    val generated = EngineeringExports.openScadMountingPlate(
                        values[0]!!, values[1]!!, values[2]!!, values[3]!!, values[4]!!
                    )
                    generated.onSuccess {
                        pendingNetlist = it
                        netlistLauncher.launch("shraddha-mounting-plate.scad")
                        result = "OpenSCAD source generated. Review tolerances before manufacturing."
                    }.onFailure { result = it.message ?: "CAD export failed." }
                }
            }, modifier = Modifier.fillMaxWidth(), type = NovaButtonType.SECONDARY)
            NovaButton(text = "EXPORT KICAD PLACEMENT CSV", onClick = {
                val width = boardWidthMm.toDoubleOrNull()
                val height = boardHeightMm.toDoubleOrNull()
                val count = seriesLedCount.toIntOrNull()
                if (width == null || height == null || count == null) {
                    result = "Enter valid board dimensions and LED count."
                } else {
                    EngineeringExports.kicadPlacementCsv(width, height, count).onSuccess {
                        exportText = it
                        exportLauncher.launch("shraddha-kicad-placement.csv")
                    }.onFailure { result = it.message ?: "PCB placement export failed." }
                }
            }, modifier = Modifier.fillMaxWidth())
        }
        NovaGlassCard {
            Text("CALCULATION RESULT", color = colors.accent)
            Text(result, color = colors.textPrimary)
            NovaButton(text = "EXPORT WORKSHEET CSV", onClick = {
                exportText = "Field,Value\nOhm voltage input,$voltage\nOhm resistance input,$resistance\nOhm current input,$current\nLED supply voltage,$ledSupply\nLED forward voltage,$ledForward\nLED target current mA,$ledCurrent\nDivider supply voltage,$dividerSupply\nDivider top resistance,$topResistor\nDivider bottom resistance,$bottomResistor\nResult,\"$result\"\n"
                exportLauncher.launch("shraddha-engineering-worksheet.csv")
            }, modifier = Modifier.fillMaxWidth())
        }
        Text("This workbench performs local first-order electrical calculations. It is not SPICE, PCB routing, thermal simulation, safety review or a certified engineering design. Verify component tolerances, power derating, load effects and applicable standards before building.", color = colors.textSecondary)
    }
}
