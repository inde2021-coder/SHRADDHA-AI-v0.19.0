package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsSubmitAttemptCodecTest {
    @Test fun roundTripsPendingAndReviewRequiredStates() {
        val timestamp = System.currentTimeMillis()
        listOf(AkkpsSubmitAttemptState.PENDING, AkkpsSubmitAttemptState.REVIEW_REQUIRED).forEach { state ->
            val decoded = AkkpsSubmitAttemptCodec.decode(
                AkkpsSubmitAttemptCodec.encode(AkkpsSubmitAttempt(state, timestamp))
            )
            assertEquals(state, decoded.state)
            assertEquals(timestamp, decoded.createdAtEpochMillis)
        }
    }

    @Test fun rejectsUnknownStateVersionTimestampAndOversizedPayload() {
        assertTrue(runCatching {
            AkkpsSubmitAttemptCodec.decode("""{"version":1,"state":"SUCCESS","createdAt":1}""")
        }.isFailure)
        assertTrue(runCatching {
            AkkpsSubmitAttemptCodec.decode("""{"version":2,"state":"PENDING","createdAt":1}""")
        }.isFailure)
        assertTrue(runCatching {
            AkkpsSubmitAttemptCodec.encode(AkkpsSubmitAttempt(AkkpsSubmitAttemptState.PENDING, 0L))
        }.isFailure)
        assertTrue(runCatching {
            AkkpsSubmitAttemptCodec.decode("x".repeat(1_001))
        }.isFailure)
    }

    @Test fun recordContainsNoCommandOrFarmerFieldValues() {
        val raw = AkkpsSubmitAttemptCodec.encode(
            AkkpsSubmitAttempt(AkkpsSubmitAttemptState.PENDING, System.currentTimeMillis())
        )
        assertFalse(raw.contains("Farmer"))
        assertFalse(raw.contains("Ram"))
        assertFalse(raw.contains("1234567890"))
        assertFalse(raw.contains("token"))
    }
}
