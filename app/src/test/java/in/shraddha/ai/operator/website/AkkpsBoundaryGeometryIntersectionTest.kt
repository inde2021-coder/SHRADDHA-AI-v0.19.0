package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsBoundaryGeometryIntersectionTest {
    @Test fun acceptsSimpleRectangle() {
        val rectangle = listOf(
            AkkpsGeoPoint(23.0, 78.0),
            AkkpsGeoPoint(23.0, 78.001),
            AkkpsGeoPoint(23.001, 78.001),
            AkkpsGeoPoint(23.001, 78.0)
        )
        assertNull(AkkpsBoundaryGeometry.validate(rectangle))
    }

    @Test fun rejectsBowTieCrossing() {
        val bowTie = listOf(
            AkkpsGeoPoint(23.0, 78.0),
            AkkpsGeoPoint(23.001, 78.001),
            AkkpsGeoPoint(23.0, 78.001),
            AkkpsGeoPoint(23.001, 78.0)
        )
        assertNotNull(AkkpsBoundaryGeometry.validate(bowTie))
    }

    @Test fun rejectsNonAdjacentCollinearEdgeOverlap() {
        val overlap = listOf(
            AkkpsGeoPoint(23.0, 78.0),
            AkkpsGeoPoint(23.0, 78.004),
            AkkpsGeoPoint(23.001, 78.004),
            AkkpsGeoPoint(23.0, 78.003),
            AkkpsGeoPoint(23.0, 78.001),
            AkkpsGeoPoint(23.001, 78.0)
        )
        assertNotNull(AkkpsBoundaryGeometry.validate(overlap))
    }

    @Test fun rejectsNonAdjacentEndpointTouch() {
        val touch = listOf(
            AkkpsGeoPoint(23.0, 78.0),
            AkkpsGeoPoint(23.0, 78.004),
            AkkpsGeoPoint(23.002, 78.004),
            AkkpsGeoPoint(23.0, 78.002),
            AkkpsGeoPoint(23.002, 78.0)
        )
        assertNotNull(AkkpsBoundaryGeometry.validate(touch))
    }
}
