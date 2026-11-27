# Resume AKKPS GPS boundary walk

Pausing GPS Walk must not silently discard already captured field-edge points. The Runner therefore preserves the in-memory point list when the user stops and starts GPS Walk again. The monotonic timestamp of the last accepted fix is retained so the first resumed fix can still be checked for an implausible jump. Only the explicit "नई Walk" action clears unsaved points and starts a fresh polygon. Saving remains a separate explicit action.
