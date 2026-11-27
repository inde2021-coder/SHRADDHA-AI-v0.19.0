package `in`.shraddha.ai.operator.website

import org.json.JSONObject

/**
 * Imports a precomputed field-boundary candidate from a GeoJSON Feature/FeatureCollection.
 * Coordinates must be WGS84 longitude/latitude. This is NOT satellite inference.
 */
data class AkkpsSatelliteBoundaryCandidate(
    val boundary: AkkpsBoundary,
    val source: String,
    val datasetYear: Int?,
    val confidence: Double?,
    val featureId: String?
)

object AkkpsSatelliteCandidateParser {
    private const val MAX_BYTES = 2_000_000
    private const val MAX_SOURCE_LENGTH = 120

    fun parse(raw: String): Result<AkkpsSatelliteBoundaryCandidate> = runCatching {
        require(raw.isNotBlank() && raw.length <= MAX_BYTES) { "GeoJSON file खाली या 2 MB से बड़ी है।" }
        val root = JSONObject(raw)
        val crsName = root.optJSONObject("crs")
            ?.optJSONObject("properties")?.optString("name").orEmpty()
        require(crsName.isBlank() || crsName.equals("urn:ogc:def:crs:OGC:1.3:CRS84", true) ||
            crsName.endsWith("EPSG::4326", true) || crsName.endsWith("EPSG:4326", true)) {
            "GeoJSON CRS WGS84/EPSG:4326 नहीं है; coordinates convert किए बिना import नहीं होंगे।"
        }
        val feature = when (root.optString("type")) {
            "Feature" -> root
            "FeatureCollection" -> {
                val features = root.optJSONArray("features") ?: error("GeoJSON features नहीं मिले।")
                require(features.length() == 1) { "एक बार में केवल एक boundary candidate import करें।" }
                features.getJSONObject(0)
            }
            else -> error("GeoJSON में Feature या FeatureCollection होना चाहिए।")
        }
        val geometry = feature.optJSONObject("geometry") ?: error("GeoJSON geometry नहीं मिला।")
        require(geometry.optString("type") == "Polygon") {
            "केवल Polygon candidate स्वीकार है; MultiPolygon/LineString अभी समर्थित नहीं।"
        }
        val rings = geometry.optJSONArray("coordinates") ?: error("Polygon coordinates नहीं मिले।")
        require(rings.length() >= 1) { "Polygon outer ring खाली है।" }
        val outer = rings.getJSONArray(0)
        require(outer.length() in 4..(AkkpsBoundaryGeometry.MAX_POINTS + 1)) {
            "Polygon outer ring में points की संख्या अमान्य है।"
        }
        val points = (0 until outer.length()).map { index ->
            val pair = outer.getJSONArray(index)
            require(pair.length() >= 2) { "GeoJSON coordinate tuple अधूरा है।" }
            val lon = pair.getDouble(0)
            val lat = pair.getDouble(1)
            AkkpsGeoPoint(lat, lon)
        }
        val issue = AkkpsBoundaryGeometry.validate(points)
        require(issue == null) { issue ?: "Boundary अमान्य है।" }
        val properties = feature.optJSONObject("properties") ?: JSONObject()
        val source = properties.optString("source", properties.optString("dataset", "Unknown dataset"))
            .trim().take(MAX_SOURCE_LENGTH).ifBlank { "Unknown dataset" }
        val yearValue = properties.opt("year") ?: properties.opt("dataset_year")
        val year = yearValue?.toString()?.toIntOrNull()?.takeIf { it in 1900..2100 }
        val confidenceValue = properties.opt("confidence")
        val confidence = confidenceValue?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
        AkkpsSatelliteBoundaryCandidate(
            boundary = AkkpsBoundary(AkkpsBoundaryGeometry.closeRing(points)),
            source = source,
            datasetYear = year,
            confidence = confidence,
            featureId = feature.optString("id").takeIf { it.isNotBlank() }
        )
    }
}
