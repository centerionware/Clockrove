#!/usr/bin/env bash
# Shared by the real emulator workflow and its synthetic CI fault-injection test.

clockrove_apk_identity() {
  local apk="$1"
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  local build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
  local aapt_bin="${CLOCKROVE_AAPT_BIN:-$sdk_root/build-tools/$build_tools_version/aapt}"
  local badging identity package version

  if [[ ! -x "$aapt_bin" ]]; then
    echo "Pinned Android aapt tool is unavailable: $aapt_bin" >&2
    return 2
  fi
  if ! badging="$(timeout --kill-after=1s 10s "$aapt_bin" dump badging "$apk" 2>&1)"; then
    echo 'Could not read package identity from the APK.' >&2
    return 2
  fi
  identity="$(awk -F"'" '/^package:/ { print $2 " " $4; exit }' <<< "$badging")"
  read -r package version <<< "$identity"
  if [[ -z "${package:-}" || ! "${version:-}" =~ ^[0-9]+$ ]]; then
    echo 'APK package name or version code could not be parsed.' >&2
    return 2
  fi
  if [[ ! "$package" =~ ^[A-Za-z0-9._]+$ ]]; then
    echo 'APK package name contains unsupported characters.' >&2
    return 2
  fi
  printf '%s %s\n' "$package" "$version"
}

# Return 0 when the exact APK version is installed, 1 when PackageManager
# consistently confirms it is absent, or 2 when the state cannot be trusted.
clockrove_verify_installed_apk() {
  local package="$1"
  local expected_version="$2"
  local log_file="$3"
  local settle_seconds="${CLOCKROVE_PACKAGE_SETTLE_SECONDS:-30}"
  local deadline remaining probe_timeout probe_output probe_status
  local absence_confirmations=0

  if [[ ! "$settle_seconds" =~ ^[1-9][0-9]*$ ]]; then
    echo 'Package settle duration must be a positive integer.' >&2
    return 2
  fi
  deadline=$((SECONDS + settle_seconds))
  : > "$log_file"
  while (( SECONDS < deadline )); do
    remaining=$((deadline - SECONDS))
    probe_timeout=3
    if (( remaining < probe_timeout )); then probe_timeout="$remaining"; fi
    if (( probe_timeout < 1 )); then break; fi

    probe_status=0
    if probe_output="$(timeout --kill-after=1s "${probe_timeout}s" adb -s emulator-5554 shell dumpsys package "$package" 2>&1)"; then
      probe_status=0
    else
      probe_status=$?
    fi
    {
      echo "query exit=$probe_status"
      echo "$probe_output"
    } >> "$log_file"

    if (( probe_status == 0 )) &&
      grep -Fq "Package [$package]" <<< "$probe_output" &&
      grep -Eq "versionCode=$expected_version([[:space:]]|$)" <<< "$probe_output"; then
      echo "Confirmed installed package $package at APK versionCode $expected_version."
      echo "result=exact-package-version-installed" >> "$log_file"
      return 0
    fi
    if (( probe_status == 0 )) && grep -Fq "Unable to find package: $package" <<< "$probe_output"; then
      absence_confirmations=$((absence_confirmations + 1))
    elif (( probe_status == 0 )) && grep -Fq "Package [$package]" <<< "$probe_output" &&
      grep -Eq 'versionCode=[0-9]+' <<< "$probe_output"; then
      absence_confirmations=$((absence_confirmations + 1))
    else
      # A timeout, binder error, or unrecognized response breaks the sequence;
      # a single earlier "not found" must never authorize another install.
      absence_confirmations=0
    fi
    if (( absence_confirmations >= 3 )); then
      echo "PackageManager confirmed the expected version of $package absent in three consecutive probes."
      echo "result=expected-package-version-absent" >> "$log_file"
      return 1
    fi
    sleep 1
  done

  echo "Could not confirm whether $package at versionCode $expected_version was installed."
  echo "result=package-state-unknown" >> "$log_file"
  return 2
}

