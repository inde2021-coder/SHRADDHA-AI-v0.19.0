import unittest

from pc_companion.reconnect import reconnect_delay_ms


class ReconnectBackoffTests(unittest.TestCase):
    def test_exponential_backoff_and_cap(self):
        self.assertEqual([reconnect_delay_ms(n) for n in range(1, 7)],
                         [2000, 4000, 8000, 16000, 32000, 60000])
        self.assertEqual(reconnect_delay_ms(100), 60000)

    def test_invalid_attempt_fails_to_first_delay(self):
        for value in (0, -1, None, 1.5, True):
            self.assertEqual(reconnect_delay_ms(value), 2000)

    def test_invalid_bounds_are_rejected(self):
        for args in ((1, 0, 100), (1, 2000, 1000), (1, True, 60000), (1, 2000, False)):
            with self.assertRaises(ValueError):
                reconnect_delay_ms(*args)


if __name__ == "__main__":
    unittest.main()
