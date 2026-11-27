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
        require(rings.length() == 1) {
            "Polygon में interior holes हैं; holes-aware area calculation आने तक इसे import नहीं किया जाएगा।"
        }
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
        val sourceValue = listOf("source", "dataset", "provider")
            .asSequence()
            .mapNotNull { key -> properties.opt(key)?.takeUnless { it == org.json.JSONObject.NULL } }
            .map { it.toString().trim() }
            .firstOrNull { it.isNotBlank() }
            ?: error("Boundary candidate का source/dataset/provider बताना आवश्यक है।")
        require(sourceValue.length <= MAX_SOURCE_LENGTH) {
            "Boundary candidate source metadata 120 characters से लंबा है।"
        }
        val source = sourceValue
        val yearValue = listOf("year", "dataset_year")
            .asSequence()
            .mapNotNull { key -> properties.opt(key)?.takeUnless { it == org.json.JSONObject.NULL } }
            .firstOrNull()
        val year = yearValue?.let {
            it.toString().toIntOrNull()?.also { parsed ->
                require(parsed in 1900..2100) { "Dataset year 1900 से 2100 के बीच होना चाहिए।" }
            } ?: error("Dataset year मान्य पूर्णांक नहीं है।")
        }
        val confidenceValue = properties.opt("confidence").takeUnless { it == org.json.JSONObject.NULL }
        val confidence = confidenceValue?.let {
            it.toString().toDoubleOrNull()?.also { parsed ->
                require(parsed.isFinite() && parsed in 0.0..1.0) {
                    "Confidence 0.0 से 1.0 के बीच होना चाहिए।"
                }
            } ?: error("Confidence संख्या मान्य नहीं है।")
        }
        val featureIdValue = feature.opt("id")
        val featureId = when (featureIdValue) {
            null, org.json.JSONObject.NULL -> null
            is String -> featureIdValue.trim().takeIf { it.isNotBlank() }
            is Number -> featureIdValue.toString()
            else -> error("GeoJSON Feature ID string या number होना चाहिए।")
        }
        AkkpsSatelliteBoundaryCandidate(
            boundary = AkkpsBoundary(AkkpsBoundaryGeometry.closeRing(points)),
            source = source,
            datasetYear = year,
            confidence = confidence,
            featureId = featureId
        )
    }
}
