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
export ADB_INSTALL_MODE=timeout-once

cat > "$test_root/bin/timeout" <<'FAKE_TIMEOUT'
#!/usr/bin/env bash
set -euo pipefail
while [[ "${1:-}" == --kill-after=* ]]; do shift; done
if [[ "${1:-}" =~ ^[0-9]+([.][0-9]+)?[smhd]?$ ]]; then shift; fi
exec "$@"
FAKE_TIMEOUT

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
    timeout-once:1|timeout-always:*) echo 'Synthetic install timeout' >&2; exit 124 ;;
    kill-once:1) echo 'Synthetic install killed' >&2; exit 137 ;;
    non-timeout:*) echo 'Synthetic non-timeout install failure' >&2; exit 1 ;;
    *) echo 'Synthetic install succeeded'; exit 0 ;;
  esac
fi
echo "Synthetic adb response: $*"
FAKE_ADB

cat > "$test_root/bin/sleep" <<'FAKE_SLEEP'
#!/usr/bin/env bash
exit 0
FAKE_SLEEP
chmod +x "$test_root/bin/timeout" "$test_root/bin/adb" "$test_root/bin/sleep"
source "$script_dir/ci-adb-install.sh"

clockrove_install_apk 24 app "$test_root/app.apk"
[[ "$(cat "$ADB_INSTALL_COUNT")" == 2 ]]
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-attempt-1.log"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-attempt-2.log"
grep -F 'Synthetic install timeout' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-attempt-1.log" >/dev/null
grep -F 'Synthetic install succeeded' "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/app-attempt-2.log" >/dev/null
echo 'Verified a timed-out install retries once and then succeeds.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG"
export ADB_INSTALL_MODE=kill-once
clockrove_install_apk 35 app "$test_root/app.apk"
[[ "$(cat "$ADB_INSTALL_COUNT")" == 2 ]]
grep -F 'Synthetic install killed' "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/app-attempt-1.log" >/dev/null
grep -F 'Synthetic install succeeded' "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/app-attempt-2.log" >/dev/null
echo 'Verified a forcibly killed install receives one retry.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG"
export ADB_INSTALL_MODE=non-timeout
if clockrove_install_apk 24 app "$test_root/app.apk"; then
  echo 'A non-timeout install failure should remain a failure.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 1 && "$(cat "$ADB_INSTALL_COUNT")" == 1 ]]
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/logcat.txt"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-24/install-diagnostics/third-party-packages.txt"
echo 'Verified non-timeout failures are not retried and diagnostics are retained.'

rm -f "$ADB_INSTALL_COUNT" "$ADB_CALL_LOG"
export ADB_INSTALL_MODE=timeout-always
if clockrove_install_apk 35 instrumentation "$test_root/instrumentation.apk"; then
  echo 'Two timed-out installs should fail.' >&2
  exit 1
else
  status=$?
fi
[[ "$status" == 124 && "$(cat "$ADB_INSTALL_COUNT")" == 2 ]]
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/instrumentation-attempt-1.log"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/instrumentation-attempt-2.log"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/logcat.txt"
test -s "$GITHUB_WORKSPACE/build/device-test-results/api-35/install-diagnostics/third-party-packages.txt"
echo 'Verified the retry limit is two total attempts and final diagnostics are retained.'
