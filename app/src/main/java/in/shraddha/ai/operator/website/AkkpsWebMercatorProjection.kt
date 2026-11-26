package `in`.shraddha.ai.operator.website

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Converts viewport-local CSS pixels to WGS84 using the map's observed center and zoom.
 * This is a projection utility only: callers must obtain center/zoom from the live AKKPS map.
 * It must not be used with guessed center, zoom, image crop, or device-pixel coordinates.
 */
object AkkpsWebMercatorProjection {
    private const val TILE_SIZE = 256.0
    private const val MAX_LATITUDE = 85.0511287798066

    data class Viewport(
        val center: AkkpsGeoPoint,
        val zoom: Double,
        val widthCssPixels: Double,
        val heightCssPixels: Double
    )

    fun viewportPixelToWgs84(viewport: Viewport, xCssPixels: Double, yCssPixels: Double): AkkpsGeoPoint {
        require(viewport.zoom.isFinite() && viewport.zoom in 0.0..30.0) { "Map zoom is invalid." }
        require(viewport.widthCssPixels.isFinite() && viewport.widthCssPixels > 0.0)
        require(viewport.heightCssPixels.isFinite() && viewport.heightCssPixels > 0.0)
        require(xCssPixels.isFinite() && yCssPixels.isFinite())
        require(viewport.center.latitude.isFinite() &&
            viewport.center.latitude in -MAX_LATITUDE..MAX_LATITUDE &&
            viewport.center.longitude.isFinite() &&
            viewport.center.longitude in -180.0..180.0) { "Map center is outside Web Mercator bounds." }

        val scale = TILE_SIZE * Math.pow(2.0, viewport.zoom)
        val centerX = (viewport.center.longitude + 180.0) / 360.0 * scale
        val latRad = Math.toRadians(viewport.center.latitude)
        val centerY = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * scale
        val worldX = centerX + xCssPixels - viewport.widthCssPixels / 2.0
        val worldY = centerY + yCssPixels - viewport.heightCssPixels / 2.0
        val wrappedX = ((worldX % scale) + scale) % scale
        val longitude = wrappedX / scale * 360.0 - 180.0
        val mercator = PI * (1.0 - 2.0 * worldY / scale)
        val latitude = Math.toDegrees(atan(sinh(mercator))).coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        return AkkpsGeoPoint(latitude, longitude)
    }
}
