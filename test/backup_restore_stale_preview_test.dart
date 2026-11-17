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
          return {'documentId': 'stale-preview-document', 'totalBytes': 3};
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

  testWidgets('stale apply disables retry and keeps restore screen open', (
    tester,
  ) async {
    var applyCalls = 0;
    messenger.setMockMethodCallHandler(importChannel, (call) async {
      switch (call.method) {
        case 'readBackupImportConflictPage':
          return {
            'importId': 'stale-preview-import',
            'previewToken': 'stale-preview-token',
            'nextCursor': null,
            'done': true,
            'conflicts': <Object?>[],
          };
        case 'applyBackupImport':
          applyCalls++;
          throw PlatformException(
            code: 'PREVIEW_STALE',
            message: 'Local data changed after this preview.',
          );
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
          importId: 'stale-preview-import',
          previewToken: 'stale-preview-token',
          schemaVersion: currentBackupImportSchemaVersion,
          localStateVersion: 12,
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
          applyReady: false,
        ),
        metrics: BackupArchiveReadMetrics(
          recordCount: 0,
          mediaCount: 0,
          recordBytes: 0,
          mediaBytes: 0,
          archiveBytes: 0,
          archiveSha256: List<int>.filled(32, 0),
        ),
        stagedRecordBytes: 0,
        api: BackupImportApi(channel: importChannel),
      );
    }

    bool? routeResult;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () async {
                  routeResult = await Navigator.of(context).push<bool>(
                    MaterialPageRoute<bool>(
                      builder: (_) => BackupRestoreScreen(
                        fileApi: BackupFileApi(channel: fileChannel),
                        previewBackup: previewBackup,
                      ),
                    ),
                  );
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
    await tester.pumpAndSettle();

    final applyButton = find.byKey(const ValueKey('restore-apply'));
    await tester.ensureVisible(applyButton);
    await tester.pumpAndSettle();
    await tester.tap(applyButton);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Apply reviewed merge'));
    await tester.pumpAndSettle();

    expect(applyCalls, 1);
    expect(routeResult, isNull);
    expect(
      find.text(
        'No records were restored because this preview is no longer current.',
      ),
      findsOneWidget,
    );
    expect(
      find.textContaining('Discard this preview and select the backup again'),
      findsOneWidget,
    );
    expect(
      find.textContaining('This preview cannot be applied again.'),
      findsOneWidget,
    );
    expect(tester.widget<FilledButton>(applyButton).onPressed, isNull);

    await tester.ensureVisible(
      find.byKey(const ValueKey('restore-discard-preview')),
    );
    await tester.tap(find.byKey(const ValueKey('restore-discard-preview')));
    await tester.pumpAndSettle();
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
}
