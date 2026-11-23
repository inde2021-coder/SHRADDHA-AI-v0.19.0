"""Bounded reconnect backoff for the Windows relay client."""


def reconnect_delay_ms(attempt, base_ms=2_000, maximum_ms=60_000):
    """Return capped exponential delay; invalid attempts fail closed to base."""
    if isinstance(attempt, bool) or not isinstance(attempt, int) or attempt < 1:
        attempt = 1
    if isinstance(base_ms, bool) or not isinstance(base_ms, int) or base_ms < 1:
        raise ValueError("base_ms must be a positive integer")
    if isinstance(maximum_ms, bool) or not isinstance(maximum_ms, int) or maximum_ms < base_ms:
        raise ValueError("maximum_ms must be an integer >= base_ms")
    return min(maximum_ms, base_ms * (2 ** min(attempt - 1, 30)))
