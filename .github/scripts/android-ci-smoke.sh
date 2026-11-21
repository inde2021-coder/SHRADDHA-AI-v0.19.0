#!/usr/bin/env bash
set -Eeuo pipefail

bash .github/scripts/wait-for-android-emulator.sh
ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then ADB="$(command -v adb)"; fi
SERIAL="${ANDROID_SERIAL:-emulator-5554}"

dump_diagnostics() {
  echo "===== ADB devices ====="
  "$ADB" devices -l || true
  echo "===== Emulator logcat ====="
  "$ADB" -s "$SERIAL" logcat -d -t 600 || true
}
trap 'status=$?; if (( status != 0 )); then dump_diagnostics; fi' EXIT

install_ok=false
for attempt in 1 2 3 4 5; do
  echo "APK install attempt $attempt/5"
  "$ADB" -s "$SERIAL" wait-for-device
  if timeout 180 "$ADB" -s "$SERIAL" install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk; then
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
