#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT
mkdir -p "$test_root/bin" "$test_root/workspace"
export GITHUB_WORKSPACE="$test_root/workspace"
export PATH="$test_root/bin:$PATH"
export ADB_CALL_LOG="$test_root/adb-calls.txt"
export ADB_INSTALL_COUNT="$test_root/install-count.txt"
export ADB_PACKAGE_QUERY_COUNT="$test_root/package-query-count.txt"
export ADB_PACKAGE_STATE_FILE="$test_root/package-state.txt"
export ADB_REBOOT_COUNT="$test_root/reboot-count.txt"
export AAPT_CALL_LOG="$test_root/aapt-calls.txt"
export ADB_FAKE_PACKAGE=io.github.centerionware.clockrove
export ADB_FAKE_VERSION=42
export ADB_FAKE_API=24
export ADB_INSTALL_MODE=timeout-once
export ADB_PACKAGE_QUERY_MODE=normal
export ADB_REBOOT_MODE=normal
export CLOCKROVE_AAPT_BIN="$test_root/bin/aapt"
export CLOCKROVE_AAPT_FIXTURE=standard
export CLOCKROVE_PACKAGE_SETTLE_SECONDS=5
export CLOCKROVE_BOOT_SETTLE_SECONDS=3
export CLOCKROVE_FLUTTER_TEST_TIMEOUT_SECONDS=3

cat > "$test_root/bin/timeout" <<'FAKE_TIMEOUT'
#!/usr/bin/env bash
set -euo pipefail
while [[ "${1:-}" == --kill-after=* ]]; do shift; done
while (($#)); do
  case "$1" in
    --kill-after=*) shift ;;
    --kill-after) shift 2 ;;
    --) shift; break ;;
    [0-9]*) shift; break ;;
    *) break ;;
  esac
done
exec "$@"
FAKE_TIMEOUT

cat > "$test_root/bin/aapt" <<'FAKE_AAPT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$AAPT_CALL_LOG"
case "$CLOCKROVE_AAPT_FIXTURE" in
  standard)
    echo "package: name='$ADB_FAKE_PACKAGE' versionCode='$ADB_FAKE_VERSION' versionName='0.1.0' platformBuildVersionName='16'"
    ;;
  reordered)
    echo "package: versionName='0.1.0' versionCode='$ADB_FAKE_VERSION' name='$ADB_FAKE_PACKAGE' platformBuildVersionName='16'"
    ;;
  instrumentation-empty-version)
    echo "package: name='io.github.centerionware.clockrove.test' versionCode='' versionName='' platformBuildVersionName='16'"
    ;;
  app-empty-version)
    echo "package: name='io.github.centerionware.clockrove' versionCode='' versionName='0.1.0' platformBuildVersionName='16'"
    ;;
  *)
    echo "Synthetic aapt failure: unknown fixture $CLOCKROVE_AAPT_FIXTURE" >&2
    exit 1
    ;;
esac
FAKE_AAPT

cat > "$test_root/bin/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$ADB_CALL_LOG"

if [[ "${1:-}" == -s && "${3:-}" == install ]]; then
  count=0
  [[ ! -f "$ADB_INSTALL_COUNT" ]] || read -r count < "$ADB_INSTALL_COUNT"
  count=$((count + 1))
  printf '%s\n' "$count" > "$ADB_INSTALL_COUNT"
  case "$ADB_INSTALL_MODE:$count" in
    timeout-once:1|timeout-always:*)
      echo 'Synthetic install timeout' >&2
      exit 124
      ;;
    kill-once:1)
      echo 'Synthetic install killed' >&2
      exit 137
      ;;
    committed-response-lost:1)
      printf '%s %s\n' "$ADB_FAKE_PACKAGE" "$ADB_FAKE_VERSION" > "$ADB_PACKAGE_STATE_FILE"
      echo 'Synthetic install committed; client reply lost' >&2
      exit 124
      ;;
    wrong-version-response-lost:1)
      printf '%s 41\n' "$ADB_FAKE_PACKAGE" > "$ADB_PACKAGE_STATE_FILE"
      echo 'Synthetic old package remains after lost reply' >&2
      exit 124
      ;;
    package-state-unknown:1)
      echo 'Synthetic install timeout; package state will be unavailable' >&2
      exit 124
      ;;
    non-timeout:*)
      echo 'Synthetic non-timeout install failure' >&2
      exit 1
      ;;
    *)
      printf '%s %s\n' "$ADB_FAKE_PACKAGE" "$ADB_FAKE_VERSION" > "$ADB_PACKAGE_STATE_FILE"
      echo 'Synthetic install succeeded'
      exit 0
      ;;
  esac
