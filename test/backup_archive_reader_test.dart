import 'dart:io';
import 'dart:typed_data';

import 'package:clockrove/backup/backup_archive.dart';
import 'package:crypto/crypto.dart' as crypto;
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('streams bounded records and checksums media from a file', () async {
    final media = Uint8List.fromList(
      List<int>.generate(600_123, (index) => index * 31 & 0xff),
    );
    final blobId = List<String>.filled(43, 'a').join();
    final archive = await BackupArchiveCodec.encode(
      schemaVersion: BackupArchiveCodec.latestSchemaVersion,
      stateVersion: 0,
      capturedAtUtcMillis: 0,
      rows: [
        const BackupArchiveRow(
          table: 'jobs',
          values: {'id': 'streamed-job'},
          blobs: [],
        ),
        BackupArchiveRow(
          table: 'logo_assets',
          values: const {'id': 'streamed-logo'},
          blobs: [BackupArchiveBlob(id: blobId, column: 'bytes', bytes: media)],
        ),
      ],
    );
    final directory = await Directory.systemTemp.createTemp(
      'clockrove-archive-reader-test-',
    );
    final file = File('${directory.path}/archive.bin');
    await file.writeAsBytes(archive, flush: true);
    try {
      final records = <Map<String, Object?>>[];
      final chunks = <Uint8List>[];
      var headerSeen = false;
      var recordsComplete = false;
      final metrics = await BackupArchiveReader.stage(
        archiveFile: file,
        authenticatedArchiveBytes: archive.length,
        authenticatedArchiveSha256: crypto.sha256.convert(archive).bytes,
        onHeader: (header) async {
          headerSeen = true;
          expect(header.schemaVersion, BackupArchiveCodec.latestSchemaVersion);
          expect(header.recordCount, 2);
          expect(header.mediaCount, 1);
          return 128 * 1024;
        },
        onRecord: (ordinal, record) async {
          expect(ordinal, records.length);
          records.add(record);
        },
        onRecordsComplete: () async {
          recordsComplete = true;
        },
        onMediaChunk: (importedBlobId, offset, bytes) async {
          expect(importedBlobId, blobId);
          expect(
            offset,
            chunks.fold<int>(0, (total, chunk) => total + chunk.length),
          );
          expect(bytes.length, lessThanOrEqualTo(128 * 1024));
          chunks.add(bytes);
        },
      );

      expect(headerSeen, isTrue);
      expect(recordsComplete, isTrue);
      expect(records.first['type'], 'row');
      expect(records.map((record) => record['table']), ['jobs', 'logo_assets']);
      expect((records.last['blobs'] as List).single['sizeBytes'], media.length);
      expect(
        chunks.fold<int>(0, (total, chunk) => total + chunk.length),
        media.length,
      );
      expect(metrics.recordCount, 2);
      expect(metrics.mediaBytes, media.length);
      expect(metrics.recordBytes, greaterThan(0));
      expect(metrics.archiveBytes, archive.length);
    } finally {
      await directory.delete(recursive: true);
    }
  });

  test('rejects plaintext changed after authenticated decrypt', () async {
    final archive = await BackupArchiveCodec.encode(
      schemaVersion: BackupArchiveCodec.latestSchemaVersion,
      stateVersion: 0,
      capturedAtUtcMillis: 0,
      rows: const [
        BackupArchiveRow(
          table: 'jobs',
          values: {'id': 'streamed-job'},
          blobs: [],
        ),
      ],
    );
    final changed = Uint8List.fromList(archive);
    changed[23] = 1;
    final directory = await Directory.systemTemp.createTemp(
      'clockrove-archive-reader-test-',
    );
    final file = File('${directory.path}/archive.bin');
    await file.writeAsBytes(changed, flush: true);
    try {
      await expectLater(
        BackupArchiveReader.stage(
          archiveFile: file,
          authenticatedArchiveBytes: changed.length,
          authenticatedArchiveSha256: crypto.sha256.convert(archive).bytes,
          onHeader: (_) async => 128 * 1024,
          onRecord: (_, _) async {},
          onRecordsComplete: () async {},
          onMediaChunk: (_, _, _) async {},
        ),
        throwsA(isA<FormatException>()),
      );
    } finally {
      await directory.delete(recursive: true);
    }
  });
}
