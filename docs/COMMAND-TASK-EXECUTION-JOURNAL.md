# Command task execution journal

This is the persistence/safety foundation for Universal Command Automation (Issue #131).

Journal JSON is encrypted at rest with the app's existing Android Keystore AES-GCM `EncryptedFeatureStore`; it is not written as plaintext SharedPreferences data. The serialized journal is capped below the encrypted store's per-record limit, and any missing-key/authentication/decryption error blocks recovery.

## What is recorded
- random task and step IDs;
- SHA-256 fingerprint of the normalized command (not the command text);
- adapter capability and action labels;
- bounded state, attempt count, safe result-code identifier and timestamps.
- every state transition uses a synchronous durable commit; if persistence fails, the caller receives failure and must not perform the side effect.

No raw command, message body, farmer field value, contact name, phone number, cookie, password, OTP or screen content is written to the journal.

## Execution state rules
- PENDING may be claimed as RUNNING.
- FAILED may be retried only within the bounded attempt count.
- A FAILED step is retained for retry after its first failure; after the second verified failure it is terminal and may be pruned with old completed history. Ambiguous tool-level `failed` results are recorded as UNCERTAIN instead, not as retryable FAILED.
- RUNNING may finish as SUCCEEDED, FAILED, UNCERTAIN, BLOCKED or SKIPPED.
- App startup must call `recoverInterruptedSteps()` before any adapter resumes work. It durably changes every leftover RUNNING step to UNCERTAIN; startup must remain blocked if recovery persistence fails.
- `LiaApplication.onCreate()` runs the shared recovery gate before the app exposes command tools. `MainBrain.executeVoiceTool()` refuses to start a tool when the gate is closed and writes a durable one-step plan before dispatching the adapter.
- Corrupt or undecodable journal data makes recovery fail closed; it is never treated as an empty journal. Storage pruning may remove terminal history only and must retain every non-terminal plan, or reject the new write when the protected set cannot fit.
- UNCERTAIN is never automatically replayed. An adapter independently inspects the remote side effect and calls reconciliation.
- While any step is RUNNING or UNCERTAIN, the voice entry point blocks all new external actions, even if the next command uses different wording. This avoids bypassing an unresolved send/submit by rephrasing it.
- Voice-tool results with `queued`, ambiguous `error`/`failed`, or missing/unknown status remain `UNCERTAIN`; queue acceptance is not proof of external completion, and a tool error may follow a partial side effect.
- SUCCEEDED, BLOCKED and SKIPPED are terminal.
- Repeated identical commands within a short deduplication window reuse the existing plan instead of creating a second action.

## Integration boundary
The voice-tool entry point is wired through `MainBrain.executeVoiceTool()` and records a write-ahead step before calling `ActionExecutor`. The journal does not itself invoke WhatsApp, Android UI, Windows, browser or AKKPS actions. Other direct adapters and a user-facing recovery/reconciliation UI still require integration; each must provide independent evidence before an uncertain action is marked complete.
