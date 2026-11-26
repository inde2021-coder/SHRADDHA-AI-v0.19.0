package `in`.shraddha.ai.operator.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AkkpsWebMercatorProjectionTest {
    @Test fun viewportCenterMapsToObservedMapCenter() {
        val center = AkkpsGeoPoint(21.5, 80.2)
        val viewport = AkkpsWebMercatorProjection.Viewport(center, 18.0, 1080.0, 1920.0)
        val actual = AkkpsWebMercatorProjection.viewportPixelToWgs84(viewport, 540.0, 960.0)
        assertEquals(center.latitude, actual.latitude, 1e-9)
        assertEquals(center.longitude, actual.longitude, 1e-9)
    }

    @Test fun pixelRightOfCenterMovesEastAndPixelBelowMovesSouth() {
        val viewport = AkkpsWebMercatorProjection.Viewport(
            AkkpsGeoPoint(21.5, 80.2), 18.0, 1000.0, 800.0
        )
        val center = AkkpsWebMercatorProjection.viewportPixelToWgs84(viewport, 500.0, 400.0)
        val east = AkkpsWebMercatorProjection.viewportPixelToWgs84(viewport, 600.0, 400.0)
        val south = AkkpsWebMercatorProjection.viewportPixelToWgs84(viewport, 500.0, 500.0)
        assertTrue(east.longitude > center.longitude)
        assertTrue(south.latitude < center.latitude)
    }

    @Test fun rejectsMissingOrInvalidViewportMetadata() {
        val badZoom = AkkpsWebMercatorProjection.Viewport(AkkpsGeoPoint(21.5, 80.2), 31.0, 500.0, 500.0)
        assertTrue(runCatching {
            AkkpsWebMercatorProjection.viewportPixelToWgs84(badZoom, 250.0, 250.0)
        }.isFailure)
        val badCenter = AkkpsWebMercatorProjection.Viewport(AkkpsGeoPoint(90.0, 80.2), 18.0, 500.0, 500.0)
        assertTrue(runCatching {
            AkkpsWebMercatorProjection.viewportPixelToWgs84(badCenter, 250.0, 250.0)
        }.isFailure)
    }
}
