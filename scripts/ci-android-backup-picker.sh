#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || exit 2
: "${GITHUB_WORKSPACE:?}" "${RUNNER_TEMP:?}"
source "$GITHUB_WORKSPACE/scripts/ci-adb-install.sh"
api="${1:?Android API required}"
[[ "$api" == 24 || "$api" == 35 ]]
app_apk="${2:?Validation APK required}"
test_apk="${3:?Instrumentation APK required}"
package=io.github.centerionware.clockrove
results="$GITHUB_WORKSPACE/build/device-test-results/api-$api"
mkdir -p "$results"
fixture_log="$results/backup-picker-fixture.txt"
fixture_file="$(mktemp "$RUNNER_TEMP/clockrove-picker.XXXXXX")"
trap 'rm -f "$fixture_file"' EXIT
clear_fixture_app() {
  timeout 30 adb -s emulator-5554 shell pm clear "$package" | grep -Fx Success
}

clear_fixture_app
clockrove_run_flutter_integration_test "$api" backup-picker-fixture \
  'CLOCKROVE_DOCUMENT_FIXTURE path=/[^[:space:]]+/clockrove-picker-fixture\.clockrove' \
  --before-retry-hook clear_fixture_app \
  -- flutter test --no-uninstall --no-pub integration_test/backup_document_fixture_test.dart \
    -d emulator-5554 --reporter expanded > "$fixture_log" 2>&1 || { cat "$fixture_log"; exit 1; }
cat "$fixture_log"
fixture_marker="$(grep -Eo 'CLOCKROVE_DOCUMENT_FIXTURE path=/data/(user/0|data)/io\.github\.centerionware\.clockrove/cache/clockrove-picker-fixture\.clockrove' "$fixture_log" | tail -n 1)"
fixture_path="${fixture_marker#CLOCKROVE_DOCUMENT_FIXTURE path=}"
[[ -n "$fixture_path" ]]
timeout 30 adb -s emulator-5554 exec-out run-as "$package" cat "$fixture_path" > "$fixture_file"
[[ -s "$fixture_file" && "$(wc -c < "$fixture_file")" -le 1048576 ]]
timeout 30 adb -s emulator-5554 shell mkdir -p /sdcard/Download
timeout 30 adb -s emulator-5554 push "$fixture_file" /sdcard/Download/clockrove-picker-fixture.clockrove
for picker in com.android.documentsui com.google.android.documentsui; do
  timeout 30 adb -s emulator-5554 shell pm enable --user 0 "$picker" >/dev/null 2>&1 || true
done

clockrove_install_apk "$api" app "$app_apk"
clockrove_install_apk "$api" instrumentation "$test_apk"
clear_fixture_app
timeout 180 adb -s emulator-5554 shell am instrument -w -r \
  -e class io.github.centerionware.backuptest.BackupPickerDeviceTest \
  -e listener io.github.centerionware.clockrove.CiXmlListener \
  "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$results/backup-picker-instrumentation.txt"
timeout 30 adb -s emulator-5554 pull \
  "/sdcard/Android/data/$package/files/backup-picker-failure.png" "$results/backup-picker-failure.png" 2>/dev/null || true
timeout 30 adb -s emulator-5554 pull \
  "/sdcard/Android/data/$package/files/clockrove-test-results.xml" "$results/TEST-backup-picker.xml"
python3 - "$results/TEST-backup-picker.xml" <<'PY_DEVICE'
import sys
import xml.etree.ElementTree as E

result = E.parse(sys.argv[1]).getroot()
assert int(result.attrib["tests"]) == 1, result.attrib
assert all(int(result.attrib[key]) == 0 for key in ("failures", "errors", "skipped")), result.attrib
case = result.find("testcase")
assert case.attrib["name"] == "documentPickerRestoresIntoAnIndependentDatabase", case.attrib
PY_DEVICE
