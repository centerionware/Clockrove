import 'package:flutter/services.dart';

import 'backup_archive.dart';

typedef BackupCancellationCheck = bool Function();
typedef BackupExportProgressCallback = void Function(
  BackupSnapshotProgress progress,
);

/// Reads a stable native snapshot and serializes it before its handle expires.
/// Native SQLite remains the sole source of persisted values and BLOB bytes.
final class BackupSnapshotExporter {
  BackupSnapshotExporter({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('clockrove/backup/v1');

  static const int _maxNativeTransferBytes = 256 * 1024;
  static const int _minNativePageBytes = 1024;

  final MethodChannel _channel;

  Future<BackupSnapshotExport> export({
    BackupCancellationCheck? isCancelled,
    BackupExportProgressCallback? onProgress,
  }) async {
    _checkCancelled(isCancelled);
    final info = await _readInfo();
    _validateInfo(info);

    String? snapshotId;
    Object? operationFailure;
    StackTrace? operationStack;
    Object? cleanupFailure;
    StackTrace? cleanupStack;
    BackupSnapshotExport? exported;

    try {
      final started = await _startSnapshot();
      snapshotId = started.snapshotId;
      _validateSnapshotStart(info, started);

      final rows = <BackupArchiveRow>[];
      var mediaCount = 0;
      var mediaBytes = 0;
      String? cursor;
      var done = false;
      var pageCount = 0;
      void reportProgress({int? mediaBytesRead}) {
        onProgress?.call(
          BackupSnapshotProgress(
            recordsRead: rows.length,
            totalRecords: started.recordCount,
            mediaBytesRead: mediaBytesRead ?? mediaBytes,
            totalMediaBytes: started.mediaBytes,
          ),
        );
      }

      reportProgress();
      while (!done) {
        _checkCancelled(isCancelled);
        pageCount++;
        if (pageCount > info.maxRecords + 1) {
          throw const FormatException(
            'The backup snapshot page sequence is invalid.',
          );
        }
        final page = await _readPage(
          snapshotId: snapshotId,
          cursor: cursor,
          maxBytes: info.maxPageBytes,
        );
        if (page.snapshotId != snapshotId) {
          throw const FormatException(
            'The backup snapshot changed unexpectedly.',
          );
        }
        if (page.records.isEmpty && !page.done) {
          throw const FormatException(
            'The backup snapshot stopped making progress.',
          );
        }

        for (final record in page.records) {
          _checkCancelled(isCancelled);
          if (rows.length >= started.recordCount ||
              rows.length >= info.maxRecords) {
            throw const FormatException(
              'The backup snapshot contains too many records.',
            );
          }
          if (!info.portableTables.contains(record.table)) {
            throw FormatException(
              'The backup snapshot contains unsupported table "${record.table}".',
            );
          }
          final blobs = <BackupArchiveBlob>[];
          for (final descriptor in record.blobs) {
            mediaCount++;
            if (mediaCount > started.mediaCount ||
                mediaCount > info.maxMediaCount) {
              throw const FormatException(
                'The backup snapshot contains too many media files.',
              );
            }
            if (descriptor.sizeBytes < 0 ||
                descriptor.sizeBytes > started.mediaBytes - mediaBytes ||
                descriptor.sizeBytes > info.maxSnapshotBytes - mediaBytes) {
              throw const FormatException(
                'The backup snapshot media size is invalid or too large.',
              );
            }
            final bytes = descriptor.sizeBytes == 0
                ? Uint8List(0)
                : await _readBlob(
                    snapshotId: snapshotId,
                    descriptor: descriptor,
                    chunkBytes: info.maxBlobChunkBytes,
                    isCancelled: isCancelled,
                    onBytesRead: (bytesRead) =>
                        reportProgress(mediaBytesRead: mediaBytes + bytesRead),
                  );
            mediaBytes += descriptor.sizeBytes;
            blobs.add(
              BackupArchiveBlob(
                id: descriptor.id,
                column: descriptor.column,
                bytes: bytes,
              ),
            );
          }
          rows.add(
            BackupArchiveRow(
              table: record.table,
              values: record.values,
              blobs: blobs,
            ),
          );
          reportProgress();
        }

        if (page.done) {
          if (page.nextCursor != null) {
            throw const FormatException(
              'The final backup page has an invalid cursor.',
            );
          }
          done = true;
        } else {
          final next = page.nextCursor;
          if (next == null || next.isEmpty || next == cursor) {
            throw const FormatException(
              'The backup snapshot cursor is invalid.',
            );
          }
          cursor = next;
        }
      }

      if (rows.length != started.recordCount ||
          mediaCount != started.mediaCount ||
          mediaBytes != started.mediaBytes) {
        throw const FormatException('The backup snapshot counts do not match.');
      }

      // The snapshot is no longer needed after every row and media byte has
      // been received. Close native staging before hashing/serialization.
      await _closeSnapshot(snapshotId);
      snapshotId = null;
      _checkCancelled(isCancelled);

      final archiveBytes = await BackupArchiveCodec.encode(
        schemaVersion: started.schemaVersion,
        stateVersion: started.stateVersion,
        capturedAtUtcMillis: started.capturedAtUtcMillis,
        rows: rows,
        maxBytes: BackupArchiveCodec.maxArchiveBytes,
        maxRows: info.maxRecords,
        maxBlobs: info.maxMediaCount,
      );
      exported = BackupSnapshotExport(
        archiveBytes: archiveBytes,
        schemaVersion: started.schemaVersion,
        stateVersion: started.stateVersion,
        capturedAtUtcMillis: started.capturedAtUtcMillis,
        openSessionCount: started.openSessionCount,
        recordCount: rows.length,
        mediaCount: mediaCount,
        mediaBytes: mediaBytes,
      );
    } on Object catch (error, stackTrace) {
      operationFailure = error;
      operationStack = stackTrace;
    }

    if (snapshotId != null) {
      try {
        await _closeSnapshot(snapshotId);
      } on Object catch (error, stackTrace) {
        cleanupFailure = error;
        cleanupStack = stackTrace;
      }
    }

    if (operationFailure != null) {
      if (cleanupFailure != null) {
        throw BackupSnapshotCleanupException(
          operationFailure: operationFailure,
          cleanupFailure: cleanupFailure,
        );
      }
      Error.throwWithStackTrace(operationFailure, operationStack!);
    }
    if (cleanupFailure != null) {
      Error.throwWithStackTrace(cleanupFailure, cleanupStack!);
    }
    return exported!;
  }

  Future<_SnapshotInfo> _readInfo() async {
    final result = await _channel.invokeMethod<Object?>(
      'getBackupSnapshotInfo',
    );
    final map = _stringMap(result, 'snapshot info');
    return _SnapshotInfo(
      archiveFormatVersion: _integer(map, 'archiveFormatVersion'),
      schemaVersion: _integer(map, 'schemaVersion'),
      stateVersion: _integer(map, 'stateVersion'),
      maxSnapshotBytes: _integer(map, 'maxSnapshotBytes'),
      maxPageBytes: _integer(map, 'maxPageBytes'),
      maxBlobChunkBytes: _integer(map, 'maxBlobChunkBytes'),
      maxRecords: _integer(map, 'maxRecords'),
      maxMediaCount: _integer(map, 'maxMediaCount'),
      idleExpiryMillis: _integer(map, 'idleExpiryMillis'),
      portableTables: _stringSet(map, 'portableTables'),
    );
  }

  Future<_SnapshotStart> _startSnapshot() async {
    final result = await _channel.invokeMethod<Object?>('startBackupSnapshot');
    final map = _stringMap(result, 'snapshot start');
    return _SnapshotStart(
      snapshotId: _requiredString(map, 'snapshotId'),
      archiveFormatVersion: _integer(map, 'archiveFormatVersion'),
      schemaVersion: _integer(map, 'schemaVersion'),
      stateVersion: _integer(map, 'stateVersion'),
      capturedAtUtcMillis: _integer(map, 'capturedAtUtcMillis'),
      recordCount: _integer(map, 'recordCount'),
      mediaCount: _integer(map, 'mediaCount'),
      mediaBytes: _integer(map, 'mediaBytes'),
      openSessionCount: _integer(map, 'openSessionCount'),
    );
  }

  Future<_SnapshotPage> _readPage({
    required String snapshotId,
    required String? cursor,
    required int maxBytes,
  }) async {
    final result = await _channel.invokeMethod<Object?>(
      'readBackupSnapshotPage',
      {'snapshotId': snapshotId, 'cursor': cursor, 'maxBytes': maxBytes},
    );
    final map = _stringMap(result, 'snapshot page');
    final rawRecords = map['records'];
    if (rawRecords is! List) {
      throw const FormatException('The backup snapshot page is invalid.');
    }
    final records = <_SnapshotRow>[];
    for (final raw in rawRecords) {
      final row = _stringMap(raw, 'snapshot row');
      if (_requiredString(row, 'type') != 'row') {
        throw const FormatException(
          'The backup snapshot has an unknown record type.',
        );
      }
      final table = _requiredString(row, 'table');
      final values = _scalarMap(row['values']);
      final rawBlobs = row['blobs'];
      if (rawBlobs is! List) {
        throw const FormatException(
          'The backup snapshot media list is invalid.',
        );
      }
      final blobs = <_SnapshotBlob>[];
      for (final rawBlob in rawBlobs) {
        final blob = _stringMap(rawBlob, 'snapshot media descriptor');
        final id = _requiredString(blob, 'id');
        final column = _requiredString(blob, 'column');
        final sizeBytes = _integer(blob, 'sizeBytes');
        if (id.isEmpty || column.isEmpty || sizeBytes < 0) {
          throw const FormatException(
            'The backup snapshot media descriptor is invalid.',
          );
        }
        blobs.add(_SnapshotBlob(id, column, sizeBytes));
      }
      records.add(_SnapshotRow(table, values, blobs));
    }
    final nextCursor = _optionalString(map, 'nextCursor');
    final done = map['done'];
    if (done is! bool) {
      throw const FormatException('The backup snapshot page state is invalid.');
    }
    return _SnapshotPage(
      snapshotId: _requiredString(map, 'snapshotId'),
      nextCursor: nextCursor,
      done: done,
      records: records,
    );
  }

  Future<Uint8List> _readBlob({
    required String snapshotId,
    required _SnapshotBlob descriptor,
    required int chunkBytes,
    required BackupCancellationCheck? isCancelled,
    required void Function(int bytesRead) onBytesRead,
  }) async {
    final output = Uint8List(descriptor.sizeBytes);
    var offset = 0;
    while (offset < descriptor.sizeBytes) {
      _checkCancelled(isCancelled);
      final requested = (descriptor.sizeBytes - offset) < chunkBytes
          ? descriptor.sizeBytes - offset
          : chunkBytes;
      final result = await _channel.invokeMethod<Object?>('readBackupBlob', {
        'snapshotId': snapshotId,
        'blobId': descriptor.id,
        'offset': offset,
        'maxBytes': requested,
      });
      final map = _stringMap(result, 'snapshot media chunk');
      final returnedOffset = _integer(map, 'offset');
      final nextOffset = _integer(map, 'nextOffset');
      final done = map['done'];
      final rawBytes = map['bytes'];
      if (rawBytes is! Uint8List ||
          done is! bool ||
          returnedOffset != offset ||
          rawBytes.isEmpty ||
          rawBytes.length > requested ||
          nextOffset != offset + rawBytes.length ||
          nextOffset > descriptor.sizeBytes ||
          done != (nextOffset == descriptor.sizeBytes)) {
        throw const FormatException(
          'The backup snapshot media chunk is invalid.',
        );
      }
      output.setRange(offset, nextOffset, rawBytes);
      offset = nextOffset;
      onBytesRead(offset);
    }
    return output;
  }

  Future<void> _closeSnapshot(String snapshotId) async {
    final result = await _channel.invokeMethod<Object?>('closeBackupSnapshot', {
      'snapshotId': snapshotId,
    });
    final map = _stringMap(result, 'snapshot close');
    if (map['closed'] != true) {
      throw const FormatException('The backup snapshot could not be closed.');
    }
  }

  static void _validateInfo(_SnapshotInfo info) {
    if (info.archiveFormatVersion != BackupArchiveCodec.currentFormatVersion ||
        info.schemaVersion < 1 ||
        info.schemaVersion > BackupArchiveCodec.latestSchemaVersion ||
        info.stateVersion < 0 ||
        info.maxSnapshotBytes < 1 ||
        info.maxSnapshotBytes > BackupArchiveCodec.maxArchiveBytes ||
        info.maxPageBytes < _minNativePageBytes ||
        info.maxPageBytes > _maxNativeTransferBytes ||
        info.maxBlobChunkBytes < 1 ||
        info.maxBlobChunkBytes > _maxNativeTransferBytes ||
        info.maxRecords < 1 ||
        info.maxRecords > BackupArchiveCodec.maxRecords ||
        info.maxMediaCount < 0 ||
        info.maxMediaCount > BackupArchiveCodec.maxMediaCount ||
        info.idleExpiryMillis < 1 ||
        !setEquals(
          info.portableTables,
          BackupArchiveCodec.portableTablesForSchema(info.schemaVersion),
        )) {
      throw const FormatException(
        'The backup snapshot contract is unsupported.',
      );
    }
  }

  static void _validateSnapshotStart(_SnapshotInfo info, _SnapshotStart start) {
    if (start.snapshotId.isEmpty ||
        start.archiveFormatVersion != info.archiveFormatVersion ||
        start.schemaVersion != info.schemaVersion ||
        start.stateVersion < 0 ||
        start.capturedAtUtcMillis < 0 ||
        start.recordCount < 0 ||
        start.recordCount > info.maxRecords ||
        start.mediaCount < 0 ||
        start.mediaCount > info.maxMediaCount ||
        start.mediaBytes < 0 ||
        start.mediaBytes > info.maxSnapshotBytes ||
        start.openSessionCount < 0 ||
        start.openSessionCount > start.recordCount) {
      throw const FormatException('The backup snapshot metadata is invalid.');
    }
  }

  static Map<String, Object?> _stringMap(Object? value, String context) {
    if (value is! Map) {
      throw FormatException('The $context response is invalid.');
    }
    final result = <String, Object?>{};
    for (final entry in value.entries) {
      if (entry.key is! String || result.containsKey(entry.key)) {
        throw FormatException('The $context response has invalid fields.');
      }
      result[entry.key as String] = entry.value;
    }
    return result;
  }

  static Map<String, Object?> _scalarMap(Object? value) {
    final map = _stringMap(value, 'snapshot scalar row');
    for (final entry in map.entries) {
      final field = entry.value;
      if (field != null &&
          field is! int &&
          field is! double &&
          field is! String) {
        throw const FormatException(
          'The backup snapshot contains a non-scalar value.',
        );
      }
      if (field is double && !field.isFinite) {
        throw const FormatException(
          'The backup snapshot contains an invalid number.',
        );
      }
    }
    return Map.unmodifiable(map);
  }

  static Set<String> _stringSet(Map<String, Object?> map, String key) {
    final value = map[key];
    if (value is! List || value.any((item) => item is! String)) {
      throw FormatException('The backup snapshot field "$key" is invalid.');
    }
    final result = value.cast<String>().toSet();
    if (result.length != value.length) {
      throw FormatException(
        'The backup snapshot field "$key" repeats a value.',
      );
    }
    return Set.unmodifiable(result);
  }

  static int _integer(Map<String, Object?> map, String key) {
    final value = map[key];
    if (value is! int) {
      throw FormatException('The backup snapshot field "$key" is invalid.');
    }
    return value;
  }

  static String _requiredString(Map<String, Object?> map, String key) {
    final value = map[key];
    if (value is! String) {
      throw FormatException('The backup snapshot field "$key" is invalid.');
    }
    return value;
  }

  static String? _optionalString(Map<String, Object?> map, String key) {
    final value = map[key];
    if (value != null && value is! String) {
      throw FormatException('The backup snapshot field "$key" is invalid.');
    }
    return value as String?;
  }

  static void _checkCancelled(BackupCancellationCheck? isCancelled) {
    if (isCancelled?.call() ?? false) {
      throw const BackupExportCancelledException();
    }
  }

  static bool setEquals(Set<String> left, Set<String> right) =>
      left.length == right.length && left.containsAll(right);
}

final class BackupSnapshotProgress {
  const BackupSnapshotProgress({
    required this.recordsRead,
    required this.totalRecords,
    required this.mediaBytesRead,
    required this.totalMediaBytes,
  });

  final int recordsRead;
  final int totalRecords;
  final int mediaBytesRead;
  final int totalMediaBytes;

  double get fraction {
    final recordFraction = totalRecords == 0 ? 1.0 : recordsRead / totalRecords;
    final mediaFraction = totalMediaBytes == 0
        ? 1.0
        : mediaBytesRead / totalMediaBytes;
    return ((recordFraction + mediaFraction) / 2).clamp(0, 1).toDouble();
  }
}

final class BackupSnapshotExport {
  const BackupSnapshotExport({
    required this.archiveBytes,
    required this.schemaVersion,
    required this.stateVersion,
    required this.capturedAtUtcMillis,
    required this.openSessionCount,
    required this.recordCount,
    required this.mediaCount,
    required this.mediaBytes,
  });

  final Uint8List archiveBytes;
  final int schemaVersion;
  final int stateVersion;
  final int capturedAtUtcMillis;
  final int openSessionCount;
  final int recordCount;
  final int mediaCount;
  final int mediaBytes;
}

final class BackupExportCancelledException implements Exception {
  const BackupExportCancelledException();

  @override
  String toString() => 'Backup export was cancelled.';
}

final class BackupSnapshotCleanupException implements Exception {
  const BackupSnapshotCleanupException({
    required this.operationFailure,
    required this.cleanupFailure,
  });

  final Object operationFailure;
  final Object cleanupFailure;

  @override
  String toString() =>
      'Backup export failed ($operationFailure), and native snapshot cleanup '
      'also failed ($cleanupFailure).';
}

final class _SnapshotInfo {
  const _SnapshotInfo({
    required this.archiveFormatVersion,
    required this.schemaVersion,
    required this.stateVersion,
    required this.maxSnapshotBytes,
    required this.maxPageBytes,
    required this.maxBlobChunkBytes,
    required this.maxRecords,
    required this.maxMediaCount,
    required this.idleExpiryMillis,
    required this.portableTables,
  });

  final int archiveFormatVersion;
  final int schemaVersion;
  final int stateVersion;
  final int maxSnapshotBytes;
  final int maxPageBytes;
  final int maxBlobChunkBytes;
  final int maxRecords;
  final int maxMediaCount;
  final int idleExpiryMillis;
  final Set<String> portableTables;
}

final class _SnapshotStart {
  const _SnapshotStart({
    required this.snapshotId,
    required this.archiveFormatVersion,
    required this.schemaVersion,
    required this.stateVersion,
    required this.capturedAtUtcMillis,
    required this.recordCount,
    required this.mediaCount,
    required this.mediaBytes,
    required this.openSessionCount,
  });

  final String snapshotId;
  final int archiveFormatVersion;
  final int schemaVersion;
  final int stateVersion;
  final int capturedAtUtcMillis;
  final int recordCount;
  final int mediaCount;
  final int mediaBytes;
  final int openSessionCount;
}

final class _SnapshotPage {
  const _SnapshotPage({
    required this.snapshotId,
    required this.nextCursor,
    required this.done,
    required this.records,
  });

  final String snapshotId;
  final String? nextCursor;
  final bool done;
  final List<_SnapshotRow> records;
}

final class _SnapshotRow {
  const _SnapshotRow(this.table, this.values, this.blobs);

  final String table;
  final Map<String, Object?> values;
  final List<_SnapshotBlob> blobs;
}

final class _SnapshotBlob {
  const _SnapshotBlob(this.id, this.column, this.sizeBytes);

  final String id;
  final String column;
  final int sizeBytes;
}
