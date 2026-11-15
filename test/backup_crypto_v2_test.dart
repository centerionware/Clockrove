import 'dart:math';
import 'dart:typed_data';

import 'package:clockrove/backup/backup_crypto.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
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

  test('v2 rejects v1 envelopes without falling back to the v1 reader', () async {
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
  });

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

  test('wrong password and frame tampering never commit staged plaintext', () async {
    final encrypted = await _encrypt(_pattern(777));
    final badTag = Uint8List.fromList(encrypted);
    badTag[BackupCryptoV2.headerBytes + BackupCryptoV2.frameHeaderBytes] ^= 1;
    final badFinalTag = Uint8List.fromList(encrypted);
    badFinalTag[badFinalTag.length - 1] ^= 1;

    for (final damaged in [encrypted, badTag, badFinalTag]) {
      final staged = _MemorySink();
      final password = identical(damaged, encrypted) ? 'wrong-password-123' : _password;
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
  });

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

  test('altered manifest count, length and digest are rejected', () async {
    final encrypted = await _encrypt(_pattern(1));
    final manifestStart =
        encrypted.length -
        BackupCryptoV2.frameHeaderBytes -
        BackupCryptoV2.manifestBytes -
        BackupCryptoV2.tagBytes;
    final manifestCiphertextStart =
        manifestStart + BackupCryptoV2.frameHeaderBytes;
    final alteredCount = Uint8List.fromList(encrypted);
    alteredCount[manifestCiphertextStart + 7] ^= 1;
    final alteredLength = Uint8List.fromList(encrypted);
    alteredLength[manifestCiphertextStart + 15] ^= 1;
    final alteredDigest = Uint8List.fromList(encrypted);
    alteredDigest[manifestCiphertextStart + 16] ^= 1;

    for (final damaged in [alteredCount, alteredLength, alteredDigest]) {
      await _expectRejectedEnvelope(damaged);
    }
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
    final end = offset +
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
  _MemorySink({this.failOnWrite});

  final int? failOnWrite;
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
  Future<void> write(Uint8List bytes) async {
    _writeCount++;
    if (_writeCount == failOnWrite) throw StateError('Synthetic write failure.');
    _parts.add(Uint8List.fromList(bytes));
  }

  @override
  Future<void> commit() async {
    committed = true;
  }

  @override
  Future<void> discard() async {
    discarded = true;
    _parts.clear();
  }
}
