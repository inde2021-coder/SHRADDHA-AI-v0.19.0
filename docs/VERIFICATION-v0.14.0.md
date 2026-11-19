# v0.14.0 verification report

## Passed
- `node --check backend/server.js`.
- Backend route smoke tests with a temporary local WebSocket stub: `/health` returned 200; council without a token returned 401; one provider returned 400; missing provider API keys were reported as structured role failures with 502; the per-IP request limit returned 429 on the seventh call.
- Kotlin compiler built the pure future modules: AgentCouncil, ProjectWorkspace, KnowledgeGraph, WorkflowEngine and CapabilityCatalog.
- Kotlin smoke tests passed for five-role local plan creation, project progress update, knowledge graph link/remove behavior, and sensitive-step approval gating.
- All Android XML resources parsed; backend package JSON parsed.
- ZIP archive integrity checked after packaging.

## Not verified / blocked
- No live provider call was made because no provider API keys were configured in this build environment. Configure at least two provider keys to test live orchestration.
- `npm install --offline` could not install `ws` because it was not present in the local npm cache. The route smoke test used a temporary minimal WebSocket stub only to exercise HTTP routes; that stub was removed and is not included in the project.
- `./gradlew assembleDebug --offline` could not run because Gradle 8.11.1 was not cached and the environment could not resolve `services.gradle.org` (DNS/network unavailable). No APK is included or claimed.
- No physical Android device/emulator test was possible. Android UI compile/runtime, HTTPS backend deployment, provider quotas, and end-to-end live council behavior still require external verification.

## Safety boundary
The council endpoint returns advice and synthesis only. It does not run app actions, send messages, control devices, make payments, or delete data. It requires `GATEWAY_TOKEN`, limits provider fan-out, and applies a basic per-IP rate limit. Use a long random gateway token and deploy behind HTTPS.
