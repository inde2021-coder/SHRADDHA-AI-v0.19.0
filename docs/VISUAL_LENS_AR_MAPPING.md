# Visual Lens: live frame intelligence and AR mapping

## Live subtitles and object tracking

Visual Lens now runs ML Kit on the camera JPEG stream for:
- Latin and Devanagari text recognition (live subtitle/sign-text extraction).
- Multiple-object detection in stream mode with tracking IDs when ML Kit provides them.
- A bounded, single-flight frame pipeline; frames are copied only for processing and cleared after completion.
- The concise derived observation is shown in the Visual Lens screen and supplied as live voice context. Raw camera frames are not written to disk by this processor.

This is on-device OCR/object detection. It is not a guaranteed speech transcription system: subtitles here mean readable text visible in the camera image, not audio speech captions.

## ARCore world mapping engine

The class in.shraddha.ai.vision.ArWorldMappingEngine provides the ARCore capture core:
- Checks ARCore availability.
- Configures horizontal + vertical plane finding and latest-camera-image updates.
- Enables ARCore AUTOMATIC depth only on devices that report support.
- Samples camera pose path, tracked plane centers/extents, point-cloud count and depth-image range.
- Exports a JSON survey with pose path and plane anchors.

The host Activity must request ARCore installation and CAMERA permission, create an OpenGL external camera texture, call start(textureId), call update() on its GL render thread, and pause/resume/close with lifecycle. The current class is a mapping engine, not a complete rendered AR UI. Its export is a sparse pose/plane/depth survey, not a dense textured mesh, photogrammetry scan, CAD-grade measurement or cloud-shared map. Device depth capability varies; devices without it still get plane/pose tracking.
