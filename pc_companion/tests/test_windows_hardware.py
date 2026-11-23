import unittest
from unittest.mock import patch
import windows_hardware


class HardwareValidationTests(unittest.TestCase):
    def test_status_refuses_non_windows(self):
        with patch.object(windows_hardware.platform, "system", return_value="Linux"):
            with self.assertRaises(RuntimeError):
                windows_hardware.get_hardware_status()

    def test_volume_rejects_out_of_range_before_driver_access(self):
        with patch.object(windows_hardware.platform, "system", return_value="Windows"):
            ok, message = windows_hardware.execute_hardware_action({"kind": "set_volume", "percent": 101})
        self.assertFalse(ok)
        self.assertIn("0 to 100", message)

    def test_brightness_rejects_boolean(self):
        with patch.object(windows_hardware.platform, "system", return_value="Windows"):
            ok, message = windows_hardware.execute_hardware_action({"kind": "set_brightness", "percent": True})
        self.assertFalse(ok)
        self.assertIn("0 to 100", message)

    def test_power_requires_local_confirmation(self):
        with patch.object(windows_hardware.platform, "system", return_value="Windows"):
            ok, message = windows_hardware.execute_hardware_action(
                {"kind": "power", "operation": "shutdown"}, confirm_power=lambda _op: False)
        self.assertFalse(ok)
        self.assertIn("cancelled", message)

    def test_power_never_accepts_unknown_operation(self):
        with patch.object(windows_hardware.platform, "system", return_value="Windows"):
            ok, message = windows_hardware.execute_hardware_action(
                {"kind": "power", "operation": "format_disk"}, confirm_power=lambda _op: True)
        self.assertFalse(ok)
        self.assertIn("Unsupported", message)


if __name__ == "__main__":
    unittest.main()
