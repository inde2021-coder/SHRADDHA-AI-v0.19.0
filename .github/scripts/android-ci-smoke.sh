#!/usr/bin/env bash
set -Eeuo pipefail

# Readiness probe discovers the real emulator serial and writes it only after
# ADB, Android boot, user storage and core system services are ready.
bash .github/scripts/wait-for-android-emulator.sh

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then ADB="$(command -v adb || true)"; fi
if [[ -z "$ADB" || ! -x "$ADB" ]]; then
  echo "ERROR: Android Debug Bridge executable was not found."
  exit 2
fi

SERIAL_FILE="${RUNNER_TEMP:-/tmp}/shraddha-emulator-serial"
if [[ ! -s "$SERIAL_FILE" ]]; then
  echo "ERROR: readiness probe did not publish a verified emulator serial."
  "$ADB" devices -l || true
  exit 1
fi
SERIAL="$(tr -d '\r\n' < "$SERIAL_FILE")"
if [[ ! "$SERIAL" =~ ^emulator-[0-9]+$ ]]; then
  echo "ERROR: readiness probe returned an invalid emulator serial: '$SERIAL'."
  exit 1
fi
if [[ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" != device ]]; then
  echo "ERROR: verified emulator '$SERIAL' disconnected before APK installation."
  "$ADB" devices -l || true
  exit 1
fi

# Pin all tools to the exact device discovered by the readiness probe.
export ANDROID_SERIAL="$SERIAL"
echo "Using verified Android emulator: $ANDROID_SERIAL"

dump_diagnostics() {
  echo "===== ADB devices ====="
  "$ADB" devices -l || true
  echo "===== Emulator logcat ====="
  "$ADB" -s "$SERIAL" logcat -d -t 600 || true
}
trap 'status=$?; if (( status != 0 )); then dump_diagnostics; fi' EXIT

APK_PATH="app/build/outputs/apk/debug/app-universal-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then APK_PATH="app/build/outputs/apk/debug/app-debug.apk"; fi
if [[ ! -f "$APK_PATH" ]]; then
  echo "ERROR: universal debug APK was not generated."
  find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' -print || true
  exit 1
fi

echo "Installing universal debug APK: $APK_PATH"
install_ok=false
for attempt in 1 2 3 4 5; do
  echo "APK install attempt $attempt/5 on $SERIAL"
  if [[ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" != device ]]; then
    echo "ERROR: emulator '$SERIAL' disconnected during install."
    "$ADB" devices -l || true
    exit 1
  fi
  if timeout 180 "$ADB" -s "$SERIAL" install --no-streaming -r "$APK_PATH"; then
    install_ok=true
    break
  fi
  "$ADB" reconnect offline >/dev/null 2>&1 || true
  sleep 10
done
if [[ "$install_ok" != true ]]; then
  echo "ERROR: APK could not be installed after 5 attempts."
  exit 1
fi

"$ADB" -s "$SERIAL" shell monkey -p in.shraddha.ai 1
for attempt in 1 2 3 4 5 6; do
  if "$ADB" -s "$SERIAL" shell pidof in.shraddha.ai | grep -q '[0-9]'; then
    echo "Shraddha AI process is running."
    exit 0
  fi
  sleep 5
done
echo "ERROR: Shraddha AI process did not remain running after launch."
exit 1
