package `in`.shraddha.ai.biometric

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricConsentVaultInstrumentedTest {
    @Test fun consentCalibrationSelectedProfileAndWithdrawalWorkEndToEnd() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = `in`.shraddha.ai.brain.EncryptedFeatureStore(context)
        val vault = BiometricConsentVault(store)
        vault.clear()
        try {
            val a1 = FloatArray(128).also { it[0] = 1f }
            val a2 = FloatArray(128).also { it[0] = .99f; it[1] = .10f }
            val b1 = FloatArray(128).also { it[1] = 1f }
            val b2 = FloatArray(128).also { it[1] = .99f; it[2] = .10f }

            assertTrue(vault.enroll("person_001", "Person A", "face", a1, false).isFailure)
            assertTrue(vault.enroll("person_001", "Person A", "face", a1, true).isSuccess)
            assertTrue(vault.enroll("person_001", "Person A", "face", a2, true).isSuccess)
            assertTrue(vault.enroll("person_002", "Person B", "face", b1, true).isSuccess)
            assertTrue(vault.enroll("person_002", "Person B", "face", b2, true).isSuccess)

            val calibration = vault.calibrateThreshold("face").getOrThrow()
            assertTrue(calibration.threshold in -1f..1f)
            assertTrue(calibration.genuinePairs > 0)
            assertTrue(calibration.impostorPairs > 0)
            vault.saveThreshold("face", calibration.threshold).getOrThrow()

            val ownMatch = vault.match("face", a1, calibration.threshold, "person_001")
            assertNotNull("Selected consented person should match their own template", ownMatch)
            val otherMatch = vault.match("face", a1, calibration.threshold, "person_002")
            assertNull("A selected different person must not be reported as a match", otherMatch)

            assertEquals(2, vault.templateCounts("person_001").getOrThrow().face)
            // Any new sample invalidates the prior threshold before the data changes.
            assertTrue(vault.enroll("person_001", "Person A", "face", a1, true).isSuccess)
            assertNull(vault.loadThreshold("face").getOrThrow())

            store.put("person_directory", "[{\"id\":\"person_001\"},{\"id\":\"person_002\"}]").getOrThrow()
            vault.withdraw("person_001", "[{\"id\":\"person_002\"}]").getOrThrow()
            assertEquals("[{\"id\":\"person_002\"}]", store.get("person_directory").getOrThrow())
            assertEquals(0, vault.templateCounts("person_001").getOrThrow().face)
            assertEquals(2, vault.templateCounts("person_002").getOrThrow().face)
        } finally {
            vault.clear()
            store.remove("person_directory")
        }
    }
}
