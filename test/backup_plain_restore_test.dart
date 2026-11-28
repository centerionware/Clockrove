import 'package:clockrove/backup/backup_file_api.dart';
import 'package:clockrove/backup/backup_restore_importer.dart';
import 'package:flutter_test/flutter_test.dart';

class _Source implements BackupEncryptedSource {
  _Source(this.bytes);

  final List<int> bytes;

  @override
  int get totalBytes => bytes.length;

  @override
  Stream<List<int>> openRead() => Stream<List<int>>.value(bytes);
}

void main() {
  test('recognizes a plaintext CLKARCH1 archive', () async {
    final source = _Source([
      0x43,
      0x4c,
      0x4b,
      0x41,
      0x52,
      0x43,
      0x48,
      0x31,
      0,
      1,
      2,
    ]);
    expect(await isPlainBackupArchive(source), isTrue);
  });

  test('recognizes a CLKROVE2 encrypted envelope', () async {
    final source = _Source([
      0x43,
      0x4c,
      0x4b,
      0x52,
      0x4f,
      0x56,
      0x45,
      0x32,
      0,
      3,
      0,
      72,
    ]);
    expect(await isPlainBackupArchive(source), isFalse);
  });

  test(
    'treats a truncated prefix as encrypted and leaves it to the codec',
    () async {
      expect(await isPlainBackupArchive(_Source([0x43, 0x4c, 0x4b])), isFalse);
      expect(await isPlainBackupArchive(_Source(const [])), isFalse);
    },
  );
}
