package `in`.shraddha.ai.future

import org.junit.Assert.*
import org.junit.Test

class AdvancedPersonalAssistantSuiteTest {
    @Test fun plannerRespectsDependenciesAndPriority() {
        val plan=AutonomousTaskPlanner.plan(listOf(
            AutonomousTaskPlanner.Task("c","Deploy",setOf("b"),1,10),
            AutonomousTaskPlanner.Task("a","Research",priority=2,estimatedMinutes=20),
            AutonomousTaskPlanner.Task("b","Build",setOf("a"),priority=1,estimatedMinutes=30)
        ))
        assertEquals(listOf("a","b","c"),plan.ordered.map { it.id })
        assertEquals(60,plan.totalEstimatedMinutes)
    }

    @Test(expected=IllegalArgumentException::class)
    fun plannerRejectsDependencyCycle() {
        AutonomousTaskPlanner.plan(listOf(
            AutonomousTaskPlanner.Task("a","A",setOf("b")),
            AutonomousTaskPlanner.Task("b","B",setOf("a"))
        ))
    }

    @Test fun selfReviewReportsFailedAcceptanceChecks() {
        val result=SelfReviewEngine.review(listOf(
            SelfReviewEngine.Check("build","Build succeeds",true),
            SelfReviewEngine.Check("security","Rules verified",false)
        ))
        assertFalse(result.passed)
        assertEquals(50,result.scorePercent)
        assertEquals("security",result.failedChecks.single().id)
    }

    @Test fun meetingParserExtractsOnlyExplicitMarkers() {
        val notes=MeetingIntelligence.parse("Discussed the release.\nDECISION: ship after tests\nACTION: @indra: review APK\nQUESTION: who owns QA?")
        assertEquals("ship after tests",notes.decisions.single())
        assertEquals("indra",notes.actions.single().owner)
        assertEquals("review APK",notes.actions.single().task)
        assertEquals(1,notes.openQuestions.size)
    }

    @Test fun dailyBriefingSeparatesUrgentEventsAndReminders() {
        val brief=DailyBriefingEngine.compose(listOf(
            DailyBriefingEngine.Item("Fix crash",priority=1),
            DailyBriefingEngine.Item("Doctor appointment","10:00",kind="event"),
            DailyBriefingEngine.Item("Call vendor","15:00",kind="reminder")
        ))
        assertEquals(1,brief.urgent.size)
        assertEquals(1,brief.schedule.size)
        assertEquals(1,brief.reminders.size)
    }

    @Test fun wakePhraseRequiresConsentAndExactPhrase() {
        val gate=WakePhraseGate("Hey Shraddha")
        try { gate.arm(false); fail("Consent must be required") } catch (_: IllegalArgumentException) {}
        gate.arm(true)
        assertFalse(gate.detected("hello",0.99f))
        assertTrue(gate.detected("hey shraddha",0.9f))
        assertEquals(WakePhraseGate.State.ACTIVE,gate.state)
        gate.deactivate()
        assertEquals(WakePhraseGate.State.DISABLED,gate.state)
    }

    @Test fun voiceProfileValidatesRateAndLanguage() {
        val profile=VoiceProfileRegistry.validate(VoiceProfileRegistry.Profile("hi_in","Hindi",setOf("hi-IN"),1.1f,0.95f,"custom-tts"))
        assertEquals("Hindi",profile.displayName)
    }

    @Test fun translationEnvelopeRejectsSameLanguage() {
        assertEquals("en",LiveTranslationEngine.prepare("Namaste","hi","en").targetLanguage)
        try { LiveTranslationEngine.prepare("hello","en","en"); fail("Same-language request should be rejected") } catch (_:IllegalArgumentException) {}
    }

    @Test fun troubleshootingProvidesSafetyFirstElectricalSteps() {
        val steps=VisualTroubleshootingEngine.playbook("electrical")
        assertTrue(steps.first().instruction.contains("Do not touch"))
    }

    @Test fun environmentMapRejectsDuplicateLandmarks() {
        val point=EnvironmentMapEngine.Point3(1f,2f,3f)
        val item=EnvironmentMapEngine.Landmark("door","Door",point,0.9f)
        assertEquals(1,EnvironmentMapEngine.validate(listOf(item)).size)
        try { EnvironmentMapEngine.validate(listOf(item,item)); fail("Duplicate IDs should be rejected") } catch (_:IllegalArgumentException) {}
    }

    @Test fun clipboardPacketEncryptsAndExpires() {
        val key=ByteArray(32) { it.toByte() }
        val packet=SecureClipboardPacket.seal("private text",key,now=1000,ttlMillis=5000)
        assertNotEquals("private text",packet.ciphertext)
        assertEquals("private text",SecureClipboardPacket.open(packet,key,now=2000))
        try { SecureClipboardPacket.open(packet,key,now=7000); fail("Expired packet must not open") } catch (_:IllegalArgumentException) {}
    }

    @Test fun fileTransferManifestValidatesChunksAndDigest() {
        val bytes="hello".toByteArray()
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest=SecureFileTransfer.manifest("hello.txt",bytes.size.toLong(),digest,16*1024)
        assertTrue(SecureFileTransfer.verifyWholeFile(bytes,manifest))
        assertTrue(SecureFileTransfer.verifyChunk(0,bytes,manifest))
        assertFalse(SecureFileTransfer.verifyChunk(1,bytes,manifest))
    }

    @Test fun notificationQuietHoursSupportMidnightWrap() {
        val rule=SmartNotificationPolicy.Rule("com.example",true,22*60,7*60)
        assertFalse(SmartNotificationPolicy.shouldNotify(rule,23*60))
        assertFalse(SmartNotificationPolicy.shouldNotify(rule,6*60))
        assertTrue(SmartNotificationPolicy.shouldNotify(rule,8*60))
        assertTrue(SmartNotificationPolicy.shouldNotify(rule,23*60,highPriority=true))
    }

    @Test fun timelineSupportsSearchAndDeletion() {
        val timeline=LifeTimeline()
        timeline.add(LifeTimeline.Event("e1","Project meeting","work",1000,"Shraddha release"))
        assertEquals(1,timeline.search("release").size)
        timeline.delete("e1")
        assertTrue(timeline.search("release").isEmpty())
    }

    @Test fun skillLearningRequiresConsentAndCanBeForgotten() {
        val learning=PersonalSkillLearning()
        try { learning.add(false,"Hindi","input","output"); fail("Consent required") } catch (_:IllegalArgumentException) {}
        learning.add(true,"Hindi","input","approved output",100)
        assertEquals(1,learning.examplesFor("hindi").size)
        learning.forget("Hindi")
        assertTrue(learning.examplesFor("Hindi").isEmpty())
    }

    @Test fun routineEngineOnlyProposesMatchingEnabledRoutine() {
        val routine=SmartRoutineEngine.Routine("r1","Morning","07:00","Prepare briefing",true,true)
        assertNull(SmartRoutineEngine.propose(routine,"08:00"))
        assertEquals(true,SmartRoutineEngine.propose(routine,"07:00")?.requiresApproval)
    }
}
