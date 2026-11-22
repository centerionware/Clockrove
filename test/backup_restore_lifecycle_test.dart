import 'dart:async';

import 'package:clockrove/backup/backup_file_api.dart';
import 'package:clockrove/backup/backup_restore_importer.dart';
import 'package:clockrove/backup/backup_restore_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const fileChannel = MethodChannel('clockrove/backup-files/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  testWidgets('can dispose while password authentication is pending', (
    tester,
  ) async {
    final pending = Completer<BackupRestoreImportResult>();
    var started = false;
    messenger.setMockMethodCallHandler(fileChannel, (call) async {
      return switch (call.method) {
        'openBackupDocumentForImport' => {
          'documentId': 'opaque-document',
          'totalBytes': 3,
        },
        'closeBackupDocumentImport' => true,
        _ => throw StateError('Unexpected file method ${call.method}.'),
      };
    });
    addTearDown(() => messenger.setMockMethodCallHandler(fileChannel, null));
    await tester.pumpWidget(
      MaterialApp(
        home: BackupRestoreScreen(
          fileApi: BackupFileApi(channel: fileChannel),
          previewBackup:
              ({
                required encryptedEnvelope,
                required password,
                cancellationToken,
              }) {
                started = true;
                return pending.future;
              },
        ),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('restore-choose-backup')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('restore-password')),
      'synthetic-passphrase',
    );
    await tester.tap(find.byKey(const ValueKey('restore-preview')));
    await tester.pump();
    await tester.pumpWidget(const SizedBox());
    expect(started, isTrue);
    pending.completeError(StateError('Authentication cancelled'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
}
