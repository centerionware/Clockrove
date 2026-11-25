import 'package:clockrove/backup/backup_archive.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:clockrove/backup/backup_file_api.dart';
import 'package:clockrove/backup/backup_import_api.dart';
import 'package:clockrove/backup/backup_restore_importer.dart';
import 'package:clockrove/backup/backup_restore_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/localized_copy.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const fileChannel = MethodChannel('clockrove/backup-files/v1');
  const importChannel = MethodChannel('clockrove/backup/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final fileCalls = <MethodCall>[];
  final importCalls = <MethodCall>[];
  var conflictResolved = false;
  var multiPageMode = false;
  var openSessionResolved = false;
  var deletionConflictMode = false;
  var unsupportedConflictMode = false;
  var summaryConflictMode = false;
  var activeJobDeletionResolved = false;
  var gpsDeletionResolved = false;
  var referenceConflictResolved = false;
  var overlapConflictResolved = false;
  late Future<Object?> Function(MethodCall call) respondToFile;
  setUp(() {
    fileCalls.clear();
    importCalls.clear();
    conflictResolved = false;
    multiPageMode = false;
    openSessionResolved = false;
    deletionConflictMode = false;
    unsupportedConflictMode = false;
    summaryConflictMode = false;
    activeJobDeletionResolved = false;
    gpsDeletionResolved = false;
    referenceConflictResolved = false;
    overlapConflictResolved = false;
    respondToFile = (_) async => throw StateError('Unexpected file call.');
    messenger.setMockMethodCallHandler(fileChannel, (call) {
      fileCalls.add(call);
      return respondToFile(call);
    });
    messenger.setMockMethodCallHandler(importChannel, (call) async {
      importCalls.add(call);
      switch (call.method) {
        case 'readBackupImportConflictPage':
          final args = call.arguments as Map;
          if (deletionConflictMode) {
            return {
              'importId': 'import-handle',
              'previewToken': 'preview-token',
              'nextCursor': null,
              'done': true,
              'conflicts': [
                {
                  'conflictId': 'active-job-deletion',
                  'kind': 'ACTIVE_JOB_DELETION',
                  'table': 'job_deletions',
                  'primaryKey': ['job-1'],
                  'localIdentifier': 'job-1',
                  'localSummary': {'identifier': 'Live local job'},
                  'backupSummary': {'identifier': 'Backup job deletion'},
                  'allowedChoices': ['KEEP_LOCAL'],
                  'defaultChoice': null,
                  'resolved': activeJobDeletionResolved,
                  if (activeJobDeletionResolved) 'selectedChoice': 'KEEP_LOCAL',
                },
                {
                  'conflictId': 'gps-deletion-coverage',
                  'kind': 'GPS_DELETION_COVERAGE',
                  'table': 'location_deletion_ranges',
                  'primaryKey': ['range-1'],
                  'localIdentifier': null,
                  'localSummary': {
                    'identifier': 'Unproven local GPS points are covered',
                  },
                  'backupSummary': {'identifier': 'Backup deletion range'},
                  'allowedChoices': ['KEEP_LOCAL', 'APPLY_BACKUP_DELETION'],
                  'defaultChoice': null,
                  'resolved': gpsDeletionResolved,
                  if (gpsDeletionResolved)
                    'selectedChoice': 'APPLY_BACKUP_DELETION',
                },
              ],
            };
          }
          if (unsupportedConflictMode) {
            return {
              'importId': 'import-handle',
              'previewToken': 'preview-token',
              'nextCursor': null,
              'done': true,
              'conflicts': [
                {
                  'conflictId': 'unsupported-divergent-head',
                  'kind': 'EXISTING_RECORD_DIFFERS',
                  'table': 'entity_heads',
                  'primaryKey': ['customer', 'customer-1'],
                  'localIdentifier': null,
                  'localSummary': <String, Object?>{},
                  'backupSummary': <String, Object?>{},
                  'allowedChoices': <String>[],
                  'defaultChoice': null,
                  'resolved': false,
                  'selectedChoice': null,
                },
              ],
            };
          }
          if (summaryConflictMode) {
            return {
              'importId': 'import-handle',
              'previewToken': 'preview-token',
              'nextCursor': null,
              'done': true,
              'conflicts': [
                {
                  'conflictId': 'dependent-reference-conflict',
                  'kind': 'DEPENDENT_ON_CONFLICT',
                  'table': 'invoice_copies',
                  'primaryKey': ['copy-1'],
                  'localIdentifier': null,
                  'localSummary': <String, Object?>{},
                  'backupSummary': {'identifier': 'Copy from skipped invoice'},
                  'allowedChoices': ['OMIT_BACKUP_RECORD'],
                  'defaultChoice': null,
                  'resolved': referenceConflictResolved,
                  if (referenceConflictResolved)
                    'selectedChoice': 'OMIT_BACKUP_RECORD',
                },
                {
                  'conflictId': 'session-overlap-conflict',
                  'kind': 'SESSION_OVERLAP',
                  'table': 'sessions',
                  'primaryKey': ['session-2'],
                  'localIdentifier': 'session-1',
                  'localSummary': {'identifier': 'Local work interval'},
                  'backupSummary': {
                    'identifier': 'Overlapping backup interval',
                  },
                  'allowedChoices': ['KEEP_LOCAL', 'OMIT_BACKUP_SESSION'],
                  'defaultChoice': 'KEEP_LOCAL',
                  'resolved': overlapConflictResolved,
                  if (overlapConflictResolved)
                    'selectedChoice': 'OMIT_BACKUP_SESSION',
                },
              ],
            };
          }
          if (multiPageMode) {
            if (args['cursor'] == null) {
              return {
                'importId': 'import-handle',
                'previewToken': 'preview-token',
                'nextCursor': 'page-2-cursor',
                'done': false,
                'conflicts': [
                  {
                    'conflictId': 'customer-conflict',
                    'kind': 'EXISTING_RECORD_DIFFERS',
                    'table': 'customers',
                    'primaryKey': ['customer-1'],
                    'localIdentifier': 'customer-1',
                    'localSummary': {'name': 'Local customer'},
                    'backupSummary': {'name': 'Backup customer'},
                    'allowedChoices': ['KEEP_LOCAL'],
                    'defaultChoice': 'KEEP_LOCAL',
                    'resolved': conflictResolved,
                    if (conflictResolved) 'selectedChoice': 'KEEP_LOCAL',
                  },
                ],
              };
            }
            return {
              'importId': 'import-handle',
              'previewToken': 'preview-token',
              'nextCursor': null,
              'done': true,
              'conflicts': [
                {
                  'conflictId': 'open-session-conflict',
                  'kind': 'OPEN_SESSION',
                  'table': 'sessions',
                  'primaryKey': ['session-1'],
                  'localIdentifier': null,
                  'localSummary': <String, Object?>{},
                  'backupSummary': {'job': 'Backup job', 'open': true},
                  'allowedChoices': ['OMIT_OPEN_SESSION'],
                  'defaultChoice': 'OMIT_OPEN_SESSION',
                  'resolved': openSessionResolved,
                  if (openSessionResolved)
                    'selectedChoice': 'OMIT_OPEN_SESSION',
                },
              ],
            };
          }
          return {
            'importId': 'import-handle',
            'previewToken': 'preview-token',
            'nextCursor': null,
            'done': true,
            'conflicts': [
              {
                'conflictId': 'customer-conflict',
                'kind': 'EXISTING_RECORD_DIFFERS',
                'table': 'customers',
                'primaryKey': ['customer-1'],
                'localIdentifier': 'customer-1',
                'localSummary': {'name': 'Local customer'},
                'backupSummary': {'name': 'Backup customer'},
                'allowedChoices': ['KEEP_LOCAL', 'OMIT_BACKUP_RECORD'],
                'defaultChoice': 'KEEP_LOCAL',
                'resolved': conflictResolved,
                if (conflictResolved) 'selectedChoice': 'KEEP_LOCAL',
              },
            ],
          };
        case 'resolveBackupImportConflict':
          final args = call.arguments as Map;
          expect(args['expectedLocalStateVersion'], 7);
          if (deletionConflictMode &&
              args['conflictId'] == 'active-job-deletion') {
            expect(args['choice'], 'KEEP_LOCAL');
            activeJobDeletionResolved = true;
          } else if (deletionConflictMode &&
              args['conflictId'] == 'gps-deletion-coverage') {
            expect(args['choice'], 'APPLY_BACKUP_DELETION');
            gpsDeletionResolved = true;
          } else if (multiPageMode &&
              args['conflictId'] == 'open-session-conflict') {
            expect(args['choice'], 'OMIT_OPEN_SESSION');
            openSessionResolved = true;
          } else if (summaryConflictMode &&
              args['conflictId'] == 'dependent-reference-conflict') {
            expect(args['choice'], 'OMIT_BACKUP_RECORD');
            referenceConflictResolved = true;
          } else if (summaryConflictMode &&
              args['conflictId'] == 'session-overlap-conflict') {
            expect(args['choice'], 'OMIT_BACKUP_SESSION');
            overlapConflictResolved = true;
          } else {
            expect(args['conflictId'], 'customer-conflict');
            expect(args['choice'], 'KEEP_LOCAL');
            conflictResolved = true;
          }
          return {'resolved': true};
        case 'applyBackupImport':
          final args = call.arguments as Map;
          expect(args['expectedLocalStateVersion'], 7);
          return {
            'importId': 'import-handle',
            'stateVersion': 8,
            'insertedCount': 3,
            'skippedCount': 1,
          };
        case 'closeBackupImport':
          return {'closed': true};
        default:
          throw StateError('Unexpected import method ${call.method}.');
      }
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(fileChannel, null);
    messenger.setMockMethodCallHandler(importChannel, null);
  });

  testWidgets('authenticates, resolves a conflict, confirms and applies', (
    tester,
  ) async {
    respondToFile = (call) async {
      switch (call.method) {
        case 'openBackupDocumentForImport':
          return {'documentId': 'opaque-document', 'totalBytes': 3};
        case 'readBackupDocumentChunk':
          final args = call.arguments as Map;
          expect(args['offset'], 0);
          return {
            'offset': 0,
            'bytes': Uint8List.fromList([1, 2, 3]),
          };
        case 'closeBackupDocumentImport':
          return true;
        default:
          throw StateError('Unexpected file method ${call.method}.');
      }
    };

    var routeResult = false;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () async {
                  routeResult =
                      await Navigator.of(context).push<bool>(
                        MaterialPageRoute<bool>(
                          builder: (_) => BackupRestoreScreen(
                            fileApi: BackupFileApi(channel: fileChannel),
                            previewBackup: _fakePreviewRunner(),
                          ),
                        ),
                      ) ??
                      false;
                },
                child: const Text('Open restore'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open restore'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('restore-password')),
      'long-enough-password',
    );
    await tester.tap(find.byKey(const ValueKey('restore-preview')));
    await tester.pump();
    await tester.pumpAndSettle();

    expect(find.text('Restore preview'), findsOneWidget);
    await _scrollRestoreUntilVisible(
      tester,
      find.textContaining('Local customer'),
    );
    expect(find.textContaining('Local customer'), findsOneWidget);
    expect(find.textContaining('Backup customer'), findsOneWidget);
    expect(find.text('Keep the version on this device'), findsOneWidget);
    await _scrollRestoreUntilVisible(
      tester,
      find.text('Keep the version on this device'),
    );
    await tester.tap(find.text('Keep the version on this device'));
    await tester.pumpAndSettle();
    await _scrollRestoreUntilVisible(
      tester,
      find.byKey(const ValueKey('restore-apply')),
    );
    await tester.tap(find.byKey(const ValueKey('restore-apply')));
    await tester.pumpAndSettle();
    final confirmApply = find.descendant(
      of: find.byType(AlertDialog),
      matching: find.text('Apply reviewed merge'),
    );
    expect(confirmApply, findsOneWidget);
    await tester.tap(confirmApply);
    await tester.pumpAndSettle();

    expect(routeResult, isTrue);
    expect(importCalls.map((call) => call.method), [
      'readBackupImportConflictPage',
      'resolveBackupImportConflict',
      'readBackupImportConflictPage',
      'applyBackupImport',
      'closeBackupImport',
    ]);
    expect(fileCalls.map((call) => call.method), [
      'openBackupDocumentForImport',
      'readBackupDocumentChunk',
      'closeBackupDocumentImport',
    ]);
  });
  testWidgets('keeps the selected file available after failed authentication', (
    tester,
  ) async {
    var previewCalls = 0;
    respondToFile = (call) async => switch (call.method) {
      'openBackupDocumentForImport' => {
        'documentId': 'opaque-document',
        'totalBytes': 3,
      },
      'closeBackupDocumentImport' => true,
      _ => throw StateError('Unexpected file method ${call.method}.'),
    };
    await tester.pumpWidget(
      MaterialApp(
        home: BackupRestoreScreen(
          fileApi: BackupFileApi(channel: fileChannel),
          previewBackup:
              ({
                required encryptedEnvelope,
                required password,
                cancellationToken,
              }) async {
                previewCalls++;
                throw const FormatException('Authentication failed.');
              },
        ),
      ),
    );

    await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('restore-password')),
      'short',
    );
    await tester.tap(find.byKey(const ValueKey('restore-preview')));
    await tester.pump();
    expect(find.textContaining('at least 12 UTF-8 bytes'), findsOneWidget);
    expect(previewCalls, 0);
    await tester.enterText(
      find.byKey(const ValueKey('restore-password')),
      'synthetic-passphrase',
    );
    await tester.tap(find.byKey(const ValueKey('restore-preview')));
    await tester.pumpAndSettle();
    expect(find.text(englishCopy(AppMessage.restoreFailed)), findsOneWidget);
    // A failed restore names the gate that stopped it so the report is actionable.
    expect(
      find.text(englishCopy(AppMessage.restoreErrorCode, {'code': 'format'})),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('restore-selected-file')), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets(
    'preview candidate counts remain stable as conflicts are resolved',
    (tester) async {
      summaryConflictMode = true;
      respondToFile = (call) async => switch (call.method) {
        'openBackupDocumentForImport' => {
          'documentId': 'opaque-document',
          'totalBytes': 3,
        },
        'readBackupDocumentChunk' => {
          'offset': 0,
          'bytes': Uint8List.fromList([1, 2, 3]),
        },
        'closeBackupDocumentImport' => true,
        _ => throw StateError('Unexpected file method ${call.method}.'),
      };

      await tester.pumpWidget(
        MaterialApp(
          home: BackupRestoreScreen(
            fileApi: BackupFileApi(channel: fileChannel),
            previewBackup: _fakePreviewRunner(
              conflictCount: 2,
              changedCount: 1,
              deletedCount: 1,
              unresolvedReferenceCount: 1,
              timeOverlapCount: 1,
            ),
          ),
        ),
      );
      await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const ValueKey('restore-password')),
        'long-enough-password',
      );
      await tester.tap(find.byKey(const ValueKey('restore-preview')));
      await tester.pumpAndSettle();

      Finder countInRow(String label, String count) {
        final row = find
            .ancestor(of: find.text(label), matching: find.byType(Row))
            .first;
        return find.descendant(of: row, matching: find.text(count));
      }

      expect(countInRow('Proposed additions', '0'), findsOneWidget);
      expect(countInRow('Existing records changed', '1'), findsOneWidget);
      expect(countInRow('Deletion markers', '1'), findsOneWidget);
      expect(countInRow('Unchanged records', '0'), findsOneWidget);
      expect(countInRow('Conflicts to review', '2'), findsOneWidget);
      expect(countInRow('Reference conflicts in backup', '1'), findsOneWidget);
      expect(
        countInRow('Time overlap conflicts in backup', '1'),
        findsOneWidget,
      );
      expect(
        find.text(
          'Deletion markers count backup tombstones, not every record they affect.',
        ),
        findsOneWidget,
      );

      await _scrollRestoreUntilVisible(
        tester,
        find.text('Omit this backup record'),
      );
      await tester.tap(find.text('Omit this backup record'));
      await tester.pumpAndSettle();
      await _scrollRestoreToTop(tester);
      expect(countInRow('Reference conflicts in backup', '1'), findsOneWidget);
      expect(
        countInRow('Time overlap conflicts in backup', '1'),
        findsOneWidget,
      );

      await _scrollRestoreUntilVisible(
        tester,
        find.text('Omit this backup session'),
      );
      await tester.tap(find.text('Omit this backup session'));
      await tester.pumpAndSettle();
      await _scrollRestoreToTop(tester);
      expect(countInRow('Reference conflicts in backup', '1'), findsOneWidget);
      expect(
        countInRow('Time overlap conflicts in backup', '1'),
        findsOneWidget,
      );

      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );

  testWidgets('renders deletion conflicts without native default choices', (
    tester,
  ) async {
    deletionConflictMode = true;
    respondToFile = (call) async => switch (call.method) {
      'openBackupDocumentForImport' => {
        'documentId': 'opaque-document',
        'totalBytes': 3,
      },
      'readBackupDocumentChunk' => {
        'offset': 0,
        'bytes': Uint8List.fromList([1, 2, 3]),
      },
      'closeBackupDocumentImport' => true,
      _ => throw StateError('Unexpected file method ${call.method}.'),
    };

    await tester.pumpWidget(
      MaterialApp(
        home: BackupRestoreScreen(
          fileApi: BackupFileApi(channel: fileChannel),
          previewBackup: _fakePreviewRunner(conflictCount: 2),
        ),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('restore-password')),
      'long-enough-password',
    );
    importCalls.clear();
    await tester.tap(find.byKey(const ValueKey('restore-preview')));
    await tester.pumpAndSettle();

    final activeJobCard = find.byKey(const ValueKey('active-job-deletion'));
    final gpsDeletionCard = find.byKey(const ValueKey('gps-deletion-coverage'));
    await _scrollRestoreUntilVisible(tester, activeJobCard);
    expect(activeJobCard, findsOneWidget);
    await tester.tap(
      find.descendant(
        of: activeJobCard,
        matching: find.text('Keep the version on this device'),
      ),
    );
    await tester.pumpAndSettle();
    await _scrollRestoreUntilVisible(tester, gpsDeletionCard);
    expect(gpsDeletionCard, findsOneWidget);
    await tester.tap(
      find.descendant(
        of: gpsDeletionCard,
        matching: find.text('Apply the deletion recorded in the backup'),
      ),
    );
    await tester.pumpAndSettle();

    expect(activeJobDeletionResolved, isTrue);
    expect(gpsDeletionResolved, isTrue);
    await _scrollRestoreUntilVisible(
      tester,
      find.byKey(const ValueKey('restore-apply')),
    );
    expect(
      tester
          .widget<FilledButton>(find.byKey(const ValueKey('restore-apply')))
          .onPressed,
      isNotNull,
    );
    expect(importCalls.map((call) => call.method), [
      'readBackupImportConflictPage',
      'resolveBackupImportConflict',
      'readBackupImportConflictPage',
      'resolveBackupImportConflict',
      'readBackupImportConflictPage',
    ]);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets('blocks apply for a conflict without supported choices', (
    tester,
  ) async {
    unsupportedConflictMode = true;
    respondToFile = (call) async => switch (call.method) {
      'openBackupDocumentForImport' => {
        'documentId': 'opaque-document',
        'totalBytes': 3,
      },
      'readBackupDocumentChunk' => {
        'offset': 0,
        'bytes': Uint8List.fromList([1, 2, 3]),
      },
      'closeBackupDocumentImport' => true,
      _ => throw StateError('Unexpected file method ${call.method}.'),
    };

    await tester.pumpWidget(
      MaterialApp(
        home: BackupRestoreScreen(
          fileApi: BackupFileApi(channel: fileChannel),
          previewBackup: _fakePreviewRunner(),
        ),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('restore-password')),
      'long-enough-password',
    );
    await tester.tap(find.byKey(const ValueKey('restore-preview')));
    await tester.pumpAndSettle();

    final unsupported = find.byKey(
      const ValueKey('unsupported-divergent-head'),
    );
    await _scrollRestoreUntilVisible(tester, unsupported);
    expect(
      find.descendant(
        of: unsupported,
        matching: find.text(
          'This app cannot resolve this conflict. Applying is blocked; discard this preview.',
        ),
      ),
      findsOneWidget,
    );
    await _scrollRestoreUntilVisible(
      tester,
      find.byKey(const ValueKey('restore-apply')),
    );
    expect(
      tester
          .widget<FilledButton>(find.byKey(const ValueKey('restore-apply')))
          .onPressed,
      isNull,
    );
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets('requires each conflict page and open session omission choice', (
    tester,
  ) async {
    multiPageMode = true;
    respondToFile = (call) async => switch (call.method) {
      'openBackupDocumentForImport' => {
        'documentId': 'opaque-document',
        'totalBytes': 3,
      },
      'readBackupDocumentChunk' => {
        'offset': 0,
        'bytes': Uint8List.fromList([1, 2, 3]),
      },
      'closeBackupDocumentImport' => true,
      _ => throw StateError('Unexpected file method ${call.method}.'),
    };

    await tester.pumpWidget(
      MaterialApp(
        home: BackupRestoreScreen(
          fileApi: BackupFileApi(channel: fileChannel),
          previewBackup: _fakePreviewRunner(
            conflictCount: 2,
            openSessionCount: 1,
          ),
        ),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('restore-password')),
      'long-enough-password',
    );
    await tester.tap(find.byKey(const ValueKey('restore-preview')));
    await tester.pumpAndSettle();

    await _scrollRestoreUntilVisible(
      tester,
      find.byKey(const ValueKey('restore-next-conflict-page')),
    );
    final nextPage = tester.widget<FilledButton>(
      find.byKey(const ValueKey('restore-next-conflict-page')),
    );
    expect(nextPage.onPressed, isNull);
    await _scrollRestoreUntilVisible(
      tester,
      find.text('Keep the version on this device'),
    );
    await tester.tap(find.text('Keep the version on this device'));
    await tester.pumpAndSettle();
    await _scrollRestoreUntilVisible(
      tester,
      find.byKey(const ValueKey('restore-next-conflict-page')),
    );
    expect(
      tester
          .widget<FilledButton>(
            find.byKey(const ValueKey('restore-next-conflict-page')),
          )
          .onPressed,
      isNotNull,
    );
    await tester.tap(find.byKey(const ValueKey('restore-next-conflict-page')));
    await tester.pumpAndSettle();

    await _scrollRestoreUntilVisible(
      tester,
      find.text('Omit this open session'),
    );
    expect(find.text('Open session in backup'), findsOneWidget);
    expect(find.text('Omit this open session'), findsOneWidget);
    await _scrollRestoreUntilVisible(
      tester,
      find.byKey(const ValueKey('restore-apply')),
    );
    final apply = tester.widget<FilledButton>(
      find.byKey(const ValueKey('restore-apply')),
    );
    expect(apply.onPressed, isNull);
    await _scrollRestoreUntilVisible(
      tester,
      find.text('Omit this open session'),
    );
    await tester.tap(find.text('Omit this open session'));
    await tester.pumpAndSettle();
    await _scrollRestoreUntilVisible(
      tester,
      find.byKey(const ValueKey('restore-apply')),
    );
    expect(
      tester
          .widget<FilledButton>(find.byKey(const ValueKey('restore-apply')))
          .onPressed,
      isNotNull,
    );
    expect(importCalls.map((call) => call.method), [
      'readBackupImportConflictPage',
      'resolveBackupImportConflict',
      'readBackupImportConflictPage',
      'readBackupImportConflictPage',
      'resolveBackupImportConflict',
      'readBackupImportConflictPage',
    ]);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets('blocks apply while a local timer is open', (tester) async {
    respondToFile = (call) async => switch (call.method) {
      'openBackupDocumentForImport' => {
        'documentId': 'opaque-document',
        'totalBytes': 3,
      },
      'readBackupDocumentChunk' => {
        'offset': 0,
        'bytes': Uint8List.fromList([1, 2, 3]),
      },
      'closeBackupDocumentImport' => true,
      _ => throw StateError('Unexpected file method ${call.method}.'),
    };

    await tester.pumpWidget(
      MaterialApp(
        home: BackupRestoreScreen(
          fileApi: BackupFileApi(channel: fileChannel),
          previewBackup: _fakePreviewRunner(localOpenSessionCount: 1),
        ),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('restore-password')),
      'long-enough-password',
    );
    await tester.tap(find.byKey(const ValueKey('restore-preview')));
    await tester.pumpAndSettle();

    await _scrollRestoreUntilVisible(
      tester,
      find.textContaining('A timer is running on this device'),
    );
    expect(
      find.textContaining('A timer is running on this device'),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('restore-apply')), findsNothing);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets(
    'choosing another file closes the previous native preview stage',
    (tester) async {
      var openedCount = 0;
      final closedDocuments = <String>[];
      respondToFile = (call) async {
        switch (call.method) {
          case 'openBackupDocumentForImport':
            openedCount++;
            return {
              'documentId': 'opaque-document-$openedCount',
              'totalBytes': 3,
            };
          case 'readBackupDocumentChunk':
            return {
              'offset': (call.arguments as Map)['offset'],
              'bytes': Uint8List.fromList([1, 2, 3]),
            };
          case 'closeBackupDocumentImport':
            closedDocuments.add(
              (call.arguments as Map)['documentId'] as String,
            );
            return true;
          default:
            throw StateError('Unexpected file method ${call.method}.');
        }
      };

      await tester.pumpWidget(
        MaterialApp(
          home: BackupRestoreScreen(
            fileApi: BackupFileApi(channel: fileChannel),
            previewBackup: _fakePreviewRunner(),
          ),
        ),
      );
      await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const ValueKey('restore-password')),
        'long-enough-password',
      );
      await tester.tap(find.byKey(const ValueKey('restore-preview')));
      await tester.pumpAndSettle();
      expect(find.text('Restore preview'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
      await tester.pumpAndSettle();

      expect(openedCount, 2);
      expect(importCalls.map((call) => call.method), [
        'readBackupImportConflictPage',
        'closeBackupImport',
      ]);
      expect(closedDocuments, ['opaque-document-1']);
      expect(find.text('Restore preview'), findsNothing);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      expect(closedDocuments, ['opaque-document-1', 'opaque-document-2']);
    },
  );
}

Future<void> _scrollRestoreToTop(WidgetTester tester) async {
  final scrollable = find
      .descendant(
        of: find.byType(CustomScrollView),
        matching: find.byType(Scrollable),
      )
      .first;
  await tester.drag(scrollable, const Offset(0, 4000));
  await tester.pumpAndSettle();
}

Future<void> _scrollRestoreUntilVisible(
  WidgetTester tester,
  Finder target,
) async {
  final scrollable = find
      .descendant(
        of: find.byType(CustomScrollView),
        matching: find.byType(Scrollable),
      )
      .first;
  for (var attempt = 0; attempt < 40; attempt++) {
    if (target.evaluate().isNotEmpty) {
      await tester.ensureVisible(target);
      await tester.pumpAndSettle();
      return;
    }
    await tester.drag(scrollable, const Offset(0, -250));
    await tester.pumpAndSettle();
  }
  expect(target, findsOneWidget);
  await tester.ensureVisible(target);
  await tester.pumpAndSettle();
}

BackupRestorePreviewRunner _fakePreviewRunner({
  int localOpenSessionCount = 0,
  int openSessionCount = 0,
  int conflictCount = 1,
  int changedCount = 0,
  int deletedCount = 0,
  int unresolvedReferenceCount = 0,
  int timeOverlapCount = 0,
}) =>
    ({
      required BackupEncryptedSource encryptedEnvelope,
      required String password,
      BackupCryptoCancellationToken? cancellationToken,
    }) async {
      expect(password, 'long-enough-password');
      await encryptedEnvelope.openRead().expand((bytes) => bytes).drain();
      final preview = BackupImportPreview(
        importId: 'import-handle',
        previewToken: 'preview-token',
        schemaVersion: currentBackupImportSchemaVersion,
        localStateVersion: 7,
        recordCount: conflictCount,
        mediaCount: 0,
        mediaBytes: 0,
        stagedRecordBytes: 64,
        addedCount: 0,
        changedCount: changedCount,
        deletedCount: deletedCount,
        unchangedCount: 0,
        unresolvedReferenceCount: unresolvedReferenceCount,
        timeOverlapCount: timeOverlapCount,
        conflictCount: conflictCount,
        openSessionCount: openSessionCount,
        localOpenSessionCount: localOpenSessionCount,
        applyReady: false,
      );
      return BackupRestoreImportResult(
        preview: preview,
        metrics: BackupArchiveReadMetrics(
          recordCount: conflictCount,
          mediaCount: 0,
          recordBytes: 64 * conflictCount,
          mediaBytes: 0,
          archiveBytes: 128,
          archiveSha256: List<int>.filled(32, 0),
        ),
        stagedRecordBytes: 64,
        api: BackupImportApi(
          channel: const MethodChannel('clockrove/backup/v1'),
        ),
      );
    };
