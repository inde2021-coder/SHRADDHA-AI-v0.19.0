#!/usr/bin/env bash
set -Eeuo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then
  ADB="$(command -v adb || true)"
fi
if [[ -z "$ADB" || ! -x "$ADB" ]]; then
  echo "ERROR: Android Debug Bridge executable was not found."
  exit 2
fi

SERIAL="${ANDROID_SERIAL:-emulator-5554}"
echo "Waiting for Android emulator $SERIAL: boot, user unlock and Android services..."
"$ADB" start-server

deadline=$((SECONDS + 900))
last_state=unknown
ready_streak=0
while (( SECONDS < deadline )); do
  state="$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)"
  boot="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  user_ce="$("$ADB" -s "$SERIAL" shell getprop sys.user.0.ce_available 2>/dev/null | tr -d '\r' || true)"
  bootanim="$("$ADB" -s "$SERIAL" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r' || true)"

  if [[ "$state" == device && "$boot" == 1 && "$user_ce" == true && "$bootanim" != running ]]; then
    package_service="$("$ADB" -s "$SERIAL" shell service check package 2>/dev/null | tr -d '\r' || true)"
    activity_service="$("$ADB" -s "$SERIAL" shell service check activity 2>/dev/null | tr -d '\r' || true)"
    window_service="$("$ADB" -s "$SERIAL" shell service check window 2>/dev/null | tr -d '\r' || true)"

    if [[ "$package_service" == "Service package: found" &&
          "$activity_service" == "Service activity: found" &&
          "$window_service" == "Service window: found" ]]; then
      package_probe="$("$ADB" -s "$SERIAL" shell pm path android 2>/dev/null | tr -d '\r' || true)"
      activity_probe="$("$ADB" -s "$SERIAL" shell am get-current-user 2>/dev/null | tr -d '\r' || true)"
      unlock_probe="$("$ADB" -s "$SERIAL" shell dumpsys user 2>/dev/null | tr -d '\r' || true)"
      if [[ "$package_probe" == package:* &&
            "$activity_probe" == 0 &&
            ( "$unlock_probe" == *"mUserUnlocked=true"* || "$unlock_probe" == *"RUNNING_UNLOCKED"* ) ]]; then
        ready_streak=$((ready_streak + 1))
        if (( ready_streak >= 3 )); then
          echo "Emulator $SERIAL is fully ready: PackageManager, ActivityManager, WindowManager and unlocked user 0 confirmed."
          "$ADB" devices -l
          exit 0
        fi
      else
        ready_streak=0
        echo "Android services are present but user/storage probes are not ready; waiting..."
      fi
    else
      ready_streak=0
    fi
  else
    ready_streak=0
  fi

  if [[ "$state" != "$last_state" ]]; then
    echo "ADB state: ${state:-unavailable}; boot_completed: ${boot:-unavailable}; user_ce_available: ${user_ce:-unavailable}; bootanim: ${bootanim:-unavailable}"
    last_state="$state"
  fi
  "$ADB" reconnect offline >/dev/null 2>&1 || true
  sleep 5
done

echo "ERROR: emulator $SERIAL did not become fully ready within 15 minutes."
"$ADB" devices -l || true
"$ADB" -s "$SERIAL" shell getprop sys.boot_completed || true
"$ADB" -s "$SERIAL" shell getprop sys.user.0.ce_available || true
"$ADB" -s "$SERIAL" shell service check package || true
"$ADB" -s "$SERIAL" shell service check activity || true
"$ADB" -s "$SERIAL" shell service check window || true
"$ADB" -s "$SERIAL" shell dumpsys user || true
"$ADB" -s "$SERIAL" logcat -d -t 1200 || true
exit 1
