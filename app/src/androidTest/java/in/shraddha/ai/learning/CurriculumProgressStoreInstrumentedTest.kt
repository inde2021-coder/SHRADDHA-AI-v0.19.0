package `in`.shraddha.ai.learning

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurriculumProgressStoreInstrumentedTest {
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
}
