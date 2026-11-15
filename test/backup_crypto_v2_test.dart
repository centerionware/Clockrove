import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:clockrove/backup/backup_crypto.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'computes exact bounded envelope lengths before destination creation',
    () {
      expect(BackupCryptoV2.envelopeLengthForArchiveBytes(1), 163);
      expect(
        BackupCryptoV2.envelopeLengthForArchiveBytes(BackupCryptoV2.chunkBytes),
        65698,
      );
      expect(
        BackupCryptoV2.envelopeLengthForArchiveBytes(
          BackupCryptoV2.chunkBytes + 1,
        ),
        65724,
      );
      expect(
        BackupCryptoV2.envelopeLengthForArchiveBytes(
          BackupCryptoV2.maxArchiveBytes,
        ),
        BackupCryptoV2.maxEnvelopeBytes,
      );
      expect(
        () => BackupCryptoV2.envelopeLengthForArchiveBytes(
          BackupCryptoV2.maxArchiveBytes + 1,
        ),
        throwsFormatException,
      );
    },
  );

  test('v2 streams split input into authenticated ordered frames', () async {
    final archive = _pattern(BackupCryptoV2.chunkBytes + 1);
    final encrypted = _MemorySink();
    final encryptedResult = await BackupCryptoV2.encrypt(
      password: _password,
      archive: _chunks(archive, 997),
      destination: encrypted,
    );

    expect(encrypted.committed, isTrue);
    expect(encrypted.discarded, isFalse);
    expect(encryptedResult.archiveBytes, archive.length);
    expect(encryptedResult.dataFrameCount, 2);
    expect(encryptedResult.envelopeBytes, encrypted.bytes.length);
    expect(encrypted.bytes.sublist(0, 8), [
      0x43,
      0x4c,
      0x4b,
      0x52,
      0x4f,
      0x56,
      0x45,
      0x32,
    ]);
    expect(encryptedResult.archiveSha256, sha256.convert(archive).bytes);

    final staged = _MemorySink();
    final decryptedResult = await BackupCryptoV2.decrypt(
      password: _password,
      envelope: _chunks(encrypted.bytes, 4093),
      privateArchive: staged,
    );

    expect(staged.committed, isTrue);
    expect(staged.discarded, isFalse);
    expect(staged.visibleBytes, orderedEquals(archive));
    expect(decryptedResult.archiveBytes, archive.length);
    expect(decryptedResult.dataFrameCount, 2);
    expect(decryptedResult.envelopeBytes, encrypted.bytes.length);
    expect(decryptedResult.archiveSha256, encryptedResult.archiveSha256);
  });

  test('v2 round-trips a one-byte archive and one full frame', () async {
    for (final length in [1, BackupCryptoV2.chunkBytes]) {
      final archive = _pattern(length);
      final encrypted = await _encrypt(archive);
      final staged = _MemorySink();
      final result = await BackupCryptoV2.decrypt(
        password: _password,
        envelope: _chunks(encrypted, 8192),
        privateArchive: staged,
      );

      expect(result.archiveBytes, length);
      expect(result.dataFrameCount, 1);
      expect(staged.visibleBytes, orderedEquals(archive));
    }
  });

  test(
    'v2 rejects v1 envelopes without falling back to the v1 reader',
    () async {
      final v1 = await BackupCrypto.encrypt(
        password: _password,
        archive: Uint8List.fromList([1, 2, 3]),
      );
      final staged = _MemorySink();

      await expectLater(
        BackupCryptoV2.decrypt(
          password: _password,
          envelope: Stream.value(v1),
          privateArchive: staged,
        ),
        throwsA(isA<FormatException>()),
      );
      expect(staged.committed, isFalse);
      expect(staged.discarded, isTrue);
      expect(staged.visibleBytes, isNull);
    },
  );

  test('unsupported KDF parameters fail before password derivation', () async {
    final encrypted = await _encrypt(_pattern(1));
    final header = Uint8List.fromList(encrypted);
    ByteData.sublistView(header).setUint32(16, 19 * 1024 + 1, Endian.big);
    final staged = _MemorySink();

    await expectLater(
      BackupCryptoV2.decrypt(
        password: _password,
        envelope: Stream.value(header),
        privateArchive: staged,
      ),
      throwsA(
        isA<FormatException>().having(
          (error) => error.message,
          'message',
          contains('unsupported or unsafe'),
        ),
      ),
    );
    expect(staged.committed, isFalse);
    expect(staged.discarded, isTrue);
  });

  test(
    'wrong password and frame tampering never commit staged plaintext',
    () async {
      final encrypted = await _encrypt(_pattern(777));
      final badTag = Uint8List.fromList(encrypted);
      badTag[BackupCryptoV2.headerBytes + BackupCryptoV2.frameHeaderBytes] ^= 1;
      final badFinalTag = Uint8List.fromList(encrypted);
      badFinalTag[badFinalTag.length - 1] ^= 1;

      for (final damaged in [encrypted, badTag, badFinalTag]) {
        final staged = _MemorySink();
        final password = identical(damaged, encrypted)
            ? 'wrong-password-123'
            : _password;
        await expectLater(
          BackupCryptoV2.decrypt(
            password: password,
            envelope: _chunks(damaged, 401),
            privateArchive: staged,
          ),
          throwsA(
            isA<FormatException>().having(
              (error) => error.message,
              'message',
              'The backup could not be unlocked. Check the password and file.',
            ),
          ),
        );
        expect(staged.committed, isFalse);
        expect(staged.discarded, isTrue);
        expect(staged.visibleBytes, isNull);
      }
    },
  );

  test(
    'missing manifest, duplicate/reordered data frames and trailing bytes fail',
    () async {
      final encrypted = await _encrypt(_pattern(BackupCryptoV2.chunkBytes + 1));
      final frames = _splitFrames(encrypted);
      expect(frames, hasLength(3));
      final missingManifest = encrypted.sublist(0, frames.last.$1);
      final reordered = Uint8List.fromList([
        ...encrypted.sublist(0, BackupCryptoV2.headerBytes),
        ...encrypted.sublist(frames[1].$1, frames[1].$2),
        ...encrypted.sublist(frames[0].$1, frames[0].$2),
        ...encrypted.sublist(frames[2].$1, frames[2].$2),
      ]);
      final duplicateDataFrame = Uint8List.fromList([
        ...encrypted.sublist(0, frames[0].$2),
        ...encrypted.sublist(frames[0].$1, frames[0].$2),
        ...encrypted.sublist(frames[0].$2),
      ]);
      final trailing = Uint8List.fromList([...encrypted, 0x42]);

      for (final damaged in [
        missingManifest,
        reordered,
        duplicateDataFrame,
        trailing,
      ]) {
        final staged = _MemorySink();
        await expectLater(
          BackupCryptoV2.decrypt(
            password: _password,
            envelope: _chunks(damaged, 8191),
            privateArchive: staged,
          ),
          throwsA(isA<FormatException>()),
        );
        expect(staged.committed, isFalse);
        expect(staged.discarded, isTrue);
        expect(staged.visibleBytes, isNull);
      }
    },
  );

  test(
    'truncated frame headers, payloads, tags and manifest are rejected',
    () async {
      final encrypted = await _encrypt(_pattern(BackupCryptoV2.chunkBytes + 1));
      final frames = _splitFrames(encrypted);
      expect(frames, hasLength(3));

      final truncatedFrameHeader = encrypted.sublist(
        0,
        frames[1].$1 + BackupCryptoV2.frameHeaderBytes - 1,
      );
      final truncatedFramePayload = encrypted.sublist(
        0,
        frames[0].$1 +
            BackupCryptoV2.frameHeaderBytes +
            BackupCryptoV2.chunkBytes -
            1,
      );
      final truncatedFrameTag = encrypted.sublist(0, frames[0].$2 - 1);
      final manifestStart = frames.last.$1;
      final truncatedManifestPayload = encrypted.sublist(
        0,
        manifestStart +
            BackupCryptoV2.frameHeaderBytes +
            BackupCryptoV2.manifestBytes -
            1,
      );
      final truncatedManifestTag = encrypted.sublist(0, encrypted.length - 1);

      for (final damaged in [
        truncatedFrameHeader,
        truncatedFramePayload,
        truncatedFrameTag,
        truncatedManifestPayload,
        truncatedManifestTag,
      ]) {
        await _expectRejectedEnvelope(damaged);
      }
    },
  );

  test(
    'unsupported frame types are rejected without exposing staged bytes',
    () async {
      final encrypted = await _encrypt(_pattern(1));
      final unsupportedType = Uint8List.fromList(encrypted);
      unsupportedType[BackupCryptoV2.headerBytes] = 0x02;

      await _expectRejectedEnvelope(unsupportedType);
    },
  );

  test(
    'authenticated manifests with mismatched count, length or digest fail',
    () async {
      final archive = _pattern(1);
      for (final fieldOffset in [7, 15, 16]) {
        final damaged = await _encryptWithInvalidManifest(
          archive,
          fieldOffset: fieldOffset,
        );
        await _expectRejectedEnvelope(damaged);
      }
    },
  );

  test(
    'cancellation during final manifest write discards instead of committing',
    () async {
      final manifestWriteStarted = Completer<void>();
      final releaseManifestWrite = Completer<void>();
      var cancelled = false;
      final destination = _MemorySink(
        beforeWrite: (writeNumber) async {
          // Header is write 1, data frame writes are 2-4, and manifest writes are 5-7.
          if (writeNumber == 7) {
            manifestWriteStarted.complete();
            await releaseManifestWrite.future;
          }
        },
      );
      final encryption = BackupCryptoV2.encrypt(
        password: _password,
        archive: Stream.value([1]),
        destination: destination,
        isCancelled: () => cancelled,
      );
      final encryptionExpectation = expectLater(
        encryption,
        throwsA(isA<BackupCryptoV2CancelledException>()),
      );

      try {
        await manifestWriteStarted.future.timeout(const Duration(seconds: 30));
        cancelled = true;
        releaseManifestWrite.complete();
      } finally {
        cancelled = true;
        if (!releaseManifestWrite.isCompleted) {
          releaseManifestWrite.complete();
        }
      }
      await encryptionExpectation.timeout(const Duration(seconds: 30));
      expect(destination.committed, isFalse);
      expect(destination.discarded, isTrue);
      expect(destination.visibleBytes, isNull);
    },
  );

  test('token cancels a stalled encrypt read before sink discard', () async {
    final sourceListening = Completer<void>();
    final sourceCancellationStarted = Completer<void>();
    final finishSourceCancellation = Completer<void>();
    var sourceCancelled = false;
    final archive = StreamController<List<int>>(
      onListen: () => sourceListening.complete(),
      onCancel: () async {
        sourceCancellationStarted.complete();
        await finishSourceCancellation.future;
        sourceCancelled = true;
      },
    );
    final token = BackupCryptoCancellationToken();
    var sourceCancelledBeforeDiscard = false;
    final destination = _MemorySink(
      onDiscard: () => sourceCancelledBeforeDiscard = sourceCancelled,
    );
    final encryption = BackupCryptoV2.encrypt(
      password: _password,
      archive: archive.stream,
      destination: destination,
      cancellationToken: token,
    );
    final encryptionExpectation = expectLater(
      encryption,
      throwsA(isA<BackupCryptoV2CancelledException>()),
    );

    try {
      await _waitForTestSignal(sourceListening.future, 'source subscription');
      expect(destination.bytes, isNotEmpty);
      token.cancel();
      await _waitForTestSignal(
        sourceCancellationStarted.future,
        'upstream cancellation start',
      );
      expect(destination.discarded, isFalse);
      expect(destination.committed, isFalse);
      expect(destination.visibleBytes, isNull);
      finishSourceCancellation.complete();
      await _waitForTestSignal(
        encryptionExpectation,
        'encryption cancellation completion',
      );
    } finally {
      token.cancel();
      if (!finishSourceCancellation.isCompleted) {
        finishSourceCancellation.complete();
      }
      try {
        if (archive.hasListener && !archive.isClosed) {
          await _waitForTestSignal(archive.close(), 'source close');
        }
      } finally {
        await _waitForTestSignal(
          encryptionExpectation,
          'encryption future cleanup',
        );
      }
    }

    expect(sourceCancelled, isTrue);
    expect(sourceCancelledBeforeDiscard, isTrue);
    expect(destination.discarded, isTrue);
    expect(destination.committed, isFalse);
    expect(destination.visibleBytes, isNull);
  });

  test('token cancels decrypt while waiting for post-manifest EOF', () async {
    final archive = _pattern(3);
    final envelope = await _encrypt(archive);
    final waitingForEof = Completer<void>();
    var sourceCancelled = false;
    final envelopeSource = StreamController<List<int>>(
      onResume: () {
        if (!waitingForEof.isCompleted) waitingForEof.complete();
      },
      onCancel: () {
        sourceCancelled = true;
      },
    );
    final token = BackupCryptoCancellationToken();
    var sourceCancelledBeforeDiscard = false;
    final staged = _MemorySink(
      onDiscard: () => sourceCancelledBeforeDiscard = sourceCancelled,
    );
    final decryption = BackupCryptoV2.decrypt(
      password: _password,
      envelope: envelopeSource.stream,
      privateArchive: staged,
      cancellationToken: token,
    );
    final decryptionExpectation = expectLater(
      decryption,
      throwsA(isA<BackupCryptoV2CancelledException>()),
    );

    try {
      envelopeSource.add(envelope);
      await waitingForEof.future.timeout(const Duration(seconds: 30));
      expect(staged.bytes, orderedEquals(archive));
      token.cancel();
      await decryptionExpectation.timeout(const Duration(seconds: 30));
    } finally {
      token.cancel();
      await envelopeSource.close().timeout(const Duration(seconds: 30));
    }

    expect(sourceCancelled, isTrue);
    expect(sourceCancelledBeforeDiscard, isTrue);
    expect(staged.discarded, isTrue);
    expect(staged.committed, isFalse);
    expect(staged.visibleBytes, isNull);
  });

  test('cancellation and destination errors discard staged output', () async {
    final cancelled = _MemorySink();
    await expectLater(
      BackupCryptoV2.encrypt(
        password: _password,
        archive: Stream.value([1]),
        destination: cancelled,
        isCancelled: () => true,
      ),
      throwsA(isA<BackupCryptoV2CancelledException>()),
    );
    expect(cancelled.committed, isFalse);
    expect(cancelled.discarded, isTrue);

    final failed = _MemorySink(failOnWrite: 2);
    await expectLater(
      BackupCryptoV2.encrypt(
        password: _password,
        archive: Stream.value([1, 2, 3]),
        destination: failed,
      ),
      throwsA(isA<StateError>()),
    );
    expect(failed.committed, isFalse);
    expect(failed.discarded, isTrue);
  });
}

