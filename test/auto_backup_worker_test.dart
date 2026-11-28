import 'package:clockrove/auto_backup_worker.dart';
import 'package:clockrove/backup/backup_snapshot_exporter.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('test/clockrove/auto-backup/v1');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'beginSession':
          return {'encrypt': false, 'password': ''};
        case 'startDocument':
          final args = call.arguments as Map;
          expect(args['totalBytes'], 5);
          expect(args['encrypted'], isFalse);
          expect(args['capturedAtUtcMillis'], 1234);
          return {'runId': 'run-1', 'name': 'clockrove-backup.clockrove'};
        case 'writeChunk':
          final args = call.arguments as Map;
          final bytes = args['bytes'] as Uint8List;
          expect(args['runId'], 'run-1');
          expect(args['offset'], 0);
          expect(bytes, [1, 2, 3, 4, 5]);
          return bytes.length;
        case 'finishDocument':
          expect((call.arguments as Map)['runId'], 'run-1');
          return {'name': 'clockrove-backup.clockrove', 'pruned': 0};
        case 'autoBackupFinished':
          return null;
        default:
          throw StateError('Unexpected auto-backup method ${call.method}.');
      }
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('streams a plaintext snapshot and reports worker success', () async {
    var closed = false;
    final snapshot = BackupSnapshotStreamExport(
      archiveByteLength: 5,
      schemaVersion: 1,
      stateVersion: 1,
      capturedAtUtcMillis: 1234,
      openSessionCount: 0,
      recordCount: 0,
      mediaCount: 0,
      mediaBytes: 0,
      archiveStream: Stream.value(Uint8List.fromList([1, 2, 3, 4, 5])),
      closeSnapshot: () async {
        closed = true;
      },
    );

    await runAutoBackup(channel: channel, exportSnapshot: () async => snapshot);

    expect(calls.map((call) => call.method), [
      'beginSession',
      'startDocument',
      'writeChunk',
      'finishDocument',
      'autoBackupFinished',
    ]);
    expect(calls.last.arguments, {
      'ok': true,
      'retry': false,
      'code': 'SUCCESS',
    });
    expect(closed, isTrue);
  });
}
