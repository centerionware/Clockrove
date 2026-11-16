part of 'backup_archive.dart';

final class BackupArchiveImportHeader {
  const BackupArchiveImportHeader({
    required this.formatVersion,
    required this.schemaVersion,
    required this.stateVersion,
    required this.capturedAtUtcMillis,
    required this.recordCount,
    required this.mediaCount,
    required this.recordBytes,
    required this.mediaBytes,
    required this.archiveBytes,
  });

  final int formatVersion;
  final int schemaVersion;
  final int stateVersion;
  final int capturedAtUtcMillis;
  final int recordCount;
  final int mediaCount;
  final int recordBytes;
  final int mediaBytes;
  final int archiveBytes;
}

final class BackupArchiveReadMetrics {
  const BackupArchiveReadMetrics({
    required this.recordCount,
    required this.mediaCount,
    required this.recordBytes,
    required this.mediaBytes,
    required this.archiveBytes,
  });

  final int recordCount;
  final int mediaCount;
  final int recordBytes;
  final int mediaBytes;
  final int archiveBytes;
}

/// Parses an authenticated plaintext archive without loading it into memory.
/// Rows and media are delivered in archive order to private staging callbacks.
abstract final class BackupArchiveReader {
  static Future<BackupArchiveReadMetrics> stage({
    required File archiveFile,
    required int authenticatedArchiveBytes,
    required List<int> authenticatedArchiveSha256,
    required Future<int> Function(BackupArchiveImportHeader header) onHeader,
    required Future<void> Function(
      int ordinal,
      Map<String, Object?> record,
    ) onRecord,
    required Future<void> Function() onRecordsComplete,
    required Future<void> Function(
      String blobId,
      int offset,
      Uint8List bytes,
    ) onMediaChunk,
  }) async {
    if (authenticatedArchiveBytes < 48 ||
        authenticatedArchiveBytes > BackupArchiveCodec.maxArchiveBytes ||
        authenticatedArchiveSha256.length != 32) {
      throw const FormatException('The backup archive bounds are invalid.');
    }

    final archiveLength = await archiveFile.length();
    if (archiveLength != authenticatedArchiveBytes) {
      throw const FormatException('The authenticated backup changed length.');
    }

    final file = await archiveFile.open(mode: FileMode.read);
    final reader = _BackupArchiveFileReader(file, archiveLength);
    try {
      final headerBytes = await reader.readExactly(48);
      for (var index = 0; index < BackupArchiveCodec._magic.length; index++) {
        if (headerBytes[index] != BackupArchiveCodec._magic[index]) {
          throw const FormatException(
            'This is not a supported Clockrove archive.',
          );
        }
      }

      final headerData = ByteData.sublistView(headerBytes);
      final formatVersion = headerData.getUint16(8, Endian.big);
      final headerLength = headerData.getUint16(10, Endian.big);
      final schemaVersion = headerData.getUint32(12, Endian.big);
      final stateVersion = headerData.getInt64(16, Endian.big);
      final capturedAt = headerData.getInt64(24, Endian.big);
      final recordCount = headerData.getUint32(32, Endian.big);
      final mediaCount = headerData.getUint32(36, Endian.big);
      final recordBytes = headerData.getUint32(40, Endian.big);
      final mediaBytes = headerData.getUint32(44, Endian.big);

      if (formatVersion != BackupArchiveCodec.currentFormatVersion ||
          headerLength != 48 ||
          48 + recordBytes + mediaBytes != archiveLength ||
          recordCount > BackupArchiveCodec.maxRecords ||
          mediaCount > BackupArchiveCodec.maxMediaCount) {
        throw const FormatException('This backup has invalid size metadata.');
      }
      BackupArchiveCodec._validateMetadata(
        schemaVersion: schemaVersion,
        stateVersion: stateVersion,
        capturedAtUtcMillis: capturedAt,
        maxBytes: BackupArchiveCodec.maxArchiveBytes,
        maxRows: BackupArchiveCodec.maxRecords,
        maxBlobs: BackupArchiveCodec.maxMediaCount,
      );

      final header = BackupArchiveImportHeader(
        formatVersion: formatVersion,
        schemaVersion: schemaVersion,
        stateVersion: stateVersion,
        capturedAtUtcMillis: capturedAt,
        recordCount: recordCount,
        mediaCount: mediaCount,
        recordBytes: recordBytes,
        mediaBytes: mediaBytes,
        archiveBytes: archiveLength,
      );
      final maxMediaChunkBytes = await onHeader(header);
      if (maxMediaChunkBytes < 1 || maxMediaChunkBytes > 255 * 1024) {
        throw const FormatException('The backup import chunk limit is invalid.');
      }

      final recordEnd = 48 + recordBytes;
      final descriptors = <_BlobDescriptor>[];
      final blobIds = <String>{};
      var declaredMediaBytes = 0;

      for (var ordinal = 0; ordinal < recordCount; ordinal++) {
        final rowStart = reader.offset;
        reader.beginRecord(rowStart, recordEnd, BackupArchiveCodec.maxRecordBytes);
        if (await reader.readByte() != 1) {
          throw const FormatException(
            'This backup contains an unknown record type.',
          );
        }
        final table = await reader.readShortString();
        final schema = BackupArchiveCodec._tables[table];
        if (schema == null || schema.minSchemaVersion > schemaVersion) {
          throw FormatException(
            'The backup contains unsupported table "$table".',
          );
        }

        final valueCount = await reader.readUint16();
        if (valueCount == 0) {
          throw const FormatException('A backup record has no fields.');
        }
        final values = <String, Object?>{};
        for (var field = 0; field < valueCount; field++) {
          final name = await reader.readShortString();
          if (name.isEmpty || values.containsKey(name)) {
            throw const FormatException(
              'A backup record repeats a field name.',
            );
          }
          values[name] = await reader.readValue();
        }

        final rowBlobCount = await reader.readUint16();
        final rowBlobs = <_BlobDescriptor>[];
        final rowBlobColumns = <String>{};
        for (var index = 0; index < rowBlobCount; index++) {
          final id = await reader.readShortString();
          final column = await reader.readShortString();
          final length = await reader.readUint32();
          final digest = await reader.readExactly(32);
          if (id.isEmpty ||
              !RegExp(r'^[A-Za-z0-9_-]{43}$').hasMatch(id) ||
              length < 1 ||
              !blobIds.add(id) ||
              !rowBlobColumns.add(column) ||
              !schema.blobColumns.contains(column) ||
              values.containsKey(column)) {
            throw const FormatException('A backup media reference is invalid.');
          }
          declaredMediaBytes += length;
          if (declaredMediaBytes > mediaBytes) {
            throw const FormatException('This backup has invalid media sizes.');
          }
          final descriptor = _BlobDescriptor(
            id: id,
            column: column,
            length: length,
            hash: digest,
          );
          rowBlobs.add(descriptor);
          descriptors.add(descriptor);
        }

        final row = BackupArchiveRow(
          table: table,
          values: values,
          blobs: const [],
        );
        BackupArchiveCodec._validateRow(row, schemaVersion);
        reader.endRecord();

        await onRecord(ordinal, {
          'type': 'row',
          'table': table,
          'values': Map<String, Object?>.unmodifiable(values),
          'blobs': [
            for (final blob in rowBlobs)
              {
                'id': blob.id,
                'column': blob.column,
                'sizeBytes': blob.length,
              },
          ],
        });
      }

      if (reader.offset != recordEnd ||
          descriptors.length != mediaCount ||
          declaredMediaBytes != mediaBytes) {
        throw const FormatException(
          'This backup contains inconsistent record counts.',
        );
      }
      await onRecordsComplete();

      var mediaOffset = 0;
      for (final descriptor in descriptors) {
        if (descriptor.length > mediaBytes - mediaOffset) {
          throw const FormatException('A backup media file is truncated.');
        }
        final blobDigest = _ArchiveSha256Stream();
        var blobOffset = 0;
        while (blobOffset < descriptor.length) {
          final count = (descriptor.length - blobOffset)
              .clamp(0, maxMediaChunkBytes)
              .toInt();
          final bytes = await reader.readExactly(count);
          blobDigest.add(bytes);
          await onMediaChunk(descriptor.id, blobOffset, bytes);
          blobOffset += bytes.length;
          mediaOffset += bytes.length;
        }
        if (!BackupArchiveCodec._constantTimeBytesEqual(
          blobDigest.close(),
          descriptor.hash,
        )) {
          throw const FormatException('A backup media file failed its checksum.');
        }
      }

      if (mediaOffset != mediaBytes || reader.offset != archiveLength) {
        throw const FormatException('This backup contains unreferenced data.');
      }
      if (await archiveFile.length() != archiveLength ||
          !BackupArchiveCodec._constantTimeBytesEqual(
            reader.archiveDigest.close(),
            authenticatedArchiveSha256,
          )) {
        throw const FormatException(
          'The authenticated backup changed while it was being read.',
        );
      }
      return BackupArchiveReadMetrics(
        recordCount: recordCount,
        mediaCount: mediaCount,
        recordBytes: recordBytes,
        mediaBytes: mediaBytes,
        archiveBytes: archiveLength,
      );
    } finally {
      await file.close();
    }
  }
}

