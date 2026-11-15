import 'dart:async';
import 'dart:typed_data';

import 'package:clockrove/backup/backup_export_screen.dart';
import 'package:clockrove/backup/backup_file_api.dart';
import 'package:clockrove/backup/backup_snapshot_exporter.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('clockrove/backup-files/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  late Future<Object?> Function(MethodCall call) respond;

  setUp(() {
    calls.clear();
    respond = (_) async => throw StateError('Unexpected backup file call.');
    messenger.setMockMethodCallHandler(channel, (call) {
      calls.add(call);
      return respond(call);
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  testWidgets('requires a long matching password before reading work', (
    tester,
  ) async {
    var exportCalls = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: BackupExportScreen(
          exportBackup: ({isCancelled, onProgress}) async {
            exportCalls++;
            return _snapshot();
          },
          encryptArchive: ({required password, required archive}) async =>
              Uint8List.fromList([1, 2, 3]),
        ),
      ),
    );

    expect(find.textContaining('Clockrove cannot recover it'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('backup-password')), 'short');
    await tester.enterText(
      find.byKey(const ValueKey('backup-password-confirmation')),
      'short',
    );
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));
    await tester.pump();

    expect(find.textContaining('at least 12 UTF-8 bytes'), findsOneWidget);
    expect(exportCalls, 0);
    expect(calls, isEmpty);
  });

  testWidgets('exports, encrypts, saves in bounded chunks and clears passwords', (
    tester,
  ) async {
    var written = 0;
    final chunkLengths = <int>[];
    respond = (call) async {
      switch (call.method) {
        case 'createBackupDocument':
          final args = call.arguments as Map;
          expect(args['totalBytes'], maxBackupDocumentChunkBytes + 3);
          expect((args['suggestedName'] as String).endsWith('.clockrove'), isTrue);
          return 'opaque-document-id';
        case 'writeBackupDocumentChunk':
          final args = call.arguments as Map;
          expect(args['documentId'], 'opaque-document-id');
          expect(args['offset'], written);
          final bytes = args['bytes'] as Uint8List;
          expect(bytes.length, lessThanOrEqualTo(maxBackupDocumentChunkBytes));
          chunkLengths.add(bytes.length);
          written += bytes.length;
          return written;
        case 'finishBackupDocument':
          expect((call.arguments as Map)['documentId'], 'opaque-document-id');
          expect(written, maxBackupDocumentChunkBytes + 3);
          return true;
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    await tester.pumpWidget(
      MaterialApp(
        home: BackupExportScreen(
          exportBackup: ({isCancelled, onProgress}) async {
            onProgress?.call(
              const BackupSnapshotProgress(
                recordsRead: 1,
                totalRecords: 1,
                mediaBytesRead: 0,
                totalMediaBytes: 0,
              ),
            );
            return _snapshot(openSessionCount: 1);
          },
          encryptArchive: ({required password, required archive}) async {
            expect(password, 'a-long-backup-password');
            expect(archive, orderedEquals([7, 8, 9]));
            return Uint8List(maxBackupDocumentChunkBytes + 3);
          },
          fileApi: BackupFileApi(channel: channel),
        ),
      ),
    );
    await tester.enterText(
      find.byKey(const ValueKey('backup-password')),
      'a-long-backup-password',
    );
    await tester.enterText(
      find.byKey(const ValueKey('backup-password-confirmation')),
      'a-long-backup-password',
    );
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));

    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 100));
      if (find.text('Encrypted backup saved.').evaluate().isNotEmpty) break;
    }

    expect(find.text('Encrypted backup saved.'), findsOneWidget);
    expect(find.text('This backup includes 1 open timer. Your timer will keep running.'), findsOneWidget);
    expect(chunkLengths, [maxBackupDocumentChunkBytes, 3]);
    expect(calls.map((call) => call.method), [
      'createBackupDocument',
      'writeBackupDocumentChunk',
      'writeBackupDocumentChunk',
      'finishBackupDocument',
    ]);
    expect(
      tester.widget<TextField>(find.byKey(const ValueKey('backup-password')))
          .controller!
          .text,
      isEmpty,
    );
  });

  testWidgets('picker cancellation reports that no complete backup was saved', (
    tester,
  ) async {
    respond = (call) async {
      expect(call.method, 'createBackupDocument');
      return null;
    };
    await tester.pumpWidget(
      MaterialApp(
        home: BackupExportScreen(
          exportBackup: ({isCancelled, onProgress}) async => _snapshot(),
          encryptArchive: ({required password, required archive}) async =>
              Uint8List.fromList([1, 2, 3]),
          fileApi: BackupFileApi(channel: channel),
        ),
      ),
    );
    await tester.enterText(
      find.byKey(const ValueKey('backup-password')),
      'a-long-backup-password',
    );
    await tester.enterText(
      find.byKey(const ValueKey('backup-password-confirmation')),
      'a-long-backup-password',
    );
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));

    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 100));
      if (find.text('Save cancelled. No complete backup was saved.').evaluate().isNotEmpty) break;
    }
    expect(find.text('Save cancelled. No complete backup was saved.'), findsOneWidget);
    expect(calls.map((call) => call.method), ['createBackupDocument']);
  });

  testWidgets('retries an ambiguous finish response idempotently', (tester) async {
    var finishCalls = 0;
    respond = (call) async {
      switch (call.method) {
        case 'createBackupDocument':
          return 'opaque-document-id';
        case 'writeBackupDocumentChunk':
          return (call.arguments as Map)['bytes'].length as int;
        case 'finishBackupDocument':
          finishCalls++;
          if (finishCalls == 1) {
            // Native SAF completion may succeed even when its response is lost.
            throw PlatformException(code: 'RESPONSE_LOST');
          }
          return true;
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    await tester.pumpWidget(
      MaterialApp(
        home: BackupExportScreen(
          exportBackup: ({isCancelled, onProgress}) async => _snapshot(),
          encryptArchive: ({required password, required archive}) async =>
              Uint8List.fromList([1, 2, 3]),
          fileApi: BackupFileApi(channel: channel),
        ),
      ),
    );
    await _enterPassword(tester);
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));
    await _pumpUntil(tester, find.text('Encrypted backup saved.'));

    expect(finishCalls, 2);
    expect(calls.map((call) => call.method), [
      'createBackupDocument',
      'writeBackupDocumentChunk',
      'finishBackupDocument',
      'finishBackupDocument',
    ]);
    expect(tester.takeException(), isNull);
  });

  testWidgets('reports an unknown save outcome after both finish replies fail', (
    tester,
  ) async {
    respond = (call) async {
      switch (call.method) {
        case 'createBackupDocument':
          return 'opaque-document-id';
        case 'writeBackupDocumentChunk':
          return (call.arguments as Map)['bytes'].length as int;
        case 'finishBackupDocument':
          throw PlatformException(code: 'RESPONSE_LOST');
        case 'cancelBackupDocument':
          return true;
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    await tester.pumpWidget(
      MaterialApp(
        home: BackupExportScreen(
          exportBackup: ({isCancelled, onProgress}) async => _snapshot(),
          encryptArchive: ({required password, required archive}) async =>
              Uint8List.fromList([1, 2, 3]),
          fileApi: BackupFileApi(channel: channel),
        ),
      ),
    );
    await _enterPassword(tester);
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));
    await _pumpUntil(
      tester,
      find.text(
        'The save may have completed. Check the chosen folder before trying again.',
      ),
    );

    expect(
      calls.map((call) => call.method),
      [
        'createBackupDocument',
        'writeBackupDocumentChunk',
        'finishBackupDocument',
        'finishBackupDocument',
        'cancelBackupDocument',
      ],
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('cancel during snapshot reading does not start encryption', (
    tester,
  ) async {
    final pending = Completer<BackupSnapshotExport>();
    BackupCancellationCheck? isCancelled;
    var encryptCalls = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: BackupExportScreen(
          exportBackup: ({isCancelled: check, onProgress}) {
            isCancelled = check;
            return pending.future;
          },
          encryptArchive: ({required password, required archive}) async {
            encryptCalls++;
            return Uint8List.fromList([1]);
          },
        ),
      ),
    );
    await _enterPassword(tester);
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('cancel-backup-export')));

    expect(isCancelled?.call(), isTrue);
    pending.completeError(const BackupExportCancelledException());
    await _pumpUntil(tester, find.text('Backup cancelled. No complete backup file was saved.'));

    expect(encryptCalls, 0);
    expect(calls, isEmpty);
  });

  testWidgets('cancel during encryption prevents opening the file picker', (
    tester,
  ) async {
    final pendingEncryption = Completer<Uint8List>();
    await tester.pumpWidget(
      MaterialApp(
        home: BackupExportScreen(
          exportBackup: ({isCancelled, onProgress}) async => _snapshot(),
          encryptArchive: ({required password, required archive}) =>
              pendingEncryption.future,
        ),
      ),
    );
    await _enterPassword(tester);
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));
    await _pumpUntil(tester, find.text('Encrypting backup on this device…'));
    await tester.tap(find.byKey(const ValueKey('cancel-backup-export')));
    pendingEncryption.complete(Uint8List.fromList([1, 2, 3]));
    await _pumpUntil(tester, find.text('Backup cancelled. No complete backup file was saved.'));

    expect(calls, isEmpty);
  });

  testWidgets('cancel during chunk transfer discards the staged document', (
    tester,
  ) async {
    final firstWrite = Completer<Object?>();
    respond = (call) async {
      switch (call.method) {
        case 'createBackupDocument':
          return 'opaque-document-id';
        case 'writeBackupDocumentChunk':
          expect((call.arguments as Map)['offset'], 0);
          return firstWrite.future;
        case 'cancelBackupDocument':
          expect((call.arguments as Map)['documentId'], 'opaque-document-id');
          return true;
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };
    await tester.pumpWidget(
      MaterialApp(
        home: BackupExportScreen(
          exportBackup: ({isCancelled, onProgress}) async => _snapshot(),
          encryptArchive: ({required password, required archive}) async =>
              Uint8List(maxBackupDocumentChunkBytes + 3),
          fileApi: BackupFileApi(channel: channel),
        ),
      ),
    );
    await _enterPassword(tester);
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));
    await _pumpUntil(tester, find.byKey(const ValueKey('cancel-backup-export')));
    await tester.tap(find.byKey(const ValueKey('cancel-backup-export')));
    firstWrite.complete(maxBackupDocumentChunkBytes);
    await _pumpUntil(tester, find.text('Backup cancelled. No complete backup file was saved.'));

    expect(calls.map((call) => call.method), [
      'createBackupDocument',
      'writeBackupDocumentChunk',
      'cancelBackupDocument',
    ]);
  });
}

Future<void> _enterPassword(WidgetTester tester) async {
  await tester.enterText(
    find.byKey(const ValueKey('backup-password')),
    'a-long-backup-password',
  );
  await tester.enterText(
    find.byKey(const ValueKey('backup-password-confirmation')),
    'a-long-backup-password',
  );
}

Future<void> _pumpUntil(WidgetTester tester, Finder finder) async {
  for (var i = 0; i < 40; i++) {
    await tester.pump(const Duration(milliseconds: 100));
    if (finder.evaluate().isNotEmpty) return;
  }
  fail('The expected UI state did not appear: $finder');
}

BackupSnapshotExport _snapshot({int openSessionCount = 0}) =>
    BackupSnapshotExport(
      archiveBytes: Uint8List.fromList([7, 8, 9]),
      schemaVersion: 15,
      stateVersion: 20,
      capturedAtUtcMillis: 1700000000000,
      openSessionCount: openSessionCount,
      recordCount: 1,
      mediaCount: 0,
      mediaBytes: 0,
    );
