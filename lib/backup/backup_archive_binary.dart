part of 'backup_archive.dart';

final class _ArchiveReader {
  _ArchiveReader(this.bytes) : _data = ByteData.sublistView(bytes);

  final Uint8List bytes;
  final ByteData _data;
  int offset = 0;
  int? _recordLimit;
  bool _recordLimitIsHard = false;

  bool get isAtEnd => offset == bytes.length;

  void beginRecord(int start, int maximumBytes) {
    if (start != offset || _recordLimit != null) {
      throw StateError('Backup archive record reader is out of sequence.');
    }
    final candidate = start + maximumBytes;
    _recordLimitIsHard = candidate < bytes.length;
    _recordLimit = _recordLimitIsHard ? candidate : bytes.length;
  }

  void endRecord() {
    _recordLimit = null;
    _recordLimitIsHard = false;
  }

  int readByte() => readBytes(1)[0];

  int readUint16() {
    _require(2);
    final result = _data.getUint16(offset, Endian.big);
    offset += 2;
    return result;
  }

  int readUint32() {
    _require(4);
    final result = _data.getUint32(offset, Endian.big);
    offset += 4;
    return result;
  }

  String readShortString({
    int maximumBytes = BackupArchiveCodec.maxRecordBytes,
  }) {
    final length = readUint16();
    if (length > maximumBytes) {
      throw const FormatException('A backup record is too large.');
    }
    return utf8.decode(readBytes(length), allowMalformed: false);
  }

  Object? readValue({
    int maximumStringBytes = BackupArchiveCodec.maxRecordBytes,
  }) {
    switch (readByte()) {
      case 0:
        return null;
      case 1:
        _require(8);
        final result = _data.getInt64(offset, Endian.big);
        offset += 8;
        return result;
      case 2:
        _require(8);
        final result = _data.getFloat64(offset, Endian.big);
        offset += 8;
        if (!result.isFinite) {
          throw const FormatException(
            'A backup record contains an invalid number.',
          );
        }
        return result;
      case 3:
        final length = readUint32();
        if (length > maximumStringBytes) {
          throw const FormatException('A backup record is too large.');
        }
        return utf8.decode(readBytes(length), allowMalformed: false);
      default:
        throw const FormatException(
          'A backup record has an unknown value type.',
        );
    }
  }

  Uint8List readBytes(int length) {
    if (length < 0) {
      throw const FormatException('A backup field has an invalid length.');
    }
    _require(length);
    final start = offset;
    offset += length;
    return Uint8List.sublistView(bytes, start, offset);
  }

  void ensureWithin(int start, int maximum) {
    if (offset - start > maximum) {
      throw const FormatException('A backup record is too large.');
    }
  }

  void _require(int length) {
    if (_recordLimitIsHard && length > _recordLimit! - offset) {
      throw const FormatException('A backup record is too large.');
    }
    if (length > bytes.length - offset) {
      throw const FormatException('This backup is truncated.');
    }
  }
}

final class _ArchiveWriter {
  _ArchiveWriter(this.bytes, this.offset) : _data = ByteData.sublistView(bytes);

  final Uint8List bytes;
  final ByteData _data;
  int offset;

  void writeByte(int value) => bytes[offset++] = value;

  void writeUint16(int value) {
    _data.setUint16(offset, value, Endian.big);
    offset += 2;
  }

  void writeUint32(int value) {
    _data.setUint32(offset, value, Endian.big);
    offset += 4;
  }

  void writeShortString(String value) {
    final encoded = utf8.encode(value);
    writeUint16(encoded.length);
    writeBytes(encoded);
  }

  void writeValue(Object? value) {
    if (value == null) {
      writeByte(0);
    } else if (value is int) {
      writeByte(1);
      _data.setInt64(offset, value, Endian.big);
      offset += 8;
    } else if (value is double) {
      writeByte(2);
      _data.setFloat64(offset, value, Endian.big);
      offset += 8;
    } else if (value is String) {
      final encoded = utf8.encode(value);
      writeByte(3);
      writeUint32(encoded.length);
      writeBytes(encoded);
    } else {
      throw const FormatException(
        'A backup record contains an unsupported value.',
      );
    }
  }

  void writeBytes(List<int> value) {
    bytes.setRange(offset, offset + value.length, value);
    offset += value.length;
  }
}

final class _ArchiveChunkBuffer {
  static const int _chunkBytes = 64 * 1024;

  BytesBuilder _pending = BytesBuilder(copy: false);
  int _pendingLength = 0;

  Iterable<Uint8List> add(List<int> bytes) sync* {
    var offset = 0;
    while (offset < bytes.length) {
      if (_pendingLength == 0 &&
          bytes is Uint8List &&
          bytes.length - offset >= _chunkBytes) {
        final frameCount = (bytes.length - offset) ~/ _chunkBytes;
        for (var frame = 0; frame < frameCount; frame++) {
          final start = offset;
          offset += _chunkBytes;
          yield Uint8List.sublistView(bytes, start, offset);
        }
        continue;
      }
      final available = _chunkBytes - _pendingLength;
      final remaining = bytes.length - offset;
      final count = available < remaining ? available : remaining;
      final chunk = Uint8List(count);
      chunk.setRange(0, count, bytes, offset);
      _pending.add(chunk);
      _pendingLength += count;
      offset += count;
      if (_pendingLength == _chunkBytes) {
        yield _pending.takeBytes();
        _pending = BytesBuilder(copy: false);
        _pendingLength = 0;
      }
    }
  }

  Iterable<Uint8List> flush() sync* {
    if (_pendingLength == 0) return;
    yield _pending.takeBytes();
    _pending = BytesBuilder(copy: false);
    _pendingLength = 0;
  }
}

final class _ArchiveSha256Stream {
  final _ArchiveDigestSink _output = _ArchiveDigestSink();
  late final ByteConversionSink _input = crypto.sha256.startChunkedConversion(
    _output,
  );
  bool _closed = false;

  void add(List<int> bytes) {
    if (_closed) throw StateError('SHA-256 stream is already closed.');
    _input.add(bytes);
  }

  List<int> close() {
    if (_closed) throw StateError('SHA-256 stream is already closed.');
    _closed = true;
    _input.close();
    return List<int>.unmodifiable(_output.value.bytes);
  }
}

final class _ArchiveDigestSink implements Sink<crypto.Digest> {
  crypto.Digest? _digest;

  crypto.Digest get value =>
      _digest ?? (throw StateError('SHA-256 is incomplete.'));

  @override
  void add(crypto.Digest value) {
    if (_digest != null) throw StateError('SHA-256 completed more than once.');
    _digest = value;
  }

  @override
  void close() {}
}
