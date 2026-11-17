import 'dart:ui' show CheckedState;

import 'package:clockrove/backup/backup_archive.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:clockrove/backup/backup_file_api.dart';
import 'package:clockrove/backup/backup_import_api.dart';
import 'package:clockrove/backup/backup_restore_importer.dart';
import 'package:clockrove/backup/backup_restore_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const fileChannel = MethodChannel('clockrove/backup-files/v1');
  const importChannel = MethodChannel('clockrove/backup/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    messenger.setMockMethodCallHandler(fileChannel, (call) async {
      switch (call.method) {
        case 'openBackupDocumentForImport':
          return {'documentId': 'restore-choice-document', 'totalBytes': 3};
        case 'readBackupDocumentChunk':
          final args = call.arguments as Map;
          return {
            'offset': args['offset'],
            'bytes': Uint8List.fromList([1, 2, 3]),
          };
        case 'closeBackupDocumentImport':
          return true;
        default:
          throw StateError('Unexpected file method ${call.method}.');
      }
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(fileChannel, null);
    messenger.setMockMethodCallHandler(importChannel, null);
  });

  testWidgets(
    'restore choice with null default is selected after page refresh',
    (tester) async {
      final semantics = tester.ensureSemantics();
      addTearDown(semantics.dispose);
      var pageReads = 0;
      var resolvedChoice = <String, Object?>{};
      messenger.setMockMethodCallHandler(importChannel, (call) async {
        switch (call.method) {
          case 'readBackupImportConflictPage':
            pageReads++;
            return {
              'importId': 'restore-choice-import',
              'previewToken': 'restore-choice-token',
              'nextCursor': null,
              'done': true,
              'conflicts': [
                {
                  'conflictId': 'deleted-comment',
                  'kind': 'ENTITY_DELETION_TOMBSTONE',
                  'table': 'task_comments',
                  'primaryKey': ['comment-1'],
                  'localIdentifier': 'comment-1',
                  'localSummary': {'identifier': 'Deleted local comment'},
                  'backupSummary': {'identifier': 'Comment in backup'},
                  'allowedChoices': [
                    'PRESERVE_LOCAL_DELETION',
                    'RESTORE_BACKUP_CONTENT',
                  ],
                  'defaultChoice': null,
                  'resolved': resolvedChoice.isNotEmpty,
                  if (resolvedChoice.isNotEmpty)
                    'selectedChoice': resolvedChoice['choice'],
                },
              ],
            };
          case 'resolveBackupImportConflict':
            final args = call.arguments as Map;
            expect(args['expectedLocalStateVersion'], 9);
            expect(args['conflictId'], 'deleted-comment');
            expect(args['choice'], 'RESTORE_BACKUP_CONTENT');
            resolvedChoice = Map<String, Object?>.from(args);
            return {'resolved': true};
          case 'closeBackupImport':
            return {'closed': true};
          default:
            throw StateError('Unexpected import method ${call.method}.');
        }
      });

      Future<BackupRestoreImportResult> previewBackup({
        required BackupEncryptedSource encryptedEnvelope,
        required String password,
        BackupCryptoCancellationToken? cancellationToken,
      }) async {
        expect(password, 'long-enough-password');
        await encryptedEnvelope.openRead().drain<void>();
        return BackupRestoreImportResult(
          preview: const BackupImportPreview(
            importId: 'restore-choice-import',
            previewToken: 'restore-choice-token',
            schemaVersion: currentBackupImportSchemaVersion,
            localStateVersion: 9,
            recordCount: 1,
            mediaCount: 0,
            mediaBytes: 0,
            stagedRecordBytes: 64,
            addedCount: 0,
            changedCount: 0,
            deletedCount: 0,
            unchangedCount: 0,
            unresolvedReferenceCount: 0,
            timeOverlapCount: 0,
            conflictCount: 1,
            openSessionCount: 0,
            localOpenSessionCount: 0,
            applyReady: false,
          ),
          metrics: BackupArchiveReadMetrics(
            recordCount: 1,
            mediaCount: 0,
            recordBytes: 64,
            mediaBytes: 0,
            archiveBytes: 128,
            archiveSha256: List<int>.filled(32, 0),
          ),
          stagedRecordBytes: 64,
          api: BackupImportApi(channel: importChannel),
        );
      }

      await tester.pumpWidget(
        MaterialApp(
          home: BackupRestoreScreen(
            fileApi: BackupFileApi(channel: fileChannel),
            previewBackup: previewBackup,
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

      const restoreLabel = 'Restore the content in this backup';
      final restoreOption = find.text(restoreLabel);
      await tester.ensureVisible(restoreOption);
      await tester.pumpAndSettle();
      final tile = find.ancestor(
        of: restoreOption,
        matching: find.byType(RadioListTile<String>),
      );
      expect(
        tester.getSemantics(tile).getSemanticsData().flagsCollection.isChecked,
        CheckedState.isFalse,
      );
      expect(pageReads, 1);

      await tester.tap(restoreOption);
      await tester.pumpAndSettle();

      expect(pageReads, 2);
      expect(resolvedChoice['choice'], 'RESTORE_BACKUP_CONTENT');
      expect(
        tester.getSemantics(tile).getSemanticsData().flagsCollection.isChecked,
        CheckedState.isTrue,
      );

      await tester.ensureVisible(
        find.byKey(const ValueKey('restore-discard-preview')),
      );
      await tester.tap(find.byKey(const ValueKey('restore-discard-preview')));
      await tester.pumpAndSettle();
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
}
