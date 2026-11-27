package `in`.shraddha.ai.operator.website

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

data class AkkpsGeoPoint(val latitude: Double, val longitude: Double)
data class AkkpsBoundary(val points: List<AkkpsGeoPoint>) {
    val areaSquareMeters: Double get() = AkkpsBoundaryGeometry.areaSquareMeters(points)
    val areaAcres: Double get() = areaSquareMeters / AkkpsBoundaryGeometry.SQUARE_METERS_PER_ACRE
}

/** Coordinate/KML geometry foundation. It does not infer a boundary from satellite pixels. */
object AkkpsBoundaryGeometry {
    const val MAX_POINTS = 500
    const val EARTH_RADIUS_METERS = 6_378_137.0
    const val SQUARE_METERS_PER_ACRE = 4_046.8564224
    private val coordinatesTag = Regex("(?is)<coordinates\\b[^>]*>(.*?)</coordinates>")

    fun validate(points: List<AkkpsGeoPoint>): String? {
        if (points.size !in 3..MAX_POINTS) return "Boundary में 3 से $MAX_POINTS points होने चाहिए।"
        if (points.any { !it.latitude.isFinite() || !it.longitude.isFinite() || it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0 }) return "Boundary में invalid latitude/longitude है।"
        val ring = closeRing(points)
        val open = ring.dropLast(1)
        if (open.distinct().size < 3) return "कम-से-कम 3 अलग boundary points चाहिए।"
        if (open.zipWithNext().any { (a, b) -> a == b }) return "लगातार दो boundary points एक जैसे नहीं हो सकते।"
        if (hasSelfIntersection(ring)) return "Boundary polygon आपस में cross कर रहा है।"
        if (areaSquareMeters(ring) < 1.0) return "Boundary का area बहुत छोटा या शून्य है।"
        return null
    }

    fun closeRing(points: List<AkkpsGeoPoint>): List<AkkpsGeoPoint> =
        if (points.isNotEmpty() && points.first() != points.last()) points + points.first() else points.toList()

    fun areaSquareMeters(points: List<AkkpsGeoPoint>): Double {
        val ring = closeRing(points)
        if (ring.size < 4) return 0.0
        var sum = 0.0
        for (i in 0 until ring.lastIndex) {
            val a = ring[i]
            val b = ring[i + 1]
            val lonDelta = Math.toRadians(b.longitude - a.longitude)
            val latA = Math.toRadians(a.latitude)
            val latB = Math.toRadians(b.latitude)
            sum += lonDelta * (2.0 + sin(latA) + sin(latB))
        }
        return abs(sum * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS / 2.0)
    }

    /** Parse the outer KML coordinates tuple order: longitude,latitude[,altitude]. */
    fun parseKml(kml: String): Result<AkkpsBoundary> = runCatching {
        require(kml.length in 1..2_000_000) { "KML file size is invalid." }
        val coordinateText = coordinatesTag.find(kml)?.groupValues?.get(1)
            ?: error("KML में coordinates section नहीं मिला।")
        val points = coordinateText.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { tuple ->
                val values = tuple.split(',')
                require(values.size >= 2) { "KML coordinate tuple अधूरा है।" }
                val longitude = values[0].toDouble()
                val latitude = values[1].toDouble()
                AkkpsGeoPoint(latitude, longitude)
            }
        validate(points)?.let { error(it) }
        AkkpsBoundary(closeRing(points))
    }

    fun toJson(boundary: AkkpsBoundary): String {
        validate(boundary.points)?.let { error(it) }
        return JSONObject().put("version", 1).put("points", JSONArray().apply {
            boundary.points.forEach { put(JSONObject().put("lat", it.latitude).put("lon", it.longitude)) }
        }).toString()
    }

    fun fromJson(raw: String): AkkpsBoundary {
        require(raw.length in 1..200_000) { "Saved boundary data size is invalid." }
        val array = JSONObject(raw).getJSONArray("points")
        require(array.length() in 3..MAX_POINTS) { "Saved boundary point count is invalid." }
        val points = (0 until array.length()).map { index ->
            val point = array.getJSONObject(index)
            AkkpsGeoPoint(point.getDouble("lat"), point.getDouble("lon"))
        }
        validate(points)?.let { error(it) }
        return AkkpsBoundary(points)
    }

    fun toKml(boundary: AkkpsBoundary, name: String = "AKKPS Plot Boundary"): String {
        validate(boundary.points)?.let { error(it) }
        val safeName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").take(100)
        val coordinates = closeRing(boundary.points).joinToString(" ") { "${it.longitude},${it.latitude},0" }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><Placemark><name>$safeName</name>" +
            "<Polygon><outerBoundaryIs><LinearRing><coordinates>$coordinates</coordinates></LinearRing></outerBoundaryIs></Polygon>" +
            "</Placemark></Document></kml>"
    }

    private fun hasSelfIntersection(closed: List<AkkpsGeoPoint>): Boolean {
        val points = closed.dropLast(1)
        fun orient(a: AkkpsGeoPoint, b: AkkpsGeoPoint, c: AkkpsGeoPoint): Double =
            (b.longitude - a.longitude) * (c.latitude - a.latitude) - (b.latitude - a.latitude) * (c.longitude - a.longitude)
        fun intersects(a: AkkpsGeoPoint, b: AkkpsGeoPoint, c: AkkpsGeoPoint, d: AkkpsGeoPoint): Boolean {
            val o1 = orient(a, b, c); val o2 = orient(a, b, d)
            val o3 = orient(c, d, a); val o4 = orient(c, d, b)
            return o1 * o2 < 0.0 && o3 * o4 < 0.0
        }
        for (i in points.indices) for (j in i + 1 until points.size) {
            val iNext = (i + 1) % points.size
            val jNext = (j + 1) % points.size
            if (i == j || iNext == j || jNext == i) continue
            if (intersects(points[i], points[iNext], points[j], points[jNext])) return true
        }
        return false
    }
}
