package `in`.shraddha.ai.future

import org.json.JSONObject
import java.io.BufferedReader
import java.io.Reader

/** Streaming CSV adapter. Supports quoted fields, escaped quotes and embedded newlines. */
object BatchCsvSource {
    fun read(reader: Reader, maxRecords: Int = BatchProcessingEngine.MAX_RECORDS): List<BatchProcessingEngine.Record> {
        require(maxRecords in 1..BatchProcessingEngine.MAX_RECORDS)
        val input = BufferedReader(reader)
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var first = true
        while (true) {
            val code = input.read()
            if (code < 0) break
            var ch = code.toChar()
            if (first) { first = false; if (ch == '\uFEFF') continue }
            if (quoted) {
                when (ch) {
                    '"' -> {
                        input.mark(1)
                        if (input.read() == '"'.code) field.append('"')
                        else { quoted = false; afterQuote = true; input.reset() }
                    }
                    else -> field.append(ch)
                }
                continue
            }
            if (afterQuote && ch != ',' && ch != '\n' && ch != '\r' && !ch.isWhitespace()) {
                throw IllegalArgumentException("Unexpected character after closing CSV quote")
            }
            when (ch) {
                '"' -> {
                    require(field.isEmpty() && !afterQuote) { "CSV quote must start an empty field" }
                    quoted = true
                }
                ',' -> { row += field.toString(); field.setLength(0); afterQuote = false }
                '\n', '\r' -> {
                    if (ch == '\r') { input.mark(1); if (input.read() != '\n'.code) input.reset() }
                    row += field.toString(); field.setLength(0); afterQuote = false
                    if (row.any { it.isNotBlank() }) rows += row.toList()
                    row.clear()
                    require(rows.size <= maxRecords + 1) { "CSV exceeds $maxRecords data records" }
                }
                else -> { field.append(ch); afterQuote = false }
            }
        }
        require(!quoted) { "CSV ended inside a quoted field" }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotBlank() }) rows += row.toList()
        }
        require(rows.isNotEmpty()) { "CSV is empty" }
        val headers = rows.first().map { it.trim() }
        require(headers.isNotEmpty() && headers.all { it.isNotBlank() }) { "CSV headers cannot be blank" }
        require(headers.distinct().size == headers.size) { "CSV contains duplicate column names" }
        val data = rows.drop(1)
        require(data.size <= maxRecords) { "CSV exceeds $maxRecords data records" }
        return data.mapIndexed { index, values ->
            require(values.size == headers.size) { "CSV row ${index + 2} has ${values.size} columns; expected ${headers.size}" }
            val json = JSONObject()
            headers.indices.forEach { json.put(headers[it], values[it]) }
            val id = json.optString("id").ifBlank { "csv_row_${index + 2}" }
            BatchProcessingEngine.Record(id, json)
        }
    }
}
