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
        assertTrue(SensitiveFieldDetector.isSensitive(false, listOf("Card number")))
        assertTrue(SensitiveFieldDetector.isSensitive(false, listOf("verification code")))
    }


    @Test fun redactsNationalIdentifiersAndValidPaymentCardNumbersFromOcr() {
        assertTrue(SensitiveFieldDetector.containsPrivateIdentifier("Aadhaar 123456789012"))
        assertTrue(SensitiveFieldDetector.containsPrivateIdentifier("PAN ABCDE1234F"))
        assertTrue(SensitiveFieldDetector.containsPrivateIdentifier("Card 4111 1111 1111 1111"))
        assertTrue(SensitiveFieldDetector.containsPrivateIdentifier("4111111111111111"))
        assertFalse(SensitiveFieldDetector.containsPrivateIdentifier("4111111111111112"))
        assertFalse(SensitiveFieldDetector.containsPrivateIdentifier("Order 1234567890123"))
    }

    @Test fun ignoresOrdinaryLabels() {
        assertFalse(SensitiveFieldDetector.isSensitive(false, listOf("Search", "Message", "Name", "Shipping address", "Shopping cart", "Shipping PINATA")))
        assertFalse(SensitiveFieldDetector.isSensitive(false, emptyList()))
    }

    @Test fun detectsResourceIdsAndSiblingLabelText() {
        assertTrue(SensitiveFieldDetector.isSensitive(false, listOf("com.bank:id/otp_input")))
        assertTrue(SensitiveFieldDetector.isSensitive(false, listOf("Account", "Security code")))
    }
}
