package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsBoundaryGeometryTest {
    private val square = listOf(
        AkkpsGeoPoint(23.0, 78.0), AkkpsGeoPoint(23.0, 78.001),
        AkkpsGeoPoint(23.001, 78.001), AkkpsGeoPoint(23.001, 78.0)
    )
    @Test fun closesRingAndComputesPositiveArea() {
        val closed = AkkpsBoundaryGeometry.closeRing(square)
        assertEquals(closed.first(), closed.last())
        assertTrue(AkkpsBoundaryGeometry.areaSquareMeters(square) > 1.0)
    }
    @Test fun rejectsTooFewAndOutOfRangePoints() {
        assertNotNull(AkkpsBoundaryGeometry.validate(square.take(2)))
        assertNotNull(AkkpsBoundaryGeometry.validate(square + AkkpsGeoPoint(91.0, 78.0)))
    }
    @Test fun rejectsSelfIntersectingPolygon() {
        val bowTie = listOf(AkkpsGeoPoint(0.0, 0.0), AkkpsGeoPoint(1.0, 1.0), AkkpsGeoPoint(0.0, 1.0), AkkpsGeoPoint(1.0, 0.0))
        assertNotNull(AkkpsBoundaryGeometry.validate(bowTie))
    }
    @Test fun kmlRoundTripKeepsCoordinateOrderAndArea() {
        val original = AkkpsBoundary(AkkpsBoundaryGeometry.closeRing(square))
        val parsed = AkkpsBoundaryGeometry.parseKml(AkkpsBoundaryGeometry.toKml(original, "Plot A")).getOrThrow()
        assertEquals(original.points, parsed.points)
        assertEquals(original.areaAcres, parsed.areaAcres, 0.000001)
    }
    @Test fun rejectsKmlWithoutCoordinates() {
        assertTrue(AkkpsBoundaryGeometry.parseKml("<kml></kml>").isFailure)
    }
}
