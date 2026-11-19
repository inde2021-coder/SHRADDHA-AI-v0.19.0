# Shraddha AI – Advanced AI Technology Roadmap

## Added in v0.5.0

1. **Proposal-only Agent Planner** – turns a natural-language goal into a bounded JSON plan with an allowlisted tool vocabulary, risk labels and mandatory approval metadata. It cannot execute device actions.
2. **Multi-Model Council** – compares up to three configured providers in parallel and returns each provider's answer or error independently.
3. **Safety boundary** – provider keys remain server-side; AI output is untrusted; planner output is not executable code and is not automatically dispatched to paired devices.

## Next advanced capabilities

### v0.6 – On-device intelligence
- Local model adapter (where device RAM/NNAPI/LLM runtime supports it), with cloud fallback.
- Offline command grammar for a small set of non-sensitive commands.
- Local OCR and document extraction with user-selected files.
- Context retrieval over user-selected local notes/files (RAG), with source references.

### v0.7 – Reliable agent execution
- Explicit action registry with typed JSON schemas and per-tool permission checks.
- Step-by-step approval UI, cancellation, timeout, retry policy and rollback where possible.
- Dry-run preview and action receipt for every operation.
- Prompt-injection isolation: screen text, documents and remote messages are treated as untrusted data, never as system instructions.

### v0.8 – Real-time multimodal intelligence
- WebRTC camera/screen streams with TURN fallback and adaptive bitrate.
- User-triggered frame sampling, OCR/object detection and voice questions about the live view.
- Event-based motion alerts only while a visible, user-approved session is active.

### v0.9 – Private device cloud
- End-to-end encrypted device-to-device file and media transport.
- Resumable chunked transfer, integrity hashes and storage quotas.
- Durable device registry, token rotation/revocation, session recovery and push notifications.
- Encrypted memory sync with per-category, per-device consent.

### v1.0 – Production readiness
- Threat model and independent security review.
- Rate limits, abuse monitoring, audit export and data retention controls.
- Battery/network stress tests and accessibility compatibility matrix.
- Android policy review for each sensitive permission and distribution channel.

## Non-goals / hard boundaries

- No bypass of Android permission prompts, secure surfaces, app sandboxing or lock screens.
- No covert camera/microphone access or hidden surveillance.
- No automatic reading/sharing of OTPs, passwords, banking data or private app databases.
- No irreversible or external action without a clear user confirmation.

## v0.6.0 delivered foundations
- AES-GCM encrypted device-local memory protected by Android Keystore.
- Offline Hindi/English command grammar that proposes intents without executing actions.
- Local keyword retrieval (RAG-lite) for saved notes; no cloud upload.
- Provider configuration diagnostics and structured text summarization endpoint.

## Still not implemented (future engineering work)
- A downloadable on-device LLM runtime/model and semantic embedding index.
- Full RAG ingestion for user-selected PDFs/Office files with citations and incremental indexing.
- WebRTC peer media with TURN fallback and end-to-end media encryption.
- Durable encrypted device registry, secure account recovery, push notifications, and background sync.
- Native contact/calendar/task integrations with granular Android runtime permissions.
- A real agent execution engine with typed tools, per-step approvals, cancellation, rollback, and audit UI.
- Wake-word detection and always-on background listening (requires battery/privacy design and explicit foreground operation).
