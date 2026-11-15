import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';

/// A private, transactional destination for one backup stream.
///
/// [write] must keep bytes unpublished and bounded. [commit] makes the complete
/// value available only after the codec calls it. [discard] removes all staged
/// bytes. The codec calls discard on every failure before commit.
abstract interface class BackupTransactionalByteSink {
  Future<void> write(List<int> bytes);

  Future<void> commit();

  Future<void> discard();
}

final class BackupCryptoV2Result {
  BackupCryptoV2Result({
    required this.archiveBytes,
    required this.dataFrameCount,
    required this.envelopeBytes,
    required List<int> archiveSha256,
  }) : archiveSha256 = List<int>.unmodifiable(archiveSha256);

  final int archiveBytes;
  final int dataFrameCount;
  final int envelopeBytes;
  final List<int> archiveSha256;
}

final class BackupCryptoV2CancelledException implements Exception {
  const BackupCryptoV2CancelledException();
}

final class BackupCryptoV2CleanupException implements Exception {
  const BackupCryptoV2CleanupException({
    required this.operationFailure,
    required this.cleanupFailure,
  });

  final Object operationFailure;
  final Object cleanupFailure;
}

/// Streaming CLKROVE2 envelope codec. CLKROVE1 remains in BackupCrypto.
abstract final class BackupCryptoV2 {
  static const int maxArchiveBytes = 64 * 1024 * 1024;
  static const int chunkBytes = 64 * 1024;
  static const int maxDataFrames = 1024;
  static const int headerBytes = 64;
  static const int frameHeaderBytes = 9;
  static const int tagBytes = 16;
  static const int manifestBytes = 48;
  static const int maxTransportChunkBytes = 256 * 1024;
  static const int maxEnvelopeBytes = 67134601;

  static const int _memoryKiB = 19 * 1024;
  static const int _iterations = 2;
  static const int _parallelism = 1;
  static const int _saltBytes = 16;
  static const int _kdfId = 1;
  static const int _cipherId = 1;
  static const int _nonceSchemeId = 1;
  static const int _dataType = 0x01;
  static const int _manifestType = 0x7f;
  static const int _keyBytes = 32;
  static const int _manifestChunkOffset = 4;
  static const int _manifestLengthOffset = 8;
  static const int _manifestDigestOffset = 16;
  static const List<int> _magic = [
    0x43,
    0x4c,
    0x4b,
    0x52,
    0x4f,
    0x56,
    0x45,
    0x32,
  ]; // CLKROVE2
  static const List<int> _manifestMagic = [0x45, 0x4e, 0x44, 0x32]; // END2