final class _BackupArchiveFileReader {
  _BackupArchiveFileReader(this.file, this.archiveBytes);

  static const int _bufferBytes = 64 * 1024;
  final RandomAccessFile file;
  final int archiveBytes;
  final Uint8List _buffer = Uint8List(_bufferBytes);
  final _ArchiveSha256Stream archiveDigest = _ArchiveSha256Stream();
  int offset = 0;
  int _bufferOffset = 0;
  int _bufferLength = 0;
  int? _recordStart;
  int? _recordEnd;

  Future<Uint8List> readExactly(int length) async {
    if (length < 0 || length > archiveBytes - offset) {
      throw const FormatException('This backup is truncated.');
    }
    final recordEnd = _recordEnd;
    final recordStart = _recordStart;
    if (recordEnd != null &&
        recordStart != null &&
        (length > recordEnd - offset ||
            length > BackupArchiveCodec.maxRecordBytes - (offset - recordStart))) {
      throw const FormatException('A backup record is too large.');
    }

    final result = Uint8List(length);
    var written = 0;
    while (written < length) {
      if (_bufferOffset == _bufferLength) {
        _bufferOffset = 0;
        _bufferLength = await file.readInto(_buffer, 0, _buffer.length);
        if (_bufferLength == 0) {
          throw const FormatException('This backup is truncated.');
        }
        archiveDigest.add(
          Uint8List.sublistView(_buffer, 0, _bufferLength),
        );
      }
      final amount = (length - written)
          .clamp(0, _bufferLength - _bufferOffset)
          .toInt();
      result.setRange(
        written,
        written + amount,
        _buffer,
        _bufferOffset,
      );
      written += amount;
      _bufferOffset += amount;
      offset += amount;
    }
    return result;
  }

