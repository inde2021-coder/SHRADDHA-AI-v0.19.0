package `in`.shraddha.ai.operator.website

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

data class AkkpsGeoPoint(val latitude: Double, val longitude: Double)
data class AkkpsBoundary(
    val points: List<AkkpsGeoPoint>,
    val source: String? = null,
    val datasetYear: Int? = null,
    val confidence: Double? = null,
    val sourceFeatureId: String? = null
) {
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
        if (areaSquareMeters(ring) < 1.0) return "Boundary का area बहुत छोटा या शून्य है।"
        if (hasSelfIntersection(ring)) return "Boundary polygon आपस में cross कर रहा है।"
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

    data class Measurement(val squareMeters: Double) {
        val hectares: Double get() = squareMeters / 10_000.0
        val acres: Double get() = squareMeters / SQUARE_METERS_PER_ACRE
    }

    /** Returns a validated area measurement; malformed or self-crossing rings fail closed. */
    fun measure(points: List<AkkpsGeoPoint>): Measurement {
        validate(points)?.let { error(it) }
        return Measurement(areaSquareMeters(points))
    }

    /**
     * True only when a valid candidate is disjoint from every valid protected parcel.
     * Touching edges/vertices count as overlap. Geometry is evaluated in the local
     * longitude/latitude plane, appropriate for adjacent rural plots at local scale.
     */
    fun doesNotOverlapExisting(
        candidate: List<AkkpsGeoPoint>,
        existingBoundaries: List<List<AkkpsGeoPoint>>
    ): Boolean {
        if (validate(candidate) != null) return false
        val candidateRing = closeRing(candidate)
        for (existing in existingBoundaries) {
            if (validate(existing) != null) return false
            val existingRing = closeRing(existing)
            for (i in 0 until candidateRing.lastIndex) {
                for (j in 0 until existingRing.lastIndex) {
                    if (segmentsTouchOrCross(candidateRing[i], candidateRing[i + 1],
                            existingRing[j], existingRing[j + 1])) return false
                }
            }
            if (pointInOrOnPolygon(candidateRing.first(), existingRing) ||
                pointInOrOnPolygon(existingRing.first(), candidateRing)) return false
        }
        return true
    }

    private fun segmentsTouchOrCross(
        a: AkkpsGeoPoint, b: AkkpsGeoPoint, c: AkkpsGeoPoint, d: AkkpsGeoPoint
    ): Boolean {
        fun cross(p: AkkpsGeoPoint, q: AkkpsGeoPoint, r: AkkpsGeoPoint) =
            (q.longitude - p.longitude) * (r.latitude - p.latitude) -
                (q.latitude - p.latitude) * (r.longitude - p.longitude)
        fun onSegment(p: AkkpsGeoPoint, q: AkkpsGeoPoint, r: AkkpsGeoPoint): Boolean {
            val epsilon = 1e-12
            return r.longitude >= minOf(p.longitude, q.longitude) - epsilon &&
                r.longitude <= maxOf(p.longitude, q.longitude) + epsilon &&
                r.latitude >= minOf(p.latitude, q.latitude) - epsilon &&
                r.latitude <= maxOf(p.latitude, q.latitude) + epsilon
        }
        val epsilon = 1e-12
        val abC = cross(a, b, c); val abD = cross(a, b, d)
        val cdA = cross(c, d, a); val cdB = cross(c, d, b)
        if (((abC > epsilon && abD < -epsilon) || (abC < -epsilon && abD > epsilon)) &&
            ((cdA > epsilon && cdB < -epsilon) || (cdA < -epsilon && cdB > epsilon))) return true
        return (abs(abC) <= epsilon && onSegment(a, b, c)) ||
            (abs(abD) <= epsilon && onSegment(a, b, d)) ||
            (abs(cdA) <= epsilon && onSegment(c, d, a)) ||
            (abs(cdB) <= epsilon && onSegment(c, d, b))
    }

    private fun pointInOrOnPolygon(point: AkkpsGeoPoint, closedRing: List<AkkpsGeoPoint>): Boolean {
        var inside = false
        for (i in 0 until closedRing.lastIndex) {
            val a = closedRing[i]
            val b = closedRing[i + 1]
            if (segmentsTouchOrCross(a, b, point, point)) return true
            val straddles = (a.latitude > point.latitude) != (b.latitude > point.latitude)
            if (straddles) {
                val crossingLongitude = a.longitude +
                    (point.latitude - a.latitude) * (b.longitude - a.longitude) /
                    (b.latitude - a.latitude)
                if (point.longitude < crossingLongitude) inside = !inside
            }
        }
        return inside
    }

    /** Parse the outer KML coordinates tuple order: longitude,latitude[,altitude]. */
    fun parseKml(kml: String): Result<AkkpsBoundary> = runCatching {
        require(kml.length in 1..2_000_000) { "KML file size is invalid." }
        require(!Regex("(?is)<!\\s*(DOCTYPE|ENTITY)\\b").containsMatchIn(kml)) {
            "KML में DTD या custom entity declarations स्वीकार नहीं हैं।"
        }
        require(!Regex("(?is)<!\\[CDATA\\[").containsMatchIn(kml)) {
            "KML में CDATA sections स्वीकार नहीं हैं।"
        }
        require(Regex("(?is)<\\s*Polygon\\b").findAll(kml).count() == 1) {
            "KML में केवल एक Polygon स्वीकार है; MultiGeometry/MultiPolygon import नहीं होगा।"
        }
        require(!Regex("(?is)<\\s*innerBoundaryIs\\b").containsMatchIn(kml)) {
            "KML Polygon में interior holes हैं; holes-aware area calculation आने तक import नहीं होगा।"
        }
        require(Regex("(?is)<\\s*outerBoundaryIs\\b").findAll(kml).count() == 1) {
            "KML में एक स्पष्ट outer boundary ring होना चाहिए।"
        }
        val coordinateMatches = coordinatesTag.findAll(kml).toList()
        require(coordinateMatches.size == 1) { "KML में exactly one coordinates ring होना चाहिए।" }
        val coordinateText = coordinateMatches.single().groupValues[1]
        val points = coordinateText.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { tuple ->
                val values = tuple.split(',')
                require(values.size in 2..3) { "KML coordinate tuple में longitude, latitude और optional altitude ही मान्य हैं।" }
                val longitude = values[0].toDouble()
                val latitude = values[1].toDouble()
                if (values.size == 3 && values[2].isNotBlank()) {
                    require(values[2].toDouble().isFinite()) { "KML altitude finite number होना चाहिए।" }
                }
                AkkpsGeoPoint(latitude, longitude)
            }
        validate(points)?.let { error(it) }
        fun metadata(name: String): String? {
            val pattern = Regex("""(?is)<Data\s+name="${Regex.escape(name)}"\s*>\s*<value>(.*?)</value>\s*</Data>""")
            val matches = pattern.findAll(kml).toList()
            require(matches.size <= 1) { "KML metadata '$name' दोहराया गया है; import रोक दिया गया।" }
            return matches.singleOrNull()?.groupValues?.get(1)?.let(::xmlUnescape)?.takeIf { it.isNotBlank() }
        }
        val source = metadata("source")
        val yearText = metadata("datasetYear")
        val year = yearText?.toIntOrNull()
        require(yearText == null || year != null) { "KML dataset year is invalid." }
        val confidenceText = metadata("confidence")
        val confidence = confidenceText?.toDoubleOrNull()
        require(confidenceText == null || confidence != null) { "KML confidence is invalid." }
        val canonicalPoints = if (points.size > 3 && points.first() == points.last()) points.dropLast(1) else points
        val boundary = AkkpsBoundary(canonicalPoints, source, year, confidence, metadata("sourceFeatureId"))
        validateProvenance(boundary)
        boundary
    }

    fun toJson(boundary: AkkpsBoundary): String {
        validate(boundary.points)?.let { error(it) }
        validateProvenance(boundary)
        return JSONObject().put("version", 2).put("points", JSONArray().apply {
            boundary.points.forEach { put(JSONObject().put("lat", it.latitude).put("lon", it.longitude)) }
        }).apply {
            boundary.source?.let { put("source", it) }
            boundary.datasetYear?.let { put("datasetYear", it) }
            boundary.confidence?.let { put("confidence", it) }
            boundary.sourceFeatureId?.let { put("sourceFeatureId", it) }
        }.toString()
    }

    fun fromJson(raw: String): AkkpsBoundary {
        require(raw.length in 1..200_000) { "Saved boundary data size is invalid." }
        val json = JSONObject(raw)
        val version = json.optInt("version", 1)
        require(version in 1..2) { "Saved boundary version is unsupported." }
        val array = json.getJSONArray("points")
        require(array.length() in 3..MAX_POINTS) { "Saved boundary point count is invalid." }
        val points = (0 until array.length()).map { index ->
            val point = array.getJSONObject(index)
            AkkpsGeoPoint(point.getDouble("lat"), point.getDouble("lon"))
        }
        validate(points)?.let { error(it) }
        val source = json.optString("source").takeIf { it.isNotBlank() }
        val datasetYear = if (json.has("datasetYear") && !json.isNull("datasetYear")) json.getInt("datasetYear") else null
        val confidence = if (json.has("confidence") && !json.isNull("confidence")) json.getDouble("confidence") else null
        val featureId = json.optString("sourceFeatureId").takeIf { it.isNotBlank() }
        val boundary = AkkpsBoundary(points, source, datasetYear, confidence, featureId)
        validateProvenance(boundary)
        return boundary
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun xmlUnescape(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun validateProvenance(boundary: AkkpsBoundary) {
        require(boundary.source == null || (boundary.source.isNotBlank() && boundary.source.length <= 120)) {
            "Boundary source metadata is invalid."
        }
        require(boundary.datasetYear == null || boundary.datasetYear in 1900..2100) {
            "Boundary dataset year is invalid."
        }
        require(boundary.confidence == null || (boundary.confidence.isFinite() && boundary.confidence in 0.0..1.0)) {
            "Boundary confidence is invalid."
        }
        require(boundary.sourceFeatureId == null || boundary.sourceFeatureId.length <= 120) {
            "Boundary source feature ID is too long."
        }
    }

    fun toKml(boundary: AkkpsBoundary, name: String = "AKKPS Plot Boundary"): String {
        validate(boundary.points)?.let { error(it) }
        validateProvenance(boundary)
        val safeName = xmlEscape(name).take(100)
        val coordinates = closeRing(boundary.points).joinToString(" ") { "${it.longitude},${it.latitude},0" }
        val metadata = buildString {
            boundary.source?.let { append("<Data name=\"source\"><value>${xmlEscape(it)}</value></Data>") }
            boundary.datasetYear?.let { append("<Data name=\"datasetYear\"><value>$it</value></Data>") }
            boundary.confidence?.let { append("<Data name=\"confidence\"><value>$it</value></Data>") }
            boundary.sourceFeatureId?.let { append("<Data name=\"sourceFeatureId\"><value>${xmlEscape(it)}</value></Data>") }
        }
        val extendedData = if (metadata.isNotEmpty()) "<ExtendedData>$metadata</ExtendedData>" else ""
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><Placemark><name>$safeName</name>$extendedData" +
            "<Polygon><outerBoundaryIs><LinearRing><coordinates>$coordinates</coordinates></LinearRing></outerBoundaryIs></Polygon>" +
            "</Placemark></Document></kml>"
    }

    private fun hasSelfIntersection(closed: List<AkkpsGeoPoint>): Boolean {
        val points = closed.dropLast(1)
        fun orient(a: AkkpsGeoPoint, b: AkkpsGeoPoint, c: AkkpsGeoPoint): Double =
            (b.longitude - a.longitude) * (c.latitude - a.latitude) - (b.latitude - a.latitude) * (c.longitude - a.longitude)
        fun onSegment(a: AkkpsGeoPoint, b: AkkpsGeoPoint, p: AkkpsGeoPoint): Boolean {
            val epsilon = 1e-12
            return p.longitude >= minOf(a.longitude, b.longitude) - epsilon &&
                p.longitude <= maxOf(a.longitude, b.longitude) + epsilon &&
                p.latitude >= minOf(a.latitude, b.latitude) - epsilon &&
                p.latitude <= maxOf(a.latitude, b.latitude) + epsilon
        }
        fun intersects(a: AkkpsGeoPoint, b: AkkpsGeoPoint, c: AkkpsGeoPoint, d: AkkpsGeoPoint): Boolean {
            val o1 = orient(a, b, c); val o2 = orient(a, b, d)
            val o3 = orient(c, d, a); val o4 = orient(c, d, b)
            val epsilon = 1e-12
            val proper = ((o1 > epsilon && o2 < -epsilon) || (o1 < -epsilon && o2 > epsilon)) &&
                ((o3 > epsilon && o4 < -epsilon) || (o3 < -epsilon && o4 > epsilon))
            if (proper) return true
            return (abs(o1) <= epsilon && onSegment(a, b, c)) ||
                (abs(o2) <= epsilon && onSegment(a, b, d)) ||
                (abs(o3) <= epsilon && onSegment(c, d, a)) ||
                (abs(o4) <= epsilon && onSegment(c, d, b))
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
