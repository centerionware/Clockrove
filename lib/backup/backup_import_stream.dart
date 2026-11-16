import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart' as crypto;
import 'package:flutter/services.dart';

import 'backup_archive.dart';
import 'backup_crypto_v2.dart';
import 'backup_import_api.dart';

const int _archiveHeaderLength = 48;
const int maxBackupImportRowBytes = 128 * 1024;
const List<int> _archiveMagic = [
  0x43,
  0x4c,
  0x4b,
  0x41,
  0x52,
  0x43,
  0x48,
  0x31,
];

const Map<String, Set<String>> _archiveBlobColumns = {
  'invoices': {'pdf'},
  'invoice_copies': {'pdf'},
  'quote_pdfs': {'bytes'},
  'logo_assets': {'bytes'},
  'task_attachments': {'data'},
};

/// The fixed metadata read during pass one, after every encrypted frame and
/// the final manifest have authenticated. No archive records are parsed here.
final class BackupArchiveImportHeader {
  BackupArchiveImportHeader._({
    required this.formatVersion,
    required this.schemaVersion,
    required this.stateVersion,
    required this.capturedAtUtcMillis,
    required this.recordCount,
    required this.mediaCount,
    required this.recordsLength,
    required this.mediaBytes,
    required this.archiveBytes,
    required Uint8List encoded,
  }) : _encoded = Uint8List.fromList(encoded);

  final int formatVersion;
  final int schemaVersion;
  final int stateVersion;
  final int capturedAtUtcMillis;
  final int recordCount;
  final int mediaCount;
  final int recordsLength;
  final int mediaBytes;
  final int archiveBytes;
  final Uint8List _encoded;

  Uint8List get encoded => Uint8List.fromList(_encoded);

  static BackupArchiveImportHeader parse(
    List<int> encoded, {
    required int authenticatedArchiveBytes,
  }) {
    if (encoded.length != _archiveHeaderLength ||
        authenticatedArchiveBytes < _archiveHeaderLength ||
        authenticatedArchiveBytes > BackupArchiveCodec.maxArchiveBytes) {
      throw const FormatException('This backup has an invalid size.');
    }
    final bytes = Uint8List.fromList(encoded);
    for (var index = 0; index < _archiveMagic.length; index++) {
      if (bytes[index] != _archiveMagic[index]) {
        throw const FormatException(
          'This is not a supported Clockrove archive.',
        );
      }
    }

    final data = ByteData.sublistView(bytes);
    final formatVersion = data.getUint16(8, Endian.big);
    final headerLength = data.getUint16(10, Endian.big);
    final schemaVersion = data.getUint32(12, Endian.big);
    final stateVersion = data.getInt64(16, Endian.big);
    final capturedAtUtcMillis = data.getInt64(24, Endian.big);
    final recordCount = data.getUint32(32, Endian.big);
    final mediaCount = data.getUint32(36, Endian.big);
    final recordsLength = data.getUint32(40, Endian.big);
    final mediaBytes = data.getUint32(44, Endian.big);
    final archiveBytes = _archiveHeaderLength + recordsLength + mediaBytes;

    if (formatVersion != BackupArchiveCodec.currentFormatVersion ||
        headerLength != _archiveHeaderLength) {
      throw const FormatException('This backup format is not supported.');
    }
    // The archive decoder understands historical schemas too. Native restore
    // still checks for an exact local schema match when staging begins; header
    // recognition alone does not imply that the current restore adapter can
    // migrate an older archive.
    if (schemaVersion < 1 ||
        schemaVersion > BackupArchiveCodec.latestSchemaVersion ||
        stateVersion < 0 ||
        capturedAtUtcMillis < 0 ||
        recordCount > maxBackupImportRows ||
        mediaCount > maxBackupImportMediaCount ||
        mediaBytes > maxBackupImportMediaBytes ||
        archiveBytes != authenticatedArchiveBytes ||
        archiveBytes > BackupArchiveCodec.maxArchiveBytes) {
      throw const FormatException('This backup uses unsupported metadata.');
    }
    return BackupArchiveImportHeader._(
      formatVersion: formatVersion,
      schemaVersion: schemaVersion,
      stateVersion: stateVersion,
      capturedAtUtcMillis: capturedAtUtcMillis,
      recordCount: recordCount,
      mediaCount: mediaCount,
      recordsLength: recordsLength,
      mediaBytes: mediaBytes,
      archiveBytes: archiveBytes,
      encoded: bytes,
    );
  }

