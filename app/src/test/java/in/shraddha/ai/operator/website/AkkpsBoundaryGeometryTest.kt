package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsBoundaryGeometryTest {
    private val square = listOf(
        AkkpsGeoPoint(23.0000, 79.0000),
        AkkpsGeoPoint(23.0000, 79.0010),
        AkkpsGeoPoint(23.0010, 79.0010),
        AkkpsGeoPoint(23.0010, 79.0000)
    )

    @Test fun validatesAndClosesGpsWalkRing() {
        assertNull(AkkpsBoundaryGeometry.validate(square))
        val closed = AkkpsBoundaryGeometry.closeRing(square)
        assertEquals(square.first(), closed.first())
        assertEquals(square.first(), closed.last())
        assertEquals(5, closed.size)
    }

    @Test fun computesPositiveAreaAndAcres() {
        val area = AkkpsBoundaryGeometry.areaSquareMeters(square)
        assertTrue("area must be positive", area > 0.0)
        assertTrue("area should be plausible for a 100m square", area in 9_000.0..13_000.0)
        assertEquals(area / AkkpsBoundaryGeometry.SQUARE_METERS_PER_ACRE, AkkpsBoundary(square).areaAcres, 0.000001)
    }

    @Test fun rejectsInvalidCoordinatesTooFewPointsAndTinyArea() {
        assertNotNull(AkkpsBoundaryGeometry.validate(square.take(2)))
        assertNotNull(AkkpsBoundaryGeometry.validate(square.dropLast(1) + AkkpsGeoPoint(91.0, 79.0)))
        assertNotNull(AkkpsBoundaryGeometry.validate(listOf(
            AkkpsGeoPoint(23.0, 79.0), AkkpsGeoPoint(23.0, 79.000001), AkkpsGeoPoint(23.000001, 79.0)
        )))
    }

    @Test fun rejectsDuplicateAndSelfCrossingEdges() {
        assertNotNull(AkkpsBoundaryGeometry.validate(listOf(
            square[0], square[1], square[1], square[3]
        )))
        val bowTie = listOf(
            AkkpsGeoPoint(23.0, 79.0),
            AkkpsGeoPoint(23.001, 79.001),
            AkkpsGeoPoint(23.0, 79.001),
            AkkpsGeoPoint(23.001, 79.0)
        )
        assertTrue(AkkpsBoundaryGeometry.validate(bowTie)?.contains("cross") == true)
    }

    @Test fun jsonRoundTripPreservesBoundaryPoints() {
        val json = AkkpsBoundaryGeometry.toJson(AkkpsBoundary(square))
        assertEquals(square, AkkpsBoundaryGeometry.fromJson(json).points)
    }

    @Test fun kmlRoundTripPreservesCoordinateOrderAndClosesRing() {
        val kml = AkkpsBoundaryGeometry.toKml(AkkpsBoundary(square), "खेत & Plot")
        assertTrue(kml.contains("खेत &amp; Plot"))
        val parsed = AkkpsBoundaryGeometry.parseKml(kml).getOrThrow()
        assertEquals(square.first(), parsed.points.first())
        assertEquals(square.first(), parsed.points.last())
        assertEquals(square.size + 1, parsed.points.size)
    }

    @Test fun rejectsMalformedAndOversizedKml() {
        assertTrue(AkkpsBoundaryGeometry.parseKml("<kml/>").isFailure)
        assertTrue(AkkpsBoundaryGeometry.parseKml("x".repeat(2_000_001)).isFailure)
    }
}
