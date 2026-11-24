package `in`.shraddha.ai.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveFieldDetectorTest {
    @Test fun detectsPasswordAndVerificationLabels() {
        assertTrue(SensitiveFieldDetector.isSensitive(true, emptyList()))
        assertTrue(SensitiveFieldDetector.isSensitive(false, listOf("Enter OTP")))
        assertTrue(SensitiveFieldDetector.isSensitive(false, listOf("UPI PIN")))
        assertTrue(SensitiveFieldDetector.isSensitive(false, listOf("सत्यापन कोड")))
    }

    @Test fun ignoresOrdinaryLabels() {
        assertFalse(SensitiveFieldDetector.isSensitive(false, listOf("Search", "Message", "Name", "Shipping address", "Shopping cart")))
        assertFalse(SensitiveFieldDetector.isSensitive(false, emptyList()))
    }

    @Test fun detectsResourceIdsAndSiblingLabelText() {
        assertTrue(SensitiveFieldDetector.isSensitive(false, listOf("com.bank:id/otp_input")))
        assertTrue(SensitiveFieldDetector.isSensitive(false, listOf("Account", "Security code")))
    }
}
