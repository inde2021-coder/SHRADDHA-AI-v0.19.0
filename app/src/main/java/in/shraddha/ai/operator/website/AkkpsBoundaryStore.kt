package `in`.shraddha.ai.operator.website

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sin

data class AkkpsGeoPoint(val latitude: Double, val longitude: Double)
data class AkkpsBoundary(val points: List<AkkpsGeoPoint>) {
    val areaSquareMeters: Double get() = AkkpsBoundaryGeometry.areaSquareMeters(points)
    val areaAcres: Double get() = areaSquareMeters / AkkpsBoundaryGeometry.SQUARE_METERS_PER_ACRE
}

/** Local boundary geometry and persistence. Coordinates are user-collected, never satellite-inferred. */
object AkkpsBoundaryGeometry {
    const val MAX_POINTS = 500
    const val EARTH_RADIUS_METERS = 6_378_137.0
    const val SQUARE_METERS_PER_ACRE = 4_046.8564224
    fun closeRing(points: List<AkkpsGeoPoint>) =
        if (points.isNotEmpty() && points.first() != points.last()) points + points.first() else points.toList()
    fun areaSquareMeters(points: List<AkkpsGeoPoint>): Double {
        val ring = closeRing(points)
        if (ring.size < 4) return 0.0
        var sum = 0.0
        for (i in 0 until ring.lastIndex) {
            val a = ring[i]; val b = ring[i + 1]
            sum += Math.toRadians(b.longitude - a.longitude) *
                (2.0 + sin(Math.toRadians(a.latitude)) + sin(Math.toRadians(b.latitude)))
        }
        return abs(sum * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS / 2.0)
    }
    fun validate(points: List<AkkpsGeoPoint>): String? {
        if (points.size !in 3..MAX_POINTS) return "कम-से-कम 3 और अधिकतम $MAX_POINTS GPS points चाहिए।"
        if (points.any { !it.latitude.isFinite() || !it.longitude.isFinite() || it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0 })
            return "Boundary में invalid GPS coordinates हैं।"
        if (points.distinct().size < 3) return "कम-से-कम 3 अलग GPS points चाहिए।"
        if (areaSquareMeters(points) < 1.0) return "Boundary area शून्य या बहुत छोटा है।"
        return null
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
        val points = (0 until array.length()).map { i ->
            val p = array.getJSONObject(i); AkkpsGeoPoint(p.getDouble("lat"), p.getDouble("lon"))
        }
        validate(points)?.let { error(it) }
        return AkkpsBoundary(points)
    }
    fun toKml(boundary: AkkpsBoundary, name: String): String {
        validate(boundary.points)?.let { error(it) }
        val safeName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").take(80)
        val coords = closeRing(boundary.points).joinToString(" ") { "${it.longitude},${it.latitude},0" }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><Placemark><name>$safeName</name><Polygon><outerBoundaryIs><LinearRing><coordinates>$coords</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Document></kml>"
    }
}

class AkkpsBoundaryStore(context: android.content.Context) {
    private val encrypted = `in`.shraddha.ai.brain.EncryptedFeatureStore(context.applicationContext)

    fun save(plotId: String, boundary: AkkpsBoundary) {
        encrypted.put(key(plotId), AkkpsBoundaryGeometry.toJson(boundary)).getOrThrow()
    }

    fun load(plotId: String): AkkpsBoundary? {
        val raw = encrypted.get(key(plotId)).getOrThrow()
        if (raw.isBlank()) return null
        return AkkpsBoundaryGeometry.fromJson(raw)
    }

    fun delete(plotId: String) {
        check(encrypted.remove(key(plotId))) { "Boundary local delete failed." }
    }

    private fun key(plotId: String): String {
        val id = plotId.trim()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
            "Plot ID में केवल अक्षर, अंक, _ और - मान्य हैं।"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return "akkps_boundary_$digest"
    }
}
