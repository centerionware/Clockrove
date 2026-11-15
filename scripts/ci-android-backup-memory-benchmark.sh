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

clockrove_clear_backup_benchmark_app_data() {
  local output
  if ! output="$(timeout --kill-after=5s 20s adb -s emulator-5554 shell pm clear "$package" 2>&1)"; then
    echo "Could not clear app data before seeding the benchmark fixture: $output" >&2
    return 2
  fi
  if [[ "$output" != *Success* ]]; then
    echo "App data clear did not succeed before benchmark seeding: $output" >&2
    return 2
  fi
}

clockrove_force_stop_backup_benchmark_app() {
  local process_status clockrove_process_deadline clockrove_live_pids
  timeout --kill-after=5s 20s adb -s emulator-5554 shell am force-stop "$package" || return 2
  clockrove_process_deadline=$((SECONDS + 30))
  clockrove_live_pids='pending'
  while (( SECONDS < clockrove_process_deadline )); do
    [[ "$(timeout --kill-after=3s 10s adb -s emulator-5554 get-state)" == device ]] || return 2
    if clockrove_live_pids="$(clockrove_app_process_pids "$package")"; then
      :
    else
      process_status=$?
      [[ "$process_status" == 1 ]] || return 2
      clockrove_live_pids=''
    fi
    [[ -n "$clockrove_live_pids" ]] || break
    sleep 1
  done
  if [[ -n "$clockrove_live_pids" ]]; then
    echo "The app process remained after force-stop: $clockrove_live_pids" >&2
    return 2
  fi
  return 0
}

clockrove_clear_backup_benchmark_app_data
clockrove_force_stop_backup_benchmark_app

clockrove_reseed_backup_memory_fixture() {
  local retry_api="$1"
  local reseed_log="$result_dir/backup-snapshot-memory-reseed-after-install-timeout.txt"
  if ! clockrove_run_flutter_integration_test "$retry_api" backup-snapshot-memory-reseed \
    'BACKUP_SNAPSHOT_MEMORY_SEED pid=[0-9]+ process_start_ticks=[0-9]+ attachment_count=[0-9]+ media_bytes=[0-9]+' \
    -- flutter test --no-uninstall --no-pub integration_test/backup_snapshot_memory_benchmark_test.dart \
    -d emulator-5554 --reporter expanded \
    --plain-name 'seed synthetic task media for full-path memory benchmark' \
    > "$reseed_log" 2>&1; then
    cat "$reseed_log"
    echo 'Could not restore synthetic media after the confirmed APK-install timeout.' >&2
    return 2
  fi
  cat "$reseed_log"
  local reseed_record
  reseed_record="$(grep -Eo 'BACKUP_SNAPSHOT_MEMORY_SEED pid=[0-9]+ process_start_ticks=[0-9]+ attachment_count=[0-9]+ media_bytes=[0-9]+' "$reseed_log" | tail -n 1)"
  if [[ "$reseed_record" =~ ^BACKUP_SNAPSHOT_MEMORY_SEED[[:space:]]pid=([0-9]+)[[:space:]]process_start_ticks=([0-9]+)[[:space:]]attachment_count=([0-9]+)[[:space:]]media_bytes=([0-9]+)$ ]]; then
    seed_pid="${BASH_REMATCH[1]}"
    seed_start_ticks="${BASH_REMATCH[2]}"
    seed_attachment_count="${BASH_REMATCH[3]}"
    seed_media_bytes="${BASH_REMATCH[4]}"
  else
    echo 'Could not capture the synthetic-media reseed details.' >&2
    return 2
  fi
  seed_recreated_after_install_timeout=true
  clockrove_force_stop_backup_benchmark_app
}

clockrove_run_flutter_integration_test "$api" backup-snapshot-memory-seed \
  'BACKUP_SNAPSHOT_MEMORY_SEED pid=[0-9]+ process_start_ticks=[0-9]+ attachment_count=[0-9]+ media_bytes=[0-9]+' \
  -- flutter test --no-uninstall --no-pub integration_test/backup_snapshot_memory_benchmark_test.dart \
  -d emulator-5554 --reporter expanded \
  --plain-name 'seed synthetic task media for full-path memory benchmark' \
  > "$seed_log" 2>&1
cat "$seed_log"

