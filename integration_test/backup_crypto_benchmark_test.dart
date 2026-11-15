import 'dart:typed_data';

import 'package:clockrove/backup/backup_crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

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
