#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This check runs only in GitHub Actions.' >&2; exit 2; }
: "${GITHUB_WORKSPACE:?}"
source "$GITHUB_WORKSPACE/scripts/ci-adb-install.sh"

api="${1:-}"
[[ "$api" == 24 || "$api" == 35 ]] || { echo 'Expected Android API 24 or 35.' >&2; exit 2; }
app_apk="${2:-}"
instrumentation_apk="${3:-}"
[[ -s "$app_apk" && -s "$instrumentation_apk" ]] || {
  echo 'Expected preserved real app and instrumentation APKs.' >&2
  exit 2
}
package='io.github.centerionware.clockrove'
result_dir="$GITHUB_WORKSPACE/build/device-test-results/api-$api"
mkdir -p "$result_dir"
seed_log="$result_dir/backup-snapshot-memory-seed.txt"
benchmark_log="$result_dir/backup-snapshot-memory-benchmark.txt"
process_log="$result_dir/backup-snapshot-memory-process-lifecycle.txt"

app_identity="$(clockrove_apk_identity "$app_apk" app)"
instrumentation_identity="$(clockrove_apk_identity "$instrumentation_apk" instrumentation)"
clockrove_install_apk "$api" app "$app_apk"
clockrove_install_apk "$api" instrumentation "$instrumentation_apk"

clockrove_run_flutter_integration_test "$api" backup-snapshot-memory-seed \
  'BACKUP_SNAPSHOT_MEMORY_SEED pid=[0-9]+ process_start_ticks=[0-9]+ attachment_count=[0-9]+ media_bytes=[0-9]+' \
  -- flutter test --no-pub integration_test/backup_snapshot_memory_benchmark_test.dart \
  -d emulator-5554 --reporter expanded \
  --plain-name 'seed synthetic task media for full-path memory benchmark' \
  > "$seed_log" 2>&1
cat "$seed_log"

seed_identity="$(grep -Eo 'BACKUP_SNAPSHOT_MEMORY_SEED pid=[0-9]+ process_start_ticks=[0-9]+' "$seed_log" | tail -n 1)"
[[ -n "$seed_identity" ]]
if [[ "$seed_identity" =~ ^BACKUP_SNAPSHOT_MEMORY_SEED[[:space:]]pid=([0-9]+)[[:space:]]process_start_ticks=([0-9]+)$ ]]; then
  seed_pid="${BASH_REMATCH[1]}"
  seed_start_ticks="${BASH_REMATCH[2]}"
else
  echo 'Could not capture the synthetic-media process identity.' >&2
  exit 2
fi

timeout --kill-after=5s 20s adb -s emulator-5554 shell am force-stop "$package"
clockrove_process_deadline=$((SECONDS + 30))
clockrove_live_pids='pending'
while (( SECONDS < clockrove_process_deadline )); do
  [[ "$(timeout --kill-after=3s 10s adb -s emulator-5554 get-state)" == device ]]
  clockrove_live_pids="$(timeout --kill-after=3s 10s adb -s emulator-5554 shell pidof "$package" 2>/dev/null || true)"
  clockrove_live_pids="${clockrove_live_pids//$'\r'/}"
  [[ -n "$clockrove_live_pids" ]] || break
  sleep 1
done
if [[ -n "$clockrove_live_pids" ]]; then
  echo "The app process remained after force-stop: $clockrove_live_pids" >&2
  exit 2
fi

clockrove_run_flutter_integration_test "$api" backup-snapshot-memory-benchmark \
  'BACKUP_SNAPSHOT_MEMORY_BENCHMARK pid=[0-9]+ process_start_ticks=[0-9]+ seed_attachment_count=[0-9]+ seed_media_bytes=[0-9]+ snapshot_media_count=[0-9]+ snapshot_media_bytes=[0-9]+ archive_bytes=[0-9]+ envelope_bytes=[0-9]+ baseline_rss_kib=[0-9]+ after_export_rss_kib=[0-9]+ after_encrypt_rss_kib=[0-9]+ full_path_peak_rss_kib=[0-9]+ full_path_peak_delta_rss_kib=[0-9]+' \
  -- flutter test --no-pub integration_test/backup_snapshot_memory_benchmark_test.dart \
  -d emulator-5554 --reporter expanded \
  --plain-name 'measure full-path backup exporter and encryption memory' \
  > "$benchmark_log" 2>&1
cat "$benchmark_log"

benchmark_identity="$(grep -Eo 'BACKUP_SNAPSHOT_MEMORY_BENCHMARK pid=[0-9]+ process_start_ticks=[0-9]+' "$benchmark_log" | tail -n 1)"
[[ -n "$benchmark_identity" ]]
if [[ "$benchmark_identity" =~ ^BACKUP_SNAPSHOT_MEMORY_BENCHMARK[[:space:]]pid=([0-9]+)[[:space:]]process_start_ticks=([0-9]+)$ ]]; then
  benchmark_pid="${BASH_REMATCH[1]}"
  benchmark_start_ticks="${BASH_REMATCH[2]}"
else
  echo 'Could not capture the full-path measurement process identity.' >&2
  exit 2
fi
[[ "$seed_pid:$seed_start_ticks" != "$benchmark_pid:$benchmark_start_ticks" ]] || {
  echo 'Seed and measurement used the same process identity.' >&2
  exit 2
}

grep -Eq 'BACKUP_SNAPSHOT_MEMORY_SEED .*media_bytes=([0-9]+)' "$seed_log"
grep -Eq 'BACKUP_SNAPSHOT_MEMORY_BENCHMARK .*archive_bytes=[0-9]+ envelope_bytes=[0-9]+' "$benchmark_log"
cat > "$process_log" <<EOF
api=$api
app_apk_identity=$app_identity
instrumentation_apk_identity=$instrumentation_identity
seed_pid=$seed_pid
seed_process_start_ticks=$seed_start_ticks
app_process_absent_after_force_stop=true
benchmark_pid=$benchmark_pid
benchmark_process_start_ticks=$benchmark_start_ticks
fresh_process_confirmed=true
EOF
cat "$process_log"
