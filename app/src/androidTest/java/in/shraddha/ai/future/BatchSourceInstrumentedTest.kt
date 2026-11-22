package \`in\`.shraddha.ai.future

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BatchSourceInstrumentedTest {
    @Test fun readsRealXlsxSharedStringsAndInlineStrings() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>id</t></si><si><t>name</t></si><si><t>Ravi</t></si></sst>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row><row r="2"><c r="A2"><v>1</v></c><c r="B2" t="s"><v>2</v></c></row><row r="3"><c r="A3"><v>2</v></c><c r="B3" t="inlineStr"><is><t>Neha</t></is></c></row></sheetData></worksheet>""".toByteArray())
            zip.closeEntry()
        }
        val records = BatchXlsxSource.read(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(2, records.size)
        assertEquals("Ravi", records[0].values.getString("name"))
        assertEquals("Neha", records[1].values.getString("name"))
    }

    @Test fun readsSqliteRowsWithBoundedLimit() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE contacts (id TEXT, name TEXT)")
            db.execSQL("INSERT INTO contacts VALUES ('1', 'Asha')")
            db.execSQL("INSERT INTO contacts VALUES ('2', 'Ravi')")
            val records = BatchSqliteSource.read(db, "contacts", limit = 1)
            assertEquals(1, records.size)
            assertEquals("Asha", records.single().values.getString("name"))
            assertThrows(IllegalArgumentException::class.java) {
                BatchSqliteSource.read(db, "contacts; DROP TABLE contacts")
            }
        } finally {
            db.close()
        }
    }
}