fi

if [[ "${3:-}" == reboot ]]; then
  if [[ "$ADB_REBOOT_MODE" == normal ]]; then
    count=0
    [[ ! -f "$ADB_REBOOT_COUNT" ]] || read -r count < "$ADB_REBOOT_COUNT"
    printf '%s\n' "$((count + 1))" > "$ADB_REBOOT_COUNT"
  fi
  echo 'Synthetic emulator reboot requested'
  exit 0
fi
if [[ "${3:-}" == wait-for-device ]]; then
  echo 'Synthetic emulator reconnected'
  exit 0
fi
if [[ "${3:-}" == get-state ]]; then
  echo 'device'
  exit 0
fi
if [[ "${3:-}" == shell && "${4:-}" == getprop && "${5:-}" == sys.boot_completed ]]; then
  echo '1'
  exit 0
fi
if [[ "${3:-}" == shell && "${4:-}" == getprop && "${5:-}" == ro.build.version.sdk ]]; then
  if [[ "${ADB_FAKE_API_CRLF:-}" == true ]]; then
    printf '%s\r\n' "$ADB_FAKE_API"
  else
    echo "$ADB_FAKE_API"
  fi
  exit 0
fi
if [[ "${3:-}" == shell && "${4:-}" == cat && "${5:-}" == /proc/sys/kernel/random/boot_id ]]; then
  count=0
  [[ ! -f "$ADB_REBOOT_COUNT" ]] || read -r count < "$ADB_REBOOT_COUNT"
  echo "synthetic-boot-$count"
  exit 0
fi
if [[ "${3:-}" == shell && "${4:-}" == dumpsys && "${5:-}" == package ]]; then
  query_count=0
  [[ ! -f "$ADB_PACKAGE_QUERY_COUNT" ]] || read -r query_count < "$ADB_PACKAGE_QUERY_COUNT"
  query_count=$((query_count + 1))
  printf '%s\n' "$query_count" > "$ADB_PACKAGE_QUERY_COUNT"
  if [[ "$ADB_PACKAGE_QUERY_MODE" == unknown ||
    ( "$ADB_PACKAGE_QUERY_MODE" == absent-then-unknown && "$query_count" -gt 1 ) ]]; then
    echo 'Synthetic PackageManager state unavailable' >&2
    exit 1
  fi
  requested_package="${6:-}"
  if [[ "$ADB_PACKAGE_QUERY_MODE" == expected-text-error ]]; then
    echo "Package [$ADB_FAKE_PACKAGE] (synthetic):"
    echo "  versionCode=$ADB_FAKE_VERSION versionName=0.1.0"
    exit 1
  fi
  if [[ -s "$ADB_PACKAGE_STATE_FILE" ]]; then
    read -r installed_package installed_version < "$ADB_PACKAGE_STATE_FILE"
    echo "Package [$installed_package] (synthetic):"
    echo "  versionCode=$installed_version versionName=0.1.0"
  else
    echo "Unable to find package: $requested_package"
  fi
  exit 0
fi

echo "Synthetic adb response: $*"
FAKE_ADB

cat > "$test_root/bin/sleep" <<'FAKE_SLEEP'
#!/usr/bin/env bash
exit 0
FAKE_SLEEP

