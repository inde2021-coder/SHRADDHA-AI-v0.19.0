# Runtime command capability registry

The registry is the capability-discovery boundary for Issue #131.

A domain/action pair is not considered supported merely because the language classifier recognizes it. A concrete Android, Windows, browser or service adapter must register its capability at runtime with:
- the domains and actions it actually implements;
- whether the adapter is enabled;
- the current permission state;
- an optional permission key and a safe unavailable reason.

Resolution can return available, permission required, blocked, ambiguous or unsupported. Ambiguous adapters are not silently selected. Unknown domains/actions fail closed.

This registry does not itself perform OS permission checks or execute commands. Platform integrations must refresh permission state when Android/Windows permissions or account connectivity change.
