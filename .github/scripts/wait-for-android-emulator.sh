#!/usr/bin/env bash
set -Eeuo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then ADB="$(command -v adb || true)"; fi
if [[ -z "$ADB" || ! -x "$ADB" ]]; then
  echo "ERROR: Android Debug Bridge executable was not found."
  exit 2
fi

SERIAL="${ANDROID_SERIAL:-}"
echo "Waiting for an online Android emulator; no shell probes will run until ADB reports a device."
"$ADB" start-server

# A missing emulator must not trigger repeated failing shell/getprop calls.
# Discover the actual emulator serial from ADB instead of assuming 5554.
deadline=$((SECONDS + 180))
last_state=""
last_report=0
ready_streak=0
while (( SECONDS < deadline )); do
  devices="$("$ADB" devices 2>/dev/null || true)"
  if [[ -n "$SERIAL" ]]; then
    state="$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)"
    if [[ "$state" != device ]]; then
      discovered="$(awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1; exit}' <<< "$devices")"
      if [[ -n "$discovered" ]]; then SERIAL="$discovered"; state=device; fi
    fi
  else
    SERIAL="$(awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1; exit}' <<< "$devices")"
    state=""
    if [[ -n "$SERIAL" ]]; then state="$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)"; fi
  fi

  if [[ "$state" != device ]]; then
    ready_streak=0
    if (( SECONDS - last_report >= 15 )); then
      echo "Waiting for emulator registration in ADB; current devices:"
      "$ADB" devices -l || true
      last_report=$SECONDS
    fi
    "$ADB" reconnect offline >/dev/null 2>&1 || true
    sleep 2
    continue
  fi

  boot="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  user_ce="$("$ADB" -s "$SERIAL" shell getprop sys.user.0.ce_available 2>/dev/null | tr -d '\r' || true)"
  bootanim="$("$ADB" -s "$SERIAL" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r' || true)"

  if [[ "$boot" == 1 ]]; then
    "$ADB" -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  fi

  if [[ "$boot" == 1 && "$user_ce" == true && "$bootanim" != running ]]; then
    package_service="$("$ADB" -s "$SERIAL" shell service check package 2>/dev/null | tr -d '\r' || true)"
    activity_service="$("$ADB" -s "$SERIAL" shell service check activity 2>/dev/null | tr -d '\r' || true)"
    window_service="$("$ADB" -s "$SERIAL" shell service check window 2>/dev/null | tr -d '\r' || true)"
    package_probe="$("$ADB" -s "$SERIAL" shell pm path android 2>/dev/null | tr -d '\r' || true)"
    activity_probe="$("$ADB" -s "$SERIAL" shell am get-current-user 2>/dev/null | tr -d '\r' || true)"

    if [[ "$package_service" == "Service package: found" &&
          "$activity_service" == "Service activity: found" &&
          "$window_service" == "Service window: found" &&
          "$package_probe" == package:* && "$activity_probe" == 0 ]]; then
      ready_streak=$((ready_streak + 1))
      if (( ready_streak >= 2 )); then
        echo "Emulator $SERIAL is fully ready."
        "$ADB" devices -l
        printf '%s\n' "$SERIAL" > "${RUNNER_TEMP:-/tmp}/shraddha-emulator-serial"
        exit 0
      fi
    else
      ready_streak=0
    fi
  else
    ready_streak=0
  fi

  if [[ "$state/$boot/$user_ce/$bootanim" != "$last_state" ]] || (( SECONDS - last_report >= 15 )); then
    echo "Emulator $SERIAL: state=$state boot=$boot user_ce=$user_ce bootanim=$bootanim"
    last_state="$state/$boot/$user_ce/$bootanim"
    last_report=$SECONDS
  fi
  sleep 2
done

echo "ERROR: no fully ready Android emulator became available within 3 minutes."
"$ADB" devices -l || true
if [[ -n "$SERIAL" ]] && "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  "$ADB" -s "$SERIAL" logcat -d -t 1200 || true
fi
exit 1
