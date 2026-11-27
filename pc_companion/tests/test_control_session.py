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