Future<T> _waitForTestSignal<T>(Future<T> signal, String description) async {
  try {
    return await signal.timeout(const Duration(seconds: 5));
  } on TimeoutException {
    throw TimeoutException('Timed out waiting for $description.');
  }
}

Future<void> _expectRejectedEnvelope(Uint8List envelope) async {
  final staged = _MemorySink();
  await expectLater(
    BackupCryptoV2.decrypt(
      password: _password,
      envelope: _chunks(envelope, 8191),
      privateArchive: staged,
    ),
    throwsA(isA<FormatException>()),
  );
  expect(staged.committed, isFalse);
  expect(staged.discarded, isTrue);
  expect(staged.visibleBytes, isNull);
}

const _password = 'synthetic-v2-backup-password';

Future<Uint8List> _encrypt(Uint8List archive) async {
  final sink = _MemorySink();
  await BackupCryptoV2.encrypt(
    password: _password,
    archive: _chunks(archive, BackupCryptoV2.chunkBytes),
    destination: sink,
  );
  return sink.bytes;
}

Future<Uint8List> _encryptWithInvalidManifest(
  Uint8List archive, {
  required int fieldOffset,
}) async {
  final random = Random.secure();
  final salt = Uint8List.fromList(
    List<int>.generate(16, (_) => random.nextInt(256)),
  );
  final header = Uint8List(BackupCryptoV2.headerBytes);
  header.setRange(0, 8, ascii.encode('CLKROVE2'));
  final headerFields = ByteData.sublistView(header);
  headerFields.setUint16(8, 2, Endian.big);
  headerFields.setUint16(10, BackupCryptoV2.headerBytes, Endian.big);
  header[12] = 1;
  header[13] = 1;
  header[14] = 1;
  headerFields.setUint32(16, 19 * 1024, Endian.big);
  headerFields.setUint32(20, 2, Endian.big);
  header[24] = 1;
  header[25] = 16;
  header[26] = BackupCryptoV2.tagBytes;
  headerFields.setUint32(28, BackupCryptoV2.chunkBytes, Endian.big);
  headerFields.setUint32(32, BackupCryptoV2.maxArchiveBytes, Endian.big);
  headerFields.setUint32(36, BackupCryptoV2.maxDataFrames, Endian.big);
  header.setRange(40, 56, salt);

  final key = await Argon2id(
    memory: 19 * 1024,
    parallelism: 1,
    iterations: 2,
    hashLength: 32,
  ).deriveKeyFromPassword(password: _password, nonce: salt);
  final cipher = AesGcm.with256bits();
  final output = BytesBuilder(copy: false)..add(header);
  var frameCount = 0;
  for (
    var offset = 0;
    offset < archive.length;
    offset += BackupCryptoV2.chunkBytes
  ) {
    final end = min(offset + BackupCryptoV2.chunkBytes, archive.length);
    final frameHeader = _testFrameHeader(
      type: 0x01,
      sequence: frameCount,
      length: end - offset,
    );
    final box = await cipher.encrypt(
      Uint8List.sublistView(archive, offset, end),
      secretKey: key,
      nonce: _testNonce(0x01, frameCount),
      aad: _testAssociatedData(header, frameHeader),
    );
    output
      ..add(frameHeader)
      ..add(box.cipherText)
      ..add(box.mac.bytes);
    frameCount++;
  }

  final manifest = Uint8List(BackupCryptoV2.manifestBytes);
  manifest.setRange(0, 4, ascii.encode('END2'));
  final manifestFields = ByteData.sublistView(manifest);
  manifestFields.setUint32(4, frameCount, Endian.big);
  manifestFields.setUint64(8, archive.length, Endian.big);
  manifest.setRange(16, manifest.length, sha256.convert(archive).bytes);
  manifest[fieldOffset] ^= 1;
  final manifestHeader = _testFrameHeader(
    type: 0x7f,
    sequence: frameCount,
    length: manifest.length,
  );
  final manifestBox = await cipher.encrypt(
    manifest,
    secretKey: key,
    nonce: _testNonce(0x7f, frameCount),
    aad: _testAssociatedData(header, manifestHeader),
  );
  output
    ..add(manifestHeader)
    ..add(manifestBox.cipherText)
    ..add(manifestBox.mac.bytes);
  return output.takeBytes();
}

