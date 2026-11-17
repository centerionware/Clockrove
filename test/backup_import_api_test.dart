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
          expect(args['schemaVersion'], currentBackupImportSchemaVersion);
          expect(args['recordCount'], 1);
          return {
            'importId': importId,
            'archiveFormatVersion': 1,
            'schemaVersion': currentBackupImportSchemaVersion,
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
      schemaVersion: currentBackupImportSchemaVersion,
      archiveDigest: 'f' * 64,
      recordCount: 1,
      mediaCount: 1,
      mediaBytes: 3,
    );
    expect(started.importId, importId);
    expect(api.encodedAppendRecordBytes(record), greaterThan(0));
    expect(
      await api.appendPage(
        importId: importId,
        firstOrdinal: 0,
        records: [record],
      ),
      1,
    );
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

  test(
    'rejects invalid bounds and oversized encoded pages before native calls',
    () async {
      final api = BackupImportApi(channel: channel);
      await expectLater(
        api.begin(
          archiveFormatVersion: 1,
          schemaVersion: currentBackupImportSchemaVersion,
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
    },
  );

  test(
    'accepts the full native media ceiling before row reconciliation',
    () async {
      respond = (call) async => {
        'importId': 'import-64m',
        'archiveFormatVersion': 1,
        'schemaVersion': currentBackupImportSchemaVersion,
        'maxPageBytes': 255 * 1024,
        'maxBlobChunkBytes': maxBackupImportBlobChunkBytes,
        'idleExpiryMillis': 60000,
      };
      final started = await BackupImportApi(channel: channel).begin(
        archiveFormatVersion: 1,
        schemaVersion: currentBackupImportSchemaVersion,
        archiveDigest: 'f' * 64,
        recordCount: 1,
        mediaCount: 1,
        mediaBytes: maxBackupImportMediaBytes,
      );
      expect(started.importId, 'import-64m');
      expect(maxBackupImportMediaBytes, maxBackupImportStagedBytes);
    },
  );

  test('requires the current archive schema for native import', () async {
    final api = BackupImportApi(channel: channel);
    await expectLater(
      api.begin(
        archiveFormatVersion: 1,
        schemaVersion: currentBackupImportSchemaVersion - 1,
        archiveDigest: 'f' * 64,
        recordCount: 0,
        mediaCount: 0,
        mediaBytes: 0,
      ),
      throwsA(isA<FormatException>()),
    );
    expect(calls, isEmpty);

    respond = (call) async => _preview(
      importId: 'import-1',
      previewToken: 'token-1',
      recordCount: 0,
      schemaVersion: currentBackupImportSchemaVersion - 1,
    );
    await expectLater(api.finish('import-1'), throwsA(isA<FormatException>()));
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

  test('preview enforces the shared 64 MiB rows plus media budget', () async {
    respond = (call) async {
      if (call.method == 'finishBackupImport') {
        return _preview(
          importId: 'import-1',
          previewToken: 'token-1',
          recordCount: 1,
          addedCount: 1,
          mediaCount: 1,
          mediaBytes: maxBackupImportStagedBytes - 1,
          stagedRecordBytes: 2,
        );
      }
      throw StateError('Unexpected backup import call.');
    };
    await expectLater(
      BackupImportApi(channel: channel).finish('import-1'),
      throwsA(isA<FormatException>()),
    );
  });

  test('accepts media above 60 MiB when combined staged bytes fit', () async {
    respond = (call) async => _preview(
      importId: 'import-1',
      previewToken: 'token-1',
      recordCount: 1,
      addedCount: 1,
      mediaCount: 1,
      mediaBytes: 61 * 1024 * 1024,
      stagedRecordBytes: 2 * 1024 * 1024,
    );
    final preview = await BackupImportApi(channel: channel).finish('import-1');
    expect(preview.mediaBytes, 61 * 1024 * 1024);
    expect(
      preview.stagedRecordBytes + preview.mediaBytes,
      lessThanOrEqualTo(maxBackupImportStagedBytes),
    );
  });

  test(
    'accepts overlapping preview summary counts from native planning',
    () async {
      respond = (call) async => _preview(
        importId: 'summary-import',
        previewToken: 'summary-token',
        recordCount: 4,
        addedCount: 1,
        changedCount: 2,
        deletedCount: 3,
        unchangedCount: 4,
        unresolvedReferenceCount: 1,
        timeOverlapCount: 2,
        conflictCount: 3,
      );

      final preview = await BackupImportApi(channel: channel)
          .finish('summary-import');

      expect(preview.addedCount, 1);
      expect(preview.changedCount, 2);
      expect(preview.deletedCount, 3);
      expect(preview.unchangedCount, 4);
      expect(preview.unresolvedReferenceCount, 1);
      expect(preview.timeOverlapCount, 2);
      expect(
        preview.unchangedCount + preview.deletedCount,
        greaterThan(preview.recordCount),
      );
    },
  );

  test('accepts dependent conflict with only an explicit omission choice', () {
    final conflict = BackupImportConflict.fromMap({
      'conflictId': 'dependent-record',
      'kind': 'DEPENDENT_ON_CONFLICT',
      'table': 'invoice_time_claims',
      'primaryKey': ['claim-1'],
      'localIdentifier': null,
      'localSummary': {
        'identifier': 'A referenced backup record requires a choice',
      },
      'backupSummary': <String, Object?>{},
      'allowedChoices': ['OMIT_BACKUP_RECORD'],
      'defaultChoice': null,
      'resolved': false,
      'selectedChoice': null,
    });

    expect(conflict.allowedChoices, ['OMIT_BACKUP_RECORD']);
    expect(conflict.defaultChoice, isNull);
    expect(conflict.resolved, isFalse);
  });

  test('accepts unresolved actionable conflicts without a default', () {
    final activeJobDeletion = BackupImportConflict.fromMap({
      'conflictId': 'active-job-deletion',
      'kind': 'ACTIVE_JOB_DELETION',
      'table': 'job_deletions',
      'primaryKey': ['job-1'],
      'localIdentifier': 'job-1',
      'localSummary': {'identifier': 'Live local job'},
      'backupSummary': <String, Object?>{},
      'allowedChoices': ['KEEP_LOCAL'],
      'defaultChoice': null,
      'resolved': false,
      'selectedChoice': null,
    });
    final gpsDeletion = BackupImportConflict.fromMap({
      'conflictId': 'gps-deletion-coverage',
      'kind': 'GPS_DELETION_COVERAGE',
      'table': 'location_deletion_ranges',
      'primaryKey': ['range-1'],
      'localIdentifier': null,
      'localSummary': {'identifier': 'Unproven local GPS points are covered'},
      'backupSummary': <String, Object?>{},
      'allowedChoices': ['KEEP_LOCAL', 'APPLY_BACKUP_DELETION'],
      'defaultChoice': null,
      'resolved': false,
      'selectedChoice': null,
    });
    final dependent = BackupImportConflict.fromMap({
      'conflictId': 'dependent-record',
      'kind': 'DEPENDENT_ON_CONFLICT',
      'table': 'invoice_time_claims',
      'primaryKey': ['claim-1'],
      'localIdentifier': null,
      'localSummary': {
        'identifier': 'A referenced backup record requires a choice',
      },
      'backupSummary': <String, Object?>{},
      'allowedChoices': ['OMIT_BACKUP_RECORD'],
      'defaultChoice': null,
      'resolved': false,
      'selectedChoice': null,
    });

    expect(activeJobDeletion.allowedChoices, ['KEEP_LOCAL']);
    expect(activeJobDeletion.defaultChoice, isNull);
    expect(gpsDeletion.allowedChoices, ['KEEP_LOCAL', 'APPLY_BACKUP_DELETION']);
    expect(gpsDeletion.defaultChoice, isNull);
    expect(dependent.allowedChoices, ['OMIT_BACKUP_RECORD']);
    expect(dependent.defaultChoice, isNull);
  });

  test(
    'accepts unresolved actionable conflicts with a null default choice',
    () {
      final conflict = BackupImportConflict.fromMap({
        'conflictId': 'ordinary-conflict',
        'kind': 'EXISTING_RECORD_DIFFERS',
        'table': 'customers',
        'primaryKey': ['customer-1'],
        'localIdentifier': null,
        'localSummary': <String, Object?>{},
        'backupSummary': <String, Object?>{},
        'allowedChoices': ['KEEP_LOCAL'],
        'defaultChoice': null,
        'resolved': false,
        'selectedChoice': null,
      });
      expect(conflict.allowedChoices, ['KEEP_LOCAL']);
      expect(conflict.defaultChoice, isNull);
      expect(conflict.resolved, isFalse);
    },
  );

  test('rejects defaults and selections outside an actionable conflict', () {
    expect(
      () => BackupImportConflict.fromMap({
        'conflictId': 'ordinary-conflict',
        'kind': 'EXISTING_RECORD_DIFFERS',
        'table': 'customers',
        'primaryKey': ['customer-1'],
        'localIdentifier': null,
        'localSummary': <String, Object?>{},
        'backupSummary': <String, Object?>{},
        'allowedChoices': ['KEEP_LOCAL'],
        'defaultChoice': 'OMIT_BACKUP_RECORD',
        'resolved': false,
        'selectedChoice': null,
      }),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => BackupImportConflict.fromMap({
        'conflictId': 'invalid-selection',
        'kind': 'ACTIVE_JOB_DELETION',
        'table': 'job_deletions',
        'primaryKey': ['job-1'],
        'localIdentifier': 'job-1',
        'localSummary': <String, Object?>{},
        'backupSummary': <String, Object?>{},
        'allowedChoices': ['KEEP_LOCAL'],
        'defaultChoice': null,
        'resolved': false,
        'selectedChoice': 'OMIT_BACKUP_RECORD',
      }),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => BackupImportConflict.fromMap({
        'conflictId': 'unresolved-selection',
        'kind': 'ACTIVE_JOB_DELETION',
        'table': 'job_deletions',
        'primaryKey': ['job-1'],
        'localIdentifier': 'job-1',
        'localSummary': <String, Object?>{},
        'backupSummary': <String, Object?>{},
        'allowedChoices': ['KEEP_LOCAL'],
        'defaultChoice': null,
        'resolved': false,
        'selectedChoice': 'KEEP_LOCAL',
      }),
      throwsA(isA<FormatException>()),
    );
  });

  test(
    'preserves blocking conflicts without offering an unsafe resolution',
    () {
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
    },
  );

  test(
    'decodes explicit local and backup choices for divergent entity heads',
    () {
      final conflict = BackupImportConflict.fromMap({
        'conflictId': 'divergent-customer-head',
        'kind': 'EXISTING_RECORD_DIFFERS',
        'table': 'entity_heads',
        'primaryKey': ['customer', 'customer-id'],
        'localIdentifier': null,
        'localSummary': <String, Object?>{},
        'backupSummary': <String, Object?>{},
        'allowedChoices': <String>[
          'MERGE_KEEP_LOCAL_CONTENT',
          'MERGE_USE_BACKUP_CONTENT',
        ],
        'defaultChoice': 'MERGE_KEEP_LOCAL_CONTENT',
        'resolved': false,
        'selectedChoice': null,
      });

      expect(conflict.allowedChoices, [
        'MERGE_KEEP_LOCAL_CONTENT',
        'MERGE_USE_BACKUP_CONTENT',
      ]);
      expect(conflict.defaultChoice, 'MERGE_KEEP_LOCAL_CONTENT');
      expect(conflict.resolved, isFalse);
    },
  );
}

Map<String, Object?> _preview({
  required String importId,
  required String previewToken,
  required int recordCount,
  int addedCount = 0,
  int changedCount = 0,
  int deletedCount = 0,
  int unchangedCount = 0,
  int unresolvedReferenceCount = 0,
  int timeOverlapCount = 0,
  int conflictCount = 0,
  int schemaVersion = currentBackupImportSchemaVersion,
  int mediaCount = 0,
  int mediaBytes = 0,
  int stagedRecordBytes = 0,
}) => {
  'importId': importId,
  'previewToken': previewToken,
  'schemaVersion': schemaVersion,
  'localStateVersion': 7,
  'recordCount': recordCount,
  'mediaCount': mediaCount,
  'mediaBytes': mediaBytes,
  'stagedRecordBytes': stagedRecordBytes,
  'addedCount': addedCount,
  'changedCount': changedCount,
  'deletedCount': deletedCount,
  'unchangedCount': unchangedCount,
  'unresolvedReferenceCount': unresolvedReferenceCount,
  'timeOverlapCount': timeOverlapCount,
  'conflictCount': conflictCount,
  'openSessionCount': 0,
  'localOpenSessionCount': 0,
  'applyReady': conflictCount == 0,
};
