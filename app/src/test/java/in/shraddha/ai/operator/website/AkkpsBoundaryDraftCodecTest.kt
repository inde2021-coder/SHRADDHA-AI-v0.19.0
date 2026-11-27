package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsBoundaryDraftCodecTest {
    private val points = listOf(
        AkkpsGeoPoint(23.0, 78.0),
        AkkpsGeoPoint(23.0001, 78.0001)
    )

    @Test fun unfinishedDraftRoundTripPreservesPointsAndAccuracy() {
        val timestamp = System.currentTimeMillis()
        val raw = AkkpsBoundaryDraftCodec.encode(points, 4.5f, timestamp)
        val restored = AkkpsBoundaryDraftCodec.decode(raw)
        assertEquals(points, restored.points)
        assertEquals(4.5f, restored.bestAccuracyMeters!!, 0.001f)
        assertEquals(timestamp, restored.updatedAtEpochMillis)
    }

    @Test fun acceptsDraftWithOnePointAndUnknownAccuracy() {
        val raw = AkkpsBoundaryDraftCodec.encode(points.take(1), null, System.currentTimeMillis())
        val restored = AkkpsBoundaryDraftCodec.decode(raw)
        assertEquals(1, restored.points.size)
        assertNull(restored.bestAccuracyMeters)
    }

    @Test fun rejectsOutOfRangeCoordinatesAndDuplicateAdjacentPoints() {
        assertTrue(runCatching {
            AkkpsBoundaryDraftCodec.encode(listOf(AkkpsGeoPoint(91.0, 78.0)), null, System.currentTimeMillis())
        }.isFailure)
        assertTrue(runCatching {
            AkkpsBoundaryDraftCodec.encode(listOf(points[0], points[0]), null, System.currentTimeMillis())
        }.isFailure)
    }

    @Test fun rejectsInvalidAccuracyTimestampAndOversizedPayload() {
        assertTrue(runCatching {
            AkkpsBoundaryDraftCodec.encode(points, 1_001f, System.currentTimeMillis())
        }.isFailure)
        assertTrue(runCatching {
            AkkpsBoundaryDraftCodec.encode(points, null, 0L)
        }.isFailure)
        assertTrue(runCatching {
            AkkpsBoundaryDraftCodec.decode("x".repeat(200_001))
        }.isFailure)
    }
}
