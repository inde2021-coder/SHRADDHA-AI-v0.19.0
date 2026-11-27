import unittest
from pc_companion.control_session import control_grant_is_valid


class ControlSessionTests(unittest.TestCase):
    def test_only_matching_peer_with_unexpired_grant_is_valid(self):
        self.assertTrue(control_grant_is_valid("peer-a", "peer-a", 120.0, 100.0))
        self.assertFalse(control_grant_is_valid("peer-b", "peer-a", 120.0, 100.0))
        self.assertFalse(control_grant_is_valid("peer-a", "peer-a", 100.0, 100.0))
        self.assertFalse(control_grant_is_valid("", "peer-a", 120.0, 100.0))

    def test_invalid_timestamps_fail_closed(self):
        self.assertFalse(control_grant_is_valid("peer-a", "peer-a", float("nan"), 100.0))
        self.assertFalse(control_grant_is_valid("peer-a", "peer-a", 120.0, float("inf")))


if __name__ == "__main__":
    unittest.main()

    def test_rejects_non_finite_and_boolean_timestamps(self):
        import math
        self.assertFalse(control_grant_is_valid("phone-1", "phone-1", math.inf, 1.0))
        self.assertFalse(control_grant_is_valid("phone-1", "phone-1", 10.0, math.nan))
        self.assertFalse(control_grant_is_valid("phone-1", "phone-1", True, 1.0))

    def test_rejects_empty_or_missing_peer(self):
        self.assertFalse(control_grant_is_valid("", "phone-1", 10.0, 1.0))
        self.assertFalse(control_grant_is_valid("phone-1", None, 10.0, 1.0))
