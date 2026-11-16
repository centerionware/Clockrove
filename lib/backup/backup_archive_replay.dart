part of 'backup_archive.dart';

/// Preflights rows from a validated native snapshot without retaining maps.
/// The source is read once during preflight and twice while encoding: once for
/// row records and once for media descriptors. Native snapshot creation checks
/// revision ownership and references; native revision writers preserve DAG
/// order inductively. Callers with arbitrary rows must use
/// [BackupArchiveCodec.prepareStream] or [BackupArchiveCodec.decode], which
/// validate the complete revision graph.
Future<BackupTrustedNativeArchiveReplayPlan>
prepareTrustedNativeBackupArchiveReplayStream({
  required int schemaVersion,
  required int stateVersion,
  required int capturedAtUtcMillis,
  required int expectedRecordCount,
  required int expectedBlobCount,
  required int expectedMediaBytes,
  required BackupArchiveStreamRows rows,
  void Function(int recordCount)? onRecord,
  int maxBytes = BackupArchiveCodec.maxArchiveBytes,
  int maxRows = BackupArchiveCodec.maxRecords,
  int maxBlobs = BackupArchiveCodec.maxMediaCount,
}) async {
  BackupArchiveCodec._validateMetadata(
    schemaVersion: schemaVersion,
    stateVersion: stateVersion,
    capturedAtUtcMillis: capturedAtUtcMillis,
    maxBytes: maxBytes,
    maxRows: maxRows,
    maxBlobs: maxBlobs,
  );
  if (expectedRecordCount < 0 ||
      expectedRecordCount > maxRows ||
      expectedBlobCount < 0 ||
      expectedBlobCount > maxBlobs ||
      expectedMediaBytes < 0 ||
      expectedMediaBytes > maxBytes) {
    throw const FormatException('The backup snapshot metadata is invalid.');
  }

  final preflight = _ReplayStreamPreflight(
    schemaVersion: schemaVersion,
    maxBytes: maxBytes,
    maxRows: maxRows,
    maxBlobs: maxBlobs,
  );
  await for (final row in rows()) {
    preflight.add(row);
    onRecord?.call(preflight.recordCount);
  }
  return preflight.finish(
    expectedRecordCount: expectedRecordCount,
    expectedBlobCount: expectedBlobCount,
    expectedMediaBytes: expectedMediaBytes,
    rows: rows,
    stateVersion: stateVersion,
    capturedAtUtcMillis: capturedAtUtcMillis,
  );
}

int _compareReplayPrimaryKeyValues(
  String table,
  Map<String, Object?> left,
  Map<String, Object?> right,
) {
  final schema = BackupArchiveCodec._tables[table];
  if (schema == null) {
    throw FormatException('The backup contains unsupported table "$table".');
  }
  for (final column in schema.primaryKey) {
    final comparison = _compareSqliteScalars(left[column], right[column]);
    if (comparison != 0) return comparison;
  }
  return 0;
}

int _compareSqliteScalars(Object? left, Object? right) {
  if (left is num && right is num) return left.compareTo(right);
  if (left is num) return -1;
  if (right is num) return 1;
  if (left is String && right is String) {
    final leftBytes = utf8.encode(left);
    final rightBytes = utf8.encode(right);
    final commonLength = leftBytes.length < rightBytes.length
        ? leftBytes.length
        : rightBytes.length;
    for (var index = 0; index < commonLength; index++) {
      final comparison = leftBytes[index].compareTo(rightBytes[index]);
      if (comparison != 0) return comparison;
    }
    return leftBytes.length.compareTo(rightBytes.length);
  }
  if (left is String) return 1;
  if (right is String) return -1;
  return 0;
}
