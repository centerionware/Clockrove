import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart' as crypto;
import 'package:clockrove/backup/backup_archive.dart';
import 'package:flutter_test/flutter_test.dart';

const _emptySha256 = <int>[
  0xe3,
  0xb0,
  0xc4,
  0x42,
  0x98,
  0xfc,
  0x1c,
  0x14,
  0x9a,
  0xfb,
  0xf4,
  0xc8,
  0x99,
  0x6f,
  0xb9,
  0x24,
  0x27,
  0xae,
  0x41,
  0xe4,
  0x64,
  0x9b,
  0x93,
  0x4c,
  0xa4,
  0x95,
  0x99,
  0x1b,
  0x78,
  0x52,
  0xb8,
  0x55,
];

void main() {
  group('BackupArchiveCodec', () {
    test(
      'accepts and round trips the current database schema version',
      () async {
        expect(BackupArchiveCodec.latestSchemaVersion, 17);
        final archive = await BackupArchiveCodec.encode(
          schemaVersion: BackupArchiveCodec.latestSchemaVersion,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: [
            _row('jobs', {'id': 'current-schema'}),
          ],
        );

        final decoded = await BackupArchiveCodec.decode(archive);
        expect(decoded.schemaVersion, BackupArchiveCodec.latestSchemaVersion);
      },
    );

    test('round trips rows and raw media deterministically', () async {
      final rows = [
        _row('jobs', {'name': 'North site', 'id': 'job-b'}),
        _row(
          'task_attachments',
          {'id': 'photo-1'},
          blobs: [
            BackupArchiveBlob(
              id: 'photo-blob-1',
              column: 'data',
              bytes: Uint8List.fromList([0, 1, 2, 255]),
            ),
          ],
        ),
        _row('jobs', {'id': 'job-a', 'name': 'South site'}),
      ];
      final first = await BackupArchiveCodec.encode(
        schemaVersion: 15,
        stateVersion: 9,
        capturedAtUtcMillis: 1234,
        rows: rows,
      );
      final second = await BackupArchiveCodec.encode(
        schemaVersion: 15,
        stateVersion: 9,
        capturedAtUtcMillis: 1234,
        rows: rows.reversed.toList(),
      );

      expect(second, orderedEquals(first));
      final decoded = await BackupArchiveCodec.decode(first);
      expect(decoded.schemaVersion, 15);
      expect(decoded.stateVersion, 9);
      expect(decoded.capturedAtUtcMillis, 1234);
      expect(decoded.rows.map((row) => row.table), [
        'jobs',
        'jobs',
        'task_attachments',
      ]);
      expect(
        decoded.rows.last.blobs.single.bytes,
        orderedEquals([0, 1, 2, 255]),
      );
    });

    test('uses the exact final plaintext size as its cap', () async {
      final rows = [
        _row('jobs', {'id': 'bounded'}),
      ];
      final archive = await BackupArchiveCodec.encode(
        schemaVersion: 15,
        stateVersion: 0,
        capturedAtUtcMillis: 0,
        rows: rows,
      );

      expect(
        await BackupArchiveCodec.encode(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: rows,
          maxBytes: archive.length,
        ),
        orderedEquals(archive),
      );
      await expectLater(
        BackupArchiveCodec.encode(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: rows,
          maxBytes: archive.length - 1,
        ),
        throwsA(isA<FormatException>()),
      );
      await expectLater(
        BackupArchiveCodec.encode(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: [
            _row('jobs', {'id': 'large', 'notes': ''.padRight(300000, 'x')}),
          ],
        ),
        throwsA(isA<FormatException>()),
      );
    });

    test(
      'stream preflight preserves the exact archive size boundary',
      () async {
        final rows = [
          BackupArchiveStreamRow(
            table: 'jobs',
            values: {'id': 'bounded'},
            blobs: const [],
          ),
        ];
        final expected = await BackupArchiveCodec.encode(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: [
            _row('jobs', {'id': 'bounded'}),
          ],
        );
        final plan = BackupArchiveCodec.prepareStream(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: rows,
          maxBytes: expected.length,
        );
        final actual = BytesBuilder(copy: false);
        await for (final chunk in plan.encode(
          readBlob: (_) => const Stream<List<int>>.empty(),
        )) {
          actual.add(chunk);
        }
        expect(plan.totalLength, expected.length);
        expect(actual.takeBytes(), orderedEquals(expected));

        expect(
          () => BackupArchiveCodec.prepareStream(
            schemaVersion: 15,
            stateVersion: 0,
            capturedAtUtcMillis: 0,
            rows: rows,
            maxBytes: expected.length - 1,
          ),
          throwsFormatException,
        );
      },
    );

    test('stream encoding preserves multi-chunk generic media bytes', () async {
      final media = List<int>.generate(
        128 * 1024 + 17,
        (index) => index & 0xff,
        growable: false,
      );
      final rows = [
        BackupArchiveStreamRow(
          table: 'task_attachments',
          values: const {'id': 'attachment-stream'},
          blobs: [
            BackupArchiveBlobSource(
              id: 'media-stream',
              column: 'data',
              sizeBytes: media.length,
              sha256: crypto.sha256.convert(media).bytes,
            ),
          ],
        ),
      ];
      final plan = BackupArchiveCodec.prepareStream(
        schemaVersion: 15,
        stateVersion: 4,
        capturedAtUtcMillis: 5,
        rows: rows,
      );
      final actual = BytesBuilder(copy: false);
      var chunkCount = 0;
      await for (final chunk in plan.encode(
        readBlob: (_) => Stream<List<int>>.value(media),
      )) {
        expect(chunk.length, lessThanOrEqualTo(64 * 1024));
        actual.add(chunk);
        chunkCount++;
      }
      plan.dispose();

      final expected = await BackupArchiveCodec.encode(
        schemaVersion: 15,
        stateVersion: 4,
        capturedAtUtcMillis: 5,
        rows: [
          _row(
            'task_attachments',
            const {'id': 'attachment-stream'},
            blobs: [
              BackupArchiveBlob(
                id: 'media-stream',
                column: 'data',
                bytes: Uint8List.fromList(media),
              ),
            ],
          ),
        ],
      );
      expect(chunkCount, greaterThan(2));
      expect(actual.takeBytes(), orderedEquals(expected));
    });

    test('stream encoding views aligned Uint8List media frames', () async {
      final media = Uint8List.fromList(
        List<int>.generate(128 * 1024 + 17, (index) => index & 0xff),
      );
      final rows = [
        BackupArchiveStreamRow(
          table: 'task_attachments',
          values: const {'id': 'attachment-view'},
          blobs: [
            BackupArchiveBlobSource(
              id: 'media-view',
              column: 'data',
              sizeBytes: media.length,
              sha256: crypto.sha256.convert(media).bytes,
            ),
          ],
        ),
      ];
      final plan = BackupArchiveCodec.prepareStream(
        schemaVersion: 15,
        stateVersion: 4,
        capturedAtUtcMillis: 5,
        rows: rows,
      );
      final actual = BytesBuilder(copy: false);
      var sourceViewFrameCount = 0;
      try {
        await for (final chunk in plan.encode(
          readBlob: (_) => Stream<Uint8List>.value(media),
        )) {
          expect(chunk.length, lessThanOrEqualTo(64 * 1024));
          if (chunk.offsetInBytes > 0 &&
              chunk.buffer.lengthInBytes == media.length) {
            expect(chunk.length, 64 * 1024);
            sourceViewFrameCount++;
          }
          actual.add(chunk);
        }
      } finally {
        plan.dispose();
      }

      final expected = await BackupArchiveCodec.encode(
        schemaVersion: 15,
        stateVersion: 4,
        capturedAtUtcMillis: 5,
        rows: [
          _row(
            'task_attachments',
            const {'id': 'attachment-view'},
            blobs: [
              BackupArchiveBlob(id: 'media-view', column: 'data', bytes: media),
            ],
          ),
        ],
      );
      expect(sourceViewFrameCount, greaterThan(0));
      expect(actual.takeBytes(), orderedEquals(expected));
    });

    test('stream preflight rejects invalid media field references', () {
      BackupArchiveStreamRow row({
        Map<String, Object?> values = const {'id': 'attachment'},
        required List<BackupArchiveBlobSource> blobs,
      }) => BackupArchiveStreamRow(
        table: 'task_attachments',
        values: values,
        blobs: blobs,
      );

      void expectRejected(BackupArchiveStreamRow row) {
        expect(
          () => BackupArchiveCodec.prepareStream(
            schemaVersion: 15,
            stateVersion: 0,
            capturedAtUtcMillis: 0,
            rows: [row],
          ),
          throwsFormatException,
        );
      }

      expectRejected(
        row(
          blobs: [
            BackupArchiveBlobSource(
              id: 'blob-a',
              column: 'data',
              sizeBytes: 0,
              sha256: _emptySha256,
            ),
            BackupArchiveBlobSource(
              id: 'blob-b',
              column: 'data',
              sizeBytes: 0,
              sha256: _emptySha256,
            ),
          ],
        ),
      );
      expectRejected(
        row(
          values: const {'id': 'attachment', 'data': null},
          blobs: [
            BackupArchiveBlobSource(
              id: 'blob-a',
              column: 'data',
              sizeBytes: 0,
              sha256: _emptySha256,
            ),
          ],
        ),
      );
    });

    test('stream preflight rejects malformed native media checksums', () {
      final row = BackupArchiveStreamRow(
        table: 'task_attachments',
        values: const {'id': 'attachment'},
        blobs: [
          BackupArchiveBlobSource(
            id: 'blob-bad-checksum',
            column: 'data',
            sizeBytes: 0,
            sha256: List<int>.filled(31, 0),
          ),
        ],
      );

      expect(
        () => BackupArchiveCodec.prepareStream(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: [row],
        ),
        throwsFormatException,
      );
    });

    test(
      'stream encoding rejects a changed media length in its single pass',
      () async {
        final media = <int>[11, 22];
        final plan = BackupArchiveCodec.prepareStream(
          schemaVersion: 15,
          stateVersion: 4,
          capturedAtUtcMillis: 5,
          rows: [
            BackupArchiveStreamRow(
              table: 'task_attachments',
              values: const {'id': 'attachment-length'},
              blobs: [
                BackupArchiveBlobSource(
                  id: 'media-length',
                  column: 'data',
                  sizeBytes: media.length,
                  sha256: crypto.sha256.convert(media).bytes,
                ),
              ],
            ),
          ],
        );
        try {
          await expectLater(
            plan
                .encode(readBlob: (_) => Stream<List<int>>.value([11]))
                .toList(),
            throwsFormatException,
          );
        } finally {
          plan.dispose();
        }
      },
    );

    test('rejects unsupported archive versions and tables', () async {
      final encoded = await _encodeJob();
      final badFormat = Uint8List.fromList(encoded);
      ByteData.sublistView(badFormat).setUint16(8, 2, Endian.big);
      await expectLater(
        BackupArchiveCodec.decode(badFormat),
        throwsA(isA<FormatException>()),
      );

      final futureSchema = Uint8List.fromList(encoded);
      ByteData.sublistView(futureSchema).setUint32(12, 18, Endian.big);
      await expectLater(
        BackupArchiveCodec.decode(futureSchema),
        throwsA(isA<FormatException>()),
      );

      final unknownTableRecord = _record('nope', [
        const MapEntry('id', 'job-1'),
      ]);
      await expectLater(
        BackupArchiveCodec.decode(_archive([unknownTableRecord])),
        throwsA(isA<FormatException>()),
      );

      final overlongRecord = _record('jobs', [
        const MapEntry('id', 'job-1'),
        MapEntry<String, Object?>(
          'notes',
          ''.padRight(BackupArchiveCodec.maxRecordBytes, 'x'),
        ),
      ]);
      await expectLater(
        BackupArchiveCodec.decode(_archive([overlongRecord])),
        throwsA(isA<FormatException>()),
      );
    });

    test('rejects duplicate field names and duplicate primary keys', () async {
      final duplicateFields = _record('jobs', [
        const MapEntry('id', 'first'),
        const MapEntry('id', 'second'),
      ]);
      await expectLater(
        BackupArchiveCodec.decode(_archive([duplicateFields])),
        throwsA(isA<FormatException>()),
      );

      final duplicateRows = _record('jobs', [const MapEntry('id', 'same')]);
      await expectLater(
        BackupArchiveCodec.decode(_archive([duplicateRows, duplicateRows])),
        throwsA(isA<FormatException>()),
      );
      await expectLater(
        BackupArchiveCodec.encode(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: [
            _row('jobs', {'id': 'same'}),
            _row('jobs', {'id': 'same'}),
          ],
        ),
        throwsA(isA<FormatException>()),
      );
    });

    test(
      'rejects duplicated, corrupt, truncated, and unreferenced media',
      () async {
        final duplicateColumn = _record(
          'task_attachments',
          [const MapEntry('id', 'attachment-1')],
          blobs: [
            _blobDescriptor('blob-a', 'data'),
            _blobDescriptor('blob-b', 'data'),
          ],
        );
        await expectLater(
          BackupArchiveCodec.decode(_archive([duplicateColumn])),
          throwsA(isA<FormatException>()),
        );

        final valid = await BackupArchiveCodec.encode(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: [
            _row(
              'task_attachments',
              {'id': 'attachment-1'},
              blobs: [
                BackupArchiveBlob(
                  id: 'blob-a',
                  column: 'data',
                  bytes: Uint8List.fromList([1, 2, 3]),
                ),
              ],
            ),
          ],
        );
        final corruptDigest = Uint8List.fromList(valid);
        // One row record begins after the 48-byte header. The digest follows
        // type, table, primary key, blob count, blob ID, column and length.
        final digestOffset =
            48 +
            1 +
            2 +
            'task_attachments'.length +
            2 +
            2 +
            'id'.length +
            1 +
            4 +
            'attachment-1'.length +
            2 +
            2 +
            'blob-a'.length +
            2 +
            'data'.length +
            4;
        corruptDigest[digestOffset] ^= 1;
        await expectLater(
          BackupArchiveCodec.decode(corruptDigest),
          throwsA(isA<FormatException>()),
        );

        await expectLater(
          BackupArchiveCodec.decode(
            Uint8List.sublistView(valid, 0, valid.length - 1),
          ),
          throwsA(isA<FormatException>()),
        );
        final unreferencedMedia = _archive(
          [
            _record('jobs', [const MapEntry('id', 'job-1')]),
          ],
          media: [7],
        );
        await expectLater(
          BackupArchiveCodec.decode(unreferencedMedia),
          throwsA(isA<FormatException>()),
        );
      },
    );

    test(
      'validates revision links and detects cycles without recursion',
      () async {
        final validDag = <BackupArchiveRow>[
          _row('entity_revisions', {
            'id': 'rev-1',
            'kind': 'job',
            'entity_id': 'job-1',
          }),
          _row('entity_revisions', {
            'id': 'rev-2',
            'kind': 'job',
            'entity_id': 'job-1',
          }),
          _row('revision_parents', {
            'revision_id': 'rev-2',
            'parent_id': 'rev-1',
          }),
          _row('entity_heads', {
            'kind': 'job',
            'entity_id': 'job-1',
            'revision_id': 'rev-2',
          }),
        ];
        final validBytes = await BackupArchiveCodec.encode(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: validDag,
        );
        expect(
          await BackupArchiveCodec.decode(validBytes),
          isA<BackupArchive>(),
        );

        final cycle = <BackupArchiveRow>[
          _row('entity_revisions', {
            'id': 'rev-1',
            'kind': 'job',
            'entity_id': 'job-1',
          }),
          _row('entity_revisions', {
            'id': 'rev-2',
            'kind': 'job',
            'entity_id': 'job-1',
          }),
          _row('revision_parents', {
            'revision_id': 'rev-1',
            'parent_id': 'rev-2',
          }),
          _row('revision_parents', {
            'revision_id': 'rev-2',
            'parent_id': 'rev-1',
          }),
          _row('entity_heads', {
            'kind': 'job',
            'entity_id': 'job-1',
            'revision_id': 'rev-1',
          }),
        ];
        await expectLater(
          BackupArchiveCodec.encode(
            schemaVersion: 15,
            stateVersion: 0,
            capturedAtUtcMillis: 0,
            rows: cycle,
          ),
          throwsA(isA<FormatException>()),
        );

        const depth = 1200;
        final deep = <BackupArchiveRow>[
          for (var index = 0; index < depth; index++)
            _row('entity_revisions', {
              'id': 'rev-$index',
              'kind': 'job',
              'entity_id': 'job-1',
            }),
          for (var index = 1; index < depth; index++)
            _row('revision_parents', {
              'revision_id': 'rev-$index',
              'parent_id': 'rev-${index - 1}',
            }),
          _row('entity_heads', {
            'kind': 'job',
            'entity_id': 'job-1',
            'revision_id': 'rev-${depth - 1}',
          }),
        ];
        final deepBytes = await BackupArchiveCodec.encode(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: deep,
        );
        expect(
          (await BackupArchiveCodec.decode(deepBytes)).rows.length,
          deep.length,
        );
      },
    );

    test('gates location range tombstones on their schema version', () async {
      expect(
        BackupArchiveCodec.portableTablesForSchema(15),
        isNot(contains('location_deletion_ranges')),
      );
      expect(
        BackupArchiveCodec.portableTablesForSchema(16),
        contains('location_deletion_ranges'),
      );
      await expectLater(
        BackupArchiveCodec.encode(
          schemaVersion: 15,
          stateVersion: 0,
          capturedAtUtcMillis: 0,
          rows: [
            _row('location_deletion_ranges', {'id': 'range-1'}),
          ],
        ),
        throwsA(isA<FormatException>()),
      );
    });
  });
}