# Reboot only when an ambiguous install timeout is known to have wedged the
# emulator. A changed kernel boot ID and sys.boot_completed are both required.
clockrove_reboot_emulator_and_wait() {
  local diagnostics_dir="$1"
  local label="$2"
  local attempt="$3"
  local expected_api="$4"
  local recovery_log="$diagnostics_dir/$label-recovery-attempt-$attempt.log"
  local boot_settle_seconds="${CLOCKROVE_BOOT_SETTLE_SECONDS:-90}"
  local reboot_status=0 wait_status=0 boot_deadline remaining probe_timeout
  local boot_output current_boot_id initial_boot_id observed_api booted=0

  if [[ ! "$boot_settle_seconds" =~ ^[1-9][0-9]*$ ]]; then
    echo 'Boot settle duration must be a positive integer.' >&2
    return 2
  fi
  if [[ ! "$expected_api" =~ ^[0-9]+$ ]]; then
    echo 'Expected emulator API must be a numeric Android API level.' >&2
    return 2
  fi
  if ! initial_boot_id="$(timeout --kill-after=1s 5s adb -s emulator-5554 shell cat /proc/sys/kernel/random/boot_id 2>> "$recovery_log")" ||
    [[ -z "$initial_boot_id" || "$initial_boot_id" == *$'\n'* ]]; then
    echo 'Could not read the emulator boot ID before recovery.' >&2
    echo 'boot-id-before=not-confirmed' >> "$recovery_log"
    return 2
  fi
  echo "Rebooting API emulator after ambiguous APK install timeout"
  timeout --kill-after=3s 10s adb -s emulator-5554 reboot >> "$recovery_log" 2>&1 || reboot_status=$?
  if (( reboot_status != 0 )); then
    echo "Could not request emulator reboot (exit $reboot_status)." >&2
    return 2
  fi
  if timeout --kill-after=5s 90s adb -s emulator-5554 wait-for-device >> "$recovery_log" 2>&1; then
    wait_status=0
  else
    wait_status=$?
  fi
  if (( wait_status != 0 )); then
    echo "Emulator did not reconnect after reboot (exit $wait_status)." >&2
    return 2
  fi

  boot_deadline=$((SECONDS + boot_settle_seconds))
  while (( SECONDS < boot_deadline )); do
    remaining=$((boot_deadline - SECONDS))
    probe_timeout=3
    if (( remaining < probe_timeout )); then probe_timeout="$remaining"; fi
    if (( probe_timeout < 1 )); then break; fi
    if current_boot_id="$(timeout --kill-after=1s "${probe_timeout}s" adb -s emulator-5554 shell cat /proc/sys/kernel/random/boot_id 2>&1)" &&
      boot_output="$(timeout --kill-after=1s "${probe_timeout}s" adb -s emulator-5554 shell getprop sys.boot_completed 2>&1)" &&
      [[ -n "$current_boot_id" && "$current_boot_id" != "$initial_boot_id" && "$boot_output" =~ ^1[[:space:]]*$ ]]; then
        booted=1
        break
    fi
    sleep 2
  done
  if (( booted != 1 )); then
    echo "Android reboot and boot completion were not both confirmed within $boot_settle_seconds seconds." >&2
    echo 'boot-restart=not-confirmed' >> "$recovery_log"
    return 2
  fi
  if ! observed_api="$(timeout --kill-after=1s 5s adb -s emulator-5554 shell getprop ro.build.version.sdk 2>> "$recovery_log")" ||
    [[ "$observed_api" != "$expected_api" ]]; then
    echo "Recovered emulator API was not confirmed as $expected_api (observed ${observed_api:-unknown})." >&2
    echo "emulator-api=${observed_api:-unknown}" >> "$recovery_log"
    echo 'boot-restart=not-confirmed' >> "$recovery_log"
    return 2
  fi
  echo 'Emulator reboot (changed boot ID) and boot completion confirmed.'
  echo "emulator-api=$observed_api" >> "$recovery_log"
  echo 'boot-restart=confirmed' >> "$recovery_log"
}

# A timed-out adb install can mean PackageManager committed the APK but its
# Binder reply was lost. Reboot to recover an unhealthy PackageManager, wait
# for Android to finish booting, then verify package+version before retrying.
clockrove_recover_and_verify_install() {
  local package="$1"
  local expected_version="$2"
  local diagnostics_dir="$3"
  local label="$4"
  local attempt="$5"
  local expected_api="$6"
  local recovery_status

  if clockrove_reboot_emulator_and_wait "$diagnostics_dir" "$label" "$attempt" "$expected_api"; then
    :
  else
    recovery_status=$?
    return "$recovery_status"
  fi
  clockrove_verify_installed_apk "$package" "$expected_version" "$diagnostics_dir/$label-package-state-attempt-$attempt.txt"
}

