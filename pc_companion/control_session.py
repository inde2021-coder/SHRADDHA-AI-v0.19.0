"""Small fail-closed predicate for short-lived remote-control approvals."""
import math


def control_grant_is_valid(requested_peer, approved_peer, expires_at, now):
    if not isinstance(requested_peer, str) or not requested_peer:
        return False
    if requested_peer != approved_peer:
        return False
    if isinstance(expires_at, bool) or isinstance(now, bool):
        return False
    if not isinstance(expires_at, (int, float)) or not isinstance(now, (int, float)):
        return False
    if not math.isfinite(float(expires_at)) or not math.isfinite(float(now)):
        return False
    return float(now) < float(expires_at)
