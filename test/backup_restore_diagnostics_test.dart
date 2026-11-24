import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:clockrove/backup/backup_file_api.dart';
import 'package:clockrove/backup/backup_restore_importer.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

final class _FixedSource implements BackupEncryptedSource {
  const _FixedSource(this.totalBytes);

  @override
  final int totalBytes;

  @override
  Stream<List<int>> openRead() => const Stream<List<int>>.empty();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('failure codes name the gate without leaking user data', () {
    expect(
      backupRestoreFailureCode(const BackupRestoreReason('file-size')),
      'file-size',
    );
    expect(
      backupRestoreFailureCode(const BackupRestoreReason('schema-14-of-17')),
      'schema-14-of-17',
    );
    // Unlock failures are a FormatException subclass, so ordering matters.
    expect(backupRestoreFailureCode(const BackupUnlockException()), 'unlock');
    expect(
      backupRestoreFailureCode(const FormatException('inner detail')),
      'format',
    );
    expect(
      backupRestoreFailureCode(
        PlatformException(code: 'BACKUP_IMPORT_LIMIT', message: 'detail'),
      ),
      'native-BACKUP_IMPORT_LIMIT',
    );
    expect(
      backupRestoreFailureCode(const BackupCryptoV2CancelledException()),
      'cancelled',
    );
    expect(backupRestoreFailureCode(StateError('inner detail')), 'state');
    expect(backupRestoreFailureCode(Object()), 'unknown');
  });

  test(
    'an unsupported envelope size is reported before any cryptography',
    () async {
      final importer = BackupRestoreImporter();
      await expectLater(
        importer.decryptAndPreview(
          encryptedEnvelope: const _FixedSource(0),
          password: 'synthetic-passphrase',
        ),
        throwsA(
          isA<BackupRestoreReason>().having(
            (reason) => reason.code,
            'code',
            'file-size',
          ),
        ),
      );
    },
  );
}
