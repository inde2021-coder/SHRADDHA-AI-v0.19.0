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
    @Test fun satelliteProvenanceSurvivesEncryptedStorePayloadRoundTrip() {
        val candidate = AkkpsBoundary(
            points = square,
            source = "FTW Global 2025",
            datasetYear = 2025,
            confidence = 0.87,
            sourceFeatureId = "field-42"
        )
        val restored = AkkpsBoundaryGeometry.fromJson(AkkpsBoundaryGeometry.toJson(candidate))
        assertEquals(candidate.points, restored.points)
        assertEquals(candidate.source, restored.source)
        assertEquals(candidate.datasetYear, restored.datasetYear)
        assertEquals(candidate.confidence!!, restored.confidence!!, 0.000001)
        assertEquals(candidate.sourceFeatureId, restored.sourceFeatureId)
    }

    @Test fun legacyVersionOneBoundaryPayloadStillLoadsWithoutProvenance() {
        val payload = """{"version":1,"points":[{"lat":23.0,"lon":78.0},{"lat":23.0,"lon":78.001},{"lat":23.001,"lon":78.001},{"lat":23.001,"lon":78.0}]}"""
        val restored = AkkpsBoundaryGeometry.fromJson(payload)
        assertEquals(square, restored.points)
        assertNull(restored.source)
        assertNull(restored.datasetYear)
    }

    @Test fun invalidSatelliteProvenanceIsRejectedOnSaveAndLoad() {
        assertTrue(runCatching {
            AkkpsBoundaryGeometry.toJson(AkkpsBoundary(square, source = "x".repeat(121)))
        }.isFailure)
        assertTrue(runCatching {
            AkkpsBoundaryGeometry.toJson(AkkpsBoundary(square, datasetYear = 1800))
        }.isFailure)
        assertTrue(runCatching {
            AkkpsBoundaryGeometry.toJson(AkkpsBoundary(square, confidence = 1.5))
        }.isFailure)
    }

    @Test fun invalidCoordinatesAndTooFewPointsAreRejected() {
        assertNotNull(AkkpsBoundaryGeometry.validate(square.take(2)))
        assertNotNull(AkkpsBoundaryGeometry.validate(square + AkkpsGeoPoint(91.0, 78.0)))
    }
    @Test fun oversizedOrMalformedSavedPayloadIsRejected() {
        assertTrue(runCatching { AkkpsBoundaryGeometry.fromJson("x".repeat(200001)) }.isFailure)
        assertTrue(runCatching { AkkpsBoundaryGeometry.fromJson("{\"points\":[]}") }.isFailure)
    }
}
