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
typedef BackupArchiveSourceBlobReader = Stream<List<int>> Function(
  BackupArchiveBlobSource blob,
);
typedef BackupArchiveCancellationCheck = bool Function();
typedef BackupArchiveStreamRows = Stream<BackupArchiveStreamRow> Function();

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
    if (emittedBytes != _headerBytes.length + recordsLength) {
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

/// A preflighted archive whose metadata rows are replayed from a bounded
/// source instead of retained as Dart maps. The source must return the same
/// canonical row sequence on every invocation.
final class BackupTrustedNativeArchiveReplayPlan {
  BackupTrustedNativeArchiveReplayPlan._({
    required this.schemaVersion,
    required this.stateVersion,
    required this.capturedAtUtcMillis,
    required BackupArchiveStreamRows rows,
    required this.recordCount,
    required this.blobCount,
    required this.recordsLength,
    required this.mediaLength,
    required this.totalLength,
    required this._headerBytes,
    required this._rowMetadataDigest,
  }) : _rows = rows; // ignore: prefer_initializing_formals

  final int schemaVersion;
  final int stateVersion;
  final int capturedAtUtcMillis;
  BackupArchiveStreamRows? _rows;
  final int recordCount;
  final int blobCount;
  final int recordsLength;
  final int mediaLength;
  final int totalLength;
  final Uint8List _headerBytes;
  final Uint8List _rowMetadataDigest;

  /// Releases the replay callback and the preflight header/digest.
  void dispose() {
    _rows = null;
    _headerBytes.fillRange(0, _headerBytes.length, 0);
    _rowMetadataDigest.fillRange(0, _rowMetadataDigest.length, 0);
  }

  Stream<Uint8List> encode({
    required BackupArchiveSourceBlobReader readBlob,
    BackupArchiveCancellationCheck? isCancelled,
  }) async* {
    final rows = _rows;
    if (rows == null) throw StateError('The backup stream plan is closed.');

    var emittedBytes = 0;
    final output = _ArchiveChunkBuffer();
    for (final chunk in output.add(_headerBytes)) {
      emittedBytes += chunk.length;
      yield chunk;
    }

    final recordDigest = _ArchiveSha256Stream();
    final recordOrder = _ReplayRowOrder();
    var actualRecords = 0;
    var actualRecordLength = 0;
    await for (final row in rows()) {
      _checkCancelled(isCancelled);
      recordOrder.add(row);
      final prepared = _prepareReplayRow(row, schemaVersion);
      final rowBytes = _encodePreparedStreamRow(prepared);
      recordDigest.add(rowBytes);
      actualRecordLength += rowBytes.length;
      actualRecords++;
      for (final chunk in output.add(rowBytes)) {
        emittedBytes += chunk.length;
        yield chunk;
      }
    }
    final actualRecordDigest = recordDigest.close();
    if (actualRecords != recordCount ||
        actualRecordLength != recordsLength ||
        !_constantTimeBytesEqual(actualRecordDigest, _rowMetadataDigest)) {
      throw const FormatException(
        'The backup snapshot changed after archive preflight.',
      );
    }
    for (final chunk in output.flush()) {
      emittedBytes += chunk.length;
      yield chunk;
    }
    if (emittedBytes != _headerBytes.length + recordsLength) {
      throw StateError('Backup archive record length did not match preflight.');
    }

    final mediaDigest = _ArchiveSha256Stream();
    final mediaOrder = _ReplayRowOrder();
    var mediaRecords = 0;
    var mediaCount = 0;
    var mediaBytes = 0;
    await for (final row in rows()) {
      _checkCancelled(isCancelled);
      mediaOrder.add(row);
      final prepared = _prepareReplayRow(row, schemaVersion);
      mediaDigest.add(_encodePreparedStreamRow(prepared));
      mediaRecords++;
      for (final blob in prepared.blobs) {
        _checkCancelled(isCancelled);
        final digest = _ArchiveSha256Stream();
        var bytesRead = 0;
        if (blob.sizeBytes > 0) {
          await for (final chunk in readBlob(blob)) {
            _checkCancelled(isCancelled);
            if (chunk.isEmpty || chunk.length > blob.sizeBytes - bytesRead) {
              throw const FormatException(
                'The backup snapshot media changed while exporting.',
              );
            }
            bytesRead += chunk.length;
            mediaBytes += chunk.length;
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
        mediaCount++;
      }
    }
    if (mediaRecords != recordCount ||
        mediaCount != blobCount ||
        mediaBytes != mediaLength ||
        !_constantTimeBytesEqual(mediaDigest.close(), _rowMetadataDigest)) {
      throw const FormatException(
        'The backup snapshot changed after archive preflight.',
      );
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

final class _ReplayRowOrder {
  String? _table;
  Map<String, Object?>? _values;

  void add(BackupArchiveStreamRow row) {
    final previousTable = _table;
    final previousValues = _values;
    if (previousTable != null && previousValues != null) {
      final tableOrder = previousTable.compareTo(row.table);
      if (tableOrder > 0 ||
          (tableOrder == 0 &&
              _compareReplayPrimaryKeyValues(
                    row.table,
                    previousValues,
                    row.values,
                  ) >=
                  0)) {
        throw const FormatException(
          'The backup snapshot rows are not in canonical order.',
        );
      }
    }
    _table = row.table;
    _values = row.values;
  }
}

final class _ReplayStreamPreflight {
  _ReplayStreamPreflight({
    required this.schemaVersion,
    required this.maxBytes,
    required this.maxRows,
    required this.maxBlobs,
  });

  final int schemaVersion;
  final int maxBytes;
  final int maxRows;
  final int maxBlobs;
  final Set<String> _blobIds = {};
  final _ArchiveSha256Stream _rowDigest = _ArchiveSha256Stream();
  final _ReplayRowOrder _rowOrder = _ReplayRowOrder();
  var recordCount = 0;
  var blobCount = 0;
  var recordsLength = 0;
  var mediaLength = 0;

  void add(BackupArchiveStreamRow row) {
    if (recordCount >= maxRows) {
      throw const FormatException('The backup contains too many records.');
    }
    _rowOrder.add(row);
    final prepared = _prepareReplayRow(
      row,
      schemaVersion,
      seenBlobIds: _blobIds,
    );
    blobCount += prepared.blobs.length;
    if (blobCount > maxBlobs) {
      throw const FormatException('The backup contains too many media files.');
    }
    mediaLength = BackupArchiveCodec._boundedAdd(
      mediaLength,
      prepared.blobs.fold<int>(0, (sum, blob) => sum + blob.sizeBytes),
      maxBytes,
    );
    recordsLength = BackupArchiveCodec._boundedAdd(
      recordsLength,
      prepared.encodedLength,
      maxBytes,
    );
    if (BackupArchiveCodec._headerLength + recordsLength + mediaLength >
        maxBytes) {
      throw const FormatException(
        'The full backup exceeds the supported size. No media was omitted.',
      );
    }
    _rowDigest.add(_encodePreparedStreamRow(prepared));
    recordCount++;
  }

  BackupTrustedNativeArchiveReplayPlan finish({
    required int expectedRecordCount,
    required int expectedBlobCount,
    required int expectedMediaBytes,
    required BackupArchiveStreamRows rows,
    required int stateVersion,
    required int capturedAtUtcMillis,
  }) {
    if (recordCount != expectedRecordCount ||
        blobCount != expectedBlobCount ||
        mediaLength != expectedMediaBytes) {
      throw const FormatException('The backup snapshot counts do not match.');
    }
    final totalLength =
        BackupArchiveCodec._headerLength + recordsLength + mediaLength;
    if (totalLength > maxBytes) {
      throw const FormatException(
        'The full backup exceeds the supported size. No media was omitted.',
      );
    }

    final headerBytes = Uint8List(BackupArchiveCodec._headerLength);
    final header = ByteData.sublistView(headerBytes);
    headerBytes.setRange(0, 8, const [
      0x43,
      0x4c,
      0x4b,
      0x41,
      0x52,
      0x43,
      0x48,
      0x31,
    ]);
    header.setUint16(8, BackupArchiveCodec.currentFormatVersion, Endian.big);
    header.setUint16(10, BackupArchiveCodec._headerLength, Endian.big);
    header.setUint32(12, schemaVersion, Endian.big);
    header.setInt64(16, stateVersion, Endian.big);
    header.setInt64(24, capturedAtUtcMillis, Endian.big);
    header.setUint32(32, recordCount, Endian.big);
    header.setUint32(36, blobCount, Endian.big);
    header.setUint64(40, recordsLength, Endian.big);
    header.setUint64(48, mediaLength, Endian.big);
    return BackupTrustedNativeArchiveReplayPlan._(
      schemaVersion: schemaVersion,
      stateVersion: stateVersion,
      capturedAtUtcMillis: capturedAtUtcMillis,
      rows: rows,
      recordCount: recordCount,
      blobCount: blobCount,
      recordsLength: recordsLength,
      mediaLength: mediaLength,
      totalLength: totalLength,
      headerBytes: headerBytes,
      rowMetadataDigest: Uint8List.fromList(_rowDigest.close()),
    );
  }
}

_PreparedStreamRow _prepareReplayRow(
  BackupArchiveStreamRow row,
  int schemaVersion, {
  Set<String>? seenBlobIds,
}) {
  final rowWithoutBlobs = BackupArchiveRow(
    table: row.table,
    values: row.values,
    blobs: const [],
  );
  BackupArchiveCodec._validateRow(rowWithoutBlobs, schemaVersion);
  final valueKeys = row.values.keys.toList()..sort();
  final rowBlobs = [...row.blobs]..sort((a, b) => a.id.compareTo(b.id));
  if (valueKeys.length > 0xffff || rowBlobs.length > 0xffff) {
    throw const FormatException('A backup record has too many fields.');
  }
  var rowLength = 1 + BackupArchiveCodec._shortStringEncodedLength(row.table);
  rowLength = BackupArchiveCodec._boundedAdd(
    rowLength,
    2,
    BackupArchiveCodec.maxRecordBytes,
  );
  for (final key in valueKeys) {
    rowLength = BackupArchiveCodec._boundedAdd(
      rowLength,
      BackupArchiveCodec._shortStringEncodedLength(key),
      BackupArchiveCodec.maxRecordBytes,
    );
    rowLength = BackupArchiveCodec._boundedAdd(
      rowLength,
      BackupArchiveCodec._valueEncodedLength(row.values[key]),
      BackupArchiveCodec.maxRecordBytes,
    );
  }
  rowLength = BackupArchiveCodec._boundedAdd(
    rowLength,
    2,
    BackupArchiveCodec.maxRecordBytes,
  );
  final rowBlobColumns = <String>{};
  for (final blob in rowBlobs) {
    if (blob.id.isEmpty ||
        (seenBlobIds != null && !seenBlobIds.add(blob.id)) ||
        !rowBlobColumns.add(blob.column) ||
        !(BackupArchiveCodec._tables[row.table]?.blobColumns.contains(
              blob.column,
            ) ??
            false) ||
        row.values.containsKey(blob.column)) {
      throw FormatException('The ${row.table} media reference is invalid.');
    }
    if (blob.sizeBytes < 0 || blob.sizeBytes > 0xffffffff) {
      throw const FormatException('A backup media file is too large.');
    }
    if (blob.sha256.length != 32 ||
        blob.sha256.any((byte) => byte < 0 || byte > 255)) {
      throw const FormatException('A backup media checksum is invalid.');
    }
    rowLength = BackupArchiveCodec._boundedAdd(
      rowLength,
      BackupArchiveCodec._shortStringEncodedLength(blob.id),
      BackupArchiveCodec.maxRecordBytes,
    );
    rowLength = BackupArchiveCodec._boundedAdd(
      rowLength,
      BackupArchiveCodec._shortStringEncodedLength(blob.column),
      BackupArchiveCodec.maxRecordBytes,
    );
    rowLength = BackupArchiveCodec._boundedAdd(
      rowLength,
      4 + 32,
      BackupArchiveCodec.maxRecordBytes,
    );
  }
  if (rowLength > BackupArchiveCodec.maxRecordBytes) {
    throw const FormatException('A backup record is too large.');
  }
  return _PreparedStreamRow(
    row,
    valueKeys,
    rowBlobs,
    BackupArchiveCodec._primaryKey(rowWithoutBlobs),
    rowLength,
  );
}

Uint8List _encodePreparedStreamRow(_PreparedStreamRow prepared) {
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
  return rowBytes;
}

final class BackupArchiveStreamCancelledException implements Exception {
  const BackupArchiveStreamCancelledException();
}
