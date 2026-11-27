package `in`.shraddha.ai.operator.website

enum class AkkpsImagerySource { AIRBUS_SPOT, SENTINEL_2, GOOGLE_MAP_TILE, UNKNOWN }
enum class AkkpsModelTask { FIELD_BOUNDARY_SEGMENTATION, GENERIC_OBJECT_SEGMENTATION }
enum class AkkpsModelLicense { APACHE_2_0, CC_BY_4_0, UNKNOWN }

data class AkkpsSatelliteModelDescriptor(
    val id: String,
    val task: AkkpsModelTask,
    val license: AkkpsModelLicense,
    val trainedSource: AkkpsImagerySource,
    val trainingResolutionMetersPerPixel: Double,
    val supportsGeoReferencedInput: Boolean,
    val checksumVerified: Boolean,
    val validationApprovedForTargetRegion: Boolean
)

data class AkkpsSatelliteInputDescriptor(
    val source: AkkpsImagerySource,
    val resolutionMetersPerPixel: Double,
    val isGeoReferenced: Boolean,
    val hasCoordinateTransform: Boolean
)

/** Fail-closed gate: generic image masks are never silently treated as cadastral field boundaries. */
object AkkpsSatelliteModelPolicy {
    fun eligibility(model: AkkpsSatelliteModelDescriptor, input: AkkpsSatelliteInputDescriptor): String? {
        if (model.id.isBlank() || model.id.length > 100) return "Model identity is invalid."
        if (model.license == AkkpsModelLicense.UNKNOWN) return "Model license has not been verified."
        if (!model.checksumVerified) return "Model checkpoint checksum has not been verified."
        if (model.task != AkkpsModelTask.FIELD_BOUNDARY_SEGMENTATION) return "Model is not trained for field-boundary segmentation."
        if (model.trainedSource != input.source) return "Input imagery sensor/source does not match the model training imagery."
        if (!model.trainingResolutionMetersPerPixel.isFinite() || model.trainingResolutionMetersPerPixel <= 0.0 ||
            !input.resolutionMetersPerPixel.isFinite() || input.resolutionMetersPerPixel <= 0.0) {
            return "Ground sample distance is missing or invalid."
        }
        val resolutionDelta = kotlin.math.abs(input.resolutionMetersPerPixel - model.trainingResolutionMetersPerPixel) /
            model.trainingResolutionMetersPerPixel
        if (resolutionDelta > 0.20) return "Input imagery resolution differs by more than 20% from the model training resolution."
        if (!input.isGeoReferenced || !input.hasCoordinateTransform || !model.supportsGeoReferencedInput)
            return "Georeferenced imagery and a verified pixel-to-coordinate transform are required."
        if (!model.validationApprovedForTargetRegion) return "Model has not passed target-region field-boundary validation."
        return null
    }

    fun mayOfferAutomaticBoundary(model: AkkpsSatelliteModelDescriptor, input: AkkpsSatelliteInputDescriptor): Boolean =
        eligibility(model, input) == null
}
