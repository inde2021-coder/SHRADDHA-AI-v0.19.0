package `in`.shraddha.ai.operator

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandExecutionJournalInstrumentedTest {
    private lateinit var context: Context
    private lateinit var journal: CommandExecutionJournal

    private val journalKey = "command_execution_journal_plans_v1"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EncryptedFeatureStore(context).remove(journalKey)
        journal = CommandExecutionJournal(context)
    }

    @After fun tearDown() {
        EncryptedFeatureStore(context).remove(journalKey)
    }

    private fun corruptEncryptedPayload() {
        context.getSharedPreferences("shraddha_feature_store", Context.MODE_PRIVATE)
            .edit().putString(journalKey, "not-an-authenticated-encrypted-payload").commit()
    }

    private fun plan(command: String = "send private message to +919876543210") =
        journal.createPlan(command, listOf(CommandTaskStep("step-1", "messaging", "send")))

    @Test fun startupGateOpensOnlyAfterSuccessfulJournalRecovery() {
        assertTrue(CommandExecutionJournalStartup.recover(context))
        assertTrue(CommandExecutionJournalStartup.isReady())

        corruptEncryptedPayload()
        assertFalse(CommandExecutionJournalStartup.recover(context))
        assertFalse(CommandExecutionJournalStartup.isReady())
    }

    @Test fun persistsPlanAndDeduplicatesIdenticalRecentCommand() {
        val first = requireNotNull(plan())
        val second = requireNotNull(plan())
        assertEquals(first.id, second.id)
        assertEquals(CommandStepState.PENDING, requireNotNull(journal.get(first.id)).steps.single().state)
    }

    @Test fun runningStepMustBeDurablyClaimedAndCannotBeClaimedTwice() {
        val created = requireNotNull(plan())
        assertEquals(CommandStepState.RUNNING, requireNotNull(journal.markRunning(created.id, "step-1")).state)
        assertNull(journal.markRunning(created.id, "step-1"))
    }

    @Test fun processRestartConvertsRunningStepToUncertainWithoutReplaying() {
        val created = requireNotNull(plan())
        assertEquals(CommandStepState.RUNNING, requireNotNull(journal.markRunning(created.id, "step-1")).state)

        // A new journal instance simulates a fresh process reading the durable store.
        val restarted = CommandExecutionJournal(context)
        assertTrue(restarted.recoverInterruptedSteps())
        assertEquals(CommandStepState.UNCERTAIN, requireNotNull(restarted.get(created.id)).steps.single().state)
        assertNull(restarted.markRunning(created.id, "step-1"))
    }

    @Test fun corruptedJournalBlocksStartupRecoveryInsteadOfSilentlyDroppingPlans() {
        corruptEncryptedPayload()

        val restarted = CommandExecutionJournal(context)
        assertFalse(restarted.recoverInterruptedSteps())
        var blocked = false
        try {
            restarted.createPlan("new command", listOf(CommandTaskStep("step-2", "files", "upload")))
        } catch (_: IllegalStateException) {
            blocked = true
        }
        assertTrue("Corrupted durable state must not be treated as an empty journal.", blocked)
    }

    @Test fun storagePruningNeverEvictsAnUnresolvedPlan() {
        val active = requireNotNull(journal.createPlan(
            "critical action one",
            listOf(CommandTaskStep("critical-step", "messaging", "send"))
        ))
        assertEquals(CommandStepState.RUNNING, requireNotNull(journal.markRunning(active.id, "critical-step")).state)

        for (index in 1..105) {
            val completed = requireNotNull(journal.createPlan(
                "completed independent task $index",
                listOf(CommandTaskStep("step-$index", "files", "read"))
            ))
            assertEquals(CommandStepState.RUNNING, requireNotNull(journal.markRunning(completed.id, "step-$index")).state)
            assertEquals(CommandStepState.SUCCEEDED,
                requireNotNull(journal.finish(completed.id, "step-$index", CommandStepState.SUCCEEDED, "done")).state)
        }

        val restarted = CommandExecutionJournal(context)
        assertNotNull("Unresolved plan must survive history pruning.", restarted.get(active.id))
        assertTrue(restarted.recoverInterruptedSteps())
        assertEquals(CommandStepState.UNCERTAIN,
            requireNotNull(restarted.get(active.id)).steps.single().state)
    }

    @Test fun malformedJournalEntryBlocksRecovery() {
        assertTrue(EncryptedFeatureStore(context).put(journalKey, """[{"id":"invalid"}]""").isSuccess)
        assertFalse(CommandExecutionJournal(context).recoverInterruptedSteps())
    }

    @Test fun fullyBlockedActionCanBeRetriedAfterUserFixesTheBlocker() {
        val first = requireNotNull(plan())
        assertNotNull(journal.markRunning(first.id, "step-1"))
        assertEquals(
            CommandStepState.BLOCKED,
            requireNotNull(journal.finish(first.id, "step-1", CommandStepState.BLOCKED, "permission_required")).state
        )

        val retry = requireNotNull(plan())
        assertNotEquals("A blocked action with no side effect must be retryable.", first.id, retry.id)
        assertEquals(CommandStepState.PENDING, retry.steps.single().state)
    }

    @Test fun deduplicationDoesNotReuseAPlanWithDifferentActions() {
        val first = requireNotNull(plan())
        val incompatible = journal.createPlan(
            "send private message to +919876543210",
            listOf(CommandTaskStep("step-1", "messaging", "delete"))
        )
        assertNull(incompatible)
        assertEquals("send", requireNotNull(journal.get(first.id)).steps.single().action)
    }

    @Test fun uncertainSideEffectCannotBeRetriedUntilIndependentReconciliation() {
        val created = requireNotNull(plan())
        journal.markRunning(created.id, "step-1")
        assertEquals(
            CommandStepState.UNCERTAIN,
            requireNotNull(journal.finish(created.id, "step-1", CommandStepState.UNCERTAIN, "timeout")).state
        )
        assertNull(journal.markRunning(created.id, "step-1"))
        assertEquals(
            CommandStepState.SUCCEEDED,
            requireNotNull(journal.reconcile(created.id, "step-1", CommandStepState.SUCCEEDED, "receipt_verified")).state
        )
    }

    @Test fun unresolvedExternalActionBlocksDifferentCommandsUntilReconciled() {
        val created = requireNotNull(plan())
        assertNotNull(journal.markRunning(created.id, "step-1"))
        assertEquals(
            CommandStepState.UNCERTAIN,
            requireNotNull(journal.finish(created.id, "step-1", CommandStepState.UNCERTAIN, "process_interrupted")).state
        )
        assertTrue(journal.hasUnresolvedSideEffects())
        assertNull(journal.createPlan(
            "send a different message to another recipient",
            listOf(CommandTaskStep("step-2", "messaging", "send"))
        ))

        assertNotNull(journal.reconcile(
            created.id, "step-1", CommandStepState.SUCCEEDED, "receipt_verified"
        ))
        assertFalse(journal.hasUnresolvedSideEffects())
        assertNotNull(journal.createPlan(
            "send a different message to another recipient",
            listOf(CommandTaskStep("step-2", "messaging", "send"))
        ))
    }

    @Test fun failedStepRetriesOnlyWithinAttemptLimit() {
        val created = requireNotNull(plan())
        journal.markRunning(created.id, "step-1", maxAttempts = 2)
        journal.finish(created.id, "step-1", CommandStepState.FAILED, "temporary")
        assertEquals(2, requireNotNull(journal.markRunning(created.id, "step-1", maxAttempts = 2)).attempt)
        journal.finish(created.id, "step-1", CommandStepState.FAILED, "temporary")
        assertNull(journal.markRunning(created.id, "step-1", maxAttempts = 2))
    }

    @Test fun journalStoresFingerprintAndMetadataNotRawCommandOrMessageBody() {
        val raw = "send private message to +919876543210: OTP 123456"
        val created = requireNotNull(journal.createPlan(raw, listOf(CommandTaskStep("step-1", "messaging", "send"))))
        val encrypted = context.getSharedPreferences("shraddha_feature_store", Context.MODE_PRIVATE)
            .getString(journalKey, "").orEmpty()
        val stored = EncryptedFeatureStore(context).get(journalKey).getOrThrow()
        assertTrue(stored.contains(created.commandFingerprint))
        assertFalse(encrypted.contains(created.commandFingerprint))
        assertFalse(encrypted.contains(raw))
        assertFalse(stored.contains(raw))
        assertFalse(stored.contains("+919876543210"))
        assertFalse(stored.contains("123456"))
    }

    @Test fun resultCodeRejectsNumericPayloads() {
        val created = requireNotNull(plan())
        journal.markRunning(created.id, "step-1")
        journal.finish(created.id, "step-1", CommandStepState.FAILED, "http_503")
        val encrypted = context.getSharedPreferences("shraddha_feature_store", Context.MODE_PRIVATE)
            .getString(journalKey, "").orEmpty()
        val stored = EncryptedFeatureStore(context).get(journalKey).getOrThrow()
        assertFalse(encrypted.contains("redacted"))
        assertFalse(stored.contains("503"))
        assertTrue(stored.contains("redacted"))
    }

    @Test fun invalidAndDuplicateStepIdsAreRejected() {
        assertNull(journal.createPlan("command", listOf(
            CommandTaskStep("same", "one", "send"), CommandTaskStep("same", "two", "save")
        )))
        assertNull(journal.createPlan("command", listOf(CommandTaskStep("", "one", "send"))))
        assertNull(journal.createPlan("command", listOf(CommandTaskStep("step-1", "+919876543210", "send"))))
        assertNull(journal.createPlan("command", listOf(CommandTaskStep("step-1", "messaging", "send_OTP_123456"))))
    }
}
