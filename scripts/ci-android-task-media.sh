#!/usr/bin/env bash
# Actual task comments, gallery imports and camera capture in the Android app.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This check runs only in GitHub Actions.' >&2; exit 2; }
: "${GITHUB_WORKSPACE:?}"
clockrove_media_api="${1:?API required}"
[[ "$clockrove_media_api" == 24 || "$clockrove_media_api" == 35 ]]
[[ "$(adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == "$clockrove_media_api" ]]
clockrove_media_results="$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_media_api"
test -s "$clockrove_media_results/TEST-quotes.xml"
adb -s emulator-5554 shell pm clear io.github.centerionware.clockrove | grep -F Success
# Keep camera and gallery intents deterministic: test-only handlers supply
# synthetic images through the granted URI and document-picker contracts.
for clockrove_camera_package in com.android.camera2 com.google.android.GoogleCamera com.android.camera com.android.documentsui com.google.android.documentsui; do
  adb -s emulator-5554 shell pm disable-user --user 0 "$clockrove_camera_package" >/dev/null 2>&1 || true
done
mkdir -p "$clockrove_media_results/task-media-captures"
adb -s emulator-5554 logcat -v threadtime > "$clockrove_media_results/task-media-logcat.txt" &
clockrove_media_logcat_pid=$!
trap 'kill "$clockrove_media_logcat_pid" 2>/dev/null || true' EXIT
clockrove_media_failed=false
if ! timeout 300 adb -s emulator-5554 shell am instrument -w -r -e class io.github.centerionware.quotetest.TaskConversationDeviceTest -e listener io.github.centerionware.clockrove.CiXmlListener io.github.centerionware.clockrove.test/androidx.test.runner.AndroidJUnitRunner | tee "$clockrove_media_results/task-media-instrumentation.txt"; then
  clockrove_media_failed=true
fi
kill "$clockrove_media_logcat_pid" 2>/dev/null || true
wait "$clockrove_media_logcat_pid" 2>/dev/null || true
trap - EXIT
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/task-media-captures/. "$clockrove_media_results/task-media-captures/" || true
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/clockrove-test-results.xml "$clockrove_media_results/TEST-task-media.xml"
[[ "$clockrove_media_failed" == false ]]
python3 - "$clockrove_media_results/TEST-task-media.xml" <<'PY_DEVICE'
import sys
import xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
assert int(root.attrib['tests']) == 1, root.attrib
assert all(int(root.attrib[k]) == 0 for k in ['failures', 'errors', 'skipped']), root.attrib
cases = root.findall('testcase')
assert len(cases) == 1
assert cases[0].attrib['classname'] == 'io.github.centerionware.quotetest.TaskConversationDeviceTest'
assert cases[0].attrib['name'] == 'actualTaskCommentAndReviewedCameraPhotosPersistInPrivateStorage'
print('Verified actual Android task comment and camera photo journey:', root.attrib)
PY_DEVICE
for clockrove_media_capture in job-location-recording-off task-comment task-gallery-review task-gallery-attached task-camera-review task-camera-attached comment-camera-attached task-photo-viewer; do
  test -s "$clockrove_media_results/task-media-captures/$clockrove_media_capture.png"
done