  bool matches(List<int> candidate) {
    if (candidate.length != _encoded.length) return false;
    var difference = 0;
    for (var index = 0; index < _encoded.length; index++) {
      difference |= _encoded[index] ^ candidate[index];
    }
    return difference == 0;
  }
}

/// Keeps only the authenticated archive header during pass one.
final class BackupArchiveHeaderProbe implements BackupTransactionalByteSink {
  final Uint8List _header = Uint8List(_archiveHeaderLength);
  int _headerLength = 0;
  int _archiveBytes = 0;
  bool _committed = false;
  bool _discarded = false;

  int get archiveBytes => _archiveBytes;

  Uint8List get headerBytes {
    if (!_committed || _headerLength != _archiveHeaderLength) {
      throw StateError('The authenticated archive header is unavailable.');
    }
    return Uint8List.fromList(_header);
  }

  @override
  Future<void> write(List<int> bytes) async {
    if (_committed || _discarded || bytes.isEmpty) {
      throw StateError('The archive header probe is closed.');
    }
    if (bytes.length > BackupCryptoV2.chunkBytes ||
        _archiveBytes > BackupArchiveCodec.maxArchiveBytes - bytes.length) {
      throw const FormatException('This backup is outside the supported size.');
    }
    final prefixBytes = (_archiveHeaderLength - _headerLength).clamp(
      0,
      bytes.length,
    );
    if (prefixBytes > 0) {
      _header.setRange(
        _headerLength,
        _headerLength + prefixBytes,
        bytes.take(prefixBytes),
      );
      _headerLength += prefixBytes;
    }
    _archiveBytes += bytes.length;
  }

  @override
  Future<void> commit() async {
    if (_discarded || _headerLength != _archiveHeaderLength) {
      throw const FormatException('This backup has an invalid header.');
    }
    _committed = true;
  }

  @override
  Future<void> discard() async {
    _header.fillRange(0, _header.length, 0);
    _headerLength = 0;
    _discarded = true;
  }
}

/// Streams authenticated CLKARCH1 plaintext into the native private import
/// stage. It retains one bounded row page and media descriptors only. The
/// caller must authenticate the complete envelope before [seal] exposes the
/// native preview.
final class BackupArchiveImportSink implements BackupTransactionalByteSink {
  BackupArchiveImportSink({
    required BackupImportApi api,
    required String importId,
    required BackupArchiveImportHeader header,
    required List<int> expectedArchiveSha256,
  }) : _api = api,
       _importId = importId,
       _parser = _BackupArchiveImportParser(api, importId, header),
       _expectedArchiveSha256 = Uint8List.fromList(expectedArchiveSha256) {
    if (_expectedArchiveSha256.length != 32) {
      throw const FormatException(
        'The authenticated archive digest is invalid.',
      );
    }
    _archiveDigestInput = crypto.sha256.startChunkedConversion(
      _archiveDigestOutput,
    );
  }

  final BackupImportApi _api;
  final String _importId;
  final _BackupArchiveImportParser _parser;
  final Uint8List _expectedArchiveSha256;
  final _SingleDigestSink _archiveDigestOutput = _SingleDigestSink();
  late final ByteConversionSink _archiveDigestInput;
  bool _authenticated = false;
  bool _closed = false;
  bool _archiveDigestClosed = false;
  BackupImportPreview? _preview;

  int get rowsStaged => _parser.rowsStaged;
  int get maximumBufferedArchiveBytes => _parser.maximumBufferedArchiveBytes;

