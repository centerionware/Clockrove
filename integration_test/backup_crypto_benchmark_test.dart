import 'dart:io';

import 'package:clockrove/backup/backup_crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/widgets.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('backup crypto runs on a minimum-API Android emulator', (
    tester,
  ) async {
    await tester.pumpWidget(const SizedBox.shrink());
    final timings = await tester.runAsync(() async {
      // Exercise the exact production plaintext bound with one fixed-size
      // allocation. A generated List<int> would add a much larger temporary
      // allocation and distort the process high-water measurement.
      final payload = Uint8List(BackupCrypto.maxArchiveBytes);
      for (var index = 0; index < payload.length; index++) {
        payload[index] = (index * 31 + 7) & 0xff;
      }
      final baselineRssKiB = await _processHighWaterRssKiB();

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
      expect(envelope.length, BackupCrypto.maxArchiveBytes + 72);
      final peakRssKiB = await _processHighWaterRssKiB();
      return (
        encryptMs: encryptClock.elapsedMilliseconds,
        decryptMs: decryptClock.elapsedMilliseconds,
        bytes: envelope.length,
        baselineRssKiB: baselineRssKiB,
        peakRssKiB: peakRssKiB,
        peakDeltaRssKiB: peakRssKiB > baselineRssKiB
            ? peakRssKiB - baselineRssKiB
            : 0,
      );
    });

    expect(timings, isNotNull);
    final result = timings!;
    // Timing is evidence for profile selection, not a flaky pass/fail target.
    // The Actions log records measurements for both API 24 and API 35.
    // ignore: avoid_print
    print(
      'BACKUP_CRYPTO_BENCHMARK encrypt_ms=${result.encryptMs} '
      'decrypt_ms=${result.decryptMs} envelope_bytes=${result.bytes} '
      'baseline_rss_kib=${result.baselineRssKiB} '
      'peak_rss_kib=${result.peakRssKiB} '
      'peak_delta_rss_kib=${result.peakDeltaRssKiB}',
    );
  });
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
