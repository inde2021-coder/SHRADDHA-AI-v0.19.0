# Memory Vault conflict handling

The Data Vault can compare a selected local encrypted memory record with its Firebase vault copy after the user explicitly enables Firebase operations and enters account credentials.

- Compare is read-only: it retrieves both values and does not overwrite either copy.
- The screen shows a short preview and reports whether both copies differ.
- **Keep local** uploads the local value to the authenticated cloud vault.
- **Keep cloud** restores the cloud value into local encrypted storage.
- A missing copy is reported; the resolver does not silently create or overwrite it.
- Cloud operations are opt-in and require Firebase credentials. The app does not run background sync.
- Individual cloud deletion removes the RTDB mirror first and then Firestore authoritative record. Local data is removed only after the cloud deletion operation succeeds.

The user must retain an encrypted backup before destructive cleanup. This workflow does not merge conflicting text automatically; the user explicitly chooses one winning copy.

## Full deletion

The Data Vault also exposes a separately confirmed **Delete everywhere** action. It requires explicit Firebase-operation consent and account credentials, deletes the owner's Firebase vault records from the Realtime Database mirror and Firestore authoritative collection, then clears local `memory_` records. Cloud deletion is attempted first; if a remote deletion fails, local deletion is not started. The Firebase vault is capped at 5,000 records for this bulk operation; larger vaults must be cleared through individual deletion/support rather than an unbounded batch.
