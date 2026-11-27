# Automatic backups and large archives — November 27

## Implemented

- Settings configures a SAF folder, daily enable/disable, optional encryption, a
  saved password, manual “Back up now”, and last result.
- Android WorkManager schedules a unique 24-hour periodic worker. The worker
  starts a short-lived headless Flutter engine, reuses the existing immutable
  native snapshot and Dart archive/encryption pipeline, and writes through the
  persisted SAF tree permission. No Activity is required.
- Automatic files use `Clockrove-auto-<UTC timestamp>.clockrove`; a `.partial`
  suffix is used until the write and size check finish, then the document is
  renamed. Partial files are pruned on the next run.
- Retention keeps the newest backup for each of the last 7 days, each of the
  last 4 weeks, each of the last 12 months, and every represented year. Only
  managed automatic files are deleted; manual exports and unrelated files are
  untouched.
- Encryption remains optional. The automatic password is stored encrypted with
  an Android Keystore AES-GCM key so unattended runs can decrypt it. Plaintext
  automatic files are detected during restore and previewed without a password.
- CLKROVE2 v3 uses 64-bit archive lengths and a 64-bit archive bound. The old
  64 MiB/4 GiB caps are gone; the new portable ceiling is 256 TiB of 64 KiB
  frames, limited only by the storage provider and free space. v2 envelopes
  and v1 archives remain readable.

## Validation status

- Local: `dart format` parsed all changed Dart files; catalog generation and
  catalog parity passed for all 17 locales. No local build or test ran.
- CI/device: pending this commit. WorkManager/headless-engine execution,
  Google Drive SAF behavior, Keystore access after reboot, and retention against
  a real provider are not yet evidenced.
- Known limits: Android may delay periodic work under Doze/battery restrictions;
  a force-stop prevents unattended runs until the app is opened; Google Drive
  and the selected filesystem impose their own per-file and quota limits.

## Open acceptance

- Run the Android instrumentation/device journey on API 24 and 35: choose a
  folder, enable plaintext and encrypted plans, run now, force-close, verify a
  scheduled/worker run, and restore both forms.
- Verify a backup larger than 4 GiB on Drive or local storage if the test device
  has enough space; do not claim it from a compile-only check.
- Confirm retention deletes only automatic sets and that an interrupted run
  leaves no restore-visible partial.
