package `in`.shraddha.ai.link

import org.junit.Assert.*
import org.junit.Test

class RemoteActionApprovalPolicyTest {
    @Test fun requiresConsentAndUnexpiredGrantAtExecutionTime() {
        assertTrue(RemoteActionApprovalPolicy.isAuthorized(true, 5001L, 5000L))
        assertFalse(RemoteActionApprovalPolicy.isAuthorized(false, 5001L, 5000L))
        assertFalse(RemoteActionApprovalPolicy.isAuthorized(true, 5000L, 5000L))
        assertFalse(RemoteActionApprovalPolicy.isAuthorized(true, 4999L, 5000L))
        assertFalse(RemoteActionApprovalPolicy.isAuthorized(false, 0L, 5000L))
    }
}
