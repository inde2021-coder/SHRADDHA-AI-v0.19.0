# Cross-device development and operations contract

Status: implementation requirements and acceptance criteria (not a claim that every capability is implemented).

## Product rule

Every routine project-management, configuration, coding, build-trigger, deployment, log-review, and release-management workflow must be usable from both a desktop browser and an Android mobile browser. The PC and Android clients may expose different hardware capabilities, but neither device is the exclusive control point for project operations.

## Supported control surfaces

- Responsive web control center: usable at narrow mobile widths and desktop widths; no horizontal scrolling for core workflows.
- GitHub web/mobile: source browsing, issue/PR review, commits and workflow dispatch through GitHub's supported UI/API.
- GitHub Actions: cloud builds so an Android device can trigger and retrieve Android artifacts without a local PC build environment.
- Cloud-hosted AI orchestration: provider credentials remain server-side; browser/mobile clients never receive provider secrets.
- PC Companion: Windows-only hardware operations run on the Windows host, but can be configured, monitored, paused and revoked from either control surface where network/security policy permits.
- Android Companion: Android-specific permissions and hardware operations remain on-device and require Android runtime permission/consent.

## Capability boundaries

| Capability | PC browser | Android browser | Native Windows app | Native Android app |
|---|---|---|---|---|
| Edit/review source and project settings | Yes | Yes | Optional | Optional |
| Trigger cloud builds and inspect logs | Yes | Yes | Optional | Optional |
| Download Android APK artifacts | Yes | Yes | Optional | Yes |
| Configure AI providers and agent routing | Yes | Yes | Optional | Optional |
| Windows OS/hardware control | Configure/monitor | Configure/monitor | Execute | No |
| Android camera/accessibility/telephony | No | Configure/monitor | No | Execute with permission |
| Local GGUF inference | Optional | Optional supported models | Execute | Execute only on compatible devices |

## Security and reliability requirements

1. Use role-based authentication and least-privilege access for project controls.
2. Store API keys, signing credentials and tokens only in server-side secrets or secure platform storage; never commit them.
3. Require explicit confirmation for destructive Git operations, releases, deployments, sending messages, purchases, and device-control actions.
4. Use an auditable job queue for long-running agent/build tasks; show queued/running/succeeded/failed/cancelled states and logs on both form factors.
5. Use responsive layouts and accessible controls; preserve all core actions at 360px viewport width.
6. Provide resumable status polling and safe retry behavior; retries must not duplicate non-idempotent actions.
7. Make platform-specific features visibly identify their execution host and unavailable permissions rather than pretending they ran.

## Acceptance checklist (must be proven by CI/device testing)

- [ ] Mobile browser can navigate all project-control screens at 360px width without horizontal overflow.
- [ ] Desktop browser can perform the same project-control workflows.
- [ ] Android user can trigger a cloud Android build and download its APK artifact.
- [ ] Build logs and terminal status are visible on both desktop and mobile.
- [ ] Provider configuration can be changed from both form factors; secret values are masked and never returned to clients.
- [ ] Windows-only tasks clearly require the Windows Companion and report its online/offline state.
- [ ] Android-only tasks clearly require Android permissions and report granted/denied state.
- [ ] Destructive actions require confirmation and produce an audit record.
- [ ] Automated CI passes; physical Android and Windows smoke tests are recorded separately.

## Testing policy

Do not mark an acceptance item complete based on source inspection alone. CI results, artifact availability, and physical-device results must be recorded independently. A successful GitHub commit is not a successful build or device test.
