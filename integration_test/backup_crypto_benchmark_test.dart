import 'dart:io';

import 'package:clockrove/backup/backup_crypto.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/widgets.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('v2 streaming envelope stays bounded at maximum archive size', (
    tester,
  ) async {
    await tester.pumpWidget(const SizedBox.shrink());
    late String temporaryDirectoryPath;
    final metrics = await tester.runAsync(() async {
      final directory = await Directory.systemTemp.createTemp(
        'clockrove-backup-v2-',
      );
      temporaryDirectoryPath = directory.path;
      final envelopeFile = File('${directory.path}/backup.clockrove');
      final restoredFile = File('${directory.path}/restored.archive');
      try {
        final expectedDigest = await _hashStream(
          _generatedArchive(BackupCryptoV2.maxArchiveBytes),
        );
        final baselineRssKiB = await _processHighWaterRssKiB();

        final encryptClock = Stopwatch()..start();
        final encrypted = await BackupCryptoV2.encrypt(
          password: 'synthetic-device-benchmark-passphrase',
          archive: _generatedArchive(BackupCryptoV2.maxArchiveBytes),
          destination: _FileTransactionalSink(envelopeFile),
        );
        encryptClock.stop();

        final decryptClock = Stopwatch()..start();
        final restored = await BackupCryptoV2.decrypt(
          password: 'synthetic-device-benchmark-passphrase',
          envelope: envelopeFile.openRead(),
          privateArchive: _FileTransactionalSink(restoredFile),
        );
        decryptClock.stop();

        expect(encrypted.archiveBytes, BackupCryptoV2.maxArchiveBytes);
        expect(encrypted.dataFrameCount, BackupCryptoV2.maxDataFrames);
        expect(encrypted.envelopeBytes, BackupCryptoV2.maxEnvelopeBytes);
        expect(await envelopeFile.length(), BackupCryptoV2.maxEnvelopeBytes);
        expect(restored.archiveBytes, BackupCryptoV2.maxArchiveBytes);
        expect(restored.dataFrameCount, BackupCryptoV2.maxDataFrames);
        expect(restored.archiveSha256, expectedDigest);
        expect(await _hashStream(restoredFile.openRead()), expectedDigest);

        final peakRssKiB = await _processHighWaterRssKiB();
        final peakDeltaRssKiB = peakRssKiB > baselineRssKiB
            ? peakRssKiB - baselineRssKiB
            : 0;
        expect(
          peakDeltaRssKiB,
          lessThanOrEqualTo(64 * 1024),
          reason: 'v2 maximum-size streaming must stay within 64 MiB RSS.',
        );
        return (
          encryptMs: encryptClock.elapsedMilliseconds,
          decryptMs: decryptClock.elapsedMilliseconds,
          archiveBytes: encrypted.archiveBytes,
          envelopeBytes: encrypted.envelopeBytes,
          baselineRssKiB: baselineRssKiB,
          peakRssKiB: peakRssKiB,
          peakDeltaRssKiB: peakDeltaRssKiB,
        );
      } finally {
        if (await envelopeFile.exists()) await envelopeFile.delete();
        if (await restoredFile.exists()) await restoredFile.delete();
        if (await directory.exists()) await directory.delete(recursive: true);
      }
    });

    expect(metrics, isNotNull);
    expect(
      await tester.runAsync(() => Directory(temporaryDirectoryPath).exists()),
      isFalse,
    );
    final result = metrics!;
    // ignore: avoid_print
    print(
      'BACKUP_STREAMING_ENVELOPE_BENCHMARK '
      'encrypt_ms=${result.encryptMs} decrypt_ms=${result.decryptMs} '
      'archive_bytes=${result.archiveBytes} envelope_bytes=${result.envelopeBytes} '
      'baseline_rss_kib=${result.baselineRssKiB} '
      'peak_rss_kib=${result.peakRssKiB} '
      'peak_delta_rss_kib=${result.peakDeltaRssKiB}',
    );
  });

  testWidgets('backup crypto runs on a minimum-API Android emulator', (
    tester,
  ) async {
    await tester.pumpWidget(const SizedBox.shrink());
    final payload = Uint8List.fromList(
      List<int>.generate(1024 * 1024, (index) => index % 251),
    );
    final timings = await tester.runAsync(() async {
      final encryptClock = Stopwatch()..start();
      final envelope = await BackupCrypto.encrypt(
        password: 'synthetic-device-benchmark-passphrase',
        archive: payload,
      );
      encryptClock.stop();

      final decryptClock = Stopwatch()..start();
      final restored = await BackupCrypto.decrypt(
        password: 'synthetic-device-benchmark-passphrase',
        envelope: envelope,
      );
      decryptClock.stop();
      expect(listEquals(restored, payload), isTrue);
      return (
        encryptMs: encryptClock.elapsedMilliseconds,
        decryptMs: decryptClock.elapsedMilliseconds,
        bytes: envelope.length,
      );
    });

    expect(timings, isNotNull);
    final result = timings!;
    // Timing is evidence for profile selection, not a flaky pass/fail target.
    // The Actions log records measurements for both API 24 and API 35.
    // ignore: avoid_print
    print(
      'BACKUP_CRYPTO_BENCHMARK encrypt_ms=${result.encryptMs} '
      'decrypt_ms=${result.decryptMs} envelope_bytes=${result.bytes}',
    );
  });
}