  @override
  Future<void> write(List<int> bytes) {
    if (_authenticated || _closed) {
      throw StateError('The backup import stream is closed.');
    }
    _archiveDigestInput.add(bytes);
    return _parser.add(bytes);
  }

  /// Called by the crypto codec only after all frames, manifest and EOF pass.
  /// The preview remains unavailable until [seal] verifies both passes agree.
  @override
  Future<void> commit() async {
    if (_authenticated || _closed) {
      throw StateError('The backup import stream is closed.');
    }
    await _parser.finishInput();
    _archiveDigestInput.close();
    _archiveDigestClosed = true;
    if (!_constantTimeEqual(
      _archiveDigestOutput.value.bytes,
      _expectedArchiveSha256,
    )) {
      throw const FormatException(
        'The backup changed between authenticated read passes.',
      );
    }
    _authenticated = true;
    _parser.clear();
  }

  Future<BackupImportPreview> seal() async {
    if (!_authenticated || _closed) {
      throw StateError('The authenticated backup import is not ready.');
    }
    try {
      return _preview ??= await _api.finish(_importId);
    } on Object catch (_) {
      await discard();
      rethrow;
    }
  }

  @override
  Future<void> discard() async {
    if (_closed) return;
    _parser.clear();
    if (!_archiveDigestClosed) {
      _archiveDigestInput.close();
      _archiveDigestClosed = true;
    }
    _expectedArchiveSha256.fillRange(0, _expectedArchiveSha256.length, 0);
    _closed = true;
    await _api.close(_importId);
  }
}

final class _BackupArchiveImportParser {
  _BackupArchiveImportParser(this._api, this._importId, this._header);

  final BackupImportApi _api;
  final String _importId;
  final BackupArchiveImportHeader _header;
  Uint8List _pending = Uint8List(0);
  int _offset = 0;
  int _bytesReceived = 0;
  int _maximumBufferedBytes = 0;
  int _rowsParsed = 0;
  int _rowsStaged = 0;
  int _recordsBytesConsumed = 0;
  int _mediaBytesDeclared = 0;
  int _mediaBytesConsumed = 0;
  int _mediaIndex = 0;
  int _mediaOffset = 0;
  int _state = 0;
  bool _finished = false;
  bool _cleared = false;
  final List<_ImportBlobDescriptor> _media = [];
  final Set<String> _blobIds = {};
  final List<Map<String, Object?>> _page = [];
  _SingleDigestSink? _digestOutput;
  ByteConversionSink? _digestInput;

  int get rowsStaged => _rowsStaged;
  int get maximumBufferedArchiveBytes => _maximumBufferedBytes;

  Future<void> add(List<int> bytes) async {
    if (_finished || _cleared || bytes.isEmpty) {
      throw StateError('The archive parser is closed.');
    }
    if (bytes.length > BackupCryptoV2.chunkBytes ||
        _bytesReceived > _header.archiveBytes - bytes.length) {
      throw const FormatException('The backup archive is too large.');
    }
    _bytesReceived += bytes.length;
    _append(bytes);
    await _parseAvailable();
  }

  Future<void> finishInput() async {
    if (_finished || _cleared) {
      throw StateError('The archive parser is closed.');
    }
    await _parseAvailable();
    if (_bytesReceived != _header.archiveBytes ||
        _state != 3 ||
        _pending.length != _offset) {
      throw const FormatException('This backup archive is truncated.');
    }
    await _flushPage();
    _finished = true;
  }

  void clear() {
    if (_cleared) return;
    _pending.fillRange(0, _pending.length, 0);
    _pending = Uint8List(0);
    _offset = 0;
    _media.clear();
    _blobIds.clear();
    _page.clear();
    _digestInput = null;
    _digestOutput = null;
    _cleared = true;
  }

