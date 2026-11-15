import 'dart:typed_data';

import 'package:clockrove/backup/backup_crypto.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const password = 'synthetic-backup-passphrase';

  test('encrypts and authenticates a bounded backup envelope', () async {
    final plaintext = Uint8List.fromList(List<int>.generate(4096, (i) => i % 251));
    final encrypted = await BackupCrypto.encrypt(
      password: password,
      archive: plaintext,
    );

    expect(encrypted.length, plaintext.length + 44 + 12 + 16);
    expect(
      await BackupCrypto.decrypt(password: password, envelope: encrypted),
      orderedEquals(plaintext),
    );

    final altered = Uint8List.fromList(encrypted);
    altered[altered.length - 1] ^= 1;
    await expectLater(
      BackupCrypto.decrypt(password: password, envelope: altered),
      throwsA(isA<FormatException>()),
    );
    await expectLater(
      BackupCrypto.decrypt(password: 'another synthetic passphrase', envelope: encrypted),
      throwsA(isA<FormatException>()),
    );
  });

  test('rejects unsafe KDF parameters before password derivation', () async {
    final encrypted = await BackupCrypto.encrypt(
      password: password,
      archive: Uint8List.fromList([1, 2, 3]),
    );
    final changedKdf = Uint8List.fromList(encrypted);
    changedKdf[12] = 0xff;
    changedKdf[13] = 0xff;
    changedKdf[14] = 0xff;
    changedKdf[15] = 0xff;
    await expectLater(
      BackupCrypto.decrypt(password: password, envelope: changedKdf),
      throwsA(isA<FormatException>()),
    );
  });

  test('rejects unreasonable payload sizes before allocating a KDF', () async {
    await expectLater(
      BackupCrypto.encrypt(
        password: password,
        archive: Uint8List(BackupCrypto.maxArchiveBytes + 1),
      ),
      throwsA(isA<FormatException>()),
    );
    await expectLater(
      BackupCrypto.encrypt(password: 'short', archive: Uint8List.fromList([1])),
      throwsA(isA<FormatException>()),
    );
  });
}