Stream<List<int>> _generatedArchive(int length) async* {
  for (var offset = 0; offset < length; offset += BackupCryptoV2.chunkBytes) {
    final remaining = length - offset;
    final chunkLength = remaining < BackupCryptoV2.chunkBytes
        ? remaining
        : BackupCryptoV2.chunkBytes;
    final chunk = Uint8List(chunkLength);
    for (var index = 0; index < chunk.length; index++) {
      chunk[index] = ((offset + index) * 31 + 7) & 0xff;
    }
    yield chunk;
  }
}

Future<List<int>> _hashStream(Stream<List<int>> source) async {
  final output = _SingleDigestSink();
  final hash = sha256.startChunkedConversion(output);
  await for (final chunk in source) {
    hash.add(chunk);
  }
  hash.close();
  return output.value.bytes;
}

Future<int> _processHighWaterRssKiB() async {
  final status = await File('/proc/self/status').readAsLines();
  final line = status.firstWhere(
    (entry) => entry.startsWith('VmHWM:'),
    orElse: () => throw const FormatException(
      'Android did not report process high-water RSS.',
    ),
  );
  final match = RegExp(r'^VmHWM:\s+(\d+)\s+kB$').firstMatch(line);
  if (match == null) {
    throw const FormatException('Android reported an invalid process RSS.');
  }
  return int.parse(match.group(1)!);
}

final class _SingleDigestSink implements Sink<Digest> {
  Digest? _digest;

  Digest get value => _digest ?? (throw StateError('Hash is incomplete.'));

  @override
  void add(Digest value) {
    if (_digest != null) throw StateError('Hash completed more than once.');
    _digest = value;
  }

  @override
  void close() {}
}

final class _FileTransactionalSink implements BackupTransactionalByteSink {
  _FileTransactionalSink(this.file);

  final File file;
  RandomAccessFile? _handle;
  bool _committed = false;

  Future<RandomAccessFile> get _open async =>
      _handle ??= await file.open(mode: FileMode.write);

  @override
  Future<void> write(Uint8List bytes) async {
    if (_committed) throw StateError('Cannot write after commit.');
    await (await _open).writeFrom(bytes);
  }

  @override
  Future<void> commit() async {
    final handle = await _open;
    await handle.flush();
    await handle.close();
    _handle = null;
    _committed = true;
  }

  @override
  Future<void> discard() async {
    final handle = _handle;
    _handle = null;
    if (handle != null) await handle.close();
    if (await file.exists()) await file.delete();
  }
}
