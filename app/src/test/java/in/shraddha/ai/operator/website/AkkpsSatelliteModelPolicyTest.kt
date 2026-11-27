package `in`.shraddha.ai.operator.website

import org.junit.Assert.*
import org.junit.Test

class AkkpsSatelliteModelPolicyTest {
    private val indiaSpotModel = AkkpsSatelliteModelDescriptor(
        id = "india-fractal-resunet-zenodo-7315090",
        task = AkkpsModelTask.FIELD_BOUNDARY_SEGMENTATION,
        license = AkkpsModelLicense.CC_BY_4_0,
        trainedSource = AkkpsImagerySource.AIRBUS_SPOT,
        trainingResolutionMetersPerPixel = 1.5,
        supportsGeoReferencedInput = true,
        checksumVerified = true,
        validationApprovedForTargetRegion = true
    )
    @Test fun matchingValidatedGeoreferencedSpotInputCanPassGate() {
        val input = AkkpsSatelliteInputDescriptor(AkkpsImagerySource.AIRBUS_SPOT, 1.5, true, true)
        assertFalse(AkkpsSatelliteModelPolicy.mayOfferAutomaticBoundary(indiaSpotModel.copy(validationApprovedForTargetRegion = false), input))
        assertTrue(AkkpsSatelliteModelPolicy.mayOfferAutomaticBoundary(indiaSpotModel, input))
    }
    @Test fun spotModelCannotBeSilentlyAppliedToSentinelOrGoogleTiles() {
        assertFalse(AkkpsSatelliteModelPolicy.mayOfferAutomaticBoundary(
            indiaSpotModel, AkkpsSatelliteInputDescriptor(AkkpsImagerySource.SENTINEL_2, 10.0, true, true)))
        assertFalse(AkkpsSatelliteModelPolicy.mayOfferAutomaticBoundary(
            indiaSpotModel, AkkpsSatelliteInputDescriptor(AkkpsImagerySource.GOOGLE_MAP_TILE, 1.0, true, true)))
    }
    @Test fun genericSamStyleSegmentationIsNotFieldBoundaryAutomation() {
        val generic = indiaSpotModel.copy(task = AkkpsModelTask.GENERIC_OBJECT_SEGMENTATION)
        assertFalse(AkkpsSatelliteModelPolicy.mayOfferAutomaticBoundary(
            generic, AkkpsSatelliteInputDescriptor(AkkpsImagerySource.AIRBUS_SPOT, 1.5, true, true)))
    }
    @Test fun rejectsDifferentGroundSampleDistance() {
        val input = AkkpsSatelliteInputDescriptor(AkkpsImagerySource.AIRBUS_SPOT, 4.8, true, true)
        assertFalse(AkkpsSatelliteModelPolicy.mayOfferAutomaticBoundary(indiaSpotModel, input))
    }
    @Test fun unknownLicenseChecksumOrMissingGeoreferenceFailsClosed() {
        assertFalse(AkkpsSatelliteModelPolicy.mayOfferAutomaticBoundary(
            indiaSpotModel.copy(license = AkkpsModelLicense.UNKNOWN),
            AkkpsSatelliteInputDescriptor(AkkpsImagerySource.AIRBUS_SPOT, 1.5, true, true)))
        assertFalse(AkkpsSatelliteModelPolicy.mayOfferAutomaticBoundary(
            indiaSpotModel.copy(checksumVerified = false),
            AkkpsSatelliteInputDescriptor(AkkpsImagerySource.AIRBUS_SPOT, 1.5, true, true)))
        assertFalse(AkkpsSatelliteModelPolicy.mayOfferAutomaticBoundary(
            indiaSpotModel,
            AkkpsSatelliteInputDescriptor(AkkpsImagerySource.AIRBUS_SPOT, 1.5, false, false)))
    }
}
