import 'dart:async';
import 'dart:typed_data';

import 'package:clockrove/backup/backup_crypto_v2.dart';
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
          encryptArchive: _fakeEncryptor(
            encrypted: _encryptedForArchiveSize(3),
          ),
        ),
      ),
    );

    expect(find.textContaining('Clockrove cannot recover it'), findsOneWidget);
    await tester.enterText(
      find.byKey(const ValueKey('backup-password')),
      'short',
    );
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

  testWidgets(
    'exports, encrypts, saves in bounded chunks and clears passwords',
    (tester) async {
      var written = 0;
      final chunkLengths = <int>[];
      respond = (call) async {
        switch (call.method) {
          case 'createBackupDocument':
            final args = call.arguments as Map;
            expect(args['totalBytes'], maxBackupDocumentChunkBytes + 3);
            expect(
              (args['suggestedName'] as String).endsWith('.clockrove'),
              isTrue,
            );
            return 'opaque-document-id';
          case 'writeBackupDocumentChunk':
            final args = call.arguments as Map;
            expect(args['documentId'], 'opaque-document-id');
            expect(args['offset'], written);
            final bytes = args['bytes'] as Uint8List;
            expect(
              bytes.length,
              lessThanOrEqualTo(maxBackupDocumentChunkBytes),
            );
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
              return _snapshot(
                openSessionCount: 1,
                archiveByteLength: 196399,
              );
            },
            encryptArchive: _fakeEncryptor(
              encrypted: Uint8List(maxBackupDocumentChunkBytes + 3),
              onArchive: (password, archive) {
                expect(password, 'a-long-backup-password');
                expect(archive.length, 196399);
              },
            ),
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

      final status = find.byKey(const ValueKey('backup-export-message'));
      await tester.scrollUntilVisible(
        status,
        250,
        scrollable: _backupScreenScrollable(),
      );
      expect(tester.widget<Text>(status).data, 'Encrypted backup saved.');
      expect(
        find.text(
          'This backup includes 1 open timer. Your timer will keep running.',
        ),
        findsOneWidget,
      );
      expect(chunkLengths, [maxBackupDocumentChunkBytes, 3]);
      expect(calls.map((call) => call.method), [
        'createBackupDocument',
        'writeBackupDocumentChunk',
        'writeBackupDocumentChunk',
        'finishBackupDocument',
      ]);
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('backup-password')))
            .controller!
            .text,
        isEmpty,
      );
    },
  );

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
          encryptArchive: _fakeEncryptor(
            encrypted: _encryptedForArchiveSize(3),
          ),
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
      if (find
          .text('Save cancelled. No complete backup was saved.')
          .evaluate()
          .isNotEmpty) {
        break;
      }
    }
    expect(
      find.text('Save cancelled. No complete backup was saved.'),
      findsOneWidget,
    );
    expect(calls.map((call) => call.method), ['createBackupDocument']);
  });

  testWidgets('retries an ambiguous finish response idempotently', (
    tester,
  ) async {
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
          encryptArchive: _fakeEncryptor(
            encrypted: _encryptedForArchiveSize(3),
          ),
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

  testWidgets(
    'reports an unknown save outcome after both finish replies fail',
    (tester) async {
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
            encryptArchive: _fakeEncryptor(
              encrypted: _encryptedForArchiveSize(3),
            ),
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

      expect(calls.map((call) => call.method), [
        'createBackupDocument',
        'writeBackupDocumentChunk',
        'finishBackupDocument',
        'finishBackupDocument',
        'cancelBackupDocument',
      ]);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('cancel during snapshot reading does not start encryption', (
    tester,
  ) async {
    final pending = Completer<BackupSnapshotStreamExport>();
    BackupCancellationCheck? cancellationCheck;
    var encryptCalls = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: BackupExportScreen(
          exportBackup: ({isCancelled, onProgress}) {
            cancellationCheck = isCancelled;
            return pending.future;
          },
          encryptArchive: _fakeEncryptor(
            encrypted: _encryptedForArchiveSize(3),
            onArchive: (_, _) => encryptCalls++,
          ),
        ),
      ),
    );
    await _enterPassword(tester);
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));
    await tester.pump();
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('cancel-backup-export')),
      250,
      scrollable: _backupScreenScrollable(),
    );
    await tester.tap(find.byKey(const ValueKey('cancel-backup-export')));

    expect(cancellationCheck?.call(), isTrue);
    pending.completeError(const BackupExportCancelledException());
    await _pumpUntil(
      tester,
      find.text('Backup cancelled. No complete backup file was saved.'),
    );

    expect(encryptCalls, 0);
    expect(calls, isEmpty);
  });

  testWidgets('cancel during encryption discards the staged document', (
    tester,
  ) async {
    final pendingEncryption = Completer<void>();
    respond = (call) async {
      switch (call.method) {
        case 'createBackupDocument':
          return 'opaque-document-id';
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
          encryptArchive: _fakeEncryptor(
            encrypted: _encryptedForArchiveSize(3),
            beforeWrite: pendingEncryption.future,
          ),
          fileApi: BackupFileApi(channel: channel),
        ),
      ),
    );
    await _enterPassword(tester);
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));
    await _pumpUntil(tester, find.text('Encrypting and saving backup…'));
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('cancel-backup-export')),
      250,
      scrollable: _backupScreenScrollable(),
    );
    await tester.tap(find.byKey(const ValueKey('cancel-backup-export')));
    pendingEncryption.complete();
    await _pumpUntil(
      tester,
      find.text('Backup cancelled. No complete backup file was saved.'),
    );

    expect(calls.map((call) => call.method), [
      'createBackupDocument',
      'cancelBackupDocument',
    ]);
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
          exportBackup: ({isCancelled, onProgress}) async =>
              _snapshot(archiveByteLength: 196399),
          encryptArchive: _fakeEncryptor(
            encrypted: Uint8List(maxBackupDocumentChunkBytes + 3),
          ),
          fileApi: BackupFileApi(channel: channel),
        ),
      ),
    );
    await _enterPassword(tester);
    await tester.tap(find.byKey(const ValueKey('create-encrypted-backup')));
    await _pumpUntil(
      tester,
      find.byKey(const ValueKey('cancel-backup-export')),
    );
    await tester.tap(find.byKey(const ValueKey('cancel-backup-export')));
    firstWrite.complete(maxBackupDocumentChunkBytes);
    await _pumpUntil(
      tester,
      find.text('Backup cancelled. No complete backup file was saved.'),
    );

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
  await tester.scrollUntilVisible(
    finder,
    250,
    scrollable: _backupScreenScrollable(),
  );
}

