#!/usr/bin/env bash
# Run only on the ephemeral GitHub runner, never the owner's VM.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This check runs only in GitHub Actions.' >&2; exit 2; }
: "${ANDROID_HOME:?}" "${RUNNER_TEMP:?}" "${GITHUB_WORKSPACE:?}"
source "$GITHUB_WORKSPACE/scripts/ci-adb-install.sh"

declare -a clockrove_validation_failures=()
declare -a clockrove_validation_failure_logs=()

clockrove_record_validation_failure() {
  local api="$1"
  local label="$2"
  local status="$3"
  local log_path="${4:-}"
  local failure="API $api: $label (exit $status)"
  clockrove_validation_failures+=("$failure")
  clockrove_validation_failure_logs+=("$log_path")
}

clockrove_run_validation_suite() {
  local api="$1"
  local label="$2"
  shift 2
  local log_directory="$GITHUB_WORKSPACE/build/device-test-results/api-$api"
  local log_path="$log_directory/validation-$label.log"
  mkdir -p "$log_directory"
  if "$@" 2>&1 | tee "$log_path"; then
    echo "Passed API $api: $label"
  else
    local status=$?
    clockrove_record_validation_failure "$api" "$label" "$status" "$log_path"
  fi
}

clockrove_require_files() {
  local api="$1"
  local label="$2"
  shift 2
  local missing=()
  local path
  for path in "$@"; do
    [[ -s "$path" ]] || missing+=("${path##*/}")
  done
  if ((${#missing[@]})); then
    clockrove_record_validation_failure "$api" "$label missing: ${missing[*]}" 1
  fi
}
# Ubuntu runner prerequisite linked by the pinned emulator, even with no audio.
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends libpulse0
sdkmanager="$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager"
export ANDROID_AVD_HOME="$RUNNER_TEMP/clockrove-pdf-avds"
mkdir -p "$ANDROID_AVD_HOME" "$GITHUB_WORKSPACE/build/device-test-results"
# Emulator 37.1.11 is pinned per Chromium's CI rollback for the 37.2.13 Linux
# host crash. URL and published SHA-256 are from Google's Android Emulator
# Archive: https://developer.android.com/studio/emulator_archive (retrieved
# 2026-11-16).
curl --fail --location --retry 3 https://redirector.gvt1.com/edgedl/android/repository/emulator-linux_x64-15917651.zip -o "$RUNNER_TEMP/clockrove-emulator.zip"
echo "95771e0ae431897b2a4bd2d97fa095f29a8b0624a7b216baf529f9306161c266  $RUNNER_TEMP/clockrove-emulator.zip" | sha256sum --check
unzip -oq "$RUNNER_TEMP/clockrove-emulator.zip" -d "$ANDROID_HOME"
# Fixed platform-tools and system-image archives use vendor-published
# checksums recorded from the official repository metadata on 2026-11-11.
curl --fail --location --retry 3 https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip -o "$RUNNER_TEMP/clockrove-platform-tools.zip"
echo "477254aa5f903c15cf51001717bdf347fb6b53e0  $RUNNER_TEMP/clockrove-platform-tools.zip" | sha1sum --check
unzip -oq "$RUNNER_TEMP/clockrove-platform-tools.zip" -d "$ANDROID_HOME"
# Archive extraction does not install the SDK package descriptor used by
# avdmanager. Declare the exact verified emulator revision without resolving a
# floating package through sdkmanager.
cat > "$ANDROID_HOME/emulator/package.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns3="http://schemas.android.com/repository/android/generic/02" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <localPackage path="emulator" obsolete="false">
    <type-details xsi:type="ns3:genericDetailsType"/>
    <revision><major>37</major><minor>1</minor><micro>11</micro></revision>
    <display-name>Android Emulator</display-name>
  </localPackage>
</ns2:repository>
XML
export PATH="$ANDROID_HOME/platform-tools:$PATH"
"$ANDROID_HOME/emulator/emulator" -version | tee "$RUNNER_TEMP/clockrove-emulator-version.txt"
grep -F 'Android emulator version 37.1.11' "$RUNNER_TEMP/clockrove-emulator-version.txt"
test -c /dev/kvm
sudo chmod a+rw /dev/kvm
(cd "$GITHUB_WORKSPACE/android" && ./gradlew :app:assembleDebugAndroidTest)
# integration_test rebuilds the shared output APK with its test entrypoint.
# Keep immutable copies so the next API always installs the real app and the
# matching native instrumentation, regardless of the preceding benchmark.
clockrove_validation_apk="$RUNNER_TEMP/clockrove-validation-app.apk"
clockrove_validation_test_apk="$RUNNER_TEMP/clockrove-validation-tests.apk"
cp "$GITHUB_WORKSPACE/build/app/outputs/flutter-apk/app-debug.apk" "$clockrove_validation_apk"
cp "$GITHUB_WORKSPACE/build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$clockrove_validation_test_apk"
for clockrove_api in 24 35; do
  if [[ "$clockrove_api" == 24 ]]; then
    clockrove_revision=8
    clockrove_archive=x86_64-24_r08.zip
    clockrove_checksum=f6559e1949a5879f31a9662f4f0e50ad60181684
  else
    clockrove_revision=2
    clockrove_archive=x86_64-35_r02.zip
    clockrove_checksum=2d857d170c0d1b827149565da34b3383e5306f7f
  fi
  curl --fail --location --retry 3 "https://dl.google.com/android/repository/sys-img/android/$clockrove_archive" -o "$RUNNER_TEMP/$clockrove_archive"
  echo "$clockrove_checksum  $RUNNER_TEMP/$clockrove_archive" | sha1sum --check
  mkdir -p "$ANDROID_HOME/system-images/android-$clockrove_api/default"
  unzip -oq "$RUNNER_TEMP/$clockrove_archive" -d "$ANDROID_HOME/system-images/android-$clockrove_api/default"
  grep -E "^Pkg.Revision\s*=\s*$clockrove_revision\s*$" "$ANDROID_HOME/system-images/android-$clockrove_api/default/x86_64/source.properties"
  # The SDK legacy-package scanner registers the fixed, manually extracted image.
  echo "Checking installed Android components for API $clockrove_api"
  timeout --kill-after=10s 120s "$sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed > "$RUNNER_TEMP/clockrove-installed-$clockrove_api.txt"
  echo "Creating API $clockrove_api test AVD"
  echo no | timeout --kill-after=10s 120s "$avdmanager" create avd --force --name "clockrove-pdf-$clockrove_api" --package "system-images;android-$clockrove_api;default;x86_64" --device pixel_2
  echo "Starting API $clockrove_api emulator"
  "$ANDROID_HOME/emulator/emulator" -avd "clockrove-pdf-$clockrove_api" -port 5554 -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 -cores 2 -feature -Vulkan > "$RUNNER_TEMP/clockrove-pdf-emulator-$clockrove_api.log" 2>&1 &
  clockrove_emulator_pid=$!
  mkdir -p "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api"
  clockrove_cleanup_emulator() {
    local cleanup_status=$?
    local crash_diagnostics="$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/crash-diagnostics"
    local emulator_crash_db='/tmp/android-runner/emu-crash-37.1.11.db'
    local crash_db_bytes

    cp "$RUNNER_TEMP/clockrove-pdf-emulator-$clockrove_api.log" "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/emulator.log" || true
    if (( cleanup_status != 0 )); then
      mkdir -p "$crash_diagnostics"
      if [[ -f "$emulator_crash_db" ]]; then
        crash_db_bytes="$(stat -c '%s' "$emulator_crash_db" 2>/dev/null || echo 0)"
        if [[ "$crash_db_bytes" =~ ^[0-9]+$ ]] && (( crash_db_bytes <= 16777216 )); then
          cp -- "$emulator_crash_db" "$crash_diagnostics/emulator-crash-37.1.11.db" || true
        else
          printf 'emulator_crash_db=omitted\nsize_bytes=%s\nmax_size_bytes=16777216\n' \
            "$crash_db_bytes" > "$crash_diagnostics/emulator-crash-db-omitted.txt"
        fi
      fi
      # Keep bounded host crash metadata, never the full process memory dump.
      {
        printf 'emulator_pid=%s\n' "$clockrove_emulator_pid"
        printf 'core_limit='; ulimit -c
        printf 'kernel_core_pattern='
        cat /proc/sys/kernel/core_pattern 2>/dev/null || printf 'unavailable\n'
      } > "$crash_diagnostics/host-crash-context.txt"
      if command -v coredumpctl >/dev/null 2>&1; then
        coredumpctl --no-pager info "$clockrove_emulator_pid" \
          > "$crash_diagnostics/emulator-coredump-info.txt" 2>&1 || true
      fi
    fi
    kill "$clockrove_emulator_pid" 2>/dev/null || true
    for clockrove_shutdown_attempt in $(seq 1 10); do
      kill -0 "$clockrove_emulator_pid" 2>/dev/null || break
      sleep 1
    done
    kill -KILL "$clockrove_emulator_pid" 2>/dev/null || true
  }
  trap clockrove_cleanup_emulator EXIT
  echo "Waiting for API $clockrove_api emulator device"
  timeout --kill-after=10s 180s adb -s emulator-5554 wait-for-device
  clockrove_booted=false
  clockrove_boot_deadline=$((SECONDS + 240))
  while (( SECONDS < clockrove_boot_deadline )); do
    kill -0 "$clockrove_emulator_pid" 2>/dev/null || break
    if [[ "$(timeout --kill-after=5s 10s adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then clockrove_booted=true; break; fi
    sleep 2
  done
  [[ "$clockrove_booted" == true ]]
  [[ "$(timeout --kill-after=5s 10s adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == "$clockrove_api" ]]
  # This benchmark uses only generated bytes and no product fixtures. Run it
  # before native journeys mutate package-manager and app state, so Flutter
  # installs its integration-test APK on a freshly booted emulator.
  clockrove_benchmark_log="$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/backup-crypto-benchmark.txt"
  if clockrove_run_flutter_integration_test "$clockrove_api" backup-crypto-benchmark \
    'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
    -- flutter test --no-pub integration_test/backup_crypto_benchmark_test.dart \
    -d emulator-5554 --reporter expanded > "$clockrove_benchmark_log" 2>&1; then
    cat "$clockrove_benchmark_log"
    if ! grep -Eq 'BACKUP_STREAMING_ENVELOPE_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ archive_bytes=67108864 envelope_bytes=67134601 baseline_rss_kib=[0-9]+ peak_rss_kib=[0-9]+ peak_delta_rss_kib=[0-9]+' \
      "$clockrove_benchmark_log"; then
      clockrove_record_validation_failure \
        "$clockrove_api" backup-crypto-benchmark-marker 1 "$clockrove_benchmark_log"
    fi
  else
    clockrove_benchmark_status=$?
    cat "$clockrove_benchmark_log"
    clockrove_record_validation_failure "$clockrove_api" backup-crypto-benchmark \
      "$clockrove_benchmark_status" "$clockrove_benchmark_log"
  fi
  adb -s emulator-5554 shell settings put global window_animation_scale 0
  adb -s emulator-5554 shell settings put global transition_animation_scale 0
  adb -s emulator-5554 shell settings put global animator_duration_scale 0
  # Run the picker before other device journeys mutate app and picker state.
  clockrove_run_validation_suite "$clockrove_api" backup-picker \
    bash "$GITHUB_WORKSPACE/scripts/ci-android-backup-picker.sh" \
      "$clockrove_api" "$clockrove_validation_apk" "$clockrove_validation_test_apk"
  # Keep both APKs installed until the actual results and pages are captured.
  # Gradle's connected-test cleanup removes app-specific external files.
  echo "Installing app and instrumentation APKs on API $clockrove_api"
  clockrove_install_apk "$clockrove_api" app "$clockrove_validation_apk"
  clockrove_install_apk "$clockrove_api" instrumentation "$clockrove_validation_test_apk"
  # Test-only real AppWidgetHost binding permission on the ephemeral emulator.
  timeout --kill-after=10s 60s adb -s emulator-5554 shell appwidget grantbind --package io.github.centerionware.clockrove --user 0
  mkdir -p "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api"
  # Stream diagnostics while tests run, so a cancelled/hung run retains its last
  # framework state instead of losing everything before the final logcat pull.
  adb -s emulator-5554 logcat -v threadtime > "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/logcat.txt" &
  clockrove_logcat_pid=$!
  clockrove_instrumentation_failed=false
  if ! timeout 300 adb -s emulator-5554 shell am instrument -w -r -e package io.github.centerionware.clockrove -e listener io.github.centerionware.clockrove.CiXmlListener io.github.centerionware.clockrove.test/androidx.test.runner.AndroidJUnitRunner | tee "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/instrumentation.txt"; then
    clockrove_instrumentation_failed=true
  fi
  kill "$clockrove_logcat_pid" 2>/dev/null || true
  wait "$clockrove_logcat_pid" 2>/dev/null || true
  if [[ "$clockrove_instrumentation_failed" == true ]]; then
    timeout 20 adb -s emulator-5554 shell dumpsys activity activities > "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/activities.txt" || true
    timeout 20 adb -s emulator-5554 shell dumpsys appwidget > "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widgets.txt" || true
    timeout 20 adb -s emulator-5554 exec-out screencap -p > "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widget-timeout.png" || true
  fi
  # This emulator contains only synthetic fixtures. Preserve native crash and
  # Flutter engine diagnostics even when a process crash prevents JUnit output.
  # The continuous stream above already contains the full synthetic test trace.
  # Do not replace it with a second, potentially blocking logcat attachment.
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/settings-accessibility.txt "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/settings-accessibility.txt" || true
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/settings-screen.png "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/settings-screen.png" || true
  mkdir -p "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/settings-captures"
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/settings-captures/. "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/settings-captures/" || true
  mkdir -p "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/map-captures"
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/map-captures/. "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/map-captures/" || true
  mkdir -p "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/gps-captures"
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/gps-captures/. "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/gps-captures/" || true
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/widget-combined.png "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widget-combined.png" || true
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/widget-accessibility.txt "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widget-accessibility.txt" || true
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/widget-setup.png "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widget-setup.png" || true
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/widget-board.png "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widget-board.png" || true
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/widget-failure.png "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widget-failure.png" || true
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/widget-timer-running.png "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widget-timer-running.png" || true
  mkdir -p "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widget-captures"
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/widget-captures/. "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/widget-captures/" || true
  if [[ "$clockrove_instrumentation_failed" == true ]]; then
    clockrove_record_validation_failure "$clockrove_api" native-instrumentation 1 \
      "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/instrumentation.txt"
  fi
  # Retain failing documents as well as successful ones before enforcing XML.
  mkdir -p "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api"
  timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/invoice-previews/. "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/" || true
  clockrove_device_results="$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/TEST-clockrove.xml"
  if ! timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/clockrove-test-results.xml "$clockrove_device_results"; then
    clockrove_record_validation_failure "$clockrove_api" native-test-results-pull 1
  fi
  if ! python3 - "$clockrove_device_results" <<'PY_DEVICE'
import sys
import xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
assert int(root.attrib['tests']) > 0, 'No actual Android tests ran'
assert all(int(root.attrib[k]) == 0 for k in ['failures','errors','skipped']), root.attrib
classes = {case.attrib['classname'] for case in root.findall('testcase')}
expected = {
    'io.github.centerionware.clockrove.invoice.BusinessReportPdfTest': 1,
    'io.github.centerionware.clockrove.invoice.InvoicePdfTest': 4,
    'io.github.centerionware.clockrove.invoice.LogoImagesTest': 5,
    'io.github.centerionware.clockrove.tracking.ReportBoundsDeviceTest': 3,
    'io.github.centerionware.clockrove.tracking.ReportMapDeviceTest': 1,
    'io.github.centerionware.clockrove.tracking.GpsRangeDeletionDeviceTest': 1,
    'io.github.centerionware.clockrove.tracking.GeofenceReceiptsDeviceTest': 1,
    'io.github.centerionware.clockrove.tracking.SessionTimeDeviceTest': 4,
    'io.github.centerionware.clockrove.settings.SettingsSaveDeviceTest': 4,
    'io.github.centerionware.clockrove.widget.WidgetHostDeviceTest': 4,
}
for classname, count in expected.items():
    actual = sum(case.attrib['classname'] == classname for case in root.findall('testcase'))
    assert actual == count, (classname, actual, count)
print('Verified actual Android JUnit results:', root.attrib)
PY_DEVICE
  then
    clockrove_record_validation_failure "$clockrove_api" native-test-results-validation 1 \
      "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/instrumentation.txt"
  fi
for clockrove_settings_capture in location-recording-global-off location-recording-global-on; do
  clockrove_require_files "$clockrove_api" settings-captures \
    "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/settings-captures/$clockrove_settings_capture.png"
done
for clockrove_gps_capture in gps-date-range-calendar gps-date-range-input gps-cancel-review gps-after-cancel gps-after-cancel-report gps-map-after-cancel gps-confirm-review gps-after-confirm gps-after-confirm-report; do
  clockrove_require_files "$clockrove_api" gps-captures \
    "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/gps-captures/$clockrove_gps_capture.png"
done
  for clockrove_style in minimal classic modern; do
    clockrove_require_files "$clockrove_api" "invoice-preview-$clockrove_style" \
      "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-long-exact-invoice-$clockrove_style.pdf" \
      "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-long-exact-invoice-$clockrove_style.png" \
      "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-wide-invoice-$clockrove_style.pdf" \
      "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-wide-invoice-$clockrove_style.png" \
      "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-invoice-$clockrove_style.pdf" \
      "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-invoice-$clockrove_style.png" \
      "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-invoice-$clockrove_style-multipage.pdf" \
      "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-invoice-$clockrove_style-multipage-page-1.png"
  done
  clockrove_require_files "$clockrove_api" settings-preview \
    "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-settings-preview-screen.png"
  # All original XML/PDF/widget evidence has been pulled before clearing the
  # ephemeral emulator for the independently numbered financial journey.
  clockrove_run_validation_suite "$clockrove_api" billing \
    bash "$GITHUB_WORKSPACE/scripts/ci-android-billing.sh" "$clockrove_api"
  clockrove_run_validation_suite "$clockrove_api" copies \
    bash "$GITHUB_WORKSPACE/scripts/ci-android-copies.sh" "$clockrove_api"
  clockrove_run_validation_suite "$clockrove_api" payments \
    bash "$GITHUB_WORKSPACE/scripts/ci-android-payments.sh" "$clockrove_api"
  clockrove_run_validation_suite "$clockrove_api" quotes \
    bash "$GITHUB_WORKSPACE/scripts/ci-android-quotes.sh" "$clockrove_api"
  clockrove_run_validation_suite "$clockrove_api" task-media \
    bash "$GITHUB_WORKSPACE/scripts/ci-android-task-media.sh" "$clockrove_api"
  # Run after native journeys because it clears app data and seeds synthetic
  # task media that must not affect those product journeys.
  clockrove_run_validation_suite "$clockrove_api" backup-memory-benchmark \
    bash "$GITHUB_WORKSPACE/scripts/ci-android-backup-memory-benchmark.sh" \
      "$clockrove_api" "$clockrove_validation_apk" "$clockrove_validation_test_apk"
  clockrove_run_validation_suite "$clockrove_api" help-navigation \
    clockrove_run_flutter_integration_test "$clockrove_api" help-navigation \
    'CLOCKROVE_HELP_NAVIGATION version_and_search=true' \
    -- flutter test --no-uninstall --no-pub integration_test/help_navigation_test.dart \
      -d emulator-5554 --reporter expanded
  cp "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/validation-help-navigation.log" \
    "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/help-navigation.txt"
  timeout --kill-after=5s 15s adb -s emulator-5554 emu kill || true
  clockrove_cleanup_emulator
  trap - EXIT
done

if ((${#clockrove_validation_failures[@]})); then
  echo 'Android validation failures:' >&2
  for clockrove_failure in "${clockrove_validation_failures[@]}"; do
    echo "::error title=Android validation failure::$clockrove_failure"
  done
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      echo '## Android validation failures'
      for index in "${!clockrove_validation_failures[@]}"; do
        echo
        printf -- '- **%s**\n' "${clockrove_validation_failures[$index]}"
        log_path="${clockrove_validation_failure_logs[$index]:-}"
        if [[ -s "$log_path" ]]; then
          echo '  Diagnostic output:'
          echo '  ```text'
          tail -n 30 "$log_path" | cut -c1-400 | sed 's/^/  /'
          echo '  ```'
        else
          echo '  The check failed before writing diagnostic output.'
        fi
      done
    } >> "$GITHUB_STEP_SUMMARY"
  fi
  for index in "${!clockrove_validation_failures[@]}"; do
    printf -- '- %s\n' "${clockrove_validation_failures[$index]}" >&2
    log_path="${clockrove_validation_failure_logs[$index]:-}"
    if [[ -s "$log_path" ]]; then
      tail -n 30 "$log_path" | cut -c1-400 >&2
    fi
  done
  exit 1
fi

echo 'All Android API 24 and 35 validation journeys passed.'
