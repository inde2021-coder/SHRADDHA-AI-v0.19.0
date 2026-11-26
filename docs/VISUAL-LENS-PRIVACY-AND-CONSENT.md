# Visual Lens privacy and live intelligence

- The foreground Visual Lens preview and capture action are user initiated.
- Background Live Eyes requires an explicit camera permission and an explicit user toggle. Android's camera privacy indicator and persistent foreground notification remain visible.
- On-device OCR (Latin + Devanagari) and object tracking can run without an AI gateway.
- Sending background camera frames to a cloud AI gateway is a separate opt-in stored as `cloud_scene_analysis_consent`; it is off by default.
- Cloud upload requires that separate consent and a configured HTTPS gateway. Turning consent off blocks subsequent frames; a request already in flight may complete.
- Camera permission is rechecked while frames are processed. Permission loss or service stop closes the camera.
- Camera frames and local OCR text are transient; the local frame processor wipes its working copy and does not persist the raw image or OCR text.
- Remote camera sharing remains a separate feature and still requires explicit camera-sharing consent, a selected paired target and mutual E2E fingerprint verification.
- Visual Lens does not identify people or infer sensitive traits. ARCore remains optional and is not required for normal camera/OCR/AI analysis.

## Device verification still required
Physical-device checks are required for background camera lifecycle, Android 14+ foreground-service behavior, permission revocation, camera contention with CameraX, battery saver and thermal throttling.
