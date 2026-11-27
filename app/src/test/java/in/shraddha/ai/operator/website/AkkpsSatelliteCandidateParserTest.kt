package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsSatelliteCandidateParserTest {
    private fun feature(
        rings: String = "[[[78.0,23.0],[78.001,23.0],[78.001,23.001],[78.0,23.001],[78.0,23.0]]]",
        properties: String = """{"source":"Field dataset","year":2025,"confidence":0.92}""",
        id: String = """"field-17""""
    ): String = """{"type":"Feature","id":$id,"properties":$properties,"geometry":{"type":"Polygon","coordinates":$rings}}"""

    @Test fun importsSingleRingWithValidProvenance() {
        val result = AkkpsSatelliteCandidateParser.parse(feature()).getOrThrow()
        assertEquals("Field dataset", result.source)
        assertEquals(2025, result.datasetYear)
        assertEquals(0.92, result.confidence!!, 0.000001)
        assertEquals("field-17", result.featureId)
        assertTrue(result.boundary.areaAcres > 0.0)
    }

    @Test fun rejectsPolygonWithInteriorHoleInsteadOfSilentlyIgnoringIt() {
        val rings = "[[[78.0,23.0],[78.004,23.0],[78.004,23.004],[78.0,23.004],[78.0,23.0]]," +
            "[[78.001,23.001],[78.002,23.001],[78.002,23.002],[78.001,23.002],[78.001,23.001]]]"
        val result = AkkpsSatelliteCandidateParser.parse(feature(rings = rings))
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("interior holes"))
    }

    @Test fun rejectsOutOfRangeDatasetYear() {
        assertFalse(AkkpsSatelliteCandidateParser.parse(
            feature(properties = """{"year":1800}""")
        ).isSuccess)
    }

    @Test fun rejectsMalformedDatasetYear() {
        assertFalse(AkkpsSatelliteCandidateParser.parse(
            feature(properties = """{"year":"unknown"}""")
        ).isSuccess)
    }

    @Test fun rejectsOutOfRangeOrNonNumericConfidence() {
        assertFalse(AkkpsSatelliteCandidateParser.parse(
            feature(properties = """{"confidence":1.2}""")
        ).isSuccess)
        assertFalse(AkkpsSatelliteCandidateParser.parse(
            feature(properties = """{"confidence":"high"}""")
        ).isSuccess)
    }

    @Test fun acceptsMissingOptionalMetadataWithoutInventingValues() {
        val result = AkkpsSatelliteCandidateParser.parse(
            feature(properties = """{"source":"Imported dataset"}""", id = "null")
        ).getOrThrow()
        assertEquals(null, result.datasetYear)
        assertEquals(null, result.confidence)
        assertEquals(null, result.featureId)
    }

    @Test fun rejectsMissingOrBlankSourceProvenance() {
        assertFalse(AkkpsSatelliteCandidateParser.parse(
            feature(properties = """{"year":2025}""")
        ).isSuccess)
        assertFalse(AkkpsSatelliteCandidateParser.parse(
            feature(properties = """{"source":"   "}""")
        ).isSuccess)
    }

    @Test fun rejectsSourceMetadataThatWouldOtherwiseBeTruncated() {
        val longSource = "x".repeat(121)
        val properties = """{"source":"$longSource"}"""
        assertFalse(AkkpsSatelliteCandidateParser.parse(
            feature(properties = properties)
        ).isSuccess)
    }

    @Test fun fallsBackToDatasetYearWhenPrimaryYearIsExplicitNull() {
        val result = AkkpsSatelliteCandidateParser.parse(
            feature(properties = """{"source":"Dataset","year":null,"dataset_year":2024}""")
        ).getOrThrow()
        assertEquals(2024, result.datasetYear)
    }

    @Test fun rejectsGeoJsonLinearRingThatIsNotClosed() {
        val rings = "[[[78.0,23.0],[78.001,23.0],[78.001,23.001],[78.0,23.001]]]"
        val result = AkkpsSatelliteCandidateParser.parse(feature(rings = rings))
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("पहला और आखिरी"))
    }

    @Test fun rejectsCoordinateTuplesWithUnexpectedDimensions() {
        val rings = "[[[78.0,23.0,0,9],[78.001,23.0,0,9],[78.001,23.001,0,9],[78.0,23.001,0,9],[78.0,23.0,0,9]]]"
        assertFalse(AkkpsSatelliteCandidateParser.parse(feature(rings = rings)).isSuccess)
    }

    @Test fun acceptsStandardThreeDimensionalClosedRing() {
        val rings = "[[[78.0,23.0,0],[78.001,23.0,0],[78.001,23.001,0],[78.0,23.001,0],[78.0,23.0,0]]]"
        assertTrue(AkkpsSatelliteCandidateParser.parse(feature(rings = rings)).isSuccess)
    }

    @Test fun rejectsStringLongitudeOrLatitudeValues() {
        val stringLongitude = """[[["78.0",23.0],[78.001,23.0],[78.001,23.001],[78.0,23.001],[78.0,23.0]]]"""
        val stringLatitude = """[[[78.0,"23.0"],[78.001,23.0],[78.001,23.001],[78.0,23.001],[78.0,23.0]]]"""
        assertFalse(AkkpsSatelliteCandidateParser.parse(feature(rings = stringLongitude)).isSuccess)
        assertFalse(AkkpsSatelliteCandidateParser.parse(feature(rings = stringLatitude)).isSuccess)
    }

    @Test fun rejectsNonNumericOrNonFiniteAltitude() {
        val stringAltitude = """[[[78.0,23.0,"high"],[78.001,23.0,0],[78.001,23.001,0],[78.0,23.001,0],[78.0,23.0,0]]]"""
        val nullAltitude = """[[[78.0,23.0,null],[78.001,23.0,0],[78.001,23.001,0],[78.0,23.001,0],[78.0,23.0,0]]]"""
        assertFalse(AkkpsSatelliteCandidateParser.parse(feature(rings = stringAltitude)).isSuccess)
        assertFalse(AkkpsSatelliteCandidateParser.parse(feature(rings = nullAltitude)).isSuccess)
    }

    @Test fun rejectsUnsupportedCrs() {
        val raw = feature().replace(
            "\"type\":\"Feature\"",
            "\"type\":\"Feature\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:3857\"}}"
        )
        assertFalse(AkkpsSatelliteCandidateParser.parse(raw).isSuccess)
    }
}
