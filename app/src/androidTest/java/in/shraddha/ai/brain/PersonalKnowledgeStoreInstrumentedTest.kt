package `in`.shraddha.ai.brain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalKnowledgeStoreInstrumentedTest {
    @Test fun timelineSkillsAndRoutinesSurviveStoreRecreationAndRemainEncrypted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PersonalKnowledgeStore(context)
        val raw = EncryptedFeatureStore(context)
        val suffix = System.currentTimeMillis().toString()
        val eventId = "timeline_$suffix"
        val skillId = "skill_$suffix"
        val routineId = "routine_$suffix"
        val privateTitle = "Private timeline title $suffix"
        try {
            store.addTimelineEvent(PersonalKnowledgeStore.TimelineEvent(
                eventId, privateTitle, "work", 123456L, "private note"
            )).getOrThrow()
            store.addApprovedSkillExample(true, PersonalKnowledgeStore.SkillExample(
                skillId, "Hindi", "input", "approved output", 123456L
            )).getOrThrow()
            store.saveRoutine(PersonalKnowledgeStore.Routine(
                routineId, "Morning brief", "07:00", "Prepare briefing", true, true, 123456L
            )).getOrThrow()

            val reopened = PersonalKnowledgeStore(context)
            assertEquals(privateTitle, reopened.searchTimeline("private note").getOrThrow().single().title)
            assertEquals("approved output", reopened.skillExamples("hindi").getOrThrow().single().approvedOutput)
            assertEquals("Prepare briefing", reopened.proposeRoutine(routineId, "07:00").getOrThrow()?.summary)
            assertNull(reopened.proposeRoutine(routineId, "08:00").getOrThrow())

            val encrypted = raw.getAllWithPrefixes(setOf("personal_timeline_", "personal_skills_", "personal_routines_"))
                .getOrThrow().values
            assertTrue(encrypted.isNotEmpty())
            assertFalse(encrypted.any { it.contains(privateTitle) || it.contains("approved output") })
            assertTrue(reopened.deleteTimelineEvent(eventId).getOrThrow())
            assertEquals(1, reopened.forgetSkill("Hindi").getOrThrow())
            assertTrue(reopened.deleteRoutine(routineId).getOrThrow())
        } finally {
            store.deleteTimelineEvent(eventId)
            store.forgetSkill("Hindi")
            store.deleteRoutine(routineId)
        }
    }
}