  /// Encrypts a bounded stream into a transactional encrypted destination.
  /// Source events must each be no larger than [chunkBytes].
  static Future<BackupCryptoV2Result> encrypt({
    required String password,
    required Stream<List<int>> archive,
    required BackupTransactionalByteSink destination,
    bool Function()? isCancelled,
  }) async {
    _validatePassword(password);
    final reader = _PlaintextFrameReader(archive);
    var committed = false;
    try {
      _checkCancelled(isCancelled);
      final salt = _secureBytes(_saltBytes);
      final header = _createHeader(salt);
      final key = await _deriveKey(password, salt);
      final cipher = AesGcm.with256bits();
      final digest = _Sha256Stream();
      var archiveLength = 0;
      var frameCount = 0;
      var envelopeLength = header.length;
      await destination.write(header);

      while (true) {
        _checkCancelled(isCancelled);
        final plaintext = await reader.nextFrame();
        if (plaintext == null) break;
        if (frameCount >= maxDataFrames ||
            plaintext.isEmpty ||
            plaintext.length > chunkBytes ||
            plaintext.length > maxArchiveBytes - archiveLength) {
          throw const FormatException(
            'The backup is outside the supported size.',
          );
        }

        final sequence = frameCount;
        final frameHeader = _frameHeader(
          type: _dataType,
          sequence: sequence,
          plaintextLength: plaintext.length,
        );
        digest.add(plaintext);
        final box = await cipher.encrypt(
          plaintext,
          secretKey: key,
          nonce: _nonce(_dataType, sequence),
          aad: _associatedData(header, frameHeader),
        );
        await _writeFrame(
          destination,
          frameHeader,
          box.cipherText,
          box.mac.bytes,
        );
        archiveLength += plaintext.length;
        frameCount++;
        envelopeLength += frameHeader.length + box.cipherText.length + tagBytes;
      }

      if (archiveLength == 0 || frameCount == 0) {
        throw const FormatException('A backup archive cannot be empty.');
      }
      _checkCancelled(isCancelled);
      final archiveSha256 = digest.close();
      final manifest = _createManifest(
        frameCount,
        archiveLength,
        archiveSha256,
      );
      final manifestHeader = _frameHeader(
        type: _manifestType,
        sequence: frameCount,
        plaintextLength: manifestBytes,
      );
      final finalBox = await cipher.encrypt(
        manifest,
        secretKey: key,
        nonce: _nonce(_manifestType, frameCount),
        aad: _associatedData(header, manifestHeader),
      );
      await _writeFrame(
        destination,
        manifestHeader,
        finalBox.cipherText,
        finalBox.mac.bytes,
      );
      envelopeLength +=
          manifestHeader.length + finalBox.cipherText.length + tagBytes;
      if (envelopeLength > maxEnvelopeBytes) {
        throw const FormatException('The backup envelope is too large.');
      }
      _checkCancelled(isCancelled);
      await destination.commit();
      committed = true;
      return BackupCryptoV2Result(
        archiveBytes: archiveLength,
        dataFrameCount: frameCount,
        envelopeBytes: envelopeLength,
        archiveSha256: archiveSha256,
      );
    } on Object catch (error, stackTrace) {
      if (!committed) {
        await _discardOrThrow(destination, error);
      }
      Error.throwWithStackTrace(error, stackTrace);
    } finally {
      await reader.cancel();
    }
  }

  /// Authenticates a v2 envelope and stages plaintext privately.
  ///
  /// The destination must not expose staged bytes until [commit]. This method
  /// calls commit only after every frame, the final manifest and EOF validate.
  /// Any failure or cancellation calls [BackupTransactionalByteSink.discard].
  static Future<BackupCryptoV2Result> decrypt({
    required String password,
    required Stream<List<int>> envelope,
    required BackupTransactionalByteSink privateArchive,
    bool Function()? isCancelled,
  }) async {
    _validatePassword(password);
    final reader = _BoundedByteStreamReader(
      envelope,
      maxEventBytes: maxTransportChunkBytes,
    );
    var committed = false;
    try {
      _checkCancelled(isCancelled);
      final header = (await reader.readExactly(headerBytes))!;
      _validateHeader(header);
      final salt = Uint8List.sublistView(header, 40, 56);
      final key = await _deriveKey(password, salt);
      final cipher = AesGcm.with256bits();
      final digest = _Sha256Stream();
      var archiveLength = 0;
      var frameCount = 0;
      var previousDataWasShort = false;

      while (true) {
        _checkCancelled(isCancelled);
        final frameHeader = await reader.readExactly(
          frameHeaderBytes,
          allowEofAtStart: true,
        );
        if (frameHeader == null) throw _invalidEnvelope();
        final frame = ByteData.sublistView(frameHeader);
        final type = frameHeader[0];
        final sequence = frame.getUint32(1, Endian.big);
        final length = frame.getUint32(5, Endian.big);

        if (type == _dataType) {
          if (previousDataWasShort ||
              sequence != frameCount ||
              frameCount >= maxDataFrames ||
              length == 0 ||
              length > chunkBytes ||
              length > maxArchiveBytes - archiveLength) {
            throw _invalidEnvelope();
          }
          final ciphertextAndTag = (await reader.readExactly(
            length + tagBytes,
          ))!;
          final plaintext = await _decryptFrame(
            cipher: cipher,
            key: key,
            type: type,
            sequence: sequence,
            frameHeader: frameHeader,
            header: header,
            ciphertextAndTag: ciphertextAndTag,
            plaintextLength: length,
          );
          digest.add(plaintext);
          await privateArchive.write(plaintext);
          archiveLength += plaintext.length;
          frameCount++;
          previousDataWasShort = length < chunkBytes;
          continue;
        }

        if (type != _manifestType ||
            sequence != frameCount ||
            length != manifestBytes ||
            frameCount == 0) {
          throw _invalidEnvelope();
        }
        final ciphertextAndTag = (await reader.readExactly(
          manifestBytes + tagBytes,
        ))!;
        final manifest = await _decryptFrame(
          cipher: cipher,
          key: key,
          type: type,
          sequence: sequence,
          frameHeader: frameHeader,
          header: header,
          ciphertextAndTag: ciphertextAndTag,
          plaintextLength: manifestBytes,
        );
        final archiveSha256 = digest.close();
        _validateManifest(
          Uint8List.fromList(manifest),
          frameCount: frameCount,
          archiveLength: archiveLength,
          archiveSha256: archiveSha256,
        );
        if (await reader.hasTrailingBytes()) throw _invalidEnvelope();
        _checkCancelled(isCancelled);
        await privateArchive.commit();
        committed = true;
        return BackupCryptoV2Result(
          archiveBytes: archiveLength,
          dataFrameCount: frameCount,
          envelopeBytes: reader.bytesRead,
          archiveSha256: archiveSha256,
        );
      }
    } on Object catch (error, stackTrace) {
      if (!committed) {
        await _discardOrThrow(privateArchive, error);
      }
      if (error is _BackupEnvelopeInvalid) {
        throw const FormatException(
          'The backup could not be unlocked. Check the password and file.',
        );
      }
      Error.throwWithStackTrace(error, stackTrace);
    } finally {
      await reader.cancel();
    }
  }

