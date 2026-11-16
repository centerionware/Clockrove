import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart' as crypto;
import 'package:clockrove/backup/backup_archive.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:clockrove/backup/backup_snapshot_exporter.dart';
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
    respond = (_) async => throw StateError('Unexpected backup method call.');
    messenger.setMockMethodCallHandler(channel, (call) {
      calls.add(call);
      return respond(call);
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('exports rows with the N+1 state version after info lookup', () async {
    var blobCalls = 0;
    respond = (call) async {
      switch (call.method) {
        case 'getBackupSnapshotInfo':
          return _snapshotInfo(maxBlobChunkBytes: 2);
        case 'startBackupSnapshot':
          return _started(
            recordCount: 3,
            mediaCount: 1,
            mediaBytes: 4,
            openSessionCount: 1,
            // A native write commits after info lookup and before snapshot start.
            stateVersion: 20,
          );
        case 'readBackupSnapshotPage':
          final args = call.arguments as Map;
          if (args['cursor'] == null) {
            return _page(
              cursor: 'page-2',
              records: [
                _row('jobs', {'id': 'job-1', 'name': 'Synthetic job'}),
                _row('sessions', {
                  'id': 'session-open',
                  'job_id': 'job-1',
                  'start_ms': 1699999999000,
                  'end_ms': null,
                }),
              ],
            );
          }
          expect(args['cursor'], 'page-2');
          return _page(
            records: [
              _row(
                'task_attachments',
                {'id': 'attachment-1'},
                blobs: [
                  _blobDescriptor('media-1', 'data', [11, 22, 33, 44]),
                ],
              ),
            ],
            done: true,
          );
        case 'readBackupBlob':
          final args = call.arguments as Map;
          expect(args['snapshotId'], 'snapshot-1');
          expect(args['blobId'], 'media-1');
          final offset = args['offset'] as int;
          blobCalls++;
          if (offset == 0) {
            expect(args['maxBytes'], 2);
            return _blobChunk(0, [11, 22], done: false);
          }
          expect(offset, 2);
          expect(args['maxBytes'], 2);
          return _blobChunk(2, [33, 44], done: true);
        case 'closeBackupSnapshot':
          expect((call.arguments as Map)['snapshotId'], 'snapshot-1');
          return {'closed': true};
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    final progress = <BackupSnapshotProgress>[];
    final result = await BackupSnapshotExporter(channel: channel)
        .exportStream(onProgress: progress.add);
    final archiveBytes = await _collectArchive(result);
    final archive = await BackupArchiveCodec.decode(archiveBytes);

    expect(result.schemaVersion, 15);
    expect(result.stateVersion, 20);
    expect(result.capturedAtUtcMillis, 1700000000000);
    expect(result.recordCount, 3);
    expect(result.mediaCount, 1);
    expect(result.mediaBytes, 4);
    expect(result.openSessionCount, 1);
    expect(archive.stateVersion, 20);
    expect(progress.last.recordsRead, 3);
    expect(progress.last.totalRecords, 3);
    expect(progress.last.mediaBytesRead, 4);
    expect(progress.last.totalMediaBytes, 4);
    expect(progress.last.fraction, 1);
    expect(blobCalls, 2);
    expect(archive.rows.map((row) => row.table), [
      'jobs',
      'sessions',
      'task_attachments',
    ]);
    expect(
      archive.rows.last.blobs.single.bytes,
      orderedEquals([11, 22, 33, 44]),
    );
    expect(calls.map((call) => call.method), [
      'getBackupSnapshotInfo',
      'startBackupSnapshot',
      'readBackupSnapshotPage',
      'readBackupSnapshotPage',
      'readBackupBlob',
      'readBackupBlob',
      'closeBackupSnapshot',
    ]);
  });

  test(
    'streams exact archive bytes in bounded chunks and closes afterward',
    () async {
      final media = Uint8List.fromList([11, 22, 33, 44]);
      var blobCalls = 0;
      var closed = false;
      respond = (call) async {
        switch (call.method) {
          case 'getBackupSnapshotInfo':
            return _snapshotInfo(maxBlobChunkBytes: 2);
          case 'startBackupSnapshot':
            return _started(recordCount: 3, mediaCount: 1, mediaBytes: 4);
          case 'readBackupSnapshotPage':
            return _page(
              records: [
                _row(
                  'task_attachments',
                  {'id': 'attachment-1'},
                  blobs: [
                    _blobDescriptor('media-1', 'data', media),
                  ],
                ),
                _row('jobs', {'id': 'job-z', 'name': 'Later job'}),
                _row('jobs', {'id': 'job-a', 'name': 'Earlier job'}),
              ],
              done: true,
            );
          case 'readBackupBlob':
            final args = call.arguments as Map;
            blobCalls++;
            final offset = args['offset'] as int;
            return _blobChunk(
              offset,
              media.sublist(offset, offset + 2),
              done: offset == 2,
            );
          case 'closeBackupSnapshot':
            closed = true;
            return {'closed': true};
          default:
            throw StateError('Unexpected method ${call.method}.');
        }
      };

      final expectedRows = [
        _archiveRow('jobs', {'id': 'job-a', 'name': 'Earlier job'}),
        _archiveRow('jobs', {'id': 'job-z', 'name': 'Later job'}),
        _archiveRow(
          'task_attachments',
          {'id': 'attachment-1'},
          blobs: [
            BackupArchiveBlob(id: 'media-1', column: 'data', bytes: media),
          ],
        ),
      ];
      final expected = await BackupArchiveCodec.encode(
        schemaVersion: 15,
        stateVersion: 19,
        capturedAtUtcMillis: 1700000000000,
        rows: expectedRows,
      );

      final exported = await BackupSnapshotExporter(channel: channel)
          .exportStream();
      expect(exported.archiveByteLength, expected.length);
      expect(closed, isFalse);
      expect(blobCalls, 0);

      final chunks = await exported.archiveStream.toList();
      final actual = BytesBuilder(copy: false);
      for (final chunk in chunks) {
        actual.add(chunk);
      }
      expect(chunks.every((chunk) => chunk.length <= 64 * 1024), isTrue);
      expect(actual.takeBytes(), orderedEquals(expected));
      expect(blobCalls, 2);
      expect(closed, isTrue);
      expect(calls.last.method, 'closeBackupSnapshot');
    },
  );

  test('closes an unconsumed stream when explicitly discarded', () async {
    var closed = false;
    respond = (call) async {
      switch (call.method) {
        case 'getBackupSnapshotInfo':
          return _snapshotInfo();
        case 'startBackupSnapshot':
          return _started(recordCount: 1);
        case 'readBackupSnapshotPage':
          return _page(
            records: [
              _row('jobs', {'id': 'job-1'}),
            ],
            done: true,
          );
        case 'closeBackupSnapshot':
          closed = true;
          return {'closed': true};
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    final exported = await BackupSnapshotExporter(channel: channel)
        .exportStream();
    expect(closed, isFalse);
    await exported.close();
    expect(closed, isTrue);
    expect(calls.last.method, 'closeBackupSnapshot');
  });

  test(
    'mismatched media digest discards encrypted staging before commit',
    () async {
      var blobReadCount = 0;
      var closed = false;
      respond = (call) async {
        switch (call.method) {
          case 'getBackupSnapshotInfo':
            return _snapshotInfo();
          case 'startBackupSnapshot':
            return _started(recordCount: 1, mediaCount: 1, mediaBytes: 2);
          case 'readBackupSnapshotPage':
            return _page(
              records: [
                _row(
                  'task_attachments',
                  {'id': 'attachment-1'},
                  blobs: [
                    _blobDescriptor('media-1', 'data', [10, 20]),
                  ],
                ),
              ],
              done: true,
            );
          case 'readBackupBlob':
            blobReadCount++;
            return _blobChunk(0, [10, 21], done: true);
          case 'closeBackupSnapshot':
            closed = true;
            return {'closed': true};
          default:
            throw StateError('Unexpected method ${call.method}.');
        }
      };

      final exported = await BackupSnapshotExporter(channel: channel)
          .exportStream();
      final destination = _TrackingTransactionalSink();
      await expectLater(
        BackupCryptoV2.encrypt(
          password: 'synthetic-checksum-mismatch-backup-password',
          archive: exported.archiveStream,
          destination: destination,
        ),
        throwsA(isA<FormatException>()),
      );
      expect(blobReadCount, 1);
      expect(closed, isTrue);
      expect(destination.discarded, isTrue);
      expect(destination.committed, isFalse);
    },
  );

  test(
    'rejects malformed native media digests and closes the snapshot',
    () async {
      var closed = false;
      respond = (call) async {
        switch (call.method) {
          case 'getBackupSnapshotInfo':
            return _snapshotInfo();
          case 'startBackupSnapshot':
            return _started(recordCount: 1, mediaCount: 1, mediaBytes: 2);
          case 'readBackupSnapshotPage':
            return _page(
              records: [
                _row(
                  'task_attachments',
                  {'id': 'attachment-1'},
                  blobs: [
                    {
                      'id': 'media-1',
                      'column': 'data',
                      'sizeBytes': 2,
                      'sha256': 'invalid-checksum',
                    },
                  ],
                ),
              ],
              done: true,
            );
          case 'closeBackupSnapshot':
            closed = true;
            return {'closed': true};
          default:
            throw StateError('Unexpected method ${call.method}.');
        }
      };

      await expectLater(
        BackupSnapshotExporter(channel: channel).exportStream(),
        throwsA(isA<FormatException>()),
      );
      expect(closed, isTrue);
    },
  );

  test('closes a started snapshot when page reading fails', () async {
    var closed = false;
    respond = (call) async {
      switch (call.method) {
        case 'getBackupSnapshotInfo':
          return _snapshotInfo();
        case 'startBackupSnapshot':
          return _started(recordCount: 1);
        case 'readBackupSnapshotPage':
          return _page(records: const [], done: false);
        case 'closeBackupSnapshot':
          closed = true;
          return {'closed': true};
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    await expectLater(
      BackupSnapshotExporter(channel: channel).exportStream(),
      throwsA(isA<FormatException>()),
    );
    expect(closed, isTrue);
    expect(calls.last.method, 'closeBackupSnapshot');
  });

  test(
    'rejects a terminal page that silently omits captured records',
    () async {
      var closed = false;
      respond = (call) async {
        switch (call.method) {
          case 'getBackupSnapshotInfo':
            return _snapshotInfo();
          case 'startBackupSnapshot':
            return _started(recordCount: 1);
          case 'readBackupSnapshotPage':
            return _page(records: const [], done: true);
          case 'closeBackupSnapshot':
            closed = true;
            return {'closed': true};
          default:
            throw StateError('Unexpected method ${call.method}.');
        }
      };

      await expectLater(
        BackupSnapshotExporter(channel: channel).exportStream(),
        throwsA(isA<FormatException>()),
      );
      expect(closed, isTrue);
      expect(calls.last.method, 'closeBackupSnapshot');
    },
  );

  test(
    'closes a started snapshot when export is cancelled between pages',
    () async {
      var cancellationChecks = 0;
      var closed = false;
      respond = (call) async {
        switch (call.method) {
          case 'getBackupSnapshotInfo':
            return _snapshotInfo();
          case 'startBackupSnapshot':
            return _started(recordCount: 2);
          case 'readBackupSnapshotPage':
            return _page(
              cursor: 'page-2',
              records: [
                _row('jobs', {'id': 'job-1'}),
              ],
            );
          case 'closeBackupSnapshot':
            closed = true;
            return {'closed': true};
          default:
            throw StateError('Unexpected method ${call.method}.');
        }
      };

      await expectLater(
        BackupSnapshotExporter(channel: channel)
            .exportStream(isCancelled: () => ++cancellationChecks == 4),
        throwsA(isA<BackupExportCancelledException>()),
      );
      expect(closed, isTrue);
      expect(calls.map((call) => call.method), [
        'getBackupSnapshotInfo',
        'startBackupSnapshot',
        'readBackupSnapshotPage',
        'closeBackupSnapshot',
      ]);
    },
  );

  test('closes a started snapshot when blob transfer fails', () async {
    var closed = false;
    respond = (call) async {
      switch (call.method) {
        case 'getBackupSnapshotInfo':
          return _snapshotInfo();
        case 'startBackupSnapshot':
          return _started(recordCount: 1, mediaCount: 1, mediaBytes: 4);
        case 'readBackupSnapshotPage':
          return _page(
            done: true,
            records: [
              _row(
                'task_attachments',
                {'id': 'attachment-1'},
                blobs: [
                    _blobDescriptor('media-1', 'data', [1, 2, 3, 4]),
                ],
              ),
            ],
          );
        case 'readBackupBlob':
          throw StateError('Synthetic media read failure.');
        case 'closeBackupSnapshot':
          closed = true;
          return {'closed': true};
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    final exported = await BackupSnapshotExporter(channel: channel)
        .exportStream();
    await expectLater(
      exported.archiveStream.toList(),
      throwsA(isA<PlatformException>()),
    );
    expect(closed, isTrue);
    expect(calls.last.method, 'closeBackupSnapshot');
  });

  test('surfaces cleanup failure together with the export error', () async {
    respond = (call) async {
      switch (call.method) {
        case 'getBackupSnapshotInfo':
          return _snapshotInfo();
        case 'startBackupSnapshot':
          return _started(recordCount: 1);
        case 'readBackupSnapshotPage':
          return _page(records: const [], done: false);
        case 'closeBackupSnapshot':
          return {'closed': false};
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    await expectLater(
      BackupSnapshotExporter(channel: channel).exportStream(),
      throwsA(
        isA<BackupSnapshotCleanupException>()
            .having(
              (error) => error.operationFailure,
              'operation failure',
              isA<FormatException>(),
            )
            .having(
              (error) => error.cleanupFailure,
              'cleanup failure',
              isA<FormatException>(),
            ),
      ),
    );
  });

  test(
    'rejects an incompatible snapshot contract before starting it',
    () async {
      respond = (call) async {
        if (call.method == 'getBackupSnapshotInfo') {
          return _snapshotInfo(portableTables: ['jobs']);
        }
        throw StateError('An incompatible contract must not start a snapshot.');
      };

      await expectLater(
        BackupSnapshotExporter(channel: channel).exportStream(),
        throwsA(isA<FormatException>()),
      );
      expect(calls.map((call) => call.method), ['getBackupSnapshotInfo']);
    },
  );

  test(
    'rejects a page budget below the native minimum before starting',
    () async {
      respond = (call) async {
        if (call.method == 'getBackupSnapshotInfo') {
          return _snapshotInfo(maxPageBytes: 1);
        }
        throw StateError('An invalid page budget must not start a snapshot.');
      };

      await expectLater(
        BackupSnapshotExporter(channel: channel).exportStream(),
        throwsA(isA<FormatException>()),
      );
      expect(calls.map((call) => call.method), ['getBackupSnapshotInfo']);
    },
  );

  test('accepts an empty snapshot with a terminal page', () async {
    respond = (call) async {
      switch (call.method) {
        case 'getBackupSnapshotInfo':
          return _snapshotInfo();
        case 'startBackupSnapshot':
          return _started(recordCount: 0);
        case 'readBackupSnapshotPage':
          return _page(records: const [], done: true);
        case 'closeBackupSnapshot':
          return {'closed': true};
        default:
          throw StateError('Unexpected method ${call.method}.');
      }
    };

    final result = await BackupSnapshotExporter(channel: channel)
        .exportStream();
    final archive = await BackupArchiveCodec.decode(
      await _collectArchive(result),
    );
    expect(archive.rows, isEmpty);
    expect(calls.map((call) => call.method), [
      'getBackupSnapshotInfo',
      'startBackupSnapshot',
      'readBackupSnapshotPage',
      'closeBackupSnapshot',
    ]);
  });
}

Map<String, Object?> _snapshotInfo({
  int schemaVersion = 15,
  int maxPageBytes = 256 * 1024,
  int maxBlobChunkBytes = 256 * 1024,
  List<String>? portableTables,
}) => {
  'archiveFormatVersion': 1,
  'schemaVersion': schemaVersion,
  'stateVersion': 19,
  'maxSnapshotBytes': 60 * 1024 * 1024,
  'maxPageBytes': maxPageBytes,
  'maxBlobChunkBytes': maxBlobChunkBytes,
  'maxRecords': 500000,
  'maxMediaCount': 10000,
  'idleExpiryMillis': 15 * 60 * 1000,
  'portableTables':
      portableTables ??
      (BackupArchiveCodec.portableTablesForSchema(schemaVersion).toList()
        ..sort()),
};

Map<String, Object?> _started({
  required int recordCount,
  int mediaCount = 0,
  int mediaBytes = 0,
  int openSessionCount = 0,
  int stateVersion = 19,
}) => {
  'snapshotId': 'snapshot-1',
  'archiveFormatVersion': 1,
  'schemaVersion': 15,
  'stateVersion': stateVersion,
  'capturedAtUtcMillis': 1700000000000,
  'recordCount': recordCount,
  'mediaCount': mediaCount,
  'mediaBytes': mediaBytes,
  'openSessionCount': openSessionCount,
};

Map<String, Object?> _row(
  String table,
  Map<String, Object?> values, {
  List<Map<String, Object?>> blobs = const [],
}) => {'type': 'row', 'table': table, 'values': values, 'blobs': blobs};

Map<String, Object?> _blobDescriptor(
  String id,
  String column,
  List<int> bytes,
) => {
  'id': id,
  'column': column,
  'sizeBytes': bytes.length,
  'sha256': base64.encode(crypto.sha256.convert(bytes).bytes),
};

BackupArchiveRow _archiveRow(
  String table,
  Map<String, Object?> values, {
  List<BackupArchiveBlob> blobs = const [],
}) => BackupArchiveRow(table: table, values: values, blobs: blobs);

Map<String, Object?> _page({
  List<Map<String, Object?>> records = const [],
  String? cursor,
  bool done = false,
}) => {
  'snapshotId': 'snapshot-1',
  'nextCursor': cursor,
  'done': done,
  'records': records,
};

Map<String, Object?> _blobChunk(
  int offset,
  List<int> bytes, {
  required bool done,
}) {
  final value = Uint8List.fromList(bytes);
  return {
    'offset': offset,
    'nextOffset': offset + value.length,
    'done': done,
    'bytes': value,
  };
}

Future<Uint8List> _collectArchive(BackupSnapshotStreamExport exported) async {
  final output = BytesBuilder(copy: false);
  await for (final chunk in exported.archiveStream) {
    output.add(chunk);
  }
  return output.takeBytes();
}

final class _TrackingTransactionalSink implements BackupTransactionalByteSink {
  final BytesBuilder _stagedBytes = BytesBuilder(copy: false);
  bool committed = false;
  bool discarded = false;

  @override
  Future<void> write(List<int> bytes) async => _stagedBytes.add(bytes);

  @override
  Future<void> commit() async {
    committed = true;
  }

  @override
  Future<void> discard() async {
    discarded = true;
  }
}
