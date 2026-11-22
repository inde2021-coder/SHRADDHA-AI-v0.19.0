package `in`.shraddha.ai.future

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Real XLSX (OOXML) reader for the first worksheet; bounded against oversized ZIP/XML input. */
object BatchXlsxSource {
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024

    fun read(input: InputStream, maxRecords: Int = BatchProcessingEngine.MAX_RECORDS): List<BatchProcessingEngine.Record> {
        require(maxRecords in 1..BatchProcessingEngine.MAX_RECORDS)
        var total = 0
        var sharedStrings = emptyList<String>()
        var sheetXml: ByteArray? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var entryBytes = 0
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        entryBytes += n
                        total += n
                        require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "XLSX archive exceeds safe size limits" }
                        out.write(buffer, 0, n)
                    }
                    when (entry.name) {
                        "xl/sharedStrings.xml" -> sharedStrings = parseSharedStrings(out.toByteArray())
                        "xl/worksheets/sheet1.xml" -> sheetXml = out.toByteArray()
                    }
                }
                zip.closeEntry()
            }
        }
        val xml = sheetXml ?: throw IllegalArgumentException("XLSX has no first worksheet")
        val rows = parseSheet(xml, sharedStrings, maxRecords + 1)
        require(rows.isNotEmpty()) { "XLSX worksheet is empty" }
        val headers = rows.first().map { it.trim() }
        require(headers.isNotEmpty() && headers.all { it.isNotBlank() }) { "XLSX headers cannot be blank" }
        require(headers.distinct().size == headers.size) { "XLSX contains duplicate column names" }
        val data = rows.drop(1)
        require(data.size <= maxRecords) { "XLSX exceeds $maxRecords data records" }
        return data.mapIndexed { index, values ->
            require(values.size <= headers.size) { "XLSX row ${index + 2} contains extra columns" }
            val json = JSONObject()
            headers.indices.forEach { col -> json.put(headers[col], values.getOrElse(col) { "" }) }
            val id = json.optString("id").ifBlank { "xlsx_row_${index + 2}" }
            BatchProcessingEngine.Record(id, json)
        }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val values = mutableListOf<String>()
        var inItem = false
        var current = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inItem = true; current = StringBuilder() }
                    "t" -> if (inItem) current.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si" && inItem) {
                    values += current.toString(); inItem = false
                }
            }
            event = parser.next()
        }
        return values
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>, maxRows: Int): List<List<String>> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val rows = mutableListOf<List<String>>()
        var row: MutableMap<Int, String>? = null
        var cellColumn = -1
        var cellType = ""
        var cellValue = ""
        var inValue = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = sortedMapOf<Int, String>()
                    "c" -> {
                        cellColumn = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        cellValue = ""; inValue = false
                    }
                    "v" -> if (cellColumn >= 0) { inValue = true; cellValue = parser.nextText(); inValue = false }
                    "t" -> if (cellColumn >= 0 && cellType == "inlineStr") cellValue += parser.nextText()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> if (cellColumn >= 0) {
                        val value = if (cellType == "s") shared.getOrNull(cellValue.toIntOrNull() ?: -1).orEmpty() else cellValue
                        row?.put(cellColumn, value)
                        cellColumn = -1
                    }
                    "row" -> {
                        val cells = row ?: mutableMapOf()
                        val width = (cells.keys.maxOrNull() ?: -1) + 1
                        rows += (0 until width).map { cells[it].orEmpty() }
                        require(rows.size <= maxRows) { "XLSX exceeds $maxRows worksheet rows" }
                        row = null
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return -1
        var value = 0
        letters.forEach { ch -> value = value * 26 + (ch - 'A' + 1) }
        return value - 1
    }
}
