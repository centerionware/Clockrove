#!/usr/bin/env bash
# Shared by the real emulator workflow and its synthetic CI fault-injection test.

clockrove_apk_identity() {
  local apk="$1"
  local label="${2:-app}"
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  local build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
  local aapt_bin="${CLOCKROVE_AAPT_BIN:-$sdk_root/build-tools/$build_tools_version/aapt}"
  local badging package_line package_lines package version
  local name_pattern="name='([^']+)'"
  local version_pattern="versionCode='([^']*)'"

  clockrove_aapt_parse_diagnostic() {
    local reason="$1"
    local output="$2"
    local diagnostic_lines line
    echo "$reason" >&2
    diagnostic_lines="$(grep -E '^package:[[:space:]]' <<< "$output" || true)"
    if [[ -z "$diagnostic_lines" ]]; then
      diagnostic_lines="$(head -n 8 <<< "$output")"
      echo 'Sanitized aapt dump badging output (first eight lines):' >&2
    else
      echo 'Sanitized aapt dump badging package line(s):' >&2
    fi
    if [[ -z "$diagnostic_lines" ]]; then
      echo '  (no output)' >&2
      return
    fi
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line//$'\r'/}"
      printf '  ' >&2
      printf '%s' "$line" | LC_ALL=C tr -cd '\11\12\15\40-\176' >&2
      printf '\n' >&2
    done <<< "$diagnostic_lines"
  }

  if [[ ! -x "$aapt_bin" ]]; then
    echo "Pinned Android aapt tool is unavailable: $aapt_bin" >&2
    return 2
  fi
  if [[ "$label" != app && "$label" != instrumentation ]]; then
    echo 'Unsupported APK identity label.' >&2
    return 2
  fi
  if ! badging="$(timeout --kill-after=1s 10s "$aapt_bin" dump badging "$apk" 2>&1)"; then
    clockrove_aapt_parse_diagnostic 'Could not read package identity from the APK; refusing ambiguous-install recovery.' "$badging"
    return 2
  fi
  package_lines="$(grep -E '^package:[[:space:]]' <<< "$badging" || true)"
  if [[ -z "$package_lines" || "$package_lines" == *$'\n'* ]]; then
    clockrove_aapt_parse_diagnostic 'Expected exactly one package record in aapt output; refusing ambiguous-install recovery.' "$badging"
    return 2
  fi
  package_line="$package_lines"
  if [[ "$package_line" =~ $name_pattern ]]; then
    package="${BASH_REMATCH[1]}"
  else
    clockrove_aapt_parse_diagnostic 'APK package name could not be parsed; refusing ambiguous-install recovery.' "$package_line"
    return 2
  fi
  if [[ "$package_line" =~ $version_pattern ]]; then
    version="${BASH_REMATCH[1]}"
  else
    clockrove_aapt_parse_diagnostic 'APK versionCode field could not be parsed; refusing ambiguous-install recovery.' "$package_line"
    return 2
  fi
  if [[ -z "$version" && "$label" == instrumentation ]]; then
    # Android test APKs omit android:versionCode; PackageManager assigns 0.
    version=0
  fi
  if [[ ! "$version" =~ ^[0-9]+$ ]]; then
    clockrove_aapt_parse_diagnostic "APK versionCode is empty or invalid for $label; refusing ambiguous-install recovery." "$package_line"
    return 2
  fi
  if [[ ! "$package" =~ ^[A-Za-z0-9._]+$ ]]; then
    clockrove_aapt_parse_diagnostic 'APK package name contains unsupported characters; refusing ambiguous-install recovery.' "$package_line"
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
  if ! observed_api="$(timeout --kill-after=1s 5s adb -s emulator-5554 shell getprop ro.build.version.sdk 2>> "$recovery_log")"; then
    echo "Recovered emulator API was not confirmed as $expected_api." >&2
    echo 'emulator-api=unknown' >> "$recovery_log"
    echo 'boot-restart=not-confirmed' >> "$recovery_log"
    return 2
  fi
  observed_api="${observed_api//$'\r'/}"
  if [[ "$observed_api" != "$expected_api" ]]; then
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

# Capture bounded device/PackageManager state while the timed-out install is
# still on the same boot. These diagnostics are best effort and never affect
# the existing recovery or retry decision.
clockrove_append_install_timeout_diagnostic() {
  local report="$1"
  local description="$2"
  shift 2
  local output status line_count=0 line

  if output="$(timeout --kill-after=1s 4s "$@" 2>&1)"; then
    status=0
  else
    status=$?
  fi
  {
    printf '\n[%s] exit=%s\n' "$description" "$status"
    if [[ -z "$output" ]]; then
      echo '  (no output)'
      return 0
    fi
    while IFS= read -r line || [[ -n "$line" ]]; do
      if (( line_count >= 40 )); then
        echo '  (remaining output omitted)'
        break
      fi
      line="$(printf '%s' "$line" | LC_ALL=C tr -cd '\11\12\15\40-\176' | \
        sed -E 's#(/data/(app|user|user_de)/[^[:space:]]+)#<android-path>#g; s#(/home/[^[:space:]]+)#<runner-path>#g' | cut -c1-400)"
      printf '  %s\n' "$line"
      line_count=$((line_count + 1))
    done <<< "$output"
  } >> "$report"
}

clockrove_capture_install_timeout_diagnostics() {
  local api="$1"
  local label="$2"
  local phase="$3"
  local attempt="$4"
  local package="${5:-unknown}"
  local diagnostics_dir="${GITHUB_WORKSPACE:?}/build/device-test-results/api-$api/install-diagnostics"
  local report="$diagnostics_dir/$label-install-timeout-$phase-attempt-$attempt.txt"
  local logcat_output logcat_status filtered_logcat

  mkdir -p "$diagnostics_dir" || return 0
  {
    echo 'bounded-install-timeout-diagnostics=v1'
    echo "captured_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo unavailable)"
    echo "api=$api phase=$phase attempt=$attempt package=$package"
    echo 'logcat scope: PackageManager/PackageInstaller/installd and install failure lines only; paths redacted.'
  } > "$report" || return 0

  clockrove_append_install_timeout_diagnostic "$report" 'adb devices -l' adb devices -l || true
  clockrove_append_install_timeout_diagnostic "$report" 'adb get-state' adb -s emulator-5554 get-state || true
  clockrove_append_install_timeout_diagnostic "$report" 'sys.boot_completed' adb -s emulator-5554 shell getprop sys.boot_completed || true
  clockrove_append_install_timeout_diagnostic "$report" 'ro.build.version.sdk' adb -s emulator-5554 shell getprop ro.build.version.sdk || true
  clockrove_append_install_timeout_diagnostic "$report" 'kernel boot id' adb -s emulator-5554 shell cat /proc/sys/kernel/random/boot_id || true
  clockrove_append_install_timeout_diagnostic "$report" 'PackageManager service' adb -s emulator-5554 shell service check package || true

  if [[ "$package" =~ ^[A-Za-z0-9._]+$ ]]; then
    clockrove_append_install_timeout_diagnostic "$report" "pm path $package" \
      adb -s emulator-5554 shell pm path "$package" || true
  fi

  if logcat_output="$(timeout --kill-after=1s 4s adb -s emulator-5554 logcat -d -v threadtime -t 120 2>&1)"; then
    logcat_status=0
  else
    logcat_status=$?
  fi
  filtered_logcat="$(grep -Eai 'PackageManager|PackageInstaller|installd|INSTALL_[A-Z_]+|install(ed|ing|ation)? (package|session|failed)' <<< "$logcat_output" | tail -n 40 || true)"
  {
    printf '\n[filtered install logcat] exit=%s\n' "$logcat_status"
    if [[ -z "$filtered_logcat" ]]; then
      echo '  (no matching lines)'
    else
      local line_count=0 line
      while IFS= read -r line || [[ -n "$line" ]]; do
        if (( line_count >= 40 )); then
          echo '  (remaining output omitted)'
          break
        fi
        line="$(printf '%s' "$line" | LC_ALL=C tr -cd '\11\12\15\40-\176' | \
          sed -E 's#(/data/(app|user|user_de)/[^[:space:]]+)#<android-path>#g; s#(/home/[^[:space:]]+)#<runner-path>#g' | cut -c1-400)"
        printf '  %s\n' "$line"
        line_count=$((line_count + 1))
      done <<< "$filtered_logcat"
    fi
  } >> "$report" || true
}

# Treat every Flutter expanded-reporter status as evidence that the test phase
# advanced, except for the pre-test loading line of the two benchmark files.
clockrove_flutter_test_has_started() {
  local log_file="$1"
  local line
  local allowed_loading='^[0-9]{2}:[0-9]{2}[[:space:]][+]0:[[:space:]]loading[[:space:]]+([^[:space:]]*/)?integration_test/(backup_crypto_benchmark|backup_snapshot_memory_benchmark)_test\.dart$'
  local reporter_progress='^[0-9]{2}:[0-9]{2}[[:space:]][+][0-9]+([[:space:]]+[~][0-9]+)?([[:space:]]+-[0-9]+)?:'

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if [[ "$line" =~ $allowed_loading ]]; then
      continue
    fi
    if [[ "$line" =~ $reporter_progress ]]; then
      return 0
    fi
  done < "$log_file"
  return 1
}

# Flutter resolves adb to the SDK's absolute platform-tools path, so a PATH
# shim does not affect its integration-test APK install. Temporarily wrap that
# executable while a Flutter command runs and add --no-streaming only to install
# subcommands. The SDK binary is restored on every normal exit path.
clockrove_with_nonstreaming_adb() (
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  local sdk_adb="$sdk_root/platform-tools/adb"
  local wrapper_dir wrapper_path saved_adb real_adb

  if [[ -z "$sdk_root" || ! -x "$sdk_adb" ]]; then
    echo 'Android SDK adb is unavailable; cannot apply the non-streaming Flutter install policy.' >&2
    return 2
  fi
  wrapper_dir="$(mktemp -d "$sdk_root/platform-tools/.clockrove-adb-wrapper.XXXXXX")" || return 2
  wrapper_path="$wrapper_dir/adb-wrapper"
  saved_adb="$wrapper_dir/adb-original"
  real_adb="$wrapper_dir/adb-real"

  if ! cp -L -p -- "$sdk_adb" "$real_adb"; then
    rm -rf -- "$wrapper_dir"
    echo 'Could not preserve the Android SDK adb before wrapping it.' >&2
    return 2
  fi

  cat > "$wrapper_path" <<'CLOCKROVE_ADB_WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
real_adb="${CLOCKROVE_REAL_ADB:?}"
args=("$@")
for ((index = 0; index < ${#args[@]}; index++)); do
  if [[ "${args[index]}" != install ]]; then
    continue
  fi
  for ((option_index = index + 1; option_index < ${#args[@]}; option_index++)); do
    if [[ "${args[option_index]}" == --no-streaming ]]; then
      exec "$real_adb" "${args[@]}"
    fi
  done
  exec "$real_adb" "${args[@]:0:index+1}" --no-streaming "${args[@]:index+1}"
done
exec "$real_adb" "${args[@]}"
CLOCKROVE_ADB_WRAPPER
  chmod --reference="$sdk_adb" "$wrapper_path" || {
    rm -rf -- "$wrapper_dir"
    echo 'Could not set permissions on the Android SDK adb wrapper.' >&2
    return 2
  }

  clockrove_restore_sdk_adb() {
    local command_status=$?
    trap - EXIT
    if [[ -e "$saved_adb" || -L "$saved_adb" ]]; then
      rm -f -- "$sdk_adb"
      if ! mv -- "$saved_adb" "$sdk_adb"; then
        echo 'Could not restore the Android SDK adb after the Flutter command.' >&2
        rm -rf -- "$wrapper_dir"
        exit 2
      fi
    fi
    rm -rf -- "$wrapper_dir"
    exit "$command_status"
  }

  trap clockrove_restore_sdk_adb EXIT
  if ! mv -- "$sdk_adb" "$saved_adb" || ! mv -- "$wrapper_path" "$sdk_adb"; then
    echo 'Could not install the temporary Android SDK adb wrapper.' >&2
    return 2
  fi
  export CLOCKROVE_REAL_ADB="$real_adb"
  "$@"
)

# Return the app's process IDs on success, status 1 only when `pidof` reports
# no process, and status 2 for ADB, timeout, or malformed-output failures.
clockrove_app_process_pids() {
  local package="$1"
  local output status
  if [[ ! "$package" =~ ^[A-Za-z0-9_.]+$ ]]; then
    echo 'Invalid Android application package for pidof.' >&2
    return 2
  fi
  if output="$(timeout --kill-after=3s 10s adb -s emulator-5554 shell pidof "$package" 2>&1)"; then
    status=0
  else
    status=$?
  fi
  output="${output//$'\r'/}"
  if (( status == 0 )) &&
    [[ "$output" =~ ^[0-9]+([[:space:]]+[0-9]+)*$ ]]; then
    printf '%s' "$output"
    return 0
  fi
  if (( status == 1 )) && [[ -z "$output" ]]; then
    return 1
  fi
  echo "Could not verify process IDs for $package (pidof exit $status): $output" >&2
  return 2
}

# Flutter's integration_test driver performs its own APK installation, outside
# clockrove_install_apk. Retry only the reproduced failure where the bounded
# command timeout interrupts that install before any tests start. A successful
# retry is accepted only when the actual test emits its success result marker.
# A caller may provide a function hook to restore synthetic state after the
# confirmed reboot and before the one retry.
clockrove_run_flutter_integration_test() {
  local api="$1"
  local label="$2"
  local success_pattern="$3"
  shift 3
  local before_retry_hook=''
  if [[ "${1:-}" == --before-retry-hook ]]; then
    before_retry_hook="${2:-}"
    if [[ ! "$before_retry_hook" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]]; then
      echo 'Flutter install retry hook must name a shell function.' >&2
      return 2
    fi
    shift 2
  fi
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
    if clockrove_with_nonstreaming_adb \
      timeout --kill-after=10s "${timeout_seconds}s" "$@" > "$attempt_log" 2>&1; then
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
      clockrove_flutter_test_has_started "$attempt_log" ||
      grep -Eq 'BACKUP_CRYPTO_BENCHMARK |BACKUP_SNAPSHOT_MEMORY_' "$attempt_log"; then
      return "$status"
    fi

    echo 'Confirmed Flutter app-install timeout before any benchmark test ran; recovering the emulator before one retry.'
    clockrove_capture_install_timeout_diagnostics "$api" "$label" flutter "$attempt" \
      io.github.centerionware.clockrove || true
    if ! clockrove_reboot_emulator_and_wait "$diagnostics_dir" "$label" "$attempt" "$api"; then
      echo 'Emulator recovery was not confirmed; refusing a blind Flutter retry.' >&2
      return 2
    fi
    if [[ -n "$before_retry_hook" ]]; then
      if ! declare -F "$before_retry_hook" >/dev/null; then
        echo "Flutter install retry hook is not defined: $before_retry_hook" >&2
        return 2
      fi
      echo "Running Flutter install retry hook: $before_retry_hook"
      if ! "$before_retry_hook" "$api"; then
        echo 'Flutter install retry hook failed; refusing to continue the benchmark.' >&2
        return 2
      fi
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
  local identity package expected_version identity_parsed=0

  if [[ "$label" != app && "$label" != instrumentation ]]; then
    echo 'Unsupported APK label.' >&2
    return 2
  fi
  mkdir -p "$diagnostics_dir"
  while (( attempt <= 2 )); do
    local attempt_log="$diagnostics_dir/$label-attempt-$attempt.log"
    echo "Installing $label APK on API $api (attempt $attempt of 2)"
    if timeout --kill-after=10s 120s adb -s emulator-5554 install --no-streaming -r -t "$apk" > "$attempt_log" 2>&1; then
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
      clockrove_capture_install_timeout_diagnostics "$api" "$label" adb "$attempt" unknown || true
      if (( identity_parsed == 0 )); then
        if identity="$(clockrove_apk_identity "$apk" "$label")"; then
          read -r package expected_version <<< "$identity"
          identity_parsed=1
        else
          echo 'Could not establish the exact APK identity after an ambiguous install; refusing recovery or retry.' >&2
          return 2
        fi
      fi
      clockrove_append_install_timeout_diagnostic \
        "$diagnostics_dir/$label-install-timeout-adb-attempt-$attempt.txt" \
        "pm path $package" adb -s emulator-5554 shell pm path "$package" || true
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

# Reuse an already installed package when its APK package/version identity is
# the expected build. If PackageManager confirms another version or absence,
# install through the normal bounded recovery path. Unknown PackageManager
# state must never authorize an install.
clockrove_ensure_apk_version() {
  local api="$1"
  local label="$2"
  local apk="$3"
  local identity package expected_version verify_status
  local diagnostics_dir="${GITHUB_WORKSPACE:?}/build/device-test-results/api-$api/install-diagnostics"

  if [[ ! "$api" =~ ^[0-9]+$ ]]; then
    echo 'Expected a numeric API level before checking an installed APK.' >&2
    return 2
  fi
  if ! identity="$(clockrove_apk_identity "$apk" "$label")"; then
    return 2
  fi
  read -r package expected_version <<< "$identity"
  mkdir -p "$diagnostics_dir"
  if clockrove_verify_installed_apk \
    "$package" "$expected_version" "$diagnostics_dir/$label-preinstall-state.txt"; then
    echo "Reusing installed $label package $package at versionCode $expected_version."
    return 0
  else
    verify_status=$?
  fi
  if (( verify_status != 1 )); then
    echo "Could not confirm installed $label package state; refusing an install." >&2
    return 2
  fi
  clockrove_install_apk "$api" "$label" "$apk"
}
