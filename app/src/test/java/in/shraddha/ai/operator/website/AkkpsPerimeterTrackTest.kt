package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsPerimeterTrackTest {
    @Test fun rejectsLowAccuracyAndKeepsOnlyGoodFixes() {
        val track = AkkpsPerimeterTrack()
        assertEquals(AkkpsPerimeterTrack.AddResult.POOR_ACCURACY,
            track.add(AkkpsGeoPoint(21.0, 79.0), 18f, 1_000))
        assertEquals(AkkpsPerimeterTrack.AddResult.ACCEPTED,
            track.add(AkkpsGeoPoint(21.0, 79.0), 4f, 2_000))
        assertEquals(1, track.points.size)
    }

    @Test fun rejectsDuplicateAndOutOfOrderFixes() {
        val track = AkkpsPerimeterTrack()
        val p = AkkpsGeoPoint(21.0, 79.0)
        assertEquals(AkkpsPerimeterTrack.AddResult.ACCEPTED, track.add(p, 3f, 1_000))
        assertEquals(AkkpsPerimeterTrack.AddResult.TOO_CLOSE, track.add(p, 3f, 2_000))
        assertEquals(AkkpsPerimeterTrack.AddResult.OUT_OF_ORDER,
            track.add(AkkpsGeoPoint(21.0001, 79.0), 3f, 900))
    }

    @Test fun rejectsImpossibleGpsJump() {
        val track = AkkpsPerimeterTrack()
        assertEquals(AkkpsPerimeterTrack.AddResult.ACCEPTED,
            track.add(AkkpsGeoPoint(21.0, 79.0), 3f, 1_000))
        assertEquals(AkkpsPerimeterTrack.AddResult.IMPLAUSIBLE_JUMP,
            track.add(AkkpsGeoPoint(21.001, 79.0), 3f, 2_000))
    }

    @Test fun requiresReturningNearStartAndProducesValidatedPolygon() {
        val track = AkkpsPerimeterTrack()
        val fixes = listOf(
            AkkpsGeoPoint(21.0, 79.0),
            AkkpsGeoPoint(21.0, 79.0001),
            AkkpsGeoPoint(21.0001, 79.0001),
            AkkpsGeoPoint(21.0001, 79.0),
            AkkpsGeoPoint(21.000001, 79.0)
        )
        fixes.forEachIndexed { i, p ->
            assertEquals(AkkpsPerimeterTrack.AddResult.ACCEPTED,
                track.add(p, 3f, (i + 1) * 10_000L))
        }
        assertEquals(4, track.finish().size)
    }

    @Test fun refusesToCloseAnIncompleteWalkByGuessing() {
        val track = AkkpsPerimeterTrack()
        listOf(
            AkkpsGeoPoint(21.0, 79.0),
            AkkpsGeoPoint(21.0, 79.0001),
            AkkpsGeoPoint(21.0001, 79.0001),
            AkkpsGeoPoint(21.0001, 79.0002)
        ).forEachIndexed { i, p -> track.add(p, 3f, (i + 1) * 10_000L) }
        assertTrue(runCatching { track.finish() }.isFailure)
    }
}