seed_record="$(grep -Eo 'BACKUP_SNAPSHOT_MEMORY_SEED pid=[0-9]+ process_start_ticks=[0-9]+ attachment_count=[0-9]+ media_bytes=[0-9]+' "$seed_log" | tail -n 1)"
[[ -n "$seed_record" ]]
if [[ "$seed_record" =~ ^BACKUP_SNAPSHOT_MEMORY_SEED[[:space:]]pid=([0-9]+)[[:space:]]process_start_ticks=([0-9]+)[[:space:]]attachment_count=([0-9]+)[[:space:]]media_bytes=([0-9]+)$ ]]; then
  seed_pid="${BASH_REMATCH[1]}"
  seed_start_ticks="${BASH_REMATCH[2]}"
  seed_attachment_count="${BASH_REMATCH[3]}"
  seed_media_bytes="${BASH_REMATCH[4]}"
else
  echo 'Could not capture the synthetic-media seed details.' >&2
  exit 2
fi
initial_seed_pid="$seed_pid"
initial_seed_start_ticks="$seed_start_ticks"
seed_recreated_after_install_timeout=false

clockrove_force_stop_backup_benchmark_app

clockrove_run_flutter_integration_test "$api" backup-snapshot-memory-benchmark \
  'BACKUP_SNAPSHOT_MEMORY_BENCHMARK pid=[0-9]+ process_start_ticks=[0-9]+ seed_attachment_count=[0-9]+ seed_media_bytes=[0-9]+ snapshot_media_count=[0-9]+ snapshot_media_bytes=[0-9]+ archive_bytes=[0-9]+ envelope_bytes=[0-9]+ baseline_rss_kib=[0-9]+ after_snapshot_setup_rss_kib=[0-9]+ after_encrypt_rss_kib=[0-9]+ full_path_peak_rss_kib=[0-9]+ full_path_peak_delta_rss_kib=[0-9]+ snapshot_setup_ms=[0-9]+ stream_export_encrypt_ms=[0-9]+ integrity_reread_peak_rss_kib=[0-9]+ integrity_reread_peak_delta_rss_kib=[0-9]+ integrity_reread_ms=[0-9]+' \
  --before-retry-hook clockrove_reseed_backup_memory_fixture \
  -- flutter test --no-uninstall --no-pub integration_test/backup_snapshot_memory_benchmark_test.dart \
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

benchmark_fixture="$(grep -Eo 'BACKUP_SNAPSHOT_MEMORY_BENCHMARK .*seed_attachment_count=[0-9]+ seed_media_bytes=[0-9]+' "$benchmark_log" | tail -n 1)"
if [[ "$benchmark_fixture" =~ seed_attachment_count=([0-9]+)[[:space:]]seed_media_bytes=([0-9]+)$ ]]; then
  benchmark_attachment_count="${BASH_REMATCH[1]}"
  benchmark_media_bytes="${BASH_REMATCH[2]}"
else
  echo 'Could not capture fixture metadata from the fresh-process benchmark.' >&2
  exit 2
fi
[[ "$seed_attachment_count:$seed_media_bytes" == "$benchmark_attachment_count:$benchmark_media_bytes" ]] || {
  echo 'Fresh-process fixture metadata does not match the seeded attachment count and bytes.' >&2
  exit 2
}

grep -Eq 'BACKUP_SNAPSHOT_MEMORY_SEED .*media_bytes=([0-9]+)' "$seed_log"
grep -Eq 'BACKUP_SNAPSHOT_MEMORY_BENCHMARK .*archive_bytes=[0-9]+ envelope_bytes=[0-9]+' "$benchmark_log"
cat > "$process_log" <<EOF
api=$api
app_apk_identity=$app_identity
instrumentation_apk_identity=$instrumentation_identity
initial_seed_pid=$initial_seed_pid
initial_seed_process_start_ticks=$initial_seed_start_ticks
seed_pid=$seed_pid
seed_process_start_ticks=$seed_start_ticks
seed_attachment_count=$seed_attachment_count
seed_media_bytes=$seed_media_bytes
seed_recreated_after_confirmed_install_timeout=$seed_recreated_after_install_timeout
app_process_absent_after_force_stop=true
app_data_cleared_before_seed=true
benchmark_pid=$benchmark_pid
benchmark_process_start_ticks=$benchmark_start_ticks
benchmark_attachment_count=$benchmark_attachment_count
benchmark_media_bytes=$benchmark_media_bytes
fresh_process_confirmed=true
EOF
cat "$process_log"