  static Future<SecretKey> _deriveKey(String password, List<int> salt) async =>
      Argon2id(
        memory: _memoryKiB,
        parallelism: _parallelism,
        iterations: _iterations,
        hashLength: _keyBytes,
      ).deriveKeyFromPassword(password: password, nonce: salt);

  static Uint8List _createHeader(Uint8List salt) {
    final result = Uint8List(headerBytes);
    result.setRange(0, _magic.length, _magic);
    final fields = ByteData.sublistView(result);
    fields.setUint16(8, 2, Endian.big);
    fields.setUint16(10, headerBytes, Endian.big);
    result[12] = _kdfId;
    result[13] = _cipherId;
    result[14] = _nonceSchemeId;
    fields.setUint32(16, _memoryKiB, Endian.big);
    fields.setUint32(20, _iterations, Endian.big);
    result[24] = _parallelism;
    result[25] = _saltBytes;
    result[26] = tagBytes;
    fields.setUint32(28, chunkBytes, Endian.big);
    fields.setUint32(32, maxArchiveBytes, Endian.big);
    fields.setUint32(36, maxDataFrames, Endian.big);
    result.setRange(40, 56, salt);
    return result;
  }

  static void _validateHeader(Uint8List header) {
    if (header.length != headerBytes) {
      throw const FormatException('The backup header has an invalid size.');
    }
    for (var index = 0; index < _magic.length; index++) {
      if (header[index] != _magic[index]) {
        throw const FormatException('This is not a Clockrove v2 backup.');
      }
    }
    final fields = ByteData.sublistView(header);
    if (fields.getUint16(8, Endian.big) != 2 ||
        fields.getUint16(10, Endian.big) != headerBytes ||
        header[12] != _kdfId ||
        header[13] != _cipherId ||
        header[14] != _nonceSchemeId ||
        header[15] != 0 ||
        fields.getUint32(16, Endian.big) != _memoryKiB ||
        fields.getUint32(20, Endian.big) != _iterations ||
        header[24] != _parallelism ||
        header[25] != _saltBytes ||
        header[26] != tagBytes ||
        header[27] != 0 ||
        fields.getUint32(28, Endian.big) != chunkBytes ||
        fields.getUint32(32, Endian.big) != maxArchiveBytes ||
        fields.getUint32(36, Endian.big) != maxDataFrames ||
        header.sublist(56).any((byte) => byte != 0)) {
      throw const FormatException(
        'This backup uses unsupported or unsafe encryption settings.',
      );
    }
  }

  static Uint8List _frameHeader({
    required int type,
    required int sequence,
    required int plaintextLength,
  }) {
    final result = Uint8List(frameHeaderBytes);
    result[0] = type;
    final fields = ByteData.sublistView(result);
    fields.setUint32(1, sequence, Endian.big);
    fields.setUint32(5, plaintextLength, Endian.big);
    return result;
  }

