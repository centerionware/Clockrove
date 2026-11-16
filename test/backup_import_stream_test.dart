import 'dart:async';
import 'dart:typed_data';

import 'package:clockrove/backup/backup_archive.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:clockrove/backup/backup_import_api.dart';
import 'package:clockrove/backup/backup_import_stream.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('header accepts historical schemas through current', () {
    final header = BackupArchiveImportHeader.parse(
      _header(schemaVersion: BackupArchiveCodec.latestSchemaVersion),
      authenticatedArchiveBytes: 48,
    );

    expect(header.schemaVersion, BackupArchiveCodec.latestSchemaVersion);
    expect(header.recordCount, 0);
    expect(header.mediaCount, 0);

    for (var schemaVersion = 1;
        schemaVersion <= BackupArchiveCodec.latestSchemaVersion;
        schemaVersion++) {
      final historical = BackupArchiveImportHeader.parse(
        _header(schemaVersion: schemaVersion),
        authenticatedArchiveBytes: 48,
      );
      expect(historical.schemaVersion, schemaVersion);
    }

    for (final schemaVersion in [
      0,
      BackupArchiveCodec.latestSchemaVersion + 1,
    ]) {
      expect(
        () => BackupArchiveImportHeader.parse(
          _header(schemaVersion: schemaVersion),
          authenticatedArchiveBytes: 48,
        ),
        throwsFormatException,
        reason: 'Only schema versions supported by the archive decoder pass.',
      );
    }
  });

  test('digest mismatch clears staging and never previews', () async {
    const channel = MethodChannel('clockrove/backup/v1');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final calls = <String>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      switch (call.method) {
        case 'beginBackupImport':
          return {
            'importId': 'synthetic-import',
            'archiveFormatVersion': 1,
            'schemaVersion': BackupArchiveCodec.latestSchemaVersion,
            'maxPageBytes': maxBackupImportRequestBytes - 1024,
            'maxBlobChunkBytes': maxBackupImportBlobChunkBytes,
            'idleExpiryMillis': 60000,
          };
        case 'appendBackupImportPage':
          final args = call.arguments as Map;
          final records = args['records'] as List;
          expect(records, hasLength(1));
          expect(
            ((records.single as Map)['values'] as Map)['notes'],
            'x' * 70 * 1024,
          );
          return {'nextOrdinal': 1};
        case 'closeBackupImport':
          return {'closed': true};
        default:
          throw StateError('Unexpected native import call ${call.method}.');
      }
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

    final archive = await BackupArchiveCodec.encode(
      schemaVersion: BackupArchiveCodec.latestSchemaVersion,
      stateVersion: 0,
      capturedAtUtcMillis: 0,
      rows: [
        BackupArchiveRow(
          table: 'customers',
          values: {
            'id': 'digest-mismatch-customer',
            'name': 'Synthetic customer',
            'notes': 'x' * 70 * 1024,
          },
          blobs: const [],
        ),
      ],
    );
    final encryptedFile = _MemorySink();
    await BackupCryptoV2.encrypt(
      password: 'synthetic-digest-mismatch-passphrase',
      archive: Stream<List<int>>.value(archive),
      destination: encryptedFile,
    );

    try {
      final probe = BackupArchiveHeaderProbe();
      final firstPass = await BackupCryptoV2.decrypt(
        password: 'synthetic-digest-mismatch-passphrase',
        envelope: Stream<List<int>>.value(encryptedFile.bytes),
        privateArchive: probe,
      );
      final header = BackupArchiveImportHeader.parse(
        probe.headerBytes,
        authenticatedArchiveBytes: firstPass.archiveBytes,
      );
      final api = BackupImportApi(channel: channel);
      final started = await api.begin(
        archiveFormatVersion: header.formatVersion,
        schemaVersion: header.schemaVersion,
        archiveDigest: _hex(firstPass.archiveSha256),
        recordCount: header.recordCount,
        mediaCount: header.mediaCount,
        mediaBytes: header.mediaBytes,
      );
      final sink = BackupArchiveImportSink(
        api: api,
        importId: started.importId,
        header: header,
        expectedArchiveSha256: List<int>.filled(32, 0),
      );

      await expectLater(
        BackupCryptoV2.decrypt(
          password: 'synthetic-digest-mismatch-passphrase',
          envelope: Stream<List<int>>.value(encryptedFile.bytes),
          privateArchive: sink,
        ),
        throwsA(isA<FormatException>()),
      );

      expect(calls, [
        'beginBackupImport',
        'appendBackupImportPage',
        'closeBackupImport',
      ]);
    } finally {
      messenger.setMockMethodCallHandler(channel, null);
    }
  });
}

Uint8List _header({required int schemaVersion}) {
  final bytes = Uint8List(48);
  bytes.setRange(0, 8, 'CLKARCH1'.codeUnits);
  final data = ByteData.sublistView(bytes);
  data.setUint16(8, BackupArchiveCodec.currentFormatVersion, Endian.big);
  data.setUint16(10, 48, Endian.big);
  data.setUint32(12, schemaVersion, Endian.big);
  data.setInt64(16, 0, Endian.big);
  data.setInt64(24, 0, Endian.big);
  return bytes;
}

String _hex(List<int> bytes) =>
    bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();

final class _MemorySink implements BackupTransactionalByteSink {
  final BytesBuilder _builder = BytesBuilder(copy: false);
  Uint8List? _bytes;

  Uint8List get bytes => _bytes ?? (throw StateError('No encrypted bytes.'));

  @override
  Future<void> write(List<int> bytes) async {
    _builder.add(bytes);
  }

  @override
  Future<void> commit() async {
    _bytes = _builder.takeBytes();
  }

  @override
  Future<void> discard() async => _builder.clear();
}