# Flutter's integration_test driver performs its own APK installation, outside
# clockrove_install_apk. Retry only the reproduced failure where the bounded
# command timeout interrupts that install before any tests start. A successful
# retry is accepted only when the actual test emits its success result marker.
clockrove_run_flutter_integration_test() {
  local api="$1"
  local label="$2"
  local success_pattern="$3"
  shift 3
  if [[ "${1:-}" == -- ]]; then shift; fi

  if [[ ! "$api" =~ ^[0-9]+$ || ! "$label" =~ ^[A-Za-z0-9._-]+$ ||
    -z "$success_pattern" || $# -eq 0 ]]; then
    echo 'Invalid Flutter integration-test retry arguments.' >&2
    return 2
  fi
  local diagnostics_dir="${GITHUB_WORKSPACE:?}/build/device-test-results/api-$api/install-diagnostics"
  local attempt=1
  local status=0
  local timeout_seconds="${CLOCKROVE_FLUTTER_TEST_TIMEOUT_SECONDS:-240}"
  local attempt_log
  if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
    echo 'Flutter integration-test timeout must be a positive integer.' >&2
    return 2
  fi
  mkdir -p "$diagnostics_dir"

  while (( attempt <= 2 )); do
    attempt_log="$diagnostics_dir/$label-flutter-attempt-$attempt.log"
    echo "Running Flutter integration test $label on API $api (attempt $attempt of 2)"
    if timeout --kill-after=10s "${timeout_seconds}s" "$@" > "$attempt_log" 2>&1; then
      cat "$attempt_log"
      if grep -Eq "$success_pattern" "$attempt_log"; then
        echo "Verified Flutter integration-test result marker for $label."
        return 0
      fi
      echo "Flutter integration test $label exited successfully without its required result marker." >&2
      return 3
    else
      status=$?
      cat "$attempt_log"
      echo "Flutter integration test $label failed with exit $status." >&2
    fi

    if (( attempt != 1 || status != 124 )) ||
      ! grep -Fq 'Installing build/app/outputs/flutter-apk/app-debug.apk...' "$attempt_log" ||
      ! grep -Fq 'No tests ran.' "$attempt_log" ||
      ! grep -Fq 'Error: ADB exited with exit code -15' "$attempt_log" ||
      grep -Eq '[[:space:]][+]([1-9][0-9]*([[:space:]]+-[0-9]+)?|0[[:space:]]+-[1-9][0-9]*):' "$attempt_log" ||
      grep -Fq 'BACKUP_CRYPTO_BENCHMARK ' "$attempt_log"; then
      return "$status"
    fi

    echo 'Confirmed Flutter app-install timeout before any benchmark test ran; recovering the emulator before one retry.'
    if ! clockrove_reboot_emulator_and_wait "$diagnostics_dir" "$label" "$attempt" "$api"; then
      echo 'Emulator recovery was not confirmed; refusing a blind Flutter retry.' >&2
      return 2
    fi
    attempt=$((attempt + 1))
  done

  return "$status"
}

clockrove_install_apk() {
  local api="$1"
  local label="$2"
  local apk="$3"
  local attempt=1
  local status=0
  local verification_status=0
  local diagnostics_dir="${GITHUB_WORKSPACE:?}/build/device-test-results/api-$api/install-diagnostics"
  local identity package expected_version

  if [[ "$label" != app && "$label" != instrumentation ]]; then
    echo 'Unsupported APK label.' >&2
    return 2
  fi
  mkdir -p "$diagnostics_dir"
  if identity="$(clockrove_apk_identity "$apk")"; then
    read -r package expected_version <<< "$identity"
  else
    status=$?
    return "$status"
  fi

  while (( attempt <= 2 )); do
    local attempt_log="$diagnostics_dir/$label-attempt-$attempt.log"
    echo "Installing $label APK on API $api (attempt $attempt of 2)"
    if timeout --kill-after=10s 120s adb -s emulator-5554 install -r -t "$apk" > "$attempt_log" 2>&1; then
      status=0
    else
      status=$?
    fi
    cat "$attempt_log"
    if (( status == 0 )); then
      return 0
    fi
    echo "APK install attempt $attempt failed with exit $status" >&2

    if (( status == 124 || status == 137 )); then
      echo 'Install response is ambiguous; recovering PackageManager before deciding whether to retry.'
      if clockrove_recover_and_verify_install "$package" "$expected_version" "$diagnostics_dir" "$label" "$attempt" "$api"; then
        echo 'The exact APK package and version are already installed; treating the timed-out install as successful.'
        return 0
      else
        verification_status=$?
      fi
      if (( verification_status == 1 && attempt < 2 )); then
        attempt=$((attempt + 1))
        echo 'Exact package/version is absent after recovery; retrying the APK install once.'
        continue
      elif (( verification_status == 1 )); then
        echo 'Exact package/version remains absent after the final install attempt.' >&2
      else
        echo 'Package state is unknown after recovery; refusing an unverified reinstall.' >&2
      fi
    fi
    break
  done

  echo 'APK install failed; collecting emulator diagnostics' >&2
  timeout --kill-after=3s 10s adb devices -l >&2 || true
  timeout --kill-after=5s 15s adb -s emulator-5554 logcat -d -v threadtime -t 250 > "$diagnostics_dir/logcat.txt" || true
  timeout --kill-after=5s 15s adb -s emulator-5554 shell pm list packages -3 > "$diagnostics_dir/third-party-packages.txt" || true
  echo "Recent emulator logcat:" >&2
  tail -n 80 "$diagnostics_dir/logcat.txt" >&2 || true
  echo "Installed third-party packages:" >&2
  cat "$diagnostics_dir/third-party-packages.txt" >&2 || true
  return "$status"
}
