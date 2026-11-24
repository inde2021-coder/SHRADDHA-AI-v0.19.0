package `in`.shraddha.ai.social

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BoundedMediaInputStreamTest {
    @Test fun allowsExactlyTheConfiguredLimit() {
        val source = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val actual = BoundedMediaInputStream(source, 5).use { it.readBytes() }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), actual)
    }

    @Test fun rejectsOneByteOverTheConfiguredLimitForBulkReads() {
        val source = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6))
        try {
            BoundedMediaInputStream(source, 5).use { it.readBytes() }
            fail("Expected size-limit exception")
        } catch (expected: IOException) {
            assertEquals("Social media uploads are limited to 100 MiB.", expected.message)
        }
    }

    @Test fun rejectsOneByteOverTheConfiguredLimitForSingleByteReads() {
        val source = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        BoundedMediaInputStream(source, 2).use { stream ->
            assertEquals(1, stream.read())
            assertEquals(2, stream.read())
            try {
                stream.read()
                fail("Expected size-limit exception")
            } catch (expected: IOException) {
                assertEquals("Social media uploads are limited to 100 MiB.", expected.message)
            }
        }
    }
}
