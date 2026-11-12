#!/usr/bin/env bash
# Run only on the ephemeral GitHub runner, never the owner's VM.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This check runs only in GitHub Actions.' >&2; exit 2; }
: "${ANDROID_HOME:?}" "${RUNNER_TEMP:?}" "${GITHUB_WORKSPACE:?}"
# Ubuntu runner prerequisite linked by the pinned emulator, even with no audio.
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends libpulse0
sdkmanager="$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager"
export ANDROID_AVD_HOME="$RUNNER_TEMP/clockrove-pdf-avds"
mkdir -p "$ANDROID_AVD_HOME" "$GITHUB_WORKSPACE/build/device-test-results"
# Fixed vendor archive URLs and vendor-published checksums, recorded from the
# official repository2-3.xml and sys-img/android/sys-img2-3.xml on 2026-11-11.
curl --fail --location --retry 3 https://dl.google.com/android/repository/emulator-linux_x64-16740729.zip -o "$RUNNER_TEMP/clockrove-emulator.zip"
echo "f41f9e31eb5ca64926603c03265c50bbc0ff4172  $RUNNER_TEMP/clockrove-emulator.zip" | sha1sum --check
unzip -oq "$RUNNER_TEMP/clockrove-emulator.zip" -d "$ANDROID_HOME"
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
    <revision><major>37</major><minor>2</minor><micro>13</micro></revision>
    <display-name>Android Emulator</display-name>
  </localPackage>
</ns2:repository>
XML
export PATH="$ANDROID_HOME/platform-tools:$PATH"
"$ANDROID_HOME/emulator/emulator" -version | tee "$RUNNER_TEMP/clockrove-emulator-version.txt"
grep -F 'Android emulator version 37.2.13' "$RUNNER_TEMP/clockrove-emulator-version.txt"
test -c /dev/kvm
sudo chmod a+rw /dev/kvm
(cd "$GITHUB_WORKSPACE/android" && ./gradlew :app:assembleDebugAndroidTest)
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
  "$sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed > "$RUNNER_TEMP/clockrove-installed-$clockrove_api.txt"
  echo no | "$avdmanager" create avd --force --name "clockrove-pdf-$clockrove_api" --package "system-images;android-$clockrove_api;default;x86_64" --device pixel_2
  "$ANDROID_HOME/emulator/emulator" -avd "clockrove-pdf-$clockrove_api" -port 5554 -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 -cores 2 -feature -Vulkan > "$RUNNER_TEMP/clockrove-pdf-emulator-$clockrove_api.log" 2>&1 &
  clockrove_emulator_pid=$!
  trap 'kill "$clockrove_emulator_pid" 2>/dev/null || true' EXIT
  timeout 180 adb -s emulator-5554 wait-for-device
  clockrove_booted=false
  for clockrove_attempt in $(seq 1 120); do
    if [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then clockrove_booted=true; break; fi
    sleep 2
  done
  [[ "$clockrove_booted" == true ]]
  [[ "$(adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == "$clockrove_api" ]]
  adb -s emulator-5554 shell settings put global window_animation_scale 0
  adb -s emulator-5554 shell settings put global transition_animation_scale 0
  adb -s emulator-5554 shell settings put global animator_duration_scale 0
  # Keep both APKs installed until the actual results and pages are captured.
  # Gradle's connected-test cleanup removes app-specific external files.
  adb -s emulator-5554 install -r -t "$GITHUB_WORKSPACE/build/app/outputs/flutter-apk/app-debug.apk"
  adb -s emulator-5554 install -r -t "$GITHUB_WORKSPACE/build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  mkdir -p "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api"
  clockrove_instrumentation_failed=false
  if ! adb -s emulator-5554 shell am instrument -w -r -e package io.github.centerionware.clockrove -e listener io.github.centerionware.clockrove.CiXmlListener io.github.centerionware.clockrove.test/androidx.test.runner.AndroidJUnitRunner | tee "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/instrumentation.txt"; then
    clockrove_instrumentation_failed=true
  fi
  # This emulator contains only synthetic fixtures. Preserve native crash and
  # Flutter engine diagnostics even when a process crash prevents JUnit output.
  adb -s emulator-5554 logcat -d -v threadtime > "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/logcat.txt"
  adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/settings-accessibility.txt "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/settings-accessibility.txt" || true
  adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/settings-screen.png "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/settings-screen.png" || true
  [[ "$clockrove_instrumentation_failed" == false ]]
  # Retain failing documents as well as successful ones before enforcing XML.
  mkdir -p "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api"
  adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/invoice-previews/. "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/" || true
  adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/clockrove-test-results.xml "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/TEST-clockrove.xml"
  python3 - "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/TEST-clockrove.xml" <<'PY_DEVICE'
import sys
import xml.etree.ElementTree as E
root = E.parse(sys.argv[1]).getroot()
assert int(root.attrib['tests']) > 0, 'No actual Android tests ran'
assert all(int(root.attrib[k]) == 0 for k in ['failures','errors','skipped']), root.attrib
classes = {case.attrib['classname'] for case in root.findall('testcase')}
expected = {
    'io.github.centerionware.clockrove.invoice.InvoicePdfTest': 3,
    'io.github.centerionware.clockrove.invoice.LogoImagesTest': 4,
    'io.github.centerionware.clockrove.tracking.ReportBoundsDeviceTest': 3,
    'io.github.centerionware.clockrove.tracking.SessionTimeDeviceTest': 4,
    'io.github.centerionware.clockrove.settings.SettingsSaveDeviceTest': 2,
}
for classname, count in expected.items():
    actual = sum(case.attrib['classname'] == classname for case in root.findall('testcase'))
    assert actual == count, (classname, actual, count)
print('Verified actual Android JUnit results:', root.attrib)
PY_DEVICE
  for clockrove_style in minimal classic modern; do
    test -s "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-invoice-$clockrove_style.pdf"
    test -s "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-invoice-$clockrove_style.png"
    test -s "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-invoice-$clockrove_style-multipage.pdf"
    test -s "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/clockrove-invoice-$clockrove_style-multipage-page-1.png"
  done
  adb -s emulator-5554 emu kill || true
  wait "$clockrove_emulator_pid" || true
  trap - EXIT
done
