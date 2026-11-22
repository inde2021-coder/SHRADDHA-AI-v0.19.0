package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

class BatchCsvSourceTest {
    @Test fun parsesQuotedCommaAndEscapedQuote() {
        val rows = BatchCsvSource.read(StringReader("id,name,note\n1,\"A, B\",\"said \"\"hello\"\"\"\n"))
        assertEquals(1, rows.size)
        assertEquals("A, B", rows[0].values.getString("name"))
        assertEquals("said \"hello\"", rows[0].values.getString("note"))
    }

    @Test fun parsesEmbeddedNewlineInsideQuotedField() {
        val rows = BatchCsvSource.read(StringReader("id,note\n1,\"line one\nline two\"\n"))
        assertEquals("line one\nline two", rows.single().values.getString("note"))
    }

    @Test fun rejectsDuplicateHeadersAndMalformedRows() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchCsvSource.read(StringReader("id,id\n1,2\n"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchCsvSource.read(StringReader("id,name\n1\n"))
        }
    }

    @Test fun enforcesOneThousandRecordLimit() {
        val csv = buildString {
            append("id\n")
            repeat(1001) { append(it).append('\n') }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchCsvSource.read(StringReader(csv))
        }
    }
}
