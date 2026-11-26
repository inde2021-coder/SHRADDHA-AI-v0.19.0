package `in`.shraddha.ai.operator.website

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Geographic polygon math used by the offline AKKPS map boundary assistant. */
data class AkkpsGeoPoint(val latitude: Double, val longitude: Double)

data class AkkpsBoundaryMeasurement(
    val squareMeters: Double,
    val hectares: Double,
    val acres: Double
)

object AkkpsBoundaryGeometry {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /**
     * Computes spherical polygon area without a network, map SDK, or API key.
     * Points must be supplied in perimeter order; the ring is closed automatically.
     */
    fun measure(points: List<AkkpsGeoPoint>): AkkpsBoundaryMeasurement {
        require(points.size >= 3) { "कम-से-कम तीन सीमा-बिंदु आवश्यक हैं।" }
        require(points.all {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }) { "सीमा-बिंदुओं के latitude/longitude मान अमान्य हैं।" }

        val distinct = points.distinctBy { it.latitude to it.longitude }
        require(distinct.size >= 3) { "तीन अलग-अलग सीमा-बिंदु आवश्यक हैं।" }
        require(points.zipWithNext().none { (a, b) ->
            a.latitude == b.latitude && a.longitude == b.longitude
        }) { "लगातार दो सीमा-बिंदु एक जैसे नहीं हो सकते।" }
        require(!hasSelfIntersection(points)) {
            "बाउंड्री की रेखाएँ आपस में काट रही हैं; ऐसे polygon का क्षेत्रफल स्वीकार नहीं होगा।"
        }

        var sum = 0.0
        for (i in points.indices) {
            val current = points[i]
            val next = points[(i + 1) % points.size]
            val lon1 = current.longitude * PI / 180.0
            val lon2 = next.longitude * PI / 180.0
            val lat1 = current.latitude * PI / 180.0
            val lat2 = next.latitude * PI / 180.0
            sum += (lon2 - lon1) * (2.0 + sin(lat1) + sin(lat2))
        }
        val squareMeters = abs(sum) * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS / 2.0
        require(squareMeters.isFinite() && squareMeters > 0.01) {
            "बाउंड्री का क्षेत्रफल शून्य या अमान्य है।"
        }
        return AkkpsBoundaryMeasurement(
            squareMeters = squareMeters,
            hectares = squareMeters / 10_000.0,
            acres = squareMeters / 4_046.8564224
        )
    }
    /** Rejects crossing/touching non-adjacent edges before an area is accepted. */
    private fun hasSelfIntersection(points: List<AkkpsGeoPoint>): Boolean {
        fun orientation(a: AkkpsGeoPoint, b: AkkpsGeoPoint, c: AkkpsGeoPoint): Double =
            (b.longitude - a.longitude) * (c.latitude - a.latitude) -
                (b.latitude - a.latitude) * (c.longitude - a.longitude)

        fun onSegment(a: AkkpsGeoPoint, b: AkkpsGeoPoint, p: AkkpsGeoPoint): Boolean =
            p.longitude >= minOf(a.longitude, b.longitude) - 1e-12 &&
                p.longitude <= maxOf(a.longitude, b.longitude) + 1e-12 &&
                p.latitude >= minOf(a.latitude, b.latitude) - 1e-12 &&
                p.latitude <= maxOf(a.latitude, b.latitude) + 1e-12

        fun intersects(a: AkkpsGeoPoint, b: AkkpsGeoPoint, c: AkkpsGeoPoint, d: AkkpsGeoPoint): Boolean {
            val o1 = orientation(a, b, c)
            val o2 = orientation(a, b, d)
            val o3 = orientation(c, d, a)
            val o4 = orientation(c, d, b)
            if (((o1 > 1e-12 && o2 < -1e-12) || (o1 < -1e-12 && o2 > 1e-12)) &&
                ((o3 > 1e-12 && o4 < -1e-12) || (o3 < -1e-12 && o4 > 1e-12))) return true
            return (abs(o1) <= 1e-12 && onSegment(a, b, c)) ||
                (abs(o2) <= 1e-12 && onSegment(a, b, d)) ||
                (abs(o3) <= 1e-12 && onSegment(c, d, a)) ||
                (abs(o4) <= 1e-12 && onSegment(c, d, b))
        }

        for (i in points.indices) {
            val iNext = (i + 1) % points.size
            for (j in i + 1 until points.size) {
                val jNext = (j + 1) % points.size
                if (i == j || iNext == j || jNext == i) continue
                if (intersects(points[i], points[iNext], points[j], points[jNext])) return true
            }
        }
        return false
    }

}
