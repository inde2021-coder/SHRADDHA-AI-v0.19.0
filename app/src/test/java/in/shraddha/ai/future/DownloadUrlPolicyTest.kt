package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class DownloadUrlPolicyTest {
    @Test fun acceptsPublicHttpsDownload() {
        val result = DownloadUrlPolicy.validate("https://example.com/files/report.pdf", "report.pdf")
        assertNotNull(result)
        assertEquals("report.pdf", result?.fileName)
    }

    @Test fun rejectsPlainHttpAndLocalHosts() {
        assertNull(DownloadUrlPolicy.validate("http://example.com/file", "file.txt"))
        assertNull(DownloadUrlPolicy.validate("https://localhost/file", "file.txt"))
        assertNull(DownloadUrlPolicy.validate("https://192.168.1.5/file", "file.txt"))
        assertNull(DownloadUrlPolicy.validate("https://10.0.0.2/file", "file.txt"))
        assertNull(DownloadUrlPolicy.validate("https://[::1]/file", "file.txt"))
        assertNull(DownloadUrlPolicy.validate("https://printer.local/file", "file.txt"))
    }

    @Test fun rejectsEmbeddedCredentialsAndUnsafeNames() {
        assertNull(DownloadUrlPolicy.validate("https://user:pass@example.com/file", "file.txt"))
        assertNull(DownloadUrlPolicy.validate("https://example.com/file", "../file.txt"))
        assertNull(DownloadUrlPolicy.validate("https://example.com/file", "folder" + 92.toChar() + "file.txt"))
    }

    @Test fun sanitizesUnsafeFilenameCharacters() {
        val result = DownloadUrlPolicy.validate("https://example.com/file", "my report?.pdf")
        assertNotNull(result)
        assertEquals("my report_.pdf", result?.fileName)
    }
}