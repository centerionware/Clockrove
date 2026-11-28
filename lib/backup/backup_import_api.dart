import 'package:clockrove/backup/backup_archive.dart';
import 'package:flutter/services.dart';

const int currentBackupImportSchemaVersion =
    BackupArchiveCodec.latestSchemaVersion;

const int maxBackupImportRequestBytes = 256 * 1024;
const int maxBackupImportPageRecords = 512;
const int maxBackupImportConflictPageBytes = 256 * 1024;
const int minBackupImportConflictPageBytes = 1024;
const int maxBackupImportBlobChunkBytes = 255 * 1024;
// The archive stores both counts as unsigned 64-bit values; no smaller app
// limit is imposed on records or media objects.
const int maxBackupImportRows = 0x7fffffffffffffff;
const int maxBackupImportMediaCount = 0x7fffffffffffffff;
// Matches the largest CLKROVE2 archive; staging is disk-backed and streamed.
const int maxBackupImportMediaBytes = 281474976645120;
const int maxBackupImportStagedBytes = 281474976645120;

/// Typed transport for the native, bounded backup staging and restore boundary.
/// It never interprets archive contents or writes a local file path.
final class BackupImportApi {
  BackupImportApi({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('clockrove/backup/v1');

  final MethodChannel _channel;

  Future<BackupImportStarted> begin({
    required int archiveFormatVersion,
    required int schemaVersion,
    required String archiveDigest,
    required int recordCount,
    required int mediaCount,
    required int mediaBytes,
  }) async {
    if (archiveFormatVersion < 1 ||
        archiveFormatVersion > BackupArchiveCodec.currentFormatVersion ||
        schemaVersion != currentBackupImportSchemaVersion ||
        !_validDigest(archiveDigest) ||
        recordCount < 0 ||
        recordCount > maxBackupImportRows ||
        mediaCount < 0 ||
        mediaCount > maxBackupImportMediaCount ||
        mediaBytes < 0 ||
        mediaBytes > maxBackupImportMediaBytes) {
      throw const FormatException('The backup import metadata is invalid.');
    }
    final result = await _invokeMap('beginBackupImport', {
      'archiveFormatVersion': archiveFormatVersion,
      'schemaVersion': schemaVersion,
      'archiveDigest': archiveDigest,
      'recordCount': recordCount,
      'mediaCount': mediaCount,
      'mediaBytes': mediaBytes,
    });
    final started = BackupImportStarted(
      importId: _requiredString(result, 'importId'),
      archiveFormatVersion: _integer(result, 'archiveFormatVersion'),
      schemaVersion: _integer(result, 'schemaVersion'),
      maxPageBytes: _integer(result, 'maxPageBytes'),
      maxBlobChunkBytes: _integer(result, 'maxBlobChunkBytes'),
      idleExpiryMillis: _integer(result, 'idleExpiryMillis'),
    );
    if (started.archiveFormatVersion != archiveFormatVersion ||
        started.schemaVersion != schemaVersion ||
        started.maxPageBytes < minBackupImportConflictPageBytes ||
        started.maxPageBytes > maxBackupImportRequestBytes ||
        started.maxBlobChunkBytes < 1 ||
        started.maxBlobChunkBytes > maxBackupImportBlobChunkBytes ||
        started.idleExpiryMillis < 1) {
      throw const FormatException('The backup import limits are invalid.');
    }
    return started;
  }

  Future<int> appendPage({
    required String importId,
    required int firstOrdinal,
    required List<Map<String, Object?>> records,
  }) async {
    _requireHandle(importId);
    if (firstOrdinal < 0 ||
        firstOrdinal > maxBackupImportRows ||
        records.isEmpty ||
        records.length > maxBackupImportPageRecords) {
      throw const FormatException('The backup import page is invalid.');
    }
    final args = <String, Object?>{
      'importId': importId,
      'firstOrdinal': firstOrdinal,
      'records': records,
    };
    _requireEncodedSize('appendBackupImportPage', args);
    final result = await _invokeMap('appendBackupImportPage', args);
    final nextOrdinal = _integer(result, 'nextOrdinal');
    if (nextOrdinal != firstOrdinal + records.length) {
      throw const FormatException('The backup import page did not advance.');
    }
    return nextOrdinal;
  }

  /// Returns the exact StandardMethodCodec size used by [appendPage].
  /// Callers use this to flush a bounded page before invoking the channel.
  int encodedAppendPageBytes({
    required String importId,
    required int firstOrdinal,
    required List<Map<String, Object?>> records,
  }) {
    _requireHandle(importId);
    final args = <String, Object?>{
      'importId': importId,
      'firstOrdinal': firstOrdinal,
      'records': records,
    };
    return const StandardMethodCodec()
        .encodeMethodCall(MethodCall('appendBackupImportPage', args))
        .lengthInBytes;
  }

  /// Encoded size of one row element for conservative page packing.
  int encodedAppendRecordBytes(Map<String, Object?> record) =>
      const StandardMessageCodec().encodeMessage(record)!.lengthInBytes;

  Future<int> writeBlob({
    required String importId,
    required String blobId,
    required int offset,
    required Uint8List bytes,
    int maxChunkBytes = maxBackupImportBlobChunkBytes,
  }) async {
    _requireHandle(importId);
    if (!RegExp(r'^[A-Za-z0-9_-]{43}$').hasMatch(blobId) ||
        offset < 0 ||
        bytes.isEmpty ||
        maxChunkBytes < 1 ||
        maxChunkBytes > maxBackupImportBlobChunkBytes ||
        bytes.length > maxChunkBytes ||
        offset > maxBackupImportMediaBytes - bytes.length) {
      throw const FormatException('The backup import media chunk is invalid.');
    }
    final args = <String, Object?>{
      'importId': importId,
      'blobId': blobId,
      'offset': offset,
      'bytes': bytes,
    };
    _requireEncodedSize('writeBackupImportBlob', args);
    final result = await _invokeMap('writeBackupImportBlob', args);
    final nextOffset = _integer(result, 'nextOffset');
    if (nextOffset != offset + bytes.length) {
      throw const FormatException('The backup media transfer did not advance.');
    }
    return nextOffset;
  }

  Future<BackupImportPreview> finish(String importId) async {
    _requireHandle(importId);
    final result = await _invokeMap('finishBackupImport', {
      'importId': importId,
    });
    final preview = BackupImportPreview.fromMap(result);
    if (preview.importId != importId) {
      throw const FormatException('The backup import preview handle changed.');
    }
    return preview;
  }

  Future<BackupImportConflictPage> readConflicts({
    required String importId,
    required String previewToken,
    String? cursor,
    int maxBytes = maxBackupImportConflictPageBytes,
  }) async {
    _requireHandle(importId);
    _requireToken(previewToken);
    if (cursor != null && (cursor.isEmpty || cursor.length > 128) ||
        maxBytes < minBackupImportConflictPageBytes ||
        maxBytes > maxBackupImportConflictPageBytes) {
      throw const FormatException(
        'The backup conflict page request is invalid.',
      );
    }
    final result = await _invokeMap('readBackupImportConflictPage', {
      'importId': importId,
      'previewToken': previewToken,
      'cursor': cursor,
      'maxBytes': maxBytes,
    });
    final page = BackupImportConflictPage.fromMap(result);
    if (page.importId != importId ||
        page.previewToken != previewToken ||
        (page.done && page.nextCursor != null) ||
        (!page.done && (page.nextCursor == null || page.nextCursor!.isEmpty))) {
      throw const FormatException(
        'The backup conflict page response is invalid.',
      );
    }
    return page;
  }

  Future<void> resolveConflict({
    required String importId,
    required String previewToken,
    required int expectedLocalStateVersion,
    required String conflictId,
    required String choice,
  }) async {
    _requireHandle(importId);
    _requireToken(previewToken);
    if (expectedLocalStateVersion < 0 ||
        expectedLocalStateVersion > 0x7fffffffffffffff ||
        conflictId.isEmpty ||
        conflictId.length > 128 ||
        choice.isEmpty ||
        choice.length > 64) {
      throw const FormatException('The backup conflict resolution is invalid.');
    }
    final result = await _invokeMap('resolveBackupImportConflict', {
      'importId': importId,
      'previewToken': previewToken,
      'expectedLocalStateVersion': expectedLocalStateVersion,
      'conflictId': conflictId,
      'choice': choice,
    });
    if (result['resolved'] != true) {
      throw const FormatException('The backup conflict was not resolved.');
    }
  }

  Future<BackupImportApplied> apply({
    required String importId,
    required String previewToken,
    required int expectedLocalStateVersion,
  }) async {
    _requireHandle(importId);
    _requireToken(previewToken);
    if (expectedLocalStateVersion < 0 ||
        expectedLocalStateVersion > 0x7fffffffffffffff) {
      throw const FormatException(
        'The expected backup state version is invalid.',
      );
    }
    final result = await _invokeMap('applyBackupImport', {
      'importId': importId,
      'previewToken': previewToken,
      'expectedLocalStateVersion': expectedLocalStateVersion,
    });
    final applied = BackupImportApplied.fromMap(result);
    if (applied.importId != importId ||
        (applied.stateVersion != expectedLocalStateVersion &&
            applied.stateVersion != expectedLocalStateVersion + 1) ||
        applied.insertedCount < 0 ||
        applied.skippedCount < 0) {
      throw const FormatException('The backup apply response is invalid.');
    }
    return applied;
  }

  Future<bool> close(String importId) async {
    _requireHandle(importId);
    final result = await _invokeMap('closeBackupImport', {
      'importId': importId,
    });
    return _boolean(result, 'closed');
  }

  Future<Map<String, Object?>> _invokeMap(
    String method,
    Map<String, Object?> arguments,
  ) async =>
      _stringMap(await _channel.invokeMethod<Object?>(method, arguments));

  static void _requireEncodedSize(String method, Map<String, Object?> args) {
    final size = const StandardMethodCodec()
        .encodeMethodCall(MethodCall(method, args))
        .lengthInBytes;
    if (size < 1 || size > maxBackupImportRequestBytes) {
      throw const FormatException(
        'The backup import request exceeds its limit.',
      );
    }
  }

  static void _requireHandle(String importId) {
    if (importId.isEmpty || importId.length > 128) {
      throw const FormatException('The backup import handle is invalid.');
    }
  }

  static void _requireToken(String token) {
    if (token.isEmpty || token.length > 128) {
      throw const FormatException('The backup preview token is invalid.');
    }
  }

  static bool _validDigest(String digest) =>
      RegExp(r'^(?:[0-9a-f]{64}|[A-Za-z0-9_-]{43})$').hasMatch(digest);
}

final class BackupImportStarted {
  const BackupImportStarted({
    required this.importId,
    required this.archiveFormatVersion,
    required this.schemaVersion,
    required this.maxPageBytes,
    required this.maxBlobChunkBytes,
    required this.idleExpiryMillis,
  });

  final String importId;
  final int archiveFormatVersion;
  final int schemaVersion;
  final int maxPageBytes;
  final int maxBlobChunkBytes;
  final int idleExpiryMillis;
}

final class BackupImportPreview {
  const BackupImportPreview({
    required this.importId,
    required this.previewToken,
    required this.schemaVersion,
    required this.localStateVersion,
    required this.recordCount,
    required this.mediaCount,
    required this.mediaBytes,
    required this.stagedRecordBytes,
    required this.addedCount,
    this.changedCount = 0,
    this.deletedCount = 0,
    required this.unchangedCount,
    this.unresolvedReferenceCount = 0,
    this.timeOverlapCount = 0,
    required this.conflictCount,
    required this.openSessionCount,
    required this.localOpenSessionCount,
    required this.applyReady,
  });

  final String importId;
  final String previewToken;
  final int schemaVersion;
  final int localStateVersion;
  final int recordCount;
  final int mediaCount;
  final int mediaBytes;
  final int stagedRecordBytes;

  /// Incoming rows absent locally; summary counts can overlap one another.
  final int addedCount;

  /// Incoming rows whose local counterpart differs or has an unresolved change.
  final int changedCount;

  /// Incoming tombstone/deletion marker rows, including historical revisions.
  final int deletedCount;

  /// Incoming rows whose current local record matches; may overlap deletedCount.
  final int unchangedCount;

  /// Initial dependent-reference conflicts, before the user resolves choices.
  final int unresolvedReferenceCount;

  /// Initial session-overlap conflicts, before the user resolves choices.
  final int timeOverlapCount;
  final int conflictCount;
  final int openSessionCount;
  final int localOpenSessionCount;
  final bool applyReady;

  static BackupImportPreview fromMap(Map<String, Object?> map) {
    final preview = BackupImportPreview(
      importId: _requiredString(map, 'importId'),
      previewToken: _requiredString(map, 'previewToken'),
      schemaVersion: _integer(map, 'schemaVersion'),
      localStateVersion: _integer(map, 'localStateVersion'),
      recordCount: _integer(map, 'recordCount'),
      mediaCount: _integer(map, 'mediaCount'),
      mediaBytes: _integer(map, 'mediaBytes'),
      stagedRecordBytes: _integer(map, 'stagedRecordBytes'),
      addedCount: _integer(map, 'addedCount'),
      changedCount: _integer(map, 'changedCount'),
      deletedCount: _integer(map, 'deletedCount'),
      unchangedCount: _integer(map, 'unchangedCount'),
      unresolvedReferenceCount: _integer(map, 'unresolvedReferenceCount'),
      timeOverlapCount: _integer(map, 'timeOverlapCount'),
      conflictCount: _integer(map, 'conflictCount'),
      openSessionCount: _integer(map, 'openSessionCount'),
      localOpenSessionCount: _integer(map, 'localOpenSessionCount'),
      applyReady: _boolean(map, 'applyReady'),
    );
    if (preview.previewToken.isEmpty ||
        preview.schemaVersion != currentBackupImportSchemaVersion ||
        preview.localStateVersion < 0 ||
        preview.recordCount < 0 ||
        preview.recordCount > maxBackupImportRows ||
        preview.mediaCount < 0 ||
        preview.mediaCount > maxBackupImportMediaCount ||
        preview.mediaBytes < 0 ||
        preview.mediaBytes > maxBackupImportMediaBytes ||
        preview.stagedRecordBytes < 0 ||
        preview.stagedRecordBytes > maxBackupImportStagedBytes ||
        preview.stagedRecordBytes >
            maxBackupImportStagedBytes - preview.mediaBytes ||
        preview.addedCount < 0 ||
        preview.addedCount > preview.recordCount ||
        preview.changedCount < 0 ||
        preview.changedCount > preview.recordCount ||
        preview.deletedCount < 0 ||
        preview.deletedCount > preview.recordCount ||
        preview.unchangedCount < 0 ||
        preview.unchangedCount > preview.recordCount ||
        preview.unresolvedReferenceCount < 0 ||
        preview.unresolvedReferenceCount > preview.recordCount ||
        preview.timeOverlapCount < 0 ||
        preview.timeOverlapCount > preview.recordCount ||
        preview.conflictCount < 0 ||
        preview.openSessionCount < 0 ||
        preview.localOpenSessionCount < 0) {
      throw const FormatException('The backup import preview is invalid.');
    }
    return preview;
  }
}

final class BackupImportConflict {
  const BackupImportConflict({
    required this.conflictId,
    required this.kind,
    required this.table,
    required this.primaryKey,
    required this.localIdentifier,
    required this.localSummary,
    required this.backupSummary,
    required this.allowedChoices,
    required this.defaultChoice,
    required this.resolved,
    required this.selectedChoice,
  });

  final String conflictId;
  final String kind;
  final String table;
  final List<Object?> primaryKey;
  final String? localIdentifier;
  final Map<String, Object?> localSummary;
  final Map<String, Object?> backupSummary;
  final List<String> allowedChoices;
  final String? defaultChoice;
  final bool resolved;
  final String? selectedChoice;

  static BackupImportConflict fromMap(Map<String, Object?> map) {
    final rawKey = map['primaryKey'];
    if (rawKey is! List || rawKey.isEmpty || rawKey.length > 4) {
      throw const FormatException('A backup conflict key is invalid.');
    }
    final key = rawKey
        .map<Object?>((value) {
          if (value != null &&
              value is! String &&
              value is! int &&
              value is! double) {
            throw const FormatException('A backup conflict key is invalid.');
          }
          if (value is double && !value.isFinite) {
            throw const FormatException('A backup conflict key is invalid.');
          }
          return value;
        })
        .toList(growable: false);
    final choices = _stringList(map, 'allowedChoices');
    if (choices.toSet().length != choices.length) {
      throw const FormatException('A backup conflict choice list is invalid.');
    }
    final defaultChoice = _nullableString(map, 'defaultChoice');
    final conflict = BackupImportConflict(
      conflictId: _requiredString(map, 'conflictId'),
      kind: _requiredString(map, 'kind'),
      table: _requiredString(map, 'table'),
      primaryKey: List.unmodifiable(key),
      localIdentifier: _nullableString(map, 'localIdentifier'),
      localSummary: _summaryMap(map, 'localSummary'),
      backupSummary: _summaryMap(map, 'backupSummary'),
      allowedChoices: choices,
      defaultChoice: defaultChoice,
      resolved: _boolean(map, 'resolved'),
      selectedChoice: _nullableString(map, 'selectedChoice'),
    );
    if (conflict.conflictId.isEmpty ||
        (choices.isEmpty &&
            (defaultChoice != null ||
                conflict.resolved ||
                conflict.selectedChoice != null)) ||
        (defaultChoice != null && !choices.contains(defaultChoice)) ||
        (conflict.resolved && !choices.contains(conflict.selectedChoice)) ||
        (!conflict.resolved && conflict.selectedChoice != null)) {
      throw const FormatException('A backup conflict response is invalid.');
    }
    return conflict;
  }
}

final class BackupImportConflictPage {
  const BackupImportConflictPage({
    required this.importId,
    required this.previewToken,
    required this.nextCursor,
    required this.done,
    required this.conflicts,
  });

  final String importId;
  final String previewToken;
  final String? nextCursor;
  final bool done;
  final List<BackupImportConflict> conflicts;

  static BackupImportConflictPage fromMap(Map<String, Object?> map) {
    final raw = map['conflicts'];
    if (raw is! List) {
      throw const FormatException('The backup conflict list is invalid.');
    }
    final conflicts = raw
        .map((item) => BackupImportConflict.fromMap(_stringMap(item)))
        .toList(growable: false);
    if (conflicts.length > 4096) {
      throw const FormatException(
        'The backup conflict list exceeds its limit.',
      );
    }
    return BackupImportConflictPage(
      importId: _requiredString(map, 'importId'),
      previewToken: _requiredString(map, 'previewToken'),
      nextCursor: _nullableString(map, 'nextCursor'),
      done: _boolean(map, 'done'),
      conflicts: List.unmodifiable(conflicts),
    );
  }
}

final class BackupImportApplied {
  const BackupImportApplied({
    required this.importId,
    required this.stateVersion,
    required this.insertedCount,
    required this.skippedCount,
  });

  final String importId;
  final int stateVersion;
  final int insertedCount;
  final int skippedCount;

  static BackupImportApplied fromMap(Map<String, Object?> map) =>
      BackupImportApplied(
        importId: _requiredString(map, 'importId'),
        stateVersion: _integer(map, 'stateVersion'),
        insertedCount: _integer(map, 'insertedCount'),
        skippedCount: _integer(map, 'skippedCount'),
      );
}

Map<String, Object?> _stringMap(Object? value) {
  if (value is! Map) {
    throw const FormatException('A backup response map is invalid.');
  }
  final result = <String, Object?>{};
  for (final entry in value.entries) {
    if (entry.key is! String || result.containsKey(entry.key)) {
      throw const FormatException('A backup response map has invalid fields.');
    }
    result[entry.key as String] = entry.value;
  }
  return Map.unmodifiable(result);
}

String _requiredString(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value is! String || value.isEmpty) {
    throw FormatException('The backup response field "$key" is invalid.');
  }
  return value;
}

String? _nullableString(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value != null && value is! String) {
    throw FormatException('The backup response field "$key" is invalid.');
  }
  return value as String?;
}

int _integer(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value is! int) {
    throw FormatException('The backup response field "$key" is invalid.');
  }
  return value;
}

bool _boolean(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value is! bool) {
    throw FormatException('The backup response field "$key" is invalid.');
  }
  return value;
}

List<String> _stringList(Map<String, Object?> map, String key) {
  final value = map[key];
  if (value is! List || value.any((item) => item is! String || item.isEmpty)) {
    throw FormatException('The backup response field "$key" is invalid.');
  }
  return List.unmodifiable(value.cast<String>());
}

Map<String, Object?> _summaryMap(Map<String, Object?> map, String key) {
  final summary = _stringMap(map[key]);
  for (final value in summary.values) {
    if (value != null &&
        value is! String &&
        value is! int &&
        value is! double &&
        value is! bool) {
      throw const FormatException('A backup conflict summary is invalid.');
    }
    if (value is double && !value.isFinite) {
      throw const FormatException('A backup conflict summary is invalid.');
    }
  }
  return summary;
}
