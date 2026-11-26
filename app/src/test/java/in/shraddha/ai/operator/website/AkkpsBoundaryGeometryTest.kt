package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsBoundaryGeometryTest {
    private val square = listOf(
        AkkpsGeoPoint(0.0, 0.0),
        AkkpsGeoPoint(0.0, 0.001),
        AkkpsGeoPoint(0.001, 0.001),
        AkkpsGeoPoint(0.001, 0.0)
    )

    @Test fun measuresClosedSquareWithoutNetwork() {
        val result = AkkpsBoundaryGeometry.measure(square)
        assertEquals(12_364.0, result.squareMeters, 40.0)
        assertEquals(result.squareMeters / 10_000.0, result.hectares, 0.0001)
        assertEquals(result.squareMeters / 4_046.8564224, result.acres, 0.0001)
    }

    @Test fun areaDoesNotDependOnClockwiseOrCounterClockwiseOrder() {
        val forward = AkkpsBoundaryGeometry.measure(square).squareMeters
        val reverse = AkkpsBoundaryGeometry.measure(square.reversed()).squareMeters
        assertEquals(forward, reverse, 0.001)
    }

    @Test fun rejectsTooFewPointsAndInvalidCoordinates() {
        val tooFew = runCatching { AkkpsBoundaryGeometry.measure(square.take(2)) }
        val invalid = runCatching {
            AkkpsBoundaryGeometry.measure(square.dropLast(1) + AkkpsGeoPoint(91.0, 0.0))
        }
        assertTrue(tooFew.isFailure)
        assertTrue(invalid.isFailure)
    }

    @Test fun rejectsRepeatedAdjacentPoints() {
        val repeated = square.toMutableList().apply { add(1, square[0]) }
        assertTrue(runCatching { AkkpsBoundaryGeometry.measure(repeated) }.isFailure)
    }

    @Test fun rejectsSelfIntersectingBowTiePolygon() {
        val bowTie = listOf(
            AkkpsGeoPoint(0.0, 0.0),
            AkkpsGeoPoint(0.001, 0.001),
            AkkpsGeoPoint(0.0, 0.001),
            AkkpsGeoPoint(0.001, 0.0)
        )
        assertTrue(runCatching { AkkpsBoundaryGeometry.measure(bowTie) }.isFailure)
    }
}
