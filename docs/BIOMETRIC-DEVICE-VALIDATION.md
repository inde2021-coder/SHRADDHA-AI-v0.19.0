# People Memory — Android device validation protocol

**Status:** protocol only. No physical-phone or real-person accuracy results are claimed by this repository.

## 1. Safety and consent
- Test only with adults who have been told what face/voice templates are created and how to withdraw them.
- Use test profiles and random participant IDs. Do not use biometric matching to unlock a device, approve payments, grant access, or make legal/medical/employment decisions.
- Raw camera frames are temporary and deleted; raw PCM/pixel buffers are cleared after inference. Keep test logs to scores, thresholds, timings and anonymous trial IDs only.
- Stop testing and withdraw a participant immediately on request.

## 2. Device matrix
Run the same test protocol on at least:
- Android API 26–30 (legacy supported range)
- Android API 31–32
- Android API 33–35
- One low-memory/entry-level phone, one mid-range phone and one current high-end phone
- At least two chipset families (for example Snapdragon and MediaTek/Exynos)

Record model, Android build, RAM, chipset, camera resolution, microphone route and app build SHA. Check model installation, available storage, cold-start load time, warm inference time, thermal throttling and process memory.

## 3. Genuine and impostor trials
- Recruit at least 20 consenting participants for an initial engineering evaluation.
- Capture at least 3 face samples and 3 voice samples per person across separate sessions; vary ordinary lighting, distance, glasses, room noise and speaking pace.
- For each selected profile, run genuine matches and impostor attempts from other consenting participants. Include unknown people who have no enrolled profile.
- Record similarity, calibrated threshold, outcome (accept/reject), model-load time and inference time. The app displays the score/threshold and inference timings after each attempt.
- Re-enroll or withdraw profiles and verify that stored thresholds are invalidated and matching is blocked until recalibration.

## 4. Presentation-attack / spoof test matrix
Test separately and record pass/fail for:
- Printed still photograph
- Still photograph shown on another phone/tablet
- Pre-recorded video with blinking
- Pre-recorded video with head turns
- Replayed video with screen brightness/refresh variations
- Mask, partial occlusion and face cut-out
- Recorded voice replay of the challenge phrase
- Loudspeaker replay and altered-speed replay
- Text-to-speech and voice-conversion/deepfake samples
- Multiple faces entering/leaving the frame
- Camera obstruction, lost frames, poor lighting and microphone interruption

A successful challenge on any replay or synthetic sample is a security finding. The current blink/head-turn + phrase flow is heuristic and is not certified PAD; it must not be represented as replay-proof.

## 5. Metrics and minimum reporting
Calculate separately for face and speaker:
- **FAR** = false accepts / impostor attempts
- **FRR** = false rejects / genuine attempts
- **TAR** = 1 − FRR
- **EER** = operating point where FAR and FRR are equal (estimate from a threshold sweep)
- Median and p95 model load/inference latency per device

Do not report an accuracy percentage from a small sample. With zero false accepts in N independent impostor trials, the approximate 95% upper bound on FAR is 3/N (rule of three); demonstrating a FAR below 0.1% therefore requires roughly 3,000 independent impostor trials with zero false accepts, plus a representative test design. Thresholds must be recalibrated per deployment population and environment.

## 6. Release gate
Do not enable biometric identity as an authentication factor until:
- CI unit tests and Android emulator ONNX smoke tests pass;
- physical-device capture and inference are verified on the supported device matrix;
- representative genuine/impostor testing is completed and reviewed;
- spoof testing is completed by an appropriately qualified team;
- a licensed PAD product with independent ISO/IEC 30107-3 evidence is integrated if certified PAD is required;
- privacy, consent, retention, deletion and applicable legal requirements are reviewed.

This protocol is a test plan, not a test report or certification.
