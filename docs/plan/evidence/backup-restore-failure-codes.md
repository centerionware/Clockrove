# Backup restore failure codes

## Reported behavior

The owner exported an encrypted backup, set a password, and immediately restored
that same file with the same password. After a couple of seconds the restore
screen showed only "The backup could not be processed. Check the selected file
and try again." Nothing was clocked in, and no data had changed between the
export and the restore attempt. The report reached the maintainers with no
information about which step failed.

## Change

Every restore rejection now carries a short, stable reason code, and the restore
screen shows it under the generic sentence as selectable text so it can be
copied into a bug report. The code never contains file names, paths, contents or
personal data.

- `BackupRestoreReason` marks the gates the importer controls: `file-size`,
  `schema-<archive>-of-<supported>`, `row-limit`, `media-count-limit`,
  `media-bytes-limit`, `archive-changed` and `stage-mismatch`.
- `backupRestoreFailureCode` maps every error the screen can receive to one
  token: `unlock`, `cancelled`, `cleanup`, `format`, `state`, `native-<code>`
  for platform failures, the importer code, or `unknown`. Unlock failures are a
  `FormatException` subclass, so that case is matched first.
- The new copy lives in all 17 Flutter catalogs as `restoreErrorCode`
  ("Error code: {code}"), and the generated message source was regenerated with
  `python3 tool/flutter_catalog.py`.

## Tests

- `test/backup_restore_diagnostics_test.dart` asserts the code mapping,
  including the unlock-before-format ordering and a platform code pass-through,
  and that an unsupported envelope size is reported as `file-size` before any
  cryptography runs.
- `test/backup_restore_screen_test.dart` asserts a failed preview renders the
  generic sentence together with `Error code: format`.

## Open

The reported failure itself is not yet reproduced. The next attempt on the
device will show which gate stopped it, and that code names the fix: a
`schema-<n>-of-17` result means the restore path rejects backups written by a
different app schema, `media-*-limit` means the archive exceeded the staging
limits, `native-<code>` names the Android staging gate, and `format` means the
authenticated stream failed structural validation. No local build or test ran;
CI covers analysis, Dart tests and the native suite.