cat > "$test_root/bin/flutter" <<'FAKE_FLUTTER'
#!/usr/bin/env bash
set -euo pipefail
flutter_test_path="${FLUTTER_TEST_PATH:-integration_test/backup_crypto_benchmark_test.dart}"
count=0
[[ ! -f "$FLUTTER_TEST_COUNT" ]] || read -r count < "$FLUTTER_TEST_COUNT"
count=$((count + 1))
printf '%s\n' "$count" > "$FLUTTER_TEST_COUNT"
case "$FLUTTER_TEST_MODE:$count" in
  install-timeout-once:1|install-timeout-always:*)
    if [[ "$FLUTTER_TEST_MODE" == install-timeout-once ]]; then
      echo "00:00 +0: loading $flutter_test_path"
    else
      echo "00:00 +0: loading /home/runner/work/Clockrove/Clockrove/$flutter_test_path"
    fi
    echo '✓ Built build/app/outputs/flutter-apk/app-debug.apk'
    echo 'Installing build/app/outputs/flutter-apk/app-debug.apk... No tests ran.'
    echo 'Error: ADB exited with exit code -15'
    exit 124
    ;;
  unrelated-timeout:*)
    echo 'Error: ADB exited with exit code -15'
    exit 124
    ;;
  started-test-timeout:*)
    echo '✓ Built build/app/outputs/flutter-apk/app-debug.apk'
    echo 'Installing build/app/outputs/flutter-apk/app-debug.apk... No tests ran.'
    echo '00:00 +1 -1: backup crypto test failed after execution began'
    echo 'Error: ADB exited with exit code -15'
    exit 124
    ;;
  zero-run-test-failure:*)
    echo '✓ Built build/app/outputs/flutter-apk/app-debug.apk'
    echo 'Installing build/app/outputs/flutter-apk/app-debug.apk... No tests ran.'
    echo '00:00 +0 -1: Flutter startup failed before the test ran'
    echo 'Error: ADB exited with exit code -15'
    exit 124
    ;;
  zero-run-test-progress:*)
    echo '00:00 +0: loading /home/runner/work/Clockrove/Clockrove/integration_test/backup_crypto_benchmark_test.dart'
    echo '✓ Built build/app/outputs/flutter-apk/app-debug.apk'
    echo 'Installing build/app/outputs/flutter-apk/app-debug.apk... No tests ran.'
    echo '00:00 +0: backup crypto runs on a minimum-API Android emulator'
    echo 'Error: ADB exited with exit code -15'
    exit 124
    ;;
  zero-skip-progress:*)
    echo '00:00 +0: loading /home/runner/work/Clockrove/Clockrove/integration_test/backup_crypto_benchmark_test.dart'
    echo '✓ Built build/app/outputs/flutter-apk/app-debug.apk'
    echo 'Installing build/app/outputs/flutter-apk/app-debug.apk... No tests ran.'
    echo '00:00 +0 ~1: backup crypto benchmark skipped'
    echo 'Error: ADB exited with exit code -15'
    exit 124
    ;;
  success-without-marker:*)
    echo '00:00 +0: Flutter returned without the crypto result'
    exit 0
    ;;
  *)
    if [[ "$flutter_test_path" == *backup_snapshot_memory_benchmark_test.dart ]]; then
      echo 'BACKUP_SNAPSHOT_MEMORY_SEED pid=42 process_start_ticks=7 attachment_count=19 media_bytes=57671680 target_bytes=57671680'
    else
      echo 'BACKUP_CRYPTO_BENCHMARK encrypt_ms=1 decrypt_ms=2 envelope_bytes=3'
    fi
    exit 0
    ;;
esac
FAKE_FLUTTER

chmod +x "$test_root/bin/timeout" "$test_root/bin/aapt" "$test_root/bin/adb" "$test_root/bin/sleep" "$test_root/bin/flutter"
source "$script_dir/ci-adb-install.sh"

clockrove_test_flutter_retry_hook() {
  printf '%s\n' "$1" >> "$FLUTTER_RETRY_HOOK_LOG"
}

export ADB_INSTALL_MODE=normal
clockrove_install_apk 24 app "$test_root/app.apk"
[[ ! -e "$AAPT_CALL_LOG" ]]
echo 'Verified a successful install does not depend on aapt identity parsing.'

export CLOCKROVE_AAPT_FIXTURE=reordered
[[ "$(clockrove_apk_identity "$test_root/app.apk" app)" == 'io.github.centerionware.clockrove 42' ]]
echo 'Verified named aapt fields are parsed independently of their order.'

export CLOCKROVE_AAPT_FIXTURE=instrumentation-empty-version
[[ "$(clockrove_apk_identity "$test_root/instrumentation.apk" instrumentation)" == 'io.github.centerionware.clockrove.test 0' ]]
echo 'Verified an empty instrumentation versionCode maps to PackageManager versionCode 0.'

export CLOCKROVE_AAPT_FIXTURE=standard
rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$AAPT_CALL_LOG" \
  "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=timeout-once