  void _append(List<int> bytes) {
    final remaining = _pending.length - _offset;
    final maximumBuffered =
        BackupArchiveCodec.maxRecordBytes + BackupCryptoV2.chunkBytes;
    if (remaining > maximumBuffered - bytes.length) {
      throw const FormatException(
        'A backup record exceeds the supported size.',
      );
    }
    final next = Uint8List(remaining + bytes.length);
    if (remaining > 0) {
      next.setRange(0, remaining, _pending, _offset);
    }
    next.setRange(remaining, next.length, bytes);
    if (next.length > _maximumBufferedBytes) {
      _maximumBufferedBytes = next.length;
    }
    _pending.fillRange(0, _pending.length, 0);
    _pending = next;
    _offset = 0;
  }

  Future<void> _parseAvailable() async {
    while (true) {
      if (_state == 0) {
        if (_pending.length - _offset < _archiveHeaderLength) return;
        final current = Uint8List.sublistView(
          _pending,
          _offset,
          _offset + _archiveHeaderLength,
        );
        if (!_header.matches(current)) {
          throw const FormatException(
            'The authenticated backup changed between read passes.',
          );
        }
        _offset += _archiveHeaderLength;
        _state = 1;
        continue;
      }

      if (_state == 1) {
        if (_rowsParsed == _header.recordCount) {
          if (_recordsBytesConsumed != _header.recordsLength ||
              _media.length != _header.mediaCount ||
              _mediaBytesDeclared != _header.mediaBytes) {
            throw const FormatException(
              'This backup has inconsistent record and media counts.',
            );
          }
          _state = 2;
          continue;
        }
        final parsed = _tryReadRow();
        if (parsed == null) return;
        _offset = parsed.nextOffset;
        _recordsBytesConsumed += parsed.recordBytes;
        if (_recordsBytesConsumed > _header.recordsLength) {
          throw const FormatException(
            'This backup has invalid record lengths.',
          );
        }
        _rowsParsed++;
        await _queueRow(parsed.record);
        continue;
      }

      if (_state == 2) {
        if (_mediaIndex == _media.length) {
          if (_mediaBytesConsumed != _header.mediaBytes) {
            throw const FormatException('This backup has unreferenced media.');
          }
          _state = 3;
          if (_pending.length != _offset) {
            throw const FormatException(
              'This backup has trailing archive data.',
            );
          }
          return;
        }
        if (_offset == _pending.length) return;
        await _writeMediaChunk();
        continue;
      }

      if (_pending.length != _offset) {
        throw const FormatException('This backup has trailing archive data.');
      }
      return;
    }
  }

