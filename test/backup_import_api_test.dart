import 'dart:typed_data';

import 'package:clockrove/backup/backup_import_api.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('clockrove/backup/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  late Future<Object?> Function(MethodCall call) respond;

  setUp(() {
    calls.clear();
    respond = (_) async => throw StateError('Unexpected backup import call.');
    messenger.setMockMethodCallHandler(channel, (call) {
      calls.add(call);
      return respond(call);
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('uses typed bounded staging, choice, and apply operations', () async {
    const importId = 'opaque-import-id';
    const previewToken = 'opaque-preview-token';
    const conflictId = 'opaque-conflict-id';
    final record = <String, Object?>{
      'type': 'row',
      'table': 'customers',
      'values': {'id': 'customer-1'},
      'blobs': <Object?>[],
    };
    final blobId = 'a' * 43;
    respond = (call) async {
      switch (call.method) {
        case 'beginBackupImport':
          final args = call.arguments as Map;
          expect(args['archiveFormatVersion'], 1);
          expect(args['schemaVersion'], 16);
          expect(args['recordCount'], 1);
          return {
            'importId': importId,
            'archiveFormatVersion': 1,
            'schemaVersion': 16,
            'maxPageBytes': 255 * 1024,
            'maxBlobChunkBytes': maxBackupImportBlobChunkBytes,
            'idleExpiryMillis': 60000,
          };
        case 'appendBackupImportPage':
          final args = call.arguments as Map;
          expect(args['importId'], importId);
          expect(args['firstOrdinal'], 0);
          expect(args['records'], [record]);
          return {'nextOrdinal': 1};
        case 'writeBackupImportBlob':
          final args = call.arguments as Map;
          expect(args['blobId'], blobId);
          expect(args['offset'], 0);
          expect(args['bytes'], orderedEquals([4, 5, 6]));
          return {'nextOffset': 3};
        case 'finishBackupImport':
          return _preview(
            importId: importId,
            previewToken: previewToken,
            recordCount: 1,
            conflictCount: 1,
          );
        case 'readBackupImportConflictPage':
          final args = call.arguments as Map;
          expect(args['previewToken'], previewToken);
          expect(args['maxBytes'], maxBackupImportConflictPageBytes);
          return {
            'importId': importId,
            'previewToken': previewToken,
            'nextCursor': null,
            'done': true,
            'conflicts': [
              {
                'conflictId': conflictId,
                'kind': 'EXISTING_RECORD_DIFFERS',
                'table': 'customers',
                'primaryKey': ['customer-1'],
                'localIdentifier': 'customer-1',
                'localSummary': {'name': 'Local'},
                'backupSummary': {'name': 'Backup'},
                'allowedChoices': ['KEEP_LOCAL'],
                'defaultChoice': 'KEEP_LOCAL',
                'resolved': false,
              },
            ],
          };
        case 'resolveBackupImportConflict':
          final args = call.arguments as Map;
          expect(args['conflictId'], conflictId);
          expect(args['choice'], 'KEEP_LOCAL');
          expect(args['expectedLocalStateVersion'], 7);
          return {'resolved': true};
        case 'applyBackupImport':
          final args = call.arguments as Map;
          expect(args['previewToken'], previewToken);
          return {
            'importId': importId,
            'stateVersion': 7,
            'insertedCount': 0,
            'skippedCount': 1,
          };
        case 'closeBackupImport':
          return {'closed': true};
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    final api = BackupImportApi(channel: channel);
    final started = await api.begin(
      archiveFormatVersion: 1,
      schemaVersion: 16,
      archiveDigest: 'f' * 64,
      recordCount: 1,
      mediaCount: 1,
      mediaBytes: 3,
    );
    expect(started.importId, importId);
    expect(await api.appendPage(
      importId: importId,
      firstOrdinal: 0,
      records: [record],
    ), 1);
    expect(
      await api.writeBlob(
        importId: importId,
        blobId: blobId,
        offset: 0,
        bytes: Uint8List.fromList([4, 5, 6]),
      ),
      3,
    );

    final preview = await api.finish(importId);
    expect(preview.conflictCount, 1);
    expect(preview.applyReady, isFalse);
    final page = await api.readConflicts(
      importId: importId,
      previewToken: previewToken,
    );
    expect(page.conflicts.single.allowedChoices, ['KEEP_LOCAL']);
    await api.resolveConflict(
      importId: importId,
      previewToken: previewToken,
      expectedLocalStateVersion: 7,
      conflictId: conflictId,
      choice: 'KEEP_LOCAL',
    );
    final applied = await api.apply(
      importId: importId,
      previewToken: previewToken,
      expectedLocalStateVersion: 7,
    );
    expect(applied.skippedCount, 1);
    expect(await api.close(importId), isTrue);
    expect(calls.map((call) => call.method), [
      'beginBackupImport',
      'appendBackupImportPage',
      'writeBackupImportBlob',
      'finishBackupImport',
      'readBackupImportConflictPage',
      'resolveBackupImportConflict',
      'applyBackupImport',
      'closeBackupImport',
    ]);
  });

  test('rejects invalid bounds and oversized encoded pages before native calls', () async {
    final api = BackupImportApi(channel: channel);
    await expectLater(
      api.begin(
        archiveFormatVersion: 1,
        schemaVersion: 16,
        archiveDigest: 'bad',
        recordCount: 0,
        mediaCount: 0,
        mediaBytes: 0,
      ),
      throwsA(isA<FormatException>()),
    );
    await expectLater(
      api.appendPage(
        importId: 'import-1',
        firstOrdinal: 0,
        records: [
          {'type': 'row', 'payload': 'x' * maxBackupImportRequestBytes},
        ],
      ),
      throwsA(isA<FormatException>()),
    );
    await expectLater(
      api.writeBlob(
        importId: 'import-1',
        blobId: 'a' * 43,
        offset: 0,
        bytes: Uint8List(maxBackupImportBlobChunkBytes + 1),
      ),
      throwsA(isA<FormatException>()),
    );
    expect(calls, isEmpty);
  });

  test('rejects malformed previews and resolution acknowledgements', () async {
    respond = (call) async {
      if (call.method == 'finishBackupImport') {
        return _preview(
          importId: 'import-1',
          previewToken: 'token-1',
          recordCount: 1,
          addedCount: 2,
        );
      }
      return {'resolved': false};
    };
    final api = BackupImportApi(channel: channel);
    await expectLater(api.finish('import-1'), throwsA(isA<FormatException>()));
    await expectLater(
      api.resolveConflict(
        importId: 'import-1',
        previewToken: 'token-1',
        expectedLocalStateVersion: 0,
        conflictId: 'conflict-1',
        choice: 'KEEP_LOCAL',
      ),
      throwsA(isA<FormatException>()),
    );
  });

  test('preserves blocking conflicts without offering an unsafe resolution', () {
    final conflict = BackupImportConflict.fromMap({
      'conflictId': 'gps-range-conflict',
      'kind': 'GPS_DELETION_COVERAGE',
      'table': 'location_deletion_ranges',
      'primaryKey': ['range-id'],
      'localIdentifier': null,
      'localSummary': {'identifier': 'Unproven local GPS points are covered'},
      'backupSummary': <String, Object?>{},
      'allowedChoices': <String>[],
      'defaultChoice': null,
      'resolved': false,
      'selectedChoice': null,
    });
    expect(conflict.allowedChoices, isEmpty);
    expect(conflict.defaultChoice, isNull);
    expect(conflict.resolved, isFalse);

    expect(
      () => BackupImportConflict.fromMap({
        'conflictId': 'resolved-gps-range-conflict',
        'kind': 'GPS_DELETION_COVERAGE',
        'table': 'location_deletion_ranges',
        'primaryKey': ['range-id'],
        'localIdentifier': null,
        'localSummary': <String, Object?>{},
        'backupSummary': <String, Object?>{},
        'allowedChoices': <String>[],
        'defaultChoice': null,
        'resolved': true,
        'selectedChoice': null,
      }),
      throwsA(isA<FormatException>()),
    );
  });
}

Map<String, Object?> _preview({
  required String importId,
  required String previewToken,
  required int recordCount,
  int addedCount = 0,
  int conflictCount = 0,
}) => {
  'importId': importId,
  'previewToken': previewToken,
  'schemaVersion': 16,
  'localStateVersion': 7,
  'recordCount': recordCount,
  'mediaCount': 0,
  'mediaBytes': 0,
  'addedCount': addedCount,
  'unchangedCount': 0,
  'conflictCount': conflictCount,
  'openSessionCount': 0,
  'localOpenSessionCount': 0,
  'applyReady': conflictCount == 0,
};