Finder _backupScreenScrollable() => find
    .descendant(
      of: find.byKey(const ValueKey('backup-export-list')),
      matching: find.byType(Scrollable),
    )
    .first;

BackupSnapshotStreamExport _snapshot({
  int openSessionCount = 0,
  int archiveByteLength = 3,
}) =>
    BackupSnapshotStreamExport(
      archiveByteLength: archiveByteLength,
      schemaVersion: 15,
      stateVersion: 20,
      capturedAtUtcMillis: 1700000000000,
      openSessionCount: openSessionCount,
      recordCount: 1,
      mediaCount: 0,
      mediaBytes: 0,
      archiveStream: _testArchiveStream(archiveByteLength),
      closeSnapshot: () async {},
    );

Stream<Uint8List> _testArchiveStream(int length) async* {
  if (length == 3) {
    yield Uint8List.fromList([7, 8, 9]);
    return;
  }
  var offset = 0;
  while (offset < length) {
    final end = (offset + BackupCryptoV2.chunkBytes) < length
        ? offset + BackupCryptoV2.chunkBytes
        : length;
    yield Uint8List(end - offset);
    offset = end;
  }
}

BackupArchiveEncryptor _fakeEncryptor({
  required Uint8List encrypted,
  void Function(String password, Uint8List archive)? onArchive,
  Future<void>? beforeWrite,
}) =>
    ({
      required password,
      required archive,
      required destination,
      isCancelled,
      cancellationToken,
    }) async {
      final archiveBuilder = BytesBuilder(copy: false);
      await for (final chunk in archive) {
        archiveBuilder.add(chunk);
      }
      final archiveBytes = archiveBuilder.takeBytes();
      onArchive?.call(password, archiveBytes);
      await beforeWrite;
      _checkFakeCancellation(isCancelled, cancellationToken);
      var offset = 0;
      while (offset < encrypted.length) {
        _checkFakeCancellation(isCancelled, cancellationToken);
        final end = (offset + BackupCryptoV2.chunkBytes) < encrypted.length
            ? offset + BackupCryptoV2.chunkBytes
            : encrypted.length;
        await destination.write(Uint8List.sublistView(encrypted, offset, end));
        offset = end;
      }
      _checkFakeCancellation(isCancelled, cancellationToken);
      await destination.commit();
      return BackupCryptoV2Result(
        archiveBytes: archiveBytes.length,
        dataFrameCount:
            (archiveBytes.length + BackupCryptoV2.chunkBytes - 1) ~/
            BackupCryptoV2.chunkBytes,
        envelopeBytes: encrypted.length,
        archiveSha256: const [],
      );
    };

Uint8List _encryptedForArchiveSize(int archiveBytes) => Uint8List(
  BackupCryptoV2.envelopeLengthForArchiveBytes(archiveBytes),
);

void _checkFakeCancellation(
  bool Function()? isCancelled,
  BackupCryptoCancellationToken? cancellationToken,
) {
  if (cancellationToken?.isCancelled == true ||
      (isCancelled?.call() ?? false)) {
    throw const BackupCryptoV2CancelledException();
  }
}
