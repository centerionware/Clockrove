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
  const fileChannel = MethodChannel('test/clockrove/backup-files/v1');
  const importChannel = MethodChannel('test/clockrove/backup/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    messenger.setMockMethodCallHandler(fileChannel, (call) async {
      switch (call.method) {
        case 'openBackupDocumentForImport':
          return {'documentId': 'plain-document', 'totalBytes': 11};
        case 'readBackupDocumentChunk':
          final args = call.arguments as Map;
          if (args['offset'] != 0) {
            return {'offset': args['offset'], 'bytes': Uint8List(0)};
          }
          return {
            'offset': 0,
            'bytes': Uint8List.fromList([
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
            ]),
          };
        case 'closeBackupDocumentImport':
          return true;
        default:
          throw StateError('Unexpected file method ${call.method}.');
      }
    });
    messenger.setMockMethodCallHandler(importChannel, (call) async {
      switch (call.method) {
        case 'readBackupImportConflictPage':
          return {
            'importId': 'import-handle',
            'previewToken': 'preview-token',
            'nextCursor': null,
            'done': true,
            'conflicts': <Object?>[],
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

  testWidgets('restores a detected plaintext backup without a password', (
    tester,
  ) async {
    var plainPreviewCalls = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: BackupRestoreScreen(
          fileApi: BackupFileApi(channel: fileChannel),
          previewBackup: ({
            required BackupEncryptedSource encryptedEnvelope,
            required String password,
            BackupCryptoCancellationToken? cancellationToken,
          }) async => throw StateError('Encrypted preview should not run.'),
          previewPlainBackup:
              ({
                required BackupEncryptedSource archive,
                BackupCryptoCancellationToken? cancellationToken,
              }) async {
                plainPreviewCalls++;
                expect(archive.totalBytes, 11);
                return BackupRestoreImportResult(
                  preview: BackupImportPreview(
                    importId: 'import-handle',
                    previewToken: 'preview-token',
                    schemaVersion: currentBackupImportSchemaVersion,
                    localStateVersion: 1,
                    recordCount: 0,
                    mediaCount: 0,
                    mediaBytes: 0,
                    stagedRecordBytes: 0,
                    addedCount: 0,
                    changedCount: 0,
                    deletedCount: 0,
                    unchangedCount: 0,
                    unresolvedReferenceCount: 0,
                    timeOverlapCount: 0,
                    conflictCount: 0,
                    openSessionCount: 0,
                    localOpenSessionCount: 0,
                    applyReady: true,
                  ),
                  metrics: BackupArchiveReadMetrics(
                    recordCount: 0,
                    mediaCount: 0,
                    recordBytes: 0,
                    mediaBytes: 0,
                    archiveBytes: 11,
                    archiveSha256: List<int>.filled(32, 0),
                  ),
                  stagedRecordBytes: 0,
                  api: BackupImportApi(channel: importChannel),
                );
              },
        ),
      ),
    );

    expect(find.text('Restore backup'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('restore-password')), findsNothing);
    expect(
      find.text('This backup is not encrypted. No password is needed.'),
      findsOneWidget,
    );
    expect(find.text('Preview backup'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('restore-preview')));
    await tester.pumpAndSettle();

    expect(plainPreviewCalls, 1);
    expect(find.text('Restore preview'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
}
