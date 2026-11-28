package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsSensitiveFieldPolicyTest {
    @Test
    fun rejectsCredentialAndVerificationTypesAndLabels() {
        for (type in listOf("password", "hidden", "file")) {
            assertTrue(AkkpsSensitiveFieldPolicy.isSensitive(type, "Field"))
        }
        for (label in listOf("Password", "OTP", "One-time code", "PIN", "CVV", "CAPTCHA",
            "Security Code", "Verification Code", "Recovery Phrase", "पासवर्ड", "ओटीपी", "पिन")) {
            assertTrue(label, AkkpsSensitiveFieldPolicy.isSensitive("text", label))
        }
    }

    @Test
    fun permitsOrdinaryFarmerAndPlotFields() {
        for (label in listOf("Farmer Name", "Village", "Survey Number", "Plot Area", "Rice Variety")) {
            assertFalse(label, AkkpsSensitiveFieldPolicy.isSensitive("text", label))
        }
    }
}
