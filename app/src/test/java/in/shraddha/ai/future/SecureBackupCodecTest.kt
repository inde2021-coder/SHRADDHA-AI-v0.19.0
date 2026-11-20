package `in`.shraddha.ai.future

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBackupCodecTest {
    @Test fun encryptsAndDecryptsHindiAndEnglishBackup() {
        val payload = """{"name":"श्रद्धा","project":"Shraddha AI","notes":["नमस्ते","secure memory"]}"""
        val envelope = SecureBackupCodec.encrypt(payload, "correct horse battery".toCharArray())
        assertTrue(envelope.startsWith("SBA1."))
        assertEquals(payload, SecureBackupCodec.decrypt(envelope, "correct horse battery".toCharArray()))
    }

    @Test fun wrongPassphraseIsRejected() {
        val envelope = SecureBackupCodec.encrypt("private data", "correct horse battery".toCharArray())
        try {
            SecureBackupCodec.decrypt(envelope, "incorrect passphrase".toCharArray())
            throw AssertionError("Wrong passphrase must not decrypt")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Wrong passphrase"))
        }
    }

    @Test fun modifiedCiphertextFailsAuthentication() {
        val envelope = SecureBackupCodec.encrypt("private data", "correct horse battery".toCharArray())
        val replacement = if (envelope.last() == 'A') 'B' else 'A'
        val tampered = envelope.dropLast(1) + replacement
        try {
            SecureBackupCodec.decrypt(tampered, "correct horse battery".toCharArray())
            throw AssertionError("Tampered backup must fail authentication")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsWeakPassphrasesAndUnsupportedVersions() {
        try {
            SecureBackupCodec.encrypt("data", "short".toCharArray())
            throw AssertionError("Weak passphrase should be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            SecureBackupCodec.decrypt("SBA9.a.b.c", "correct horse battery".toCharArray())
            throw AssertionError("Unknown version should be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
