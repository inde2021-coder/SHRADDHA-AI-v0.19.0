package `in`.shraddha.ai.operator

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandExecutionJournalInstrumentedTest {
    private lateinit var context: Context
    private lateinit var journal: CommandExecutionJournal

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("shraddha_command_execution_journal", Context.MODE_PRIVATE)
            .edit().clear().commit()
        journal = CommandExecutionJournal(context)
    }

    @After fun tearDown() {
        context.getSharedPreferences("shraddha_command_execution_journal", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun plan(command: String = "send private message to +919876543210") =
        journal.createPlan(command, listOf(CommandTaskStep("step-1", "messaging", "send")))

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
        val stored = context.getSharedPreferences("shraddha_command_execution_journal", Context.MODE_PRIVATE)
            .getString("plans_v1", "").orEmpty()
        assertTrue(stored.contains(created.commandFingerprint))
        assertFalse(stored.contains(raw))
        assertFalse(stored.contains("+919876543210"))
        assertFalse(stored.contains("123456"))
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
