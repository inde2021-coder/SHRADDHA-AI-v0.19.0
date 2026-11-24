package `in`.shraddha.ai.link

/** Authorization must be valid when an approved action is actually executed. */
object RemoteActionApprovalPolicy {
    fun isAuthorized(consentEnabled: Boolean, grantUntilMillis: Long, nowMillis: Long): Boolean =
        consentEnabled && grantUntilMillis > nowMillis
}
