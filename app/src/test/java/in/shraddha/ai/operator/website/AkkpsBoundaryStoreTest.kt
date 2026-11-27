package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsBoundaryStoreTest {
    private val square = listOf(
        AkkpsGeoPoint(23.0, 78.0), AkkpsGeoPoint(23.0, 78.001),
        AkkpsGeoPoint(23.001, 78.001), AkkpsGeoPoint(23.001, 78.0)
    )
    @Test fun ringAreaAndKmlAreGenerated() {
        val boundary = AkkpsBoundary(square)
        assertTrue(boundary.areaAcres > 0.0)
        val kml = AkkpsBoundaryGeometry.toKml(boundary, "Plot 1")
        assertTrue(kml.contains("<Polygon>"))
        assertTrue(kml.contains("78.0,23.0,0"))
    }
    @Test fun jsonRoundTripPreservesEveryPoint() {
        val original = AkkpsBoundary(square)
        val restored = AkkpsBoundaryGeometry.fromJson(AkkpsBoundaryGeometry.toJson(original))
        assertEquals(original.points, restored.points)
        assertEquals(original.areaAcres, restored.areaAcres, 0.0000001)
    }
    @Test fun invalidCoordinatesAndTooFewPointsAreRejected() {
        assertNotNull(AkkpsBoundaryGeometry.validate(square.take(2)))
        assertNotNull(AkkpsBoundaryGeometry.validate(square + AkkpsGeoPoint(91.0, 78.0)))
    }
    @Test fun oversizedOrMalformedSavedPayloadIsRejected() {
        assertTrue(runCatching { AkkpsBoundaryGeometry.fromJson("x".repeat(200001)) }.isFailure)
        assertTrue(runCatching { AkkpsBoundaryGeometry.fromJson("{\"points\":[]}") }.isFailure)
    }

    @Test fun rejectsNonAdjacentRepeatedVertices() {
        val repeated = listOf(
            AkkpsGeoPoint(23.0, 78.0),
            AkkpsGeoPoint(23.0, 78.002),
            AkkpsGeoPoint(23.002, 78.002),
            AkkpsGeoPoint(23.0, 78.002),
            AkkpsGeoPoint(23.002, 78.0)
        )
        assertNotNull(AkkpsBoundaryGeometry.validate(repeated))
    }

    @Test fun rejectsCollinearOverlapBetweenNonAdjacentEdges() {
        val overlapping = listOf(
            AkkpsGeoPoint(23.0, 78.0),
            AkkpsGeoPoint(23.0, 78.004),
            AkkpsGeoPoint(23.001, 78.004),
            AkkpsGeoPoint(23.001, 78.001),
            AkkpsGeoPoint(23.0, 78.001),
            AkkpsGeoPoint(23.0, 78.003),
            AkkpsGeoPoint(22.999, 78.003),
            AkkpsGeoPoint(22.999, 78.0)
        )
        assertNotNull(AkkpsBoundaryGeometry.validate(overlapping))
    }

    @Test fun rejectsSelfCrossingPolygon() {
        val bowTie = listOf(
            AkkpsGeoPoint(23.0, 78.0),
            AkkpsGeoPoint(23.002, 78.002),
            AkkpsGeoPoint(23.0, 78.002),
            AkkpsGeoPoint(23.002, 78.0)
        )
        assertNotNull(AkkpsBoundaryGeometry.validate(bowTie))
    }
}
