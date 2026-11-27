# Command task execution journal

This is the persistence/safety foundation for Universal Command Automation (Issue #131).

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
- RUNNING may finish as SUCCEEDED, FAILED, UNCERTAIN, BLOCKED or SKIPPED.
- UNCERTAIN is never automatically replayed. A recovered RUNNING step must be marked UNCERTAIN, then an adapter independently inspects the remote side effect and calls reconciliation.
- SUCCEEDED, BLOCKED and SKIPPED are terminal.
- Repeated identical commands within a short deduplication window reuse the existing plan instead of creating a second action.

## Integration boundary
This class provides durable plan/journal primitives. It does not itself invoke WhatsApp, Android UI, Windows, browser or AKKPS actions. Each adapter must call the journal before and after side effects and must provide independent evidence before reporting success. Wiring all adapters and recovery UI remains part of the wider Issue #131 implementation.
