#!/usr/bin/env bash
# Run only on the ephemeral GitHub runner, never the owner's VM.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This check runs only in GitHub Actions.' >&2; exit 2; }
: "${ANDROID_HOME:?}" "${RUNNER_TEMP:?}" "${GITHUB_WORKSPACE:?}"
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
  clockrove_result=0
  (cd "$GITHUB_WORKSPACE/android" && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.centerionware.clockrove.invoice.InvoicePdfTest,io.github.centerionware.clockrove.invoice.LogoImagesTest "-Pandroid.testInstrumentationRunnerArguments.clockroveApi=$clockrove_api") || clockrove_result=$?
  mkdir -p "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api"
  if [[ -d "$GITHUB_WORKSPACE/build/app/outputs/androidTest-results" ]]; then cp -R "$GITHUB_WORKSPACE/build/app/outputs/androidTest-results/." "$GITHUB_WORKSPACE/build/device-test-results/api-$clockrove_api/"; fi
  mkdir -p "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api"
  adb -s emulator-5554 pull /sdcard/Android/data/io.github.centerionware.clockrove/files/invoice-previews/. "$GITHUB_WORKSPACE/build/native-ui-preview/pdf-api-$clockrove_api/" || true
  adb -s emulator-5554 emu kill || true
  wait "$clockrove_emulator_pid" || true
  trap - EXIT
  [[ "$clockrove_result" == 0 ]]
done