  static Uint8List _nonce(int type, int sequence) {
    final result = Uint8List(12);
    final fields = ByteData.sublistView(result);
    fields.setUint32(0, type, Endian.big);
    fields.setUint64(4, sequence, Endian.big);
    return result;
  }

  static Uint8List _associatedData(Uint8List header, Uint8List frameHeader) {
    final result = Uint8List(header.length + frameHeader.length);
    result.setRange(0, header.length, header);
    result.setRange(header.length, result.length, frameHeader);
    return result;
  }

  static Future<void> _writeFrame(
    BackupTransactionalByteSink destination,
    Uint8List frameHeader,
    List<int> ciphertext,
    List<int> tag,
  ) async {
    if (tag.length != tagBytes) {
      throw StateError('AES-GCM returned an unexpected tag length.');
    }
    await destination.write(frameHeader);
    await destination.write(ciphertext);
    await destination.write(tag);
  }

  static Uint8List _createManifest(
    int frameCount,
    int archiveLength,
    List<int> digest,
  ) {
    final result = Uint8List(manifestBytes);
    result.setRange(0, _manifestMagic.length, _manifestMagic);
    final fields = ByteData.sublistView(result);
    fields.setUint32(_manifestChunkOffset, frameCount, Endian.big);
    fields.setUint64(_manifestLengthOffset, archiveLength, Endian.big);
    result.setRange(_manifestDigestOffset, result.length, digest);
    return result;
  }

  static void _validateManifest(
    Uint8List manifest, {
    required int frameCount,
    required int archiveLength,
    required List<int> archiveSha256,
  }) {
    for (var index = 0; index < _manifestMagic.length; index++) {
      if (manifest[index] != _manifestMagic[index]) throw _invalidEnvelope();
    }
    final fields = ByteData.sublistView(manifest);
    if (fields.getUint32(_manifestChunkOffset, Endian.big) != frameCount ||
        fields.getUint64(_manifestLengthOffset, Endian.big) != archiveLength ||
        !_constantTimeEquals(
          manifest.sublist(_manifestDigestOffset),
          archiveSha256,
        )) {
      throw _invalidEnvelope();
    }
  }

  static Future<List<int>> _decryptFrame({
    required AesGcm cipher,
    required SecretKey key,
    required int type,
    required int sequence,
    required Uint8List frameHeader,
    required Uint8List header,
    required Uint8List ciphertextAndTag,
    required int plaintextLength,
  }) async {
    final ciphertext = Uint8List.sublistView(
      ciphertextAndTag,
      0,
      plaintextLength,
    );
    final tag = Uint8List.sublistView(
      ciphertextAndTag,
      plaintextLength,
      plaintextLength + tagBytes,
    );
    try {
      final plaintext = await cipher.decrypt(
        SecretBox(ciphertext, nonce: _nonce(type, sequence), mac: Mac(tag)),
        secretKey: key,
        aad: _associatedData(header, frameHeader),
      );
      if (plaintext.length != plaintextLength) throw _invalidEnvelope();
      return plaintext;
    } on _BackupEnvelopeInvalid {
      rethrow;
    } on Object {
      throw _invalidEnvelope();
    }
  }

  static Uint8List _secureBytes(int length) {
    final random = Random.secure();
    return Uint8List.fromList(
      List<int>.generate(length, (_) => random.nextInt(256), growable: false),
    );
  }

  static void _validatePassword(String password) {
    final length = utf8.encode(password).length;
    if (length < 12 || length > 1024) {
      throw const FormatException(
        'Use a backup password between 12 and 1024 UTF-8 bytes.',
      );
    }
  }

  static void _checkCancelled(bool Function()? isCancelled) {
    if (isCancelled?.call() ?? false) {
      throw const BackupCryptoV2CancelledException();
    }
  }

  static _BackupEnvelopeInvalid _invalidEnvelope() =>
      const _BackupEnvelopeInvalid();

  static bool _constantTimeEquals(List<int> left, List<int> right) {
    if (left.length != right.length) return false;
    var difference = 0;
    for (var index = 0; index < left.length; index++) {
      difference |= left[index] ^ right[index];
    }
    return difference == 0;
  }

