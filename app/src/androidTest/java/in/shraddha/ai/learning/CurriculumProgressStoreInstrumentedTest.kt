package `in`.shraddha.ai.learning

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurriculumProgressStoreInstrumentedTest {
    @Test fun activeStudyTimeIsCountedAndAbandonedSessionIsRecovered() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CurriculumProgressStore(context)
        store.startSession()
        Thread.sleep(1_100L)
        assertTrue("Active study time must appear in the live snapshot.", store.snapshot().usedTodayMinutes >= 1)
        store.endSession()
        val afterPause = store.snapshot().usedTodayMinutes
        assertTrue(afterPause >= 1)

        // Simulate a new Activity/process starting while an old session marker remains.
        store.startSession()
        Thread.sleep(1_100L)
        store.startSession()
        assertTrue("Starting a new session must first recover the previous one.", store.snapshot().usedTodayMinutes >= afterPause + 1)
        store.endSession()
    }

    @Test fun recoveredCommitDoesNotDoubleCountWhenProcessDiesBeforeMarkerClear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CurriculumProgressStore(context)
        store.endSession()
        val before = store.snapshot().usedTodayMinutes
        store.startSession()
        Thread.sleep(1_100L)

        // Simulate a crash after encrypted usage was committed but before the recovery marker cleared.
        store.endSessionAt(System.currentTimeMillis(), clearSessionMarker = false)
        val restarted = CurriculumProgressStore(context)
        restarted.endSession()
        val afterRecovery = restarted.snapshot().usedTodayMinutes

        assertTrue("The recovered session must be counted.", afterRecovery >= before + 1)
        assertTrue("The recovered session must not be counted twice.", afterRecovery <= before + 1)
    }

    @Test fun guardianPinRateLimitsRepeatedFailuresAndUnlocksAfterCooldown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CurriculumProgressStore(context)
        store.setGuardianPin("5831".toCharArray())
        val start = System.currentTimeMillis()
        repeat(5) {
            assertFalse(store.verifyGuardianPinAt("0000".toCharArray(), start + it))
        }
        assertFalse("Correct PIN must be blocked during cooldown",
            store.verifyGuardianPinAt("5831".toCharArray(), start + 10_000L))
        assertTrue("Correct PIN is accepted after cooldown",
            store.verifyGuardianPinAt("5831".toCharArray(), start + 61_000L))
    }

    @Test fun guardianPinAndProgressPersistLocallyWithEncryptedState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CurriculumProgressStore(context)
        store.setGuardianPin("5831".toCharArray())
        assertTrue(store.hasGuardianPin())
        assertTrue(store.verifyGuardianPin("5831".toCharArray()))
        assertFalse(store.verifyGuardianPin("1111".toCharArray()))

        store.setGuardianMode(true)
        store.setDailyLimit(45)
        store.markVerseComplete("18.78")
        store.markPackComplete("curriculum_baby_care_0_2_v1")
        val snapshot = store.snapshot()
        assertTrue(snapshot.guardianMode)
        assertTrue(snapshot.completedVerses.contains("18.78"))
        assertTrue(snapshot.completedPacks.contains("curriculum_baby_care_0_2_v1"))
        assertTrue(snapshot.dailyLimitMinutes == 45)

        store.setGuardianMode(false)
    }
    @Test fun guardianApprovalGrantsOnlyATimeLimitedLearningExtension() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CurriculumProgressStore(context)
        store.setGuardianPin("5831".toCharArray())
        store.setGuardianMode(true)
        val now = System.currentTimeMillis()
        store.grantGuardianOverrideAt(now, 15)
        assertTrue(store.hasGuardianOverrideAt(now + 1L))
        assertTrue(store.hasGuardianOverrideAt(now + 14 * 60_000L))
        assertFalse(store.hasGuardianOverrideAt(now + 15 * 60_000L))
        store.setGuardianMode(false)
    }

}