  void beginRecord(int start, int recordsEnd, int maximumBytes) {
    if (_recordStart != null || start != offset || recordsEnd < start) {
      throw StateError('Backup archive record reader is out of sequence.');
    }
    _recordStart = start;
    _recordEnd = recordsEnd < start + maximumBytes
        ? recordsEnd
        : start + maximumBytes;
  }

  void endRecord() {
    _recordStart = null;
    _recordEnd = null;
  }

  Future<int> readByte() async => (await readExactly(1))[0];

  Future<int> readUint16() async =>
      ByteData.sublistView(await readExactly(2)).getUint16(0, Endian.big);

  Future<int> readUint32() async =>
      ByteData.sublistView(await readExactly(4)).getUint32(0, Endian.big);

  Future<String> readShortString() async {
    final length = await readUint16();
    if (length > BackupArchiveCodec.maxRecordBytes) {
      throw const FormatException('A backup record is too large.');
    }
    return utf8.decode(await readExactly(length), allowMalformed: false);
  }

  Future<Object?> readValue() async {
    switch (await readByte()) {
      case 0:
        return null;
      case 1:
        return ByteData.sublistView(await readExactly(8)).getInt64(0, Endian.big);
      case 2:
        final value = ByteData.sublistView(await readExactly(8))
            .getFloat64(0, Endian.big);
        if (!value.isFinite) {
          throw const FormatException('A backup record contains an invalid number.');
        }
        return value;
      case 3:
        final length = await readUint32();
        if (length > BackupArchiveCodec.maxRecordBytes) {
          throw const FormatException('A backup record is too large.');
        }
        return utf8.decode(await readExactly(length), allowMalformed: false);
      default:
        throw const FormatException('A backup record has an unknown value type.');
    }
  }
}
