package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsKmlImportValidationTest {
    private fun kml(coordinates: String, extra: String = "") =
        """<?xml version="1.0"?><kml><Document><Placemark>$extra<Polygon><outerBoundaryIs><LinearRing><coordinates>$coordinates</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Document></kml>"""

    private val rectangle = "78.0,23.0,0 78.001,23.0,0 78.001,23.001,0 78.0,23.001,0 78.0,23.0,0"

    @Test fun acceptsOneClosedOuterRing() {
        val parsed = AkkpsBoundaryGeometry.parseKml(kml(rectangle)).getOrThrow()
        assertEquals(5, parsed.points.size)
    }

    @Test fun rejectsMultiplePolygonsAndCoordinateSections() {
        val twoPolygons = kml(rectangle).replace("</Placemark>", "<Polygon><outerBoundaryIs><LinearRing><coordinates>$rectangle</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>")
        assertTrue(AkkpsBoundaryGeometry.parseKml(twoPolygons).isFailure)
        val twoCoordinateBlocks = kml(rectangle).replace("</Placemark>", "<coordinates>$rectangle</coordinates></Placemark>")
        assertTrue(AkkpsBoundaryGeometry.parseKml(twoCoordinateBlocks).isFailure)
    }

    @Test fun rejectsInteriorRingsAndUnwrappedCoordinateContainers() {
        val withHole = kml(rectangle).replace(
            "</Polygon>",
            "<innerBoundaryIs><LinearRing><coordinates>78,23 78,23.0001 78.0001,23 78,23</coordinates></LinearRing></innerBoundaryIs></Polygon>"
        )
        assertTrue(AkkpsBoundaryGeometry.parseKml(withHole).isFailure)
        val withoutOuter = kml(rectangle).replace("<outerBoundaryIs>", "").replace("</outerBoundaryIs>", "")
        assertTrue(AkkpsBoundaryGeometry.parseKml(withoutOuter).isFailure)
    }

    @Test fun rejectsExtraDimensionsAndNonFiniteAltitude() {
        assertTrue(AkkpsBoundaryGeometry.parseKml(kml(
            "78,23,0,9 78.001,23,0,9 78.001,23.001,0,9 78,23.001,0,9 78,23,0,9"
        )).isFailure)
        assertTrue(AkkpsBoundaryGeometry.parseKml(kml(
            "78,23,NaN 78.001,23,0 78.001,23.001,0 78,23.001,0 78,23,0"
        )).isFailure)
    }
}