BackupArchiveRow _row(
  String table,
  Map<String, Object?> values, {
  List<BackupArchiveBlob> blobs = const [],
}) => BackupArchiveRow(table: table, values: values, blobs: blobs);

Future<Uint8List> _encodeJob() => BackupArchiveCodec.encode(
  schemaVersion: 15,
  stateVersion: 1,
  capturedAtUtcMillis: 2,
  rows: [
    _row('jobs', {'id': 'job-1'}),
  ],
);

_TestBlobDescriptor _blobDescriptor(String id, String column) =>
    _TestBlobDescriptor(id, column, 0, List.filled(32, 0));

Uint8List _archive(List<List<int>> records, {List<int> media = const []}) {
  final encodedRecords = records.expand((record) => record).toList();
  final result = Uint8List(48 + encodedRecords.length + media.length);
  result.setRange(0, 8, const [0x43, 0x4c, 0x4b, 0x41, 0x52, 0x43, 0x48, 0x31]);
  final header = ByteData.sublistView(result);
  header.setUint16(8, 1, Endian.big);
  header.setUint16(10, 48, Endian.big);
  header.setUint32(12, 15, Endian.big);
  header.setInt64(16, 0, Endian.big);
  header.setInt64(24, 0, Endian.big);
  header.setUint32(32, records.length, Endian.big);
  header.setUint32(36, 0, Endian.big);
  header.setUint32(40, encodedRecords.length, Endian.big);
  header.setUint32(44, media.length, Endian.big);
  result.setRange(48, 48 + encodedRecords.length, encodedRecords);
  result.setRange(48 + encodedRecords.length, result.length, media);
  return result;
}

