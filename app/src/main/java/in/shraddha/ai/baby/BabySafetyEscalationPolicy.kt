package `in`.shraddha.ai.baby

/**
 * Fail-closed decision policy for the optional Smart Baby Safety workflow.
 *
 * This class does NOT detect crying or people. It consumes independently validated
 * on-device cry-event and camera-scene results from their canonical pipelines.
 * NO_ADULT_IN_FRAME is deliberately not described as "no adult in the home".
 */
object BabySafetyEscalationPolicy {
    const val MIN_CRY_CONFIDENCE = 0.85f
    const val MIN_SUSTAINED_CRY_MS = 30_000L
    const val ESCALATION_GRACE_MS = 30_000L
    const val ALERT_COOLDOWN_MS = 5 * 60_000L

    enum class AdultVisibility { ADULT_VISIBLE, NO_ADULT_IN_FRAME, UNKNOWN }
    enum class Stage { NONE, PRIMARY_ATTEMPTED, BACKUP_REQUIRED, COOLDOWN }
    enum class Action { NONE, CALL_PRIMARY, CALL_BACKUP, SEND_GUARDIAN_SMS, MONITORING_UNAVAILABLE }

    data class Input(
        val monitoringEnabled: Boolean,
        val guardianConsent: Boolean,
        val contactsVerified: Boolean,
        val monitoringHealthy: Boolean,
        val cryConfidence: Float,
        val sustainedCryMs: Long,
        val adultVisibility: AdultVisibility,
        val nowMs: Long,
        val lastAlertAtMs: Long = 0L,
        val stage: Stage = Stage.NONE,
        val primaryCallAnswered: Boolean? = null,
        val primaryAttemptAtMs: Long = 0L,
        val backupCallAttempted: Boolean = false
    )

    data class Decision(val actions: List<Action>, val nextStage: Stage, val reason: String)

    fun evaluate(input: Input): Decision {
        if (!input.monitoringEnabled || !input.guardianConsent || !input.contactsVerified) {
            return Decision(emptyList(), Stage.NONE, "Guardian monitoring consent/configuration is missing")
        }
        if (!input.monitoringHealthy) {
            return Decision(listOf(Action.MONITORING_UNAVAILABLE), input.stage, "Camera, microphone, power, network or permission health is unavailable")
        }
        if (input.nowMs < 0L || input.sustainedCryMs < 0L ||
            input.cryConfidence !in 0f..1f || input.lastAlertAtMs < 0L ||
            input.primaryAttemptAtMs < 0L
        ) {
            return Decision(emptyList(), input.stage, "Invalid event metadata")
        }
        if (input.adultVisibility != AdultVisibility.NO_ADULT_IN_FRAME) {
            return Decision(emptyList(), Stage.NONE, "An adult is visible or camera evidence is uncertain")
        }
        if (input.cryConfidence < MIN_CRY_CONFIDENCE || input.sustainedCryMs < MIN_SUSTAINED_CRY_MS) {
            return Decision(emptyList(), Stage.NONE, "Cry event did not meet the confidence and duration thresholds")
        }
        // Cooldown applies only before starting a new incident. It must not block
        // the backup escalation for an incident already in progress.
        if (input.stage == Stage.NONE || input.stage == Stage.COOLDOWN) {
            if (input.lastAlertAtMs > 0L) {
                if (input.nowMs < input.lastAlertAtMs) return Decision(emptyList(), input.stage, "Clock moved backwards")
                if (input.nowMs - input.lastAlertAtMs < ALERT_COOLDOWN_MS) {
                    return Decision(emptyList(), Stage.COOLDOWN, "Alert cooldown is active")
                }
            }
        }

        return when (input.stage) {
            Stage.NONE, Stage.COOLDOWN -> Decision(
                listOf(Action.CALL_PRIMARY, Action.SEND_GUARDIAN_SMS),
                Stage.PRIMARY_ATTEMPTED,
                "Sustained cry detected and no adult is visible in the configured camera frame"
            )
            Stage.PRIMARY_ATTEMPTED -> {
                if (input.primaryCallAnswered == true) {
                    Decision(emptyList(), Stage.NONE, "Primary guardian answered")
                } else if (input.primaryCallAnswered == false &&
                    input.nowMs >= input.primaryAttemptAtMs &&
                    input.nowMs - input.primaryAttemptAtMs >= ESCALATION_GRACE_MS
                ) {
                    Decision(
                        if (input.backupCallAttempted) emptyList() else listOf(Action.CALL_BACKUP, Action.SEND_GUARDIAN_SMS),
                        if (input.backupCallAttempted) Stage.PRIMARY_ATTEMPTED else Stage.BACKUP_REQUIRED,
                        "Primary guardian did not answer within the escalation grace period"
                    )
                } else {
                    Decision(emptyList(), Stage.PRIMARY_ATTEMPTED, "Waiting for primary guardian call result")
                }
            }
            Stage.BACKUP_REQUIRED -> Decision(emptyList(), Stage.BACKUP_REQUIRED, "Backup guardian escalation already requested")
        }
    }
}
