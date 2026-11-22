package `in`.shraddha.ai.future

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONObject

/** Read-only SQLite adapter with identifier validation and a hard 1,000-row cap. */
object BatchDatabaseSource {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]{0,62}")

    fun read(
        database: SQLiteDatabase,
        table: String,
        idColumn: String = "id",
        columns: Array<String>? = null,
        orderBy: String? = null,
        maxRecords: Int = BatchProcessingEngine.MAX_RECORDS
    ): List<BatchProcessingEngine.Record> {
        require(maxRecords in 1..BatchProcessingEngine.MAX_RECORDS)
        require(identifier.matches(table)) { "Invalid table name" }
        require(identifier.matches(idColumn)) { "Invalid ID column" }
        columns?.forEach { require(identifier.matches(it)) { "Invalid column name" } }
        orderBy?.let { require(it.split(',').all { part -> part.trim().split(Regex("\\s+")).let { bits -> bits.size in 1..2 && identifier.matches(bits[0]) && (bits.size == 1 || bits[1].uppercase() in setOf("ASC", "DESC")) } }) { "Invalid sort expression" } }

        val projection = columns?.let { (it.toList() + idColumn).distinct().toTypedArray() }
        val result = ArrayList<BatchProcessingEngine.Record>()
        database.query(table, projection, null, null, null, null, orderBy, (maxRecords + 1).toString()).use { cursor ->
            val idIndex = cursor.getColumnIndex(idColumn)
            require(idIndex >= 0) { "ID column '$idColumn' is not present in the result" }
            while (cursor.moveToNext()) {
                require(result.size < maxRecords) { "Database query exceeds $maxRecords records" }
                val id = cursor.getString(idIndex)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Database record has a blank ID")
                require(id.length <= 32) { "Database record ID exceeds 32 characters" }
                val values = JSONObject()
                for (i in 0 until cursor.columnCount) {
                    val name = cursor.getColumnName(i)
                    values.put(name, cursorValue(cursor, i))
                }
                result += BatchProcessingEngine.Record(id, values)
            }
        }
        require(result.map { it.id }.distinct().size == result.size) { "Database query returned duplicate IDs" }
        return result
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
        else -> cursor.getString(index).orEmpty()
    }
}
