# Shraddha AI v0.5.0 feature matrix

| Feature | v0.4.0 status | Notes |
|---|---|---|
| Two-way device pairing/control | Prototype present | Mutual mode; remote receiver must enable consent + Accessibility |
| App launch / visible UI actions | Prototype present | Protected apps/screens remain restricted |
| Live screen share | Prototype present | MediaProjection approval; JPEG over WebSocket |
| Rear/front remote camera | Prototype present | Camera2, target-device-only frame routing, visible notification; JPEG relay, not WebRTC |
| AI vision on screenshots | Present | Gemini/OpenAI/Anthropic only |
| AI chat providers | Present | Gemini/OpenAI/Anthropic/Groq/Perplexity; server API keys required |
| Custom category sharing policy | Present | Contacts/media/documents/messages/calendar/location/device status/AI memory toggles; permission metadata only |
| One-time selected-file transfer | Prototype present | Request/approve, short-lived grant, max 650 KB in Android UI |
| AI Agent Planner | Prototype present | Returns bounded JSON proposals only; does not execute actions; approval required |
| Multi-Model AI Council | Present | Compares 2-3 configured providers in parallel |
| Local AI notes | Present | Stored on one phone only; not synced or automatically inserted into prompts |
| True native Contacts/Calendar sync | Not implemented | Needs separate provider/API implementation and Android permissions |
| SMS/call-log sync | Not implemented | Restricted by Android and Play policies; no automatic access |
| WebRTC + TURN | Not implemented | Current camera/screen transport is low-rate JPEG over WebSocket |
| Durable cloud sessions / auto recovery | Not implemented | Relay sessions remain in RAM and disappear after server restart |
| End-to-end encryption | Not implemented | TLS/WSS transport only; relay can inspect payloads |
| Large resumable file transfer | Not implemented | Current one-time file limit is 650 KB |
| Cloud memory sync / push alerts | Not implemented | Future phase |
| Continuous wake-word / offline AI | Not implemented | Existing voice input is user-triggered Android speech recognition |
| Autonomous all-app agent | Not implemented | Remote actions are explicit supported actions, not unrestricted control |

This matrix is the release truth. Do not advertise a prototype or planned feature as production-complete.

## v0.6.0 additions
| Feature | Status | Notes |
|---|---|---|
| Offline bilingual command recognition | Implemented (basic grammar) | Intent proposals only; no action execution or wake-word listener |
| Encrypted personal memory | Implemented | AES-GCM; Android Keystore; device-local; key is non-exportable |
| Local knowledge retrieval | Implemented (RAG-lite) | Keyword retrieval over decrypted local memory; no embeddings/vector DB |
| Provider diagnostics | Implemented | Reports which provider keys are configured on backend, never returns keys |
| AI text summarization | Implemented | Server-side provider call; requires configured API key and internet |

| Expert Studio | v0.7.0 | Domain-aware learning, problem solving, conceptual project design, repair diagnosis, exam prep and creator drafts across 10 fields; AI advisory only |


## v0.8.0 Complete Expert Hub
| Capability | Status | Notes |
|---|---|---|
| 20 expert workspaces | Implemented | Server-side prompt orchestration via configured provider |
| 10 task modes | Implemented | Course blueprint, quiz, design, diagnose, research, estimate, review, etc. |
| Android Expert Hub | Implemented | Workspace/task/provider selectors and generated answer panel |
| Local project library | Implemented | Up to 12 text outputs on this phone only |
| Share current output | Implemented | Android ACTION_SEND share sheet |
| CAD/3D/PCB output | Conceptual | Text/parametric code where requested; no native CAD renderer or fabrication validation |
| Research citations | Not included | Research synthesis only; no verified live web search in this version |
| Cloud project sync | Not included | Saved work remains local to the phone |
| Professional medical/legal/structural certification | Not included | Requires qualified professionals |
