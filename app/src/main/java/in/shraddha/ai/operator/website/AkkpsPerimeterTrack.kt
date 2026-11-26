package `in`.shraddha.ai.operator.website

import kotlin.math.*

/**
 * Validates a perimeter recorded from an accessible field edge.
 * The caller must only accept fixes while the user is on a path, bund, or outer edge.
 */
class AkkpsPerimeterTrack(
    private val maxAccuracyMeters: Float = 8f,
    private val minPointSpacingMeters: Double = 2.0,
    private val maxPlausibleSpeedMetersPerSecond: Double = 4.5,
    private val maxClosureGapMeters: Double = 20.0
) {
    private val accepted = mutableListOf<AkkpsGeoPoint>()
    private var lastTimestampMillis: Long = Long.MIN_VALUE

    enum class AddResult { ACCEPTED, POOR_ACCURACY, TOO_CLOSE, OUT_OF_ORDER, IMPLAUSIBLE_JUMP }
    val points: List<AkkpsGeoPoint> get() = accepted.toList()

    fun add(point: AkkpsGeoPoint, accuracyMeters: Float, timestampMillis: Long): AddResult {
        require(point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
        if (!accuracyMeters.isFinite() || accuracyMeters < 0f || accuracyMeters > maxAccuracyMeters)
            return AddResult.POOR_ACCURACY
        if (timestampMillis <= lastTimestampMillis) return AddResult.OUT_OF_ORDER
        val previous = accepted.lastOrNull()
        if (previous != null) {
            val elapsed = (timestampMillis - lastTimestampMillis) / 1000.0
            val distance = distanceMeters(previous, point)
            if (distance < minPointSpacingMeters) return AddResult.TOO_CLOSE
            if (elapsed <= 0.0 || distance / elapsed > maxPlausibleSpeedMetersPerSecond)
                return AddResult.IMPLAUSIBLE_JUMP
        }
        accepted += point
        lastTimestampMillis = timestampMillis
        return AddResult.ACCEPTED
    }

    /** Requires a real return near the start; never invents a closing straight line. */
    fun finish(): List<AkkpsGeoPoint> {
        require(accepted.size >= 4) { "खेत के किनारे कम-से-कम चार अच्छे GPS बिंदु रिकॉर्ड करें।" }
        val closure = distanceMeters(accepted.first(), accepted.last())
        require(closure <= maxClosureGapMeters) {
            "सीमा का शुरुआती सिरा अभी ${closure.roundToInt()} मीटर दूर है। बाहर से लौटें; फसल के अंदर न जाएँ।"
        }
        val ring = accepted.dropLast(1)
        AkkpsBoundaryGeometry.measure(ring)
        return ring
    }

    fun clear() {
        accepted.clear()
        lastTimestampMillis = Long.MIN_VALUE
    }

    private fun distanceMeters(a: AkkpsGeoPoint, b: AkkpsGeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2.0 * 6_371_008.8 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