clockrove_install_apk 24 app "$test_root/app.apk"
[[ "$(cat "$ADB_INSTALL_COUNT")" == 2 ]]
[[ "$(wc -l < "$AAPT_CALL_LOG")" == 1 ]]
grep -F 'Synthetic install timeout' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-attempt-1.log" >/dev/null
grep -F 'Synthetic install succeeded' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-attempt-2.log" >/dev/null
[[ "$(grep -Fc 'reboot' "$ADB_CALL_LOG")" == 1 ]]
grep -F 'result=expected-package-version-absent' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-package-state-attempt-1.txt" >/dev/null
echo 'Verified a timed-out install reboots, confirms the expected version absent, and retries once.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=kill-once
export ADB_FAKE_API=35
clockrove_install_apk 35 app "$test_root/app.apk"
[[ "$(cat "$ADB_INSTALL_COUNT")" == 2 ]]
grep -F 'Synthetic install killed' "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/app-attempt-1.log" >/dev/null
grep -F 'Synthetic install succeeded' "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/app-attempt-2.log" >/dev/null
echo 'Verified a forcibly killed install gets one bounded recovery and retry.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=non-timeout
export ADB_FAKE_API=24
if clockrove_install_apk 24 app "$test_root/app.apk"; then
  echo 'A non-timeout install failure should remain a failure.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 1 && "$(cat "$ADB_INSTALL_COUNT")" == 1 ]]
! grep -Fq 'reboot' "$ADB_CALL_LOG"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/logcat.txt"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/third-party-packages.txt"
echo 'Verified ordinary install errors do not trigger recovery or retry.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=timeout-always
export ADB_FAKE_API=35
if clockrove_install_apk 35 instrumentation "$test_root/instrumentation.apk"; then
  echo 'Two timed-out installs with an absent package should fail.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$ADB_INSTALL_COUNT")" == 2 ]]
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/instrumentation-attempt-1.log"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/instrumentation-attempt-2.log"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/instrumentation-package-state-attempt-1.txt"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/instrumentation-package-state-attempt-2.txt"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/logcat.txt"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/third-party-packages.txt"
echo 'Verified two install attempts are the strict limit when package absence is confirmed.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=committed-response-lost
export ADB_FAKE_API=24
clockrove_install_apk 24 app "$test_root/app.apk"
[[ "$(cat "$ADB_INSTALL_COUNT")" == 1 ]]
grep -F 'result=exact-package-version-installed' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-package-state-attempt-1.txt" >/dev/null
echo 'Verified a lost install reply after server-side commit is resolved without duplicate installation.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=committed-response-lost
export ADB_FAKE_PACKAGE=io.github.centerionware.clockrove.test
export ADB_FAKE_VERSION=0
export CLOCKROVE_AAPT_FIXTURE=instrumentation-empty-version
clockrove_install_apk 24 instrumentation "$test_root/instrumentation.apk"
[[ "$(cat "$ADB_INSTALL_COUNT")" == 1 ]]
grep -F 'versionCode=0' \
  "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/instrumentation-package-state-attempt-1.txt" >/dev/null
grep -F 'result=exact-package-version-installed' \
  "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/instrumentation-package-state-attempt-1.txt" >/dev/null
echo 'Verified the instrumentation APK lost-reply path accepts its exact PackageManager versionCode 0.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=timeout-once
export ADB_FAKE_PACKAGE=io.github.centerionware.clockrove
export ADB_FAKE_VERSION=42
export CLOCKROVE_AAPT_FIXTURE=app-empty-version
if clockrove_install_apk 24 app "$test_root/app.apk" > "$test_root/missing-app-version.log" 2>&1; then
  echo 'An app APK with an empty versionCode must fail closed after an ambiguous install.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 2 && "$(cat "$ADB_INSTALL_COUNT")" == 1 ]]
! grep -Fq 'reboot' "$ADB_CALL_LOG"
grep -F "package: name='io.github.centerionware.clockrove' versionCode=''" \
  "$test_root/missing-app-version.log" >/dev/null
echo 'Verified app versionCode remains required and parse failures report sanitized aapt package metadata.'

export CLOCKROVE_AAPT_FIXTURE=standard

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=wrong-version-response-lost
clockrove_install_apk 24 app "$test_root/app.apk"
[[ "$(cat "$ADB_INSTALL_COUNT")" == 2 ]]
grep -F 'result=expected-package-version-absent' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-package-state-attempt-1.txt" >/dev/null
echo 'Verified a stale installed version triggers only the bounded retry.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=package-state-unknown
export ADB_PACKAGE_QUERY_MODE=unknown
if clockrove_install_apk 24 app "$test_root/app.apk"; then
  echo 'Unknown package state must fail closed.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$ADB_INSTALL_COUNT")" == 1 ]]
