# Encrypted conversation memory consent lifecycle

Conversation recap is opt-in and device-local. This change makes the consent boundary explicit:
- turning memory off synchronously records the disabled state and clears the encrypted recap;
- record operations propagate vault decryption/key errors instead of treating unreadable memory as empty and overwriting it;
- explicit clear is synchronized with writes so a concurrent turn cannot immediately recreate the recap during deletion.

This does not enable memory by default, sync it to cloud, or store the full conversation transcript. Existing bounded recap limits remain in force.
