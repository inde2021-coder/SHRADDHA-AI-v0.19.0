package `in`.shraddha.ai.future

import org.junit.Assert.*
import org.junit.Test

class EncryptedBackupCodecTest {
    @Test fun roundTripEncryptsAndRestoresPayload() {
        val plain = """{"schema":"shraddha-backup-v2","notes":"private memory"}"""
        val encrypted = EncryptedBackupCodec.encrypt(plain, "a-long-passphrase-2026".toCharArray())
        assertTrue(encrypted.contains("shraddha-encrypted-backup-v2"))
        assertFalse(encrypted.contains("private memory"))
        assertEquals(plain, EncryptedBackupCodec.decrypt(encrypted, "a-long-passphrase-2026".toCharArray()))
    }

    @Test fun wrongPassphraseIsRejected() {
        val encrypted = EncryptedBackupCodec.encrypt("secret", "correct-passphrase".toCharArray())
        assertThrows(Exception::class.java) {
            EncryptedBackupCodec.decrypt(encrypted, "incorrect-passphrase".toCharArray())
        }
    }

    @Test fun plaintextAndUnsupportedEnvelopeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackupCodec.encrypt("data", "short".toCharArray())
        }
        assertThrows(Exception::class.java) {
            EncryptedBackupCodec.decrypt("""{"schema":"shraddha-backup-v1"}""", "long-passphrase".toCharArray())
        }
    }
}