grep -F 'result=package-state-unknown' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-package-state-attempt-1.txt" >/dev/null
echo 'Verified unknown package state fails closed without a blind reinstall.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=timeout-once
export ADB_PACKAGE_QUERY_MODE=absent-then-unknown
if clockrove_install_apk 24 app "$test_root/app.apk"; then
  echo 'An absent result followed by an unknown result must not authorize retry.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$ADB_INSTALL_COUNT")" == 1 ]]
grep -F 'result=package-state-unknown' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-package-state-attempt-1.txt" >/dev/null
echo 'Verified a PackageManager error resets consecutive absence confirmation and prevents retry.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_PACKAGE_QUERY_MODE=normal
export ADB_REBOOT_MODE=stuck
export ADB_FAKE_API=24
if clockrove_install_apk 24 app "$test_root/app.apk"; then
  echo 'An unconfirmed reboot must fail closed.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$ADB_INSTALL_COUNT")" == 1 ]]
grep -F 'boot-restart=not-confirmed' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-recovery-attempt-1.log" >/dev/null
echo 'Verified recovery requires a changed boot ID and will not retry when reboot is unconfirmed.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG" "$ADB_PACKAGE_STATE_FILE" "$ADB_PACKAGE_QUERY_COUNT" "$ADB_REBOOT_COUNT"
export ADB_INSTALL_MODE=committed-response-lost
export ADB_PACKAGE_QUERY_MODE=expected-text-error
export ADB_REBOOT_MODE=normal
if clockrove_install_apk 24 app "$test_root/app.apk"; then
  echo 'Package/version text from a failed dumpsys call must not count as installed.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$ADB_INSTALL_COUNT")" == 1 ]]
grep -F 'result=package-state-unknown' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-package-state-attempt-1.txt" >/dev/null
echo 'Verified failed PackageManager probes cannot confirm an installed version from their text.'

export FLUTTER_TEST_COUNT="$test_root/flutter-test-count.txt"
export FLUTTER_RETRY_HOOK_LOG="$test_root/flutter-retry-hook.log"
export FLUTTER_TEST_MODE=success
rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
clockrove_run_flutter_integration_test 24 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart
[[ "$(cat "$FLUTTER_TEST_COUNT")" == 1 ]]
! grep -Fq 'reboot' "$ADB_CALL_LOG"
echo 'Verified a successful Flutter device test requires its crypto result marker and runs only once.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=install-timeout-once
export ADB_FAKE_API_CRLF=true
clockrove_run_flutter_integration_test 24 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  --before-retry-hook clockrove_test_flutter_retry_hook \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart
[[ "$(cat "$FLUTTER_TEST_COUNT")" == 2 ]]
[[ "$(grep -Fc 'reboot' "$ADB_CALL_LOG")" == 1 ]]
[[ "$(cat "$FLUTTER_RETRY_HOOK_LOG")" == 24 ]]
unset ADB_FAKE_API_CRLF
grep -F 'No tests ran.' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/backup-crypto-benchmark-flutter-attempt-1.log" >/dev/null
grep -F 'BACKUP_CRYPTO_BENCHMARK encrypt_ms=1 decrypt_ms=2 envelope_bytes=3' \
  "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/backup-crypto-benchmark-flutter-attempt-2.log" >/dev/null
echo 'Verified the exact Flutter app-install timeout gets one reboot and one real test retry.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=install-timeout-once
export ADB_FAKE_API=35
if clockrove_run_flutter_integration_test 24 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart; then
  echo 'A rebooted emulator with the wrong API level must not retry.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 2 && "$(cat "$FLUTTER_TEST_COUNT")" == 1 ]]
grep -F 'emulator-api=35' \
  "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/backup-crypto-benchmark-recovery-attempt-1.log" >/dev/null
export ADB_FAKE_API=24
echo 'Verified the Flutter retry refuses a rebooted emulator on the wrong API level.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=unrelated-timeout
rm -f "$FLUTTER_RETRY_HOOK_LOG"
if clockrove_run_flutter_integration_test 24 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  --before-retry-hook clockrove_test_flutter_retry_hook \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart; then
  echo 'An unrelated Flutter timeout must remain a failure.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$FLUTTER_TEST_COUNT")" == 1 ]]
