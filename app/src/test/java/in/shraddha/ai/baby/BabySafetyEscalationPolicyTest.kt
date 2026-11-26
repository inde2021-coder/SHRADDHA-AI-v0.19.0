package `in`.shraddha.ai.baby

import org.junit.Assert.*
import org.junit.Test

class BabySafetyEscalationPolicyTest {
    private fun input(
        visibility: BabySafetyEscalationPolicy.AdultVisibility = BabySafetyEscalationPolicy.AdultVisibility.NO_ADULT_IN_FRAME,
        stage: BabySafetyEscalationPolicy.Stage = BabySafetyEscalationPolicy.Stage.NONE,
        now: Long = 60_000L,
        lastAlert: Long = 0L
    ) = BabySafetyEscalationPolicy.Input(
        monitoringEnabled = true,
        guardianConsent = true,
        contactsVerified = true,
        monitoringHealthy = true,
        cryConfidence = 0.95f,
        sustainedCryMs = 35_000L,
        adultVisibility = visibility,
        nowMs = now,
        lastAlertAtMs = lastAlert,
        stage = stage
    )

    @Test fun firstConfirmedEventCallsPrimaryAndSendsSms() {
        val result = BabySafetyEscalationPolicy.evaluate(input())
        assertEquals(
            listOf(BabySafetyEscalationPolicy.Action.CALL_PRIMARY, BabySafetyEscalationPolicy.Action.SEND_GUARDIAN_SMS),
            result.actions
        )
        assertEquals(BabySafetyEscalationPolicy.Stage.PRIMARY_ATTEMPTED, result.nextStage)
    }

    @Test fun noAdultInFrameIsRequiredAndUncertainCameraFailsClosed() {
        assertTrue(BabySafetyEscalationPolicy.evaluate(input(
            visibility = BabySafetyEscalationPolicy.AdultVisibility.UNKNOWN
        )).actions.isEmpty())
        assertTrue(BabySafetyEscalationPolicy.evaluate(input(
            visibility = BabySafetyEscalationPolicy.AdultVisibility.ADULT_VISIBLE
        )).actions.isEmpty())
    }

    @Test fun guardianConsentAndMonitoringHealthAreRequired() {
        val noConsent = input().copy(guardianConsent = false)
        assertTrue(BabySafetyEscalationPolicy.evaluate(noConsent).actions.isEmpty())
        val unhealthy = input().copy(monitoringHealthy = false)
        assertEquals(
            listOf(BabySafetyEscalationPolicy.Action.MONITORING_UNAVAILABLE),
            BabySafetyEscalationPolicy.evaluate(unhealthy).actions
        )
    }

    @Test fun weakOrShortCryDoesNotEscalate() {
        assertTrue(BabySafetyEscalationPolicy.evaluate(input().copy(cryConfidence = 0.5f)).actions.isEmpty())
        assertTrue(BabySafetyEscalationPolicy.evaluate(input().copy(sustainedCryMs = 2_000L)).actions.isEmpty())
    }

    @Test fun unansweredPrimaryEscalatesToBackupOnlyAfterGracePeriod() {
        val pending = input(stage = BabySafetyEscalationPolicy.Stage.PRIMARY_ATTEMPTED, now = 20_000L)
            .copy(primaryCallAnswered = false, primaryAttemptAtMs = 10_000L)
        assertTrue(BabySafetyEscalationPolicy.evaluate(pending).actions.isEmpty())
        val overdue = pending.copy(nowMs = 40_000L)
        assertEquals(
            listOf(BabySafetyEscalationPolicy.Action.CALL_BACKUP, BabySafetyEscalationPolicy.Action.SEND_GUARDIAN_SMS),
            BabySafetyEscalationPolicy.evaluate(overdue).actions
        )
    }

    @Test fun cooldownPreventsRepeatedCalls() {
        val result = BabySafetyEscalationPolicy.evaluate(input(now = 100_000L, lastAlert = 99_000L))
        assertTrue(result.actions.isEmpty())
        assertEquals(BabySafetyEscalationPolicy.Stage.COOLDOWN, result.nextStage)
    }
}
