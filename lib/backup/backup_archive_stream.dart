part of 'backup_archive.dart';

final class _PreparedStreamRow {
  const _PreparedStreamRow(
    this.row,
    this.valueKeys,
    this.blobs,
    this.primaryKey,
    this.encodedLength,
  );

  final BackupArchiveStreamRow row;
  final List<String> valueKeys;
  final List<BackupArchiveBlobSource> blobs;
  final String primaryKey;
  final int encodedLength;
}

typedef BackupArchiveBlobReader = Stream<List<int>> Function(String blobId);
typedef BackupArchiveCancellationCheck = bool Function();

/// A row whose media is described by length and loaded on demand.
final class BackupArchiveStreamRow {
  BackupArchiveStreamRow({
    required this.table,
    required Map<String, Object?> values,
    required List<BackupArchiveBlobSource> blobs,
  }) : values = Map.unmodifiable(Map<String, Object?>.of(values)),
       blobs = List.unmodifiable(blobs);

  final String table;
  final Map<String, Object?> values;
  final List<BackupArchiveBlobSource> blobs;
}

final class BackupArchiveBlobSource {
  BackupArchiveBlobSource({
    required this.id,
    required this.column,
    required this.sizeBytes,
    required List<int> sha256,
  }) : sha256 = List<int>.unmodifiable(sha256);

  final String id;
  final String column;
  final int sizeBytes;
  final List<int> sha256;
}

/// A preflighted CLKARCH1 stream. Its output stays within 64 KiB per event.
final class BackupArchiveStreamPlan {
  BackupArchiveStreamPlan._({
    required this.schemaVersion,
    required this.stateVersion,
    required this.capturedAtUtcMillis,
    required this._rows,
    required this._headerBytes,
    required this.recordCount,
    required this.blobCount,
    required this.recordsLength,
    required this.mediaLength,
    required this.totalLength,
  });

  final int schemaVersion;
  final int stateVersion;
  final int capturedAtUtcMillis;
  final List<_PreparedStreamRow> _rows;
  final Uint8List _headerBytes;
  final int recordCount;
  final int blobCount;
  final int recordsLength;
  final int mediaLength;
  final int totalLength;

  /// Drops preflighted row metadata and the plaintext archive header.
  void dispose() {
    _rows.clear();
    _headerBytes.fillRange(0, _headerBytes.length, 0);
  }

  Stream<Uint8List> encode({
    required BackupArchiveBlobReader readBlob,
    BackupArchiveCancellationCheck? isCancelled,
  }) async* {
    var emittedBytes = 0;
    final output = _ArchiveChunkBuffer();
    for (final chunk in output.add(_headerBytes)) {
      emittedBytes += chunk.length;
      yield chunk;
    }
    for (final prepared in _rows) {
      _checkCancelled(isCancelled);
      final rowBytes = Uint8List(prepared.encodedLength);
      final writer = _ArchiveWriter(rowBytes, 0);
      writer.writeByte(1);
      writer.writeShortString(prepared.row.table);
      writer.writeUint16(prepared.valueKeys.length);
      for (final key in prepared.valueKeys) {
        writer.writeShortString(key);
        writer.writeValue(prepared.row.values[key]);
      }
      writer.writeUint16(prepared.blobs.length);
      for (final blob in prepared.blobs) {
        writer.writeShortString(blob.id);
        writer.writeShortString(blob.column);
        writer.writeUint32(blob.sizeBytes);
        writer.writeBytes(blob.sha256);
      }
      if (writer.offset != rowBytes.length) {
        throw StateError('Backup archive row length changed after preflight.');
      }
      for (final chunk in output.add(rowBytes)) {
        emittedBytes += chunk.length;
        yield chunk;
      }
    }
    for (final chunk in output.flush()) {
      emittedBytes += chunk.length;
      yield chunk;
    }
    if (emittedBytes != 48 + recordsLength) {
      throw StateError('Backup archive record length did not match preflight.');
    }

    for (final prepared in _rows) {
      for (final blob in prepared.blobs) {
        _checkCancelled(isCancelled);
        final digest = _ArchiveSha256Stream();
        var bytesRead = 0;
        if (blob.sizeBytes > 0) {
          await for (final chunk in readBlob(blob.id)) {
            _checkCancelled(isCancelled);
            if (chunk.isEmpty || chunk.length > blob.sizeBytes - bytesRead) {
              throw const FormatException(
                'The backup snapshot media changed while exporting.',
              );
            }
            bytesRead += chunk.length;
            digest.add(chunk);
            for (final outputChunk in output.add(chunk)) {
              emittedBytes += outputChunk.length;
              yield outputChunk;
            }
          }
        }
        if (bytesRead != blob.sizeBytes ||
            !_constantTimeBytesEqual(digest.close(), blob.sha256)) {
          throw const FormatException(
            'The backup snapshot media changed while exporting.',
          );
        }
      }
    }
    for (final chunk in output.flush()) {
      emittedBytes += chunk.length;
      yield chunk;
    }
    if (emittedBytes != totalLength) {
      throw StateError('Backup archive length did not match preflight.');
    }
  }

  static void _checkCancelled(BackupArchiveCancellationCheck? check) {
    if (check?.call() ?? false) {
      throw const BackupArchiveStreamCancelledException();
    }
  }

  static bool _constantTimeBytesEqual(List<int> left, List<int> right) {
    if (left.length != right.length) return false;
    var difference = 0;
    for (var index = 0; index < left.length; index++) {
      difference |= left[index] ^ right[index];
    }
    return difference == 0;
  }
}

final class BackupArchiveStreamCancelledException implements Exception {
  const BackupArchiveStreamCancelledException();
}
