package `in`.shraddha.ai.operator.security

import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorSafetyPolicyTest {
    @Test fun passwordAndOtpValuesAreBlocked() {
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK, OperatorSafetyPolicy.assess("TYPE_TEXT", value = "123456 OTP").risk)
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK, OperatorSafetyPolicy.assess("TYPE_TEXT", value = "my password").risk)
    }

    @Test fun credentialsAreBlockedEvenWhenPassedAsRuntimeMessageContent() {
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK, OperatorSafetyPolicy.assess("TYPE_TEXT", value = "Your UPI PIN is 1234").risk)
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK, OperatorSafetyPolicy.assess("TYPE_TEXT", value = "यह OTP 654321 है").risk)
        assertEquals(OperatorSafetyPolicy.Risk.BLOCK, OperatorSafetyPolicy.assess("TYPE_TEXT", value = "CVV 123").risk)
    }

    @Test fun finalButtonsRequireConfirmation() {
        assertEquals(OperatorSafetyPolicy.Risk.CONFIRM, OperatorSafetyPolicy.assess("CLICK_TEXT", target = "Send").risk)
        assertEquals(OperatorSafetyPolicy.Risk.CONFIRM, OperatorSafetyPolicy.assess("CLICK", target = "Delete").risk)
        assertEquals(OperatorSafetyPolicy.Risk.CONFIRM, OperatorSafetyPolicy.assess("TAP", target = "Pay").risk)
    }

    @Test fun ordinaryNavigationRemainsLowRisk() {
        assertEquals(OperatorSafetyPolicy.Risk.LOW, OperatorSafetyPolicy.assess("OPEN_APP", target = "Chrome").risk)
        assertEquals(OperatorSafetyPolicy.Risk.LOW, OperatorSafetyPolicy.assess("SCROLL_FORWARD").risk)
    }
}
