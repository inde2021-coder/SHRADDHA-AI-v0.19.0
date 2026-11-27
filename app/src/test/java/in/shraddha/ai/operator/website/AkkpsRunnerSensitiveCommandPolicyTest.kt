package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsRunnerSensitiveCommandPolicyTest {
    @Test fun blocksCredentialAndSensitiveIdentityLabelsInEnglishAndHindi() {
        listOf(
            "password is abc123",
            "OTP 123456",
            "PIN 1234",
            "UPI PIN 1234",
            "CVV 123",
            "Aadhaar number",
            "आधार नंबर",
            "ओटीपी 123456",
            "पासवर्ड बताओ",
            "पिन नंबर"
        ).forEach { assertTrue(it, AkkpsRunnerSensitiveCommandPolicy.containsSensitiveData(it)) }
    }

    @Test fun blocksLongNumericIdentifiersBeforeCloudAi() {
        assertTrue(AkkpsRunnerSensitiveCommandPolicy.containsSensitiveData("farmer phone 9876543210"))
        assertTrue(AkkpsRunnerSensitiveCommandPolicy.containsSensitiveData("account 123456789012"))
    }

    @Test fun doesNotBlockOrdinaryFarmerAndPlotCommands() {
        listOf(
            "Farmer name is Ram Kumar",
            "create new farmer",
            "search for PLOT-123",
            "plot number 123",
            "गाँव रामपुर"
        ).forEach { assertFalse(it, AkkpsRunnerSensitiveCommandPolicy.containsSensitiveData(it)) }
    }

    @Test fun detectsFullWidthAndZeroWidthObfuscation() {
        assertTrue(AkkpsRunnerSensitiveCommandPolicy.containsSensitiveData("ｏｔｐ １２３４５６"))
        assertTrue(AkkpsRunnerSensitiveCommandPolicy.containsSensitiveData("o\u200Btp 123456"))
    }
}