  _ParsedImportRow? _tryReadRow() {
    final start = _offset;
    final reader = _ArchiveChunkReader(_pending, start);
    try {
      if (reader.readByte() != 1) {
        throw const FormatException('This backup contains an unknown record.');
      }
      final table = reader.readShortString(
        maximumBytes: BackupArchiveCodec.maxRecordBytes,
      );
      if (!BackupArchiveCodec.portableTablesForSchema(_header.schemaVersion)
          .contains(table)) {
        throw const FormatException(
          'This backup contains an unsupported table.',
        );
      }

      final valueCount = reader.readUint16();
      if (valueCount == 0) {
        throw const FormatException('A backup record has no fields.');
      }
      final values = <String, Object?>{};
      for (var index = 0; index < valueCount; index++) {
        final key = reader.readShortString(maximumBytes: 128);
        if (key.isEmpty || key.contains('\u0000') || values.containsKey(key)) {
          throw const FormatException('A backup record has an invalid field.');
        }
        values[key] = reader.readValue();
        reader.ensureRecordWithin(start, BackupArchiveCodec.maxRecordBytes);
      }

      final rawBlobCount = reader.readUint16();
      if (rawBlobCount > _header.mediaCount - _media.length) {
        throw const FormatException(
          'This backup has too many media references.',
        );
      }
      final blobs = <Map<String, Object?>>[];
      final blobColumns = <String>{};
      final rowBlobIds = <String>{};
      final rowDescriptors = <_ImportBlobDescriptor>[];
      var rowMediaBytes = 0;
      final allowedBlobColumns = _archiveBlobColumns[table] ?? const <String>{};
      for (var index = 0; index < rawBlobCount; index++) {
        final id = reader.readShortString(maximumBytes: 128);
        final column = reader.readShortString(maximumBytes: 128);
        final sizeBytes = reader.readUint32();
        final digest = reader.readBytes(32);
        if (!RegExp(r'^[A-Za-z0-9_-]{43}$').hasMatch(id) ||
            _blobIds.contains(id) ||
            !rowBlobIds.add(id) ||
            !allowedBlobColumns.contains(column) ||
            !blobColumns.add(column) ||
            (values.containsKey(column) && values[column] != null) ||
            sizeBytes < 1 ||
            sizeBytes > maxBackupImportMediaBytes ||
            _mediaBytesDeclared + rowMediaBytes >
                maxBackupImportMediaBytes - sizeBytes) {
          throw const FormatException('A backup media reference is invalid.');
        }
        rowMediaBytes += sizeBytes;
        final descriptor = _ImportBlobDescriptor(
          id: id,
          column: column,
          sizeBytes: sizeBytes,
          sha256: Uint8List.fromList(digest),
        );
        rowDescriptors.add(descriptor);
        blobs.add({'id': id, 'column': column, 'sizeBytes': sizeBytes});
        reader.ensureRecordWithin(start, BackupArchiveCodec.maxRecordBytes);
      }

      final recordBytes = reader.offset - start;
      if (recordBytes > BackupArchiveCodec.maxRecordBytes) {
        throw const FormatException('A backup record is too large.');
      }
      final record = <String, Object?>{
        'type': 'row',
        'table': table,
        'values': Map<String, Object?>.unmodifiable(values),
        'blobs': List<Map<String, Object?>>.unmodifiable(blobs),
      };
      if (utf8.encode(jsonEncode(record)).length > maxBackupImportRowBytes) {
        throw const FormatException('A backup record is too large to stage.');
      }
      _blobIds.addAll(rowBlobIds);
      _media.addAll(rowDescriptors);
      _mediaBytesDeclared += rowMediaBytes;
      return _ParsedImportRow(record, reader.offset, recordBytes);
    } on _NeedMoreArchiveData {
      return null;
    }
  }

  Future<void> _queueRow(Map<String, Object?> record) async {
    final candidate = [..._page, record];
    final candidateBytes = const StandardMethodCodec()
        .encodeMethodCall(
          MethodCall('appendBackupImportPage', {
            'importId': _importId,
            'firstOrdinal': _rowsStaged,
            'records': candidate,
          }),
        )
        .lengthInBytes;
    if (_page.isNotEmpty &&
        (candidate.length > maxBackupImportPageRecords ||
            candidateBytes > maxBackupImportRequestBytes)) {
      await _flushPage();
    }
    final singleRowBytes = const StandardMethodCodec()
        .encodeMethodCall(
          MethodCall('appendBackupImportPage', {
            'importId': _importId,
            'firstOrdinal': _rowsStaged,
            'records': [record],
          }),
        )
        .lengthInBytes;
    if (singleRowBytes > maxBackupImportRequestBytes) {
      throw const FormatException(
        'A backup import row exceeds the page limit.',
      );
    }
    _page.add(record);
    if (_page.length >= maxBackupImportPageRecords) await _flushPage();
  }

  Future<void> _flushPage() async {
    if (_page.isEmpty) return;
    final records = List<Map<String, Object?>>.of(_page);
    await _api.appendPage(
      importId: _importId,
      firstOrdinal: _rowsStaged,
      records: records,
    );
    _rowsStaged += records.length;
    _page.clear();
  }