  static Future<void> _discardOrThrow(
    BackupTransactionalByteSink sink,
    Object operationFailure,
  ) async {
    try {
      await sink.discard();
    } on Object catch (cleanupFailure) {
      throw BackupCryptoV2CleanupException(
        operationFailure: operationFailure,
        cleanupFailure: cleanupFailure,
      );
    }
  }
}

final class _BackupEnvelopeInvalid implements Exception {
  const _BackupEnvelopeInvalid();
}

final class _PlaintextFrameReader {
  _PlaintextFrameReader(Stream<List<int>> source)
    : _iterator = StreamIterator<List<int>>(source);

  final StreamIterator<List<int>> _iterator;
  List<int>? _current;
  int _currentOffset = 0;
  bool _ended = false;

  Future<Uint8List?> nextFrame() async {
    if (_ended) return null;
    final frame = Uint8List(BackupCryptoV2.chunkBytes);
    var frameLength = 0;
    while (frameLength < frame.length) {
      if (_current == null || _currentOffset >= _current!.length) {
        if (!await _iterator.moveNext()) {
          _ended = true;
          break;
        }
        _current = _iterator.current;
        _currentOffset = 0;
        if (_current!.length > BackupCryptoV2.chunkBytes) {
          throw const FormatException(
            'The backup source yielded an oversized chunk.',
          );
        }
        if (_current!.isEmpty) continue;
      }
      final available = _current!.length - _currentOffset;
      final count = min(frame.length - frameLength, available);
      frame.setRange(
        frameLength,
        frameLength + count,
        _current!,
        _currentOffset,
      );
      frameLength += count;
      _currentOffset += count;
    }
    if (frameLength == 0) return null;
    return Uint8List.sublistView(frame, 0, frameLength);
  }

  Future<void> cancel() async {
    try {
      await _iterator.cancel();
    } on Object {
      // Preserve the export failure; the destination is already discarded.
    }
  }
}

final class _BoundedByteStreamReader {
  _BoundedByteStreamReader(
    Stream<List<int>> source, {
    required this.maxEventBytes,
  }) : _iterator = StreamIterator<List<int>>(source);

  final int maxEventBytes;
  final StreamIterator<List<int>> _iterator;
  List<int>? _current;
  int _currentOffset = 0;
  int bytesRead = 0;
  bool _ended = false;

  Future<Uint8List?> readExactly(
    int length, {
    bool allowEofAtStart = false,
  }) async {
    final result = Uint8List(length);
    var offset = 0;
    while (offset < length) {
      if (_current == null || _currentOffset >= _current!.length) {
        if (!await _readNext()) {
          if (allowEofAtStart && offset == 0) {
            return null;
          }
          throw BackupCryptoV2._invalidEnvelope();
        }
        if (_current!.isEmpty) continue;
      }
      final available = _current!.length - _currentOffset;
      final count = min(length - offset, available);
      if (count > BackupCryptoV2.maxEnvelopeBytes - bytesRead) {
        throw BackupCryptoV2._invalidEnvelope();
      }
      result.setRange(offset, offset + count, _current!, _currentOffset);
      offset += count;
      _currentOffset += count;
      bytesRead += count;
    }
    return result;
  }

  Future<bool> hasTrailingBytes() async {
    if (_current != null && _currentOffset < _current!.length) return true;
    while (await _readNext()) {
      if (_current!.isNotEmpty) return true;
    }
    return false;
  }

  Future<bool> _readNext() async {
    if (_ended || !await _iterator.moveNext()) {
      _ended = true;
      return false;
    }
    _current = _iterator.current;
    _currentOffset = 0;
    if (_current!.length > maxEventBytes) {
      throw BackupCryptoV2._invalidEnvelope();
    }
    return true;
  }

  Future<void> cancel() async {
    try {
      await _iterator.cancel();
    } on Object {
      // Preserve the import failure; its private destination is discarded.
    }
  }
}

final class _Sha256Stream {
  final _DigestSink _output = _DigestSink();
  late final ByteConversionSink _input = sha256.startChunkedConversion(_output);
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

final class _DigestSink implements Sink<Digest> {
  Digest? _digest;

  Digest get value => _digest ?? (throw StateError('SHA-256 is incomplete.'));

  @override
  void add(Digest value) {
    if (_digest != null) throw StateError('SHA-256 completed more than once.');
    _digest = value;
  }

  @override
  void close() {}
}