Uint8List _testFrameHeader({
  required int type,
  required int sequence,
  required int length,
}) {
  final header = Uint8List(BackupCryptoV2.frameHeaderBytes);
  header[0] = type;
  final fields = ByteData.sublistView(header);
  fields.setUint32(1, sequence, Endian.big);
  fields.setUint32(5, length, Endian.big);
  return header;
}

Uint8List _testNonce(int type, int sequence) {
  final nonce = Uint8List(12);
  final fields = ByteData.sublistView(nonce);
  fields.setUint32(0, type, Endian.big);
  fields.setUint64(4, sequence, Endian.big);
  return nonce;
}

Uint8List _testAssociatedData(Uint8List header, Uint8List frameHeader) =>
    Uint8List.fromList([...header, ...frameHeader]);

Uint8List _pattern(int length) {
  final result = Uint8List(length);
  for (var index = 0; index < result.length; index++) {
    result[index] = (index * 29 + 11) & 0xff;
  }
  return result;
}

Stream<List<int>> _chunks(Uint8List bytes, int chunkLength) async* {
  for (var offset = 0; offset < bytes.length; offset += chunkLength) {
    final end = min(offset + chunkLength, bytes.length);
    yield Uint8List.sublistView(bytes, offset, end);
  }
}

List<(int, int)> _splitFrames(Uint8List envelope) {
  final result = <(int, int)>[];
  var offset = BackupCryptoV2.headerBytes;
  while (offset < envelope.length) {
    final frame = ByteData.sublistView(
      envelope,
      offset,
      offset + BackupCryptoV2.frameHeaderBytes,
    );
    final plaintextLength = frame.getUint32(5, Endian.big);
    final end =
        offset +
        BackupCryptoV2.frameHeaderBytes +
        plaintextLength +
        BackupCryptoV2.tagBytes;
    result.add((offset, end));
    offset = end;
  }
  expect(offset, envelope.length);
  return result;
}

final class _MemorySink implements BackupTransactionalByteSink {
  _MemorySink({this.failOnWrite, this.beforeWrite, this.onDiscard});

  final int? failOnWrite;
  final Future<void> Function(int writeNumber)? beforeWrite;
  final void Function()? onDiscard;
  final List<Uint8List> _parts = [];
  int _writeCount = 0;
  bool committed = false;
  bool discarded = false;

  Uint8List get bytes {
    final length = _parts.fold<int>(0, (sum, part) => sum + part.length);
    final result = Uint8List(length);
    var offset = 0;
    for (final part in _parts) {
      result.setRange(offset, offset + part.length, part);
      offset += part.length;
    }
    return result;
  }

  Uint8List? get visibleBytes => committed ? bytes : null;

  @override
  Future<void> write(List<int> bytes) async {
    _writeCount++;
    await beforeWrite?.call(_writeCount);
    if (_writeCount == failOnWrite) {
      throw StateError('Synthetic write failure.');
    }
    _parts.add(Uint8List.fromList(bytes));
  }

  @override
  Future<void> commit() async {
    committed = true;
  }

  @override
  Future<void> discard() async {
    onDiscard?.call();
    discarded = true;
    _parts.clear();
  }
}