List<int> _record(
  String table,
  List<MapEntry<String, Object?>> values, {
  List<_TestBlobDescriptor> blobs = const [],
}) {
  final writer = _TestWriter();
  writer.byte(1);
  writer.shortString(table);
  writer.uint16(values.length);
  for (final entry in values) {
    writer.shortString(entry.key);
    writer.value(entry.value);
  }
  writer.uint16(blobs.length);
  for (final blob in blobs) {
    writer.shortString(blob.id);
    writer.shortString(blob.column);
    writer.uint32(blob.length);
    writer.bytes(blob.hash);
  }
  return writer.takeBytes();
}

final class _TestBlobDescriptor {
  const _TestBlobDescriptor(this.id, this.column, this.length, this.hash);

  final String id;
  final String column;
  final int length;
  final List<int> hash;
}

final class _TestWriter {
  final BytesBuilder _bytes = BytesBuilder(copy: false);

  void byte(int value) => _bytes.addByte(value);

  void uint16(int value) {
    final data = ByteData(2)..setUint16(0, value, Endian.big);
    _bytes.add(data.buffer.asUint8List());
  }

  void uint32(int value) {
    final data = ByteData(4)..setUint32(0, value, Endian.big);
    _bytes.add(data.buffer.asUint8List());
  }

  void shortString(String value) {
    final encoded = utf8.encode(value);
    uint16(encoded.length);
    _bytes.add(encoded);
  }

  void value(Object? value) {
    if (value == null) {
      byte(0);
    } else if (value is int) {
      byte(1);
      final data = ByteData(8)..setInt64(0, value, Endian.big);
      _bytes.add(data.buffer.asUint8List());
    } else if (value is double) {
      byte(2);
      final data = ByteData(8)..setFloat64(0, value, Endian.big);
      _bytes.add(data.buffer.asUint8List());
    } else if (value is String) {
      final encoded = utf8.encode(value);
      byte(3);
      uint32(encoded.length);
      _bytes.add(encoded);
    }
  }

  void bytes(List<int> values) => _bytes.add(values);

  Uint8List takeBytes() => _bytes.takeBytes();
}
