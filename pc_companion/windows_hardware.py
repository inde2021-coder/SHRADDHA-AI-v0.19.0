"""Windows PC hardware telemetry and allowlisted controls for Shraddha AI.

No shell strings or arbitrary commands are accepted. Optional vendor features report
unavailable rather than pretending to control unsupported hardware.
"""
import platform
import subprocess
import ctypes
import json
import shutil


def _windows_only():
    if platform.system() != "Windows":
        raise RuntimeError("PC hardware controls are available only on Windows.")


def get_hardware_status():
    _windows_only()
    result = {"platform": "Windows", "cpu": {}, "memory": {}, "battery": None,
              "disks": [], "displays": [], "gpu": {"available": False},
              "temperature": {"available": False}}
    try:
        import psutil
        result["cpu"] = {"usage_percent": psutil.cpu_percent(interval=0.2),
                         "logical_cores": psutil.cpu_count(logical=True),
                         "physical_cores": psutil.cpu_count(logical=False)}
        memory = psutil.virtual_memory()
        result["memory"] = {"total_bytes": memory.total, "available_bytes": memory.available,
                            "usage_percent": memory.percent}
        battery = psutil.sensors_battery()
        if battery:
            result["battery"] = {"percent": battery.percent, "plugged_in": battery.power_plugged}
        for part in psutil.disk_partitions(all=False):
            try:
                usage = psutil.disk_usage(part.mountpoint)
                result["disks"].append({"mount": part.mountpoint, "total_bytes": usage.total,
                                        "free_bytes": usage.free, "usage_percent": usage.percent})
            except (PermissionError, OSError):
                continue
    except ImportError:
        result["telemetry_note"] = "Install psutil to enable CPU, RAM, battery and disk telemetry."
    try:
        import mss
        with mss.mss() as capture:
            result["displays"] = [{"index": i, "width": int(m["width"]), "height": int(m["height"])}
                                  for i, m in enumerate(capture.monitors[1:], start=1)]
    except Exception:
        result["displays"] = []
    try:
        nvidia_smi = shutil.which("nvidia-smi")
        if nvidia_smi:
            completed = subprocess.run(
                [nvidia_smi, "--query-gpu=name,temperature.gpu,utilization.gpu,memory.used,memory.total",
                 "--format=csv,noheader,nounits"],
                capture_output=True, text=True, timeout=2, check=False, shell=False
            )
            if completed.returncode == 0 and completed.stdout.strip():
                result["gpu"] = {"available": True, "vendor": "NVIDIA",
                                 "telemetry": "driver", "summary": completed.stdout.strip()[:500]}
    except (OSError, subprocess.TimeoutExpired):
        pass
    if not result["gpu"].get("available"):
        # AMD/Intel fallback: Windows WMI reports adapter identity, driver and
        # the adapter RAM field. It does not provide reliable utilization data.
        powershell = shutil.which("powershell.exe") or shutil.which("powershell")
        if powershell:
            command = (
                "$ErrorActionPreference='Stop'; "
                "Get-CimInstance Win32_VideoController | "
                "Select-Object Name,AdapterRAM,DriverVersion | ConvertTo-Json -Compress"
            )
            try:
                completed = subprocess.run(
                    [powershell, "-NoProfile", "-NonInteractive", "-Command", command],
                    capture_output=True, text=True, timeout=3, check=False, shell=False
                )
                if completed.returncode == 0 and completed.stdout.strip():
                    raw = json.loads(completed.stdout)
                    adapters = raw if isinstance(raw, list) else [raw]
                    clean = []
                    for adapter in adapters[:8]:
                        if not isinstance(adapter, dict) or not str(adapter.get("Name", "")).strip():
                            continue
                        name = str(adapter.get("Name"))[:160]
                        ram = adapter.get("AdapterRAM")
                        clean.append({
                            "name": name,
                            "adapter_ram_bytes_reported": ram if isinstance(ram, int) and ram > 0 else None,
                            "driver_version": str(adapter.get("DriverVersion", ""))[:80]
                        })
                    if clean:
                        result["gpu"] = {
                            "available": True, "vendor": "AMD/Intel/Other",
                            "telemetry": "Windows WMI adapter inventory",
                            "adapters": clean, "utilization_available": False,
                            "note": "WMI adapter RAM may be capped by the Windows driver; live GPU utilization is not reported."
                        }
            except (OSError, subprocess.TimeoutExpired, ValueError, TypeError):
                pass
    # CPU temperature and fan speed are vendor/driver-specific. Do not invent values.
    return result


def _set_volume(percent):
    from ctypes import POINTER, cast
    from comtypes import CLSCTX_ALL
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
    speakers = AudioUtilities.GetSpeakers()
    interface = speakers.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    endpoint = cast(interface, POINTER(IAudioEndpointVolume))
    endpoint.SetMasterVolumeLevelScalar(percent / 100.0, None)


def _set_brightness(percent):
    import screen_brightness_control as sbc
    sbc.set_brightness(percent)


def execute_hardware_action(action, confirm_power=None):
    """Execute a strictly validated local hardware action."""
    _windows_only()
    kind = action.get("kind")
    if kind == "hardware_status":
        return True, get_hardware_status()
    if kind == "set_volume":
        value = action.get("percent")
        if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 100:
            return False, "Volume must be an integer from 0 to 100."
        try:
            _set_volume(value)
            return True, f"PC master volume set to {value}%."
        except Exception as exc:
            return False, f"Volume control unavailable: {str(exc)[:140]}"
    if kind == "set_brightness":
        value = action.get("percent")
        if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 100:
            return False, "Brightness must be an integer from 0 to 100."
        try:
            _set_brightness(value)
            return True, f"PC display brightness set to {value}%."
        except Exception as exc:
            return False, f"Brightness control unavailable on this display: {str(exc)[:140]}"
    if kind == "power":
        operation = action.get("operation")
        if operation not in {"shutdown", "restart", "sleep"}:
            return False, "Unsupported power operation."
        if not callable(confirm_power) or not confirm_power(operation):
            return False, "Power action cancelled or not locally confirmed."
        try:
            if operation == "sleep":
                ctypes.windll.powrprof.SetSuspendState(False, False, False)
            else:
                flag = 0x00000002 if operation == "restart" else 0x00000001
                if not ctypes.windll.user32.ExitWindowsEx(flag | 0x00000004, 0x00040000):
                    raise OSError("Windows rejected the power request.")
            return True, f"Windows {operation} request accepted."
        except Exception as exc:
            return False, f"Power action failed: {str(exc)[:140]}"
    return False, "Unsupported PC hardware action."
