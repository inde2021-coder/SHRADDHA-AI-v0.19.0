package `in`.shraddha.ai.vision

/** Fail-closed policy shared by camera service startup and per-frame dispatch. */
object RemoteCameraPolicy {
    fun mayStream(cameraPermissionGranted: Boolean, sharingConsentEnabled: Boolean, targetDeviceId: String, facing: String): Boolean {
        return cameraPermissionGranted &&
            sharingConsentEnabled &&
            targetDeviceId.matches(Regex("[A-Za-z0-9_-]{8,80}")) &&
            facing in setOf("front", "back")
    }

    /** Stop camera capture immediately when a frame cannot be delivered or consent changes. */
    fun shouldContinueAfterFrame(
        sendSucceeded: Boolean,
        cameraPermissionGranted: Boolean,
        sharingConsentEnabled: Boolean,
        targetDeviceId: String,
        facing: String
    ): Boolean = sendSucceeded && mayStream(cameraPermissionGranted, sharingConsentEnabled, targetDeviceId, facing)
}
