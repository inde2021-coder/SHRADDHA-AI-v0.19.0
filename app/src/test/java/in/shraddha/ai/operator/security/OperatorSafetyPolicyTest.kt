package `in`.shraddha.ai.operator.security

import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorSafetyPolicyTest {
    @Test fun blocksPasswordAndOtpTargets() {
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK,
            OperatorSafetyPolicy.assess("TYPE", "Password field", "not-a-real-secret").risk)
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK,
            OperatorSafetyPolicy.assess("TYPE", "सत्यापन ओटीपी", "123456").risk)
    }

    @Test fun blocksPaymentSecurityFields() {
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK,
            OperatorSafetyPolicy.assess("TYPE", "UPI PIN", "1234").risk)
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK,
            OperatorSafetyPolicy.assess("TYPE", "CVV", "123").risk)
    }

    @Test fun requiresConfirmationForExternalSideEffects() {
        listOf("send", "pay", "transfer", "purchase", "publish").forEach { action ->
            assertEquals(action, OperatorSafetyPolicy.Risk.CONFIRM,
                OperatorSafetyPolicy.assess(action, "primary action", "").risk)
        }
    }

    @Test fun blocksSecuritySettingChanges() {
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK,
            OperatorSafetyPolicy.assess("open", "Accessibility settings", "").risk)
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK,
            OperatorSafetyPolicy.assess("install apk", "unknown sources", "").risk)
    }

    @Test fun allowsOrdinaryNavigation() {
        assertEquals(OperatorSafetyPolicy.Risk.LOW,
            OperatorSafetyPolicy.assess("scroll", "message list", "").risk)
    }


    @Test fun blocksAlternateOneTimeCodeLabelsAndSensitivePostconditions() {
        assertEquals("generic Code label", OperatorSafetyPolicy.Risk.BLOCK, OperatorSafetyPolicy.assess("TYPE", "Code", "123456", "Enter one time code").risk)
        assertEquals("one-time password postcondition", OperatorSafetyPolicy.Risk.BLOCK, OperatorSafetyPolicy.assess("CLICK", "Continue", "", "Enter one-time password").risk)
        assertEquals("verification code postcondition", OperatorSafetyPolicy.Risk.BLOCK, OperatorSafetyPolicy.assess("CLICK", "Continue", "", "Verification code").risk)
        assertEquals("UPI account number postcondition", OperatorSafetyPolicy.Risk.BLOCK, OperatorSafetyPolicy.assess("TYPE", "Field", "", "UPI account number").risk)
    }

}
