import 'package:clockrove/backup/backup_file_api.dart';
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

  test(
    'creates a destination, appends sequential encrypted chunks, and saves',
    () async {
      var written = 0;
      respond = (call) async {
        switch (call.method) {
          case 'createBackupDocument':
            final args = call.arguments as Map;
            expect(args['totalBytes'], 4);
            expect(args['suggestedName'], 'Clockrove-backup-test.clockrove');
            return 'opaque-document-id';
          case 'writeBackupDocumentChunk':
            final args = call.arguments as Map;
            expect(args['documentId'], 'opaque-document-id');
            expect(args['offset'], written);
            final bytes = args['bytes'] as Uint8List;
            expect(bytes, orderedEquals([10, 20, 30, 40]));
            written += bytes.length;
            return written;
          case 'finishBackupDocument':
            expect((call.arguments as Map)['documentId'], 'opaque-document-id');
            expect(written, 4);
            return true;
          default:
            throw StateError('Unexpected method ${call.method}.');
        }
      };

      final api = BackupFileApi(channel: channel);
      final id = await api.createBackupDocument(
        totalBytes: 4,
        suggestedName: 'Clockrove-backup-test.clockrove',
      );
      expect(id, 'opaque-document-id');
      expect(
        await api.writeBackupDocumentChunk(
          documentId: id!,
          offset: 0,
          bytes: Uint8List.fromList([10, 20, 30, 40]),
        ),
        4,
      );
      expect(await api.finishBackupDocument(id), isTrue);
      expect(calls.map((call) => call.method), [
        'createBackupDocument',
        'writeBackupDocumentChunk',
        'finishBackupDocument',
      ]);
    },
  );

  test(
    'rejects files above the authenticated envelope bound before native calls',
    () async {
      await expectLater(
        BackupFileApi(channel: channel).createBackupDocument(
          totalBytes: maxEncryptedBackupBytes + 1,
          suggestedName: 'too-large.clockrove',
        ),
        throwsA(isA<FormatException>()),
      );
      expect(calls, isEmpty);
    },
  );

  test('rejects an oversized chunk before calling native code', () async {
    await expectLater(
      BackupFileApi(channel: channel).writeBackupDocumentChunk(
        documentId: 'opaque-document-id',
        offset: 0,
        bytes: Uint8List(maxBackupDocumentChunkBytes + 1),
      ),
      throwsA(isA<FormatException>()),
    );
    expect(calls, isEmpty);
  });

  test('rejects an unexpected native offset', () async {
    respond = (call) async {
      expect(call.method, 'writeBackupDocumentChunk');
      return 9;
    };

    await expectLater(
      BackupFileApi(channel: channel).writeBackupDocumentChunk(
        documentId: 'opaque-document-id',
        offset: 0,
        bytes: Uint8List.fromList([1, 2, 3]),
      ),
      throwsA(isA<FormatException>()),
    );
  });
}