  Future<void> _writeMediaChunk() async {
    final descriptor = _media[_mediaIndex];
    final remaining = descriptor.sizeBytes - _mediaOffset;
    if (_digestInput == null) {
      _digestOutput = _SingleDigestSink();
      _digestInput = crypto.sha256.startChunkedConversion(_digestOutput!);
    }
    final available = _pending.length - _offset;
    final count = [
      available,
      remaining,
      maxBackupImportBlobChunkBytes,
    ].reduce((left, right) => left < right ? left : right);
    if (count < 1) {
      throw const FormatException('A backup media file is truncated.');
    }
    final bytes = Uint8List.sublistView(_pending, _offset, _offset + count);
    await _api.writeBlob(
      importId: _importId,
      blobId: descriptor.id,
      offset: _mediaOffset,
      bytes: bytes,
    );
    _digestInput!.add(bytes);
    _offset += count;
    _mediaOffset += count;
    _mediaBytesConsumed += count;
    if (_mediaOffset == descriptor.sizeBytes) {
      _digestInput!.close();
      if (!_constantTimeEqual(_digestOutput!.value.bytes, descriptor.sha256)) {
        throw const FormatException('A backup media file failed its checksum.');
      }
      _digestInput = null;
      _digestOutput = null;
      _mediaIndex++;
      _mediaOffset = 0;
    }
  }
}

final class _ArchiveChunkReader {
  _ArchiveChunkReader(this._bytes, this.offset)
    : _data = ByteData.sublistView(_bytes);

  final Uint8List _bytes;
  final ByteData _data;
  int offset;

  int readByte() => readBytes(1)[0];

  int readUint16() {
    _require(2);
    final value = _data.getUint16(offset, Endian.big);
    offset += 2;
    return value;
  }

  int readUint32() {
    _require(4);
    final value = _data.getUint32(offset, Endian.big);
    offset += 4;
    return value;
  }

  String readShortString({required int maximumBytes}) {
    final length = readUint16();
    if (length > maximumBytes) {
      throw const FormatException('A backup string is too large.');
    }
    return utf8.decode(readBytes(length), allowMalformed: false);
  }

  Object? readValue() {
    switch (readByte()) {
      case 0:
        return null;
      case 1:
        _require(8);
        final value = _data.getInt64(offset, Endian.big);
        offset += 8;
        return value;
      case 2:
        _require(8);
        final value = _data.getFloat64(offset, Endian.big);
        offset += 8;
        if (!value.isFinite) {
          throw const FormatException('A backup number is invalid.');
        }
        return value;
      case 3:
        final length = readUint32();
        if (length > BackupArchiveCodec.maxRecordBytes) {
          throw const FormatException('A backup string is too large.');
        }
        return utf8.decode(readBytes(length), allowMalformed: false);
      default:
        throw const FormatException('A backup field type is unsupported.');
    }
  }

  Uint8List readBytes(int length) {
    _require(length);
    final value = Uint8List.sublistView(_bytes, offset, offset + length);
    offset += length;
    return value;
  }

  void ensureRecordWithin(int start, int maximumBytes) {
    if (offset - start > maximumBytes) {
      throw const FormatException('A backup record is too large.');
    }
  }

  void _require(int length) {
    if (length < 0 || length > _bytes.length - offset) {
      throw const _NeedMoreArchiveData();
    }
  }
}

final class _ImportBlobDescriptor {
  const _ImportBlobDescriptor({
    required this.id,
    required this.column,
    required this.sizeBytes,
    required this.sha256,
  });

  final String id;
  final String column;
  final int sizeBytes;
  final Uint8List sha256;
}

final class _ParsedImportRow {
  const _ParsedImportRow(this.record, this.nextOffset, this.recordBytes);

  final Map<String, Object?> record;
  final int nextOffset;
  final int recordBytes;
}

final class _NeedMoreArchiveData implements Exception {
  const _NeedMoreArchiveData();
}

final class _SingleDigestSink implements Sink<crypto.Digest> {
  crypto.Digest? _digest;

  crypto.Digest get value =>
      _digest ?? (throw StateError('The media checksum is incomplete.'));

  @override
  void add(crypto.Digest digest) {
    if (_digest != null) throw StateError('The media checksum closed twice.');
    _digest = digest;
  }

  @override
  void close() {}
}

bool _constantTimeEqual(List<int> left, List<int> right) {
  if (left.length != right.length) return false;
  var difference = 0;
  for (var index = 0; index < left.length; index++) {
    difference |= left[index] ^ right[index];
  }
  return difference == 0;
}
