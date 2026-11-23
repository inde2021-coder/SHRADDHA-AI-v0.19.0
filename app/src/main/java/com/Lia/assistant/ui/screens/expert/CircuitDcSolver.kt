package com.Lia.assistant.ui.screens.expert

import kotlin.math.abs

data class CircuitDcResult(val nodeVoltages: Map<String, Double>, val voltageSourceCurrents: Map<String, Double>)

/** Bounded DC operating-point solver for a safe SPICE subset: R, V, I, .op and .end. */
object CircuitDcSolver {
    private sealed interface Element {
        val name: String; val positive: String; val negative: String
        data class Resistor(override val name: String, override val positive: String, override val negative: String, val ohms: Double) : Element
        data class Voltage(override val name: String, override val positive: String, override val negative: String, val volts: Double) : Element
        data class Current(override val name: String, override val positive: String, override val negative: String, val amps: Double) : Element
    }

    fun solve(netlist: String): Result<CircuitDcResult> = runCatching {
        require(netlist.length in 1..32_000) { "Netlist must contain 1-32000 characters." }
        val lines = netlist.lineSequence().toList()
        require(lines.size <= 256) { "Netlist exceeds 256 lines." }
        val elements = mutableListOf<Element>()
        val names = mutableSetOf<String>()
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("*") || line.startsWith(";") || line.startsWith(".")) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            require(parts.size in 4..5) { "Line ${index + 1}: expected R/V/I name, two nodes and a value." }
            val name = parts[0].uppercase()
            require(name.matches(Regex("[RVI][A-Z0-9_]{0,31}"))) { "Line ${index + 1}: unsupported component name." }
            require(names.add(name)) { "Duplicate component name: $name" }
            val p = normalizeNode(parts[1]); val n = normalizeNode(parts[2])
            require(p != n) { "Line ${index + 1}: component terminals must be different nodes." }
            val valueToken = if (parts.size == 5) {
                require(parts[3].equals("DC", true)) { "Line ${index + 1}: only DC sources are supported." }
                parts[4]
            } else parts[3]
            val value = parseValue(valueToken)
            when (name.first()) {
                'R' -> { require(value > 0.0) { "Line ${index + 1}: resistance must be greater than zero." }; elements += Element.Resistor(name, p, n, value) }
                'V' -> elements += Element.Voltage(name, p, n, value)
                'I' -> elements += Element.Current(name, p, n, value)
            }
        }
        require(elements.isNotEmpty()) { "Add at least one supported R, V or I element." }
        val nodes = elements.flatMap { listOf(it.positive, it.negative) }.filter { it != "0" }.distinct().sorted()
        val voltageSources = elements.filterIsInstance<Element.Voltage>()
        require(nodes.size in 1..32) { "Circuit must contain between 1 and 32 non-ground nodes." }
        require(voltageSources.size <= 16) { "At most 16 independent voltage sources are supported." }
        val nodeIndex = nodes.withIndex().associate { it.value to it.index }
        val size = nodes.size + voltageSources.size
        val matrix = Array(size) { DoubleArray(size) }
        val rhs = DoubleArray(size)
        fun index(node: String): Int? = nodeIndex[node]
        fun conductance(a: String, b: String, g: Double) {
            val ia = index(a); val ib = index(b)
            if (ia != null) matrix[ia][ia] += g
            if (ib != null) matrix[ib][ib] += g
            if (ia != null && ib != null) { matrix[ia][ib] -= g; matrix[ib][ia] -= g }
        }
        elements.filterIsInstance<Element.Resistor>().forEach { conductance(it.positive, it.negative, 1.0 / it.ohms) }
        elements.filterIsInstance<Element.Current>().forEach {
            index(it.positive)?.let { i -> rhs[i] -= it.amps }
            index(it.negative)?.let { i -> rhs[i] += it.amps }
        }
        voltageSources.forEachIndexed { sourceIndex, source ->
            val branch = nodes.size + sourceIndex
            index(source.positive)?.let { node -> matrix[node][branch] += 1.0; matrix[branch][node] += 1.0 }
            index(source.negative)?.let { node -> matrix[node][branch] -= 1.0; matrix[branch][node] -= 1.0 }
            rhs[branch] = source.volts
        }
        val solution = gaussianSolve(matrix, rhs)
        val voltages = linkedMapOf("0" to 0.0)
        nodes.forEachIndexed { i, node -> voltages[node] = solution[i] }
        val currents = linkedMapOf<String, Double>()
        voltageSources.forEachIndexed { i, source -> currents[source.name] = solution[nodes.size + i] }
        CircuitDcResult(voltages, currents)
    }

    private fun normalizeNode(value: String): String {
        val node = value.trim().uppercase()
        require(node.matches(Regex("[A-Z0-9_]{1,32}"))) { "Invalid node name: $value" }
        return if (node == "GND" || node == "GROUND") "0" else node
    }

    private fun parseValue(raw: String): Double {
        val match = Regex("^([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:e[+-]?[0-9]+)?)(meg|[tgkmunp]?)(?:[a-z]*)$", RegexOption.IGNORE_CASE)
            .matchEntire(raw.trim()) ?: error("Invalid numeric value: $raw")
        val base = match.groupValues[1].toDouble()
        val multiplier = when (match.groupValues[2].lowercase()) {
            "t" -> 1e12; "g" -> 1e9; "meg" -> 1e6; "k" -> 1e3
            "m" -> 1e-3; "u" -> 1e-6; "n" -> 1e-9; "p" -> 1e-12; else -> 1.0
        }
        val result = base * multiplier
        require(result.isFinite()) { "Numeric value is outside the supported range: $raw" }
        return result
    }

    private fun gaussianSolve(input: Array<DoubleArray>, inputRhs: DoubleArray): DoubleArray {
        val n = input.size; val a = Array(n) { input[it].clone() }; val b = inputRhs.clone()
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
            val scale = a[pivot].maxOfOrNull { abs(it) } ?: 0.0
            require(scale > 0.0 && abs(a[pivot][col]) > scale * 1e-12) {
                "Circuit is singular or floating; connect every node to ground and check source loops."
            }
            if (pivot != col) {
                val temp = a[col]; a[col] = a[pivot]; a[pivot] = temp
                val value = b[col]; b[col] = b[pivot]; b[pivot] = value
            }
            for (row in col + 1 until n) {
                val factor = a[row][col] / a[col][col]
                for (j in col until n) a[row][j] -= factor * a[col][j]
                b[row] -= factor * b[col]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var value = b[row]
            for (col in row + 1 until n) value -= a[row][col] * x[col]
            x[row] = value / a[row][row]
            require(x[row].isFinite()) { "Circuit solution overflowed numeric range." }
        }
        return x
    }
}
