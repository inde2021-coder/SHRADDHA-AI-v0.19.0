package `in`.shraddha.ai.future

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Reads a caller-authorized SQLite database table through a read-only connection. */
object BatchSqliteSource {
    fun read(
        database: SQLiteDatabase,
        table: String,
        columns: List<String>? = null,
        whereClause: String? = null,
        whereArgs: Array<String>? = null,
        limit: Int = BatchProcessingEngine.MAX_RECORDS
    ): List<BatchProcessingEngine.Record> {
        require(limit in 1..BatchProcessingEngine.MAX_RECORDS)
        requireIdentifier(table)
        columns?.forEach(::requireIdentifier)
        require(whereClause == null || (whereClause.length <= 1000 &&
            whereClause.matches(Regex("[A-Za-z0-9_ ?<>=!().]+")))) {
            "Filter must use a bounded SQL expression with ? placeholders and selectionArgs"
        }
        val projection = columns?.toTypedArray()
        val records = mutableListOf<BatchProcessingEngine.Record>()
        database.query(table, projection, whereClause, whereArgs, null, null, null, limit.toString()).use { cursor ->
            val names = cursor.columnNames
            require(names.isNotEmpty() && names.distinct().size == names.size) { "Database query has invalid columns" }
            while (cursor.moveToNext()) {
                val json = JSONObject()
                names.forEachIndexed { index, name ->
                    if (cursor.isNull(index)) json.put(name, JSONObject.NULL)
                    else json.put(name, when (cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        android.database.Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP)
                        else -> cursor.getString(index)
                    })
                }
                val id = json.optString("id").ifBlank { "db_row_${records.size + 1}" }
                records += BatchProcessingEngine.Record(id, json)
            }
        }
        return records
    }

    private fun requireIdentifier(value: String) {
        require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,62}"))) { "Unsafe SQL identifier" }
    }
}
