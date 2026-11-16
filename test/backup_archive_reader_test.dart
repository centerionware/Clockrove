import 'dart:async';
import 'dart:typed_data';

import 'package:clockrove/backup/backup_archive.dart';
import 'package:crypto/crypto.dart' as crypto;
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('streams bounded records and checksums media with backpressure', () async {
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
    final channel = BackupArchivePlaintextChannel();
    final callbackEntered = Completer<void>();
    final allowRecord = Completer<void>();
    final records = <Map<String, Object?>>[];
    final chunks = <Uint8List>[];
    var recordsComplete = false;
    final parsing = BackupArchiveReader.stage(
      plaintext: channel,
      authenticatedArchiveBytes: archive.length,
      authenticatedArchiveSha256: crypto.sha256.convert(archive).bytes,
      onHeader: (header) async {
        expect(header.schemaVersion, BackupArchiveCodec.latestSchemaVersion);
        expect(header.recordCount, 2);
        expect(header.mediaCount, 1);
        return 128 * 1024;
      },
      onRecord: (ordinal, record) async {
        expect(ordinal, records.length);
        if (ordinal == 0) {
          callbackEntered.complete();
          await allowRecord.future;
        }
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

    final firstChunkLength = BackupArchivePlaintextChannel.maxChunkBytes;
    var firstWriteCompleted = false;
    final firstWrite = channel.add(
      Uint8List.sublistView(archive, 0, firstChunkLength),
    ).then<void>((_) {
      firstWriteCompleted = true;
    });
    try {
      await callbackEntered.future.timeout(const Duration(seconds: 5));
      await Future<void>.delayed(Duration.zero);
      expect(firstWriteCompleted, isFalse);
    } finally {
      if (!allowRecord.isCompleted) allowRecord.complete();
    }
    await firstWrite;

    for (var offset = firstChunkLength; offset < archive.length;) {
      final end = (offset + BackupArchivePlaintextChannel.maxChunkBytes)
          .clamp(0, archive.length)
          .toInt();
      await channel.add(Uint8List.sublistView(archive, offset, end));
      offset = end;
    }
    await channel.close();
    final metrics = await parsing;

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
    expect(metrics.archiveSha256, crypto.sha256.convert(archive).bytes);
  });

  test('rejects changed plaintext against the authenticated digest', () async {
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
    final changed = Uint8List.fromList(archive)..[23] = 1;
    final channel = BackupArchivePlaintextChannel();
    final parsing = BackupArchiveReader.stage(
      plaintext: channel,
      authenticatedArchiveBytes: changed.length,
      authenticatedArchiveSha256: crypto.sha256.convert(archive).bytes,
      onHeader: (_) async => 128 * 1024,
      onRecord: (_, _) async {},
      onRecordsComplete: () async {},
      onMediaChunk: (_, _, _) async {},
    );
    for (var offset = 0; offset < changed.length;) {
      final end = (offset + BackupArchivePlaintextChannel.maxChunkBytes)
          .clamp(0, changed.length)
          .toInt();
      await channel.add(Uint8List.sublistView(changed, offset, end));
      offset = end;
    }
    await channel.close();
    await expectLater(parsing, throwsA(isA<FormatException>()));
  });

  test('parser failure releases a producer waiting on a chunk', () async {
    final invalidArchive = Uint8List(48);
    final channel = BackupArchivePlaintextChannel();
    final parsing = BackupArchiveReader.stage(
      plaintext: channel,
      authenticatedArchiveBytes: invalidArchive.length,
      authenticatedArchiveSha256: List<int>.filled(32, 0),
      onHeader: (_) async => 128 * 1024,
      onRecord: (_, _) async {},
      onRecordsComplete: () async {},
      onMediaChunk: (_, _, _) async {},
    );
    await expectLater(
      channel.add(invalidArchive),
      throwsA(isA<FormatException>()),
    );
    await expectLater(parsing, throwsA(isA<FormatException>()));
  });
}