! grep -Fq 'reboot' "$ADB_CALL_LOG"
[[ ! -s "$FLUTTER_RETRY_HOOK_LOG" ]]
echo 'Verified unrelated Flutter failures do not trigger a retry.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=started-test-timeout
if clockrove_run_flutter_integration_test 24 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart; then
  echo 'A Flutter test that started must not be retried.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$FLUTTER_TEST_COUNT")" == 1 ]]
! grep -Fq 'reboot' "$ADB_CALL_LOG"
echo 'Verified test progress/failure prevents the Flutter install retry.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=zero-run-test-failure
if clockrove_run_flutter_integration_test 24 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart; then
  echo 'A Flutter +0 -1 failure summary must not be retried.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$FLUTTER_TEST_COUNT")" == 1 ]]
! grep -Fq 'reboot' "$ADB_CALL_LOG"
echo 'Verified a Flutter +0 -1 failure summary prevents retry while +0 loading remains eligible.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=zero-run-test-progress
if clockrove_run_flutter_integration_test 24 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart; then
  echo 'The actual Flutter +0 benchmark test-progress event must not be retried.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$FLUTTER_TEST_COUNT")" == 1 ]]
! grep -Fq 'reboot' "$ADB_CALL_LOG"
echo 'Verified the actual Flutter +0 benchmark test-progress event prevents retry.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=zero-skip-progress
if clockrove_run_flutter_integration_test 24 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart; then
  echo 'A Flutter +0 skipped status must not be retried.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$FLUTTER_TEST_COUNT")" == 1 ]]
! grep -Fq 'reboot' "$ADB_CALL_LOG"
echo 'Verified Flutter +0 skipped status prevents retry.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=success-without-marker
if clockrove_run_flutter_integration_test 24 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart; then
  echo 'A Flutter command without the real crypto result marker must fail.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 3 && "$(cat "$FLUTTER_TEST_COUNT")" == 1 ]]
! grep -Fq 'reboot' "$ADB_CALL_LOG"
echo 'Verified a zero exit without benchmark execution cannot pass.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=install-timeout-always
export ADB_FAKE_API=35
if clockrove_run_flutter_integration_test 35 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart; then
  echo 'A repeated Flutter install timeout must remain a failure.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$FLUTTER_TEST_COUNT")" == 2 ]]
[[ "$(grep -Fc 'reboot' "$ADB_CALL_LOG")" == 1 ]]
echo 'Verified the Flutter install recovery allows exactly one retry.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=install-timeout-once
export ADB_REBOOT_MODE=stuck
if clockrove_run_flutter_integration_test 35 backup-crypto-benchmark \
  'BACKUP_CRYPTO_BENCHMARK encrypt_ms=[0-9]+ decrypt_ms=[0-9]+ envelope_bytes=[0-9]+' \
  -- flutter test integration_test/backup_crypto_benchmark_test.dart; then
  echo 'An unconfirmed Flutter emulator reboot must fail closed.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 2 && "$(cat "$FLUTTER_TEST_COUNT")" == 1 ]]
grep -F 'boot-restart=not-confirmed' \
  "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/backup-crypto-benchmark-recovery-attempt-1.log" >/dev/null
echo 'Verified Flutter test recovery refuses retry unless emulator reboot is confirmed.'

rm -f "$FLUTTER_TEST_COUNT" "$ADB_CALL_LOG" "$ADB_REBOOT_COUNT"
export FLUTTER_TEST_MODE=install-timeout-once
export FLUTTER_TEST_PATH=integration_test/backup_snapshot_memory_benchmark_test.dart
export ADB_REBOOT_MODE=normal
export ADB_FAKE_API=24
clockrove_run_flutter_integration_test 24 backup-snapshot-memory-seed \
  'BACKUP_SNAPSHOT_MEMORY_SEED pid=[0-9]+ process_start_ticks=[0-9]+ attachment_count=[0-9]+ media_bytes=[0-9]+' \
  -- flutter test integration_test/backup_snapshot_memory_benchmark_test.dart
[[ "$(cat "$FLUTTER_TEST_COUNT")" == 2 ]]
[[ "$(grep -Fc 'reboot' "$ADB_CALL_LOG")" == 1 ]]
unset FLUTTER_TEST_PATH
echo 'Verified the synthetic-media integration test loading line permits only the bounded pre-test install retry.'
