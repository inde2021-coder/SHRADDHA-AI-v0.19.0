package `in`.shraddha.ai.future

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference\nimport java.io.ByteArrayInputStream\nimport java.util.zip.ZipEntry\nimport java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BatchProcessingEngineInstrumentedTest {
    @Test fun processesOneThousandWithBoundedParallelismAndResumesCheckpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = BatchProcessingEngine(context, maxParallel = 3, checkpointEvery = 7)
        engine.clearCheckpoint("instrumented_1000")
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val records = (1..1000).map { id ->
            BatchProcessingEngine.Record(id.toString(), JSONObject().put("id", id.toString()).put("value", id))
        }
        val first = engine.run("instrumented_1000", records) {
            val now = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, now) }
            Thread.sleep(2)
            active.decrementAndGet()
        }
        assertEquals(1000, first.succeeded)
        assertEquals(0, first.failed.size)
        assertTrue(maximum.get() <= 3)
        val resumed = engine.run("instrumented_1000", records) { error("Completed rows must not execute again") }
        assertTrue(resumed.resumed)
        assertEquals(1000, resumed.succeeded)
        engine.clearCheckpoint("instrumented_1000")
    }

    @Test fun readsRealXlsxSharedStringsAndInlineStrings() {
        val archive = java.io.ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>id</t></si><si><t>name</t></si><si><t>42</t></si></sst>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row><row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2" t="inlineStr"><is><t>Radhe Krishna</t></is></c></row></sheetData></worksheet>""".toByteArray())
            zip.closeEntry()
        }
        val rows = BatchXlsxSource.read(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(1, rows.size)
        assertEquals("42", rows.single().values.getString("id"))
        assertEquals("Radhe Krishna", rows.single().values.getString("name"))
    }

    @Test fun duplicateRowsAreReportedAndFailedRowsCanBeRetried() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = BatchProcessingEngine(context, maxParallel = 2)
        engine.clearCheckpoint("instrumented_retry")
        val records = listOf(
            BatchProcessingEngine.Record("one", JSONObject().put("v", "same")),
            BatchProcessingEngine.Record("two", JSONObject().put("v", "same")),
            BatchProcessingEngine.Record("three", JSONObject().put("v", "retry"))
        )
        val failOnce = AtomicReference(true)
        val first = engine.run("instrumented_retry", records) {
            if (it.id == "three" && failOnce.getAndSet(false)) error("temporary failure")
        }
        assertEquals(1, first.duplicates)
        assertEquals(1, first.failed.size)
        val retried = engine.run("instrumented_retry", records, retryFailed = true) {}
        assertEquals(2, retried.succeeded)
        assertEquals(1, retried.duplicates)
        assertEquals(0, retried.failed.size)
        engine.clearCheckpoint("instrumented_retry")
    }
}
