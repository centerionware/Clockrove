#!/usr/bin/env bash
# Shared by the real emulator workflow and its synthetic CI fault-injection test.
clockrove_install_apk() {
  local api="$1"
  local label="$2"
  local apk="$3"
  local attempt=1
  local status=0
  local diagnostics_dir="${GITHUB_WORKSPACE:?}/build/device-test-results/api-$api/install-diagnostics"
  if [[ "$label" != app && "$label" != instrumentation ]]; then
    echo 'Unsupported APK label.' >&2
    return 2
  fi
  mkdir -p "$diagnostics_dir"
  while (( attempt <= 2 )); do
    local attempt_log="$diagnostics_dir/$label-attempt-$attempt.log"
    echo "Installing $label APK on API $api (attempt $attempt of 2)"
    if timeout --kill-after=10s 120s adb -s emulator-5554 install -r -t "$apk" 2>&1 | tee "$attempt_log"; then
      return 0
    else
      status=$?
      echo "APK install attempt $attempt failed with exit $status" >&2
    fi
    if (( attempt < 2 && (status == 124 || status == 137) )); then
      echo "Install timed out; checking the emulator before one retry"
      timeout --kill-after=3s 10s adb devices -l || true
      timeout --kill-after=3s 10s adb -s emulator-5554 get-state || true
      timeout --kill-after=3s 10s adb -s emulator-5554 shell getprop sys.boot_completed || true
      sleep 5
      attempt=$((attempt + 1))
      continue
    fi
    break
  done

  echo "APK install failed; collecting emulator diagnostics" >&2
  timeout --kill-after=3s 10s adb devices -l >&2 || true
  timeout --kill-after=5s 15s adb -s emulator-5554 logcat -d -v threadtime -t 250 > "$diagnostics_dir/logcat.txt" || true
  timeout --kill-after=5s 15s adb -s emulator-5554 shell pm list packages -3 > "$diagnostics_dir/third-party-packages.txt" || true
  echo "Recent emulator logcat:" >&2
  tail -n 80 "$diagnostics_dir/logcat.txt" >&2 || true
  echo "Installed third-party packages:" >&2
  cat "$diagnostics_dir/third-party-packages.txt" >&2 || true
  return "$status"
}
