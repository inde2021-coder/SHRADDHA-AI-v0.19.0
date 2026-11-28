# Follow-up command reference context

The process-local reference context supports short follow-ups such as "send it to the same person" or "उसे भेजो" only when a matching target was explicitly selected earlier in the current session.

Rules:
- only explicit selections are remembered;
- context is in memory only, not persisted or synced;
- references expire after five minutes by default;
- the caller requests a specific entity type, so a prior contact cannot be substituted for a file or app;
- a resolved ID must still be revalidated against current permissions and live data before use;
- missing/expired context returns a clarification state rather than guessing.

This is a reference-resolution primitive, not a permission grant or action executor.
