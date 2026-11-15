import 'dart:convert';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

/// Authenticated plaintext archive v1.
///
/// The caller must only decode bytes returned by [BackupCrypto.decrypt]. The
/// archive uses typed, length-prefixed SQLite scalar values and a raw media
/// section, so media is not base64-expanded and duplicate map keys are
/// unrepresentable. Every media descriptor is SHA-256 checked after the outer
/// AES-GCM authentication succeeds.
abstract final class BackupArchiveCodec {
  static const int currentFormatVersion = 1;
  static const int latestSchemaVersion = 16;
  static const int maxArchiveBytes = 64 * 1024 * 1024;
  static const int maxRecordBytes = 256 * 1024;
  static const int maxRecords = 1000000;
  static const int maxMediaCount = 100000;
  static const int _maxInt64 = 0x7fffffffffffffff;
  static const int _minInt64 = -0x7fffffffffffffff - 1;
  static const int _headerLength = 48;
  static const List<int> _magic = [
    0x43,
    0x4c,
    0x4b,
    0x41,
    0x52,
    0x43,
    0x48,
    0x31,
  ]; // CLKARCH1

  static final Map<String, BackupTableSchema> _tables =
      Map<String, BackupTableSchema>.unmodifiable({
        for (final table in _tableDefinitions) table.name: table,
      });

  /// The allowlist must stay synchronized with the native snapshot contract.
  static Set<String> get portableTables => _tables.keys.toSet();

  static Set<String> portableTablesForSchema(int schemaVersion) => _tables
      .values
      .where((table) => table.minSchemaVersion <= schemaVersion)
      .map((table) => table.name)
      .toSet();

  static const List<BackupTableSchema> _tableDefinitions = [
    BackupTableSchema('actual_costs', ['id']),
    BackupTableSchema('app_settings', ['id']),
    BackupTableSchema('automation_policies', ['job_id']),
    BackupTableSchema('billing_tax_settings', ['id']),
    BackupTableSchema('card_money', ['id']),
    BackupTableSchema('customers', ['id']),
    BackupTableSchema('entity_heads', ['kind', 'entity_id']),
    BackupTableSchema('entity_revisions', ['id']),
    BackupTableSchema('invoice_accounting_settings', ['id']),
    BackupTableSchema('invoice_billing_defaults', ['id']),
    BackupTableSchema('invoice_copies', ['id'], blobColumns: {'pdf'}),
    BackupTableSchema('invoice_coverage', ['invoice_id']),
    BackupTableSchema('invoice_payment_events', ['id']),
    BackupTableSchema('invoice_payment_reversals', ['id']),
    BackupTableSchema('invoice_payments', ['id']),
    BackupTableSchema('invoice_task_claims', ['id']),
    BackupTableSchema('invoice_time_claims', ['id']),
    BackupTableSchema('invoice_voids', ['invoice_id']),
    BackupTableSchema('invoices', ['id'], blobColumns: {'pdf'}),
    BackupTableSchema('job_deletions', ['id']),
    BackupTableSchema('job_invoice_billing_policy', ['job_id']),
    BackupTableSchema('job_location_recording', ['job_id']),
    BackupTableSchema('job_money', ['id']),
    BackupTableSchema('job_tax_rates', ['job_id']),
    BackupTableSchema('jobs', ['id']),
    BackupTableSchema('kanban_cards', ['id']),
    BackupTableSchema('kanban_columns', ['id']),
    BackupTableSchema('location_deletion_ranges', ['id'], minSchemaVersion: 16),
    BackupTableSchema('location_points', ['id']),
    BackupTableSchema('location_recording_settings', ['id']),
    BackupTableSchema('logo_assets', ['id'], blobColumns: {'bytes'}),
    BackupTableSchema('payment_destinations', ['payment_id']),
    BackupTableSchema('quote_counter', ['id']),
    BackupTableSchema('quote_decisions', ['quote_id', 'revision']),
    BackupTableSchema('quote_drafts', ['quote_id']),
    BackupTableSchema('quote_invoice_links', ['invoice_id']),
    BackupTableSchema('quote_pdfs', ['id'], blobColumns: {'bytes'}),
    BackupTableSchema('quote_revisions', ['quote_id', 'revision']),
    BackupTableSchema('quote_scope_lines', ['quote_id', 'line_id']),
    BackupTableSchema('quote_scope_revisions', [
      'quote_id',
      'revision',
      'line_id',
    ]),
    BackupTableSchema('quotes', ['id']),
    BackupTableSchema('revision_parents', ['revision_id', 'parent_id']),
    BackupTableSchema('sessions', ['id']),
    BackupTableSchema('settings_money', ['id']),
    BackupTableSchema('task_attachments', ['id'], blobColumns: {'data'}),
    BackupTableSchema('task_comment_events', ['id']),
    BackupTableSchema('task_comments', ['id']),
    BackupTableSchema('task_quantities', ['card_id']),
    BackupTableSchema('task_templates', ['id']),
    BackupTableSchema('template_money', ['id']),
    BackupTableSchema('template_quantities', ['template_id']),
    BackupTableSchema('work_sites', ['id']),
  ];

  /// Encodes all rows and media. Oversize archives fail as a whole; media is
  /// never silently left out to fit the limit.
  static Future<Uint8List> encode({
    required int schemaVersion,
    required int stateVersion,
    required int capturedAtUtcMillis,
    required List<BackupArchiveRow> rows,
    int maxBytes = maxArchiveBytes,
    int maxRows = maxRecords,
    int maxBlobs = maxMediaCount,
  }) async {
    _validateMetadata(
      schemaVersion: schemaVersion,
      stateVersion: stateVersion,
      capturedAtUtcMillis: capturedAtUtcMillis,
      maxBytes: maxBytes,
      maxRows: maxRows,
      maxBlobs: maxBlobs,
    );
    if (rows.length > maxRows) {
      throw const FormatException('The backup contains too many records.');
    }
    _validateRevisionGraph(rows);

    final preparedRows = <_PreparedRow>[];
    final primaryKeys = <String, Set<String>>{};
    final blobIds = <String>{};
    var blobCount = 0;
    var recordsLength = 0;
    var mediaLength = 0;

    for (final row in rows) {
      _validateRow(row, schemaVersion);
      blobCount += row.blobs.length;
      if (blobCount > maxBlobs) {
        throw const FormatException(
          'The backup contains too many media files.',
        );
      }

      final valueKeys = row.values.keys.toList()..sort();
      final rowBlobs = [...row.blobs]..sort((a, b) => a.id.compareTo(b.id));
      if (valueKeys.length > 0xffff || rowBlobs.length > 0xffff) {
        throw const FormatException('A backup record has too many fields.');
      }

      // Measure before UTF-8 encoding, hashing media, or allocating archive
      // buffers. Oversized input is rejected with bounded work and memory.
      var rowLength = 1 + _shortStringEncodedLength(row.table);
      rowLength = _boundedAdd(rowLength, 2, maxRecordBytes);
      for (final key in valueKeys) {
        rowLength = _boundedAdd(
          rowLength,
          _shortStringEncodedLength(key),
          maxRecordBytes,
        );
        rowLength = _boundedAdd(
          rowLength,
          _valueEncodedLength(row.values[key]),
          maxRecordBytes,
        );
      }
      rowLength = _boundedAdd(rowLength, 2, maxRecordBytes);
      for (final blob in rowBlobs) {
        if (blob.id.isEmpty || !blobIds.add(blob.id)) {
          throw const FormatException('The backup repeats a media identifier.');
        }
        if (!(_tables[row.table]?.blobColumns.contains(blob.column) ?? false)) {
          throw FormatException(
            'The backup contains an unsupported ${row.table} media field.',
          );
        }
        if (blob.bytes.length > 0xffffffff) {
          throw const FormatException('A backup media file is too large.');
        }
        rowLength = _boundedAdd(
          rowLength,
          _shortStringEncodedLength(blob.id),
          maxRecordBytes,
        );
        rowLength = _boundedAdd(
          rowLength,
          _shortStringEncodedLength(blob.column),
          maxRecordBytes,
        );
        rowLength = _boundedAdd(rowLength, 4 + 32, maxRecordBytes);
        mediaLength = _boundedAdd(mediaLength, blob.bytes.length, maxBytes);
      }
      if (rowLength > maxRecordBytes) {
        throw const FormatException('A backup record is too large.');
      }
      final primaryKey = _primaryKey(row);
      if (!primaryKeys.putIfAbsent(row.table, () => {}).add(primaryKey)) {
        throw FormatException('The backup repeats a ${row.table} record.');
      }
      recordsLength = _boundedAdd(recordsLength, rowLength, maxBytes);
      if (_headerLength + recordsLength + mediaLength > maxBytes) {
        throw const FormatException(
          'The full backup exceeds the supported size. No media was omitted.',
        );
      }
      preparedRows.add(_PreparedRow(row, valueKeys, rowBlobs, primaryKey));
    }

    preparedRows.sort((left, right) {
      final tableOrder = left.row.table.compareTo(right.row.table);
      if (tableOrder != 0) return tableOrder;
      return left.primaryKey.compareTo(right.primaryKey);
    });

    final totalLength = _headerLength + recordsLength + mediaLength;
    if (totalLength > maxBytes ||
        recordsLength > 0xffffffff ||
        mediaLength > 0xffffffff) {
      throw const FormatException(
        'The full backup exceeds the supported size. No media was omitted.',
      );
    }

    final hashesById = <String, List<int>>{};
    for (final prepared in preparedRows) {
      for (final blob in prepared.blobs) {
        final digest = await const Sha256().hash(blob.bytes);
        hashesById[blob.id] = digest.bytes;
      }
    }

    final output = Uint8List(totalLength);
    final header = ByteData.sublistView(output, 0, _headerLength);
    output.setRange(0, _magic.length, _magic);
    header.setUint16(8, currentFormatVersion, Endian.big);
    header.setUint16(10, _headerLength, Endian.big);
    header.setUint32(12, schemaVersion, Endian.big);
    header.setInt64(16, stateVersion, Endian.big);
    header.setInt64(24, capturedAtUtcMillis, Endian.big);
    header.setUint32(32, rows.length, Endian.big);
    header.setUint32(36, blobCount, Endian.big);
    header.setUint32(40, recordsLength, Endian.big);
    header.setUint32(44, mediaLength, Endian.big);

    final writer = _ArchiveWriter(output, _headerLength);
    for (final prepared in preparedRows) {
      writer.writeByte(1); // row record
      writer.writeShortString(prepared.row.table);
      writer.writeUint16(prepared.valueKeys.length);
      for (final key in prepared.valueKeys) {
        writer.writeShortString(key);
        writer.writeValue(prepared.row.values[key]);
      }
      writer.writeUint16(prepared.blobs.length);
      for (final blob in prepared.blobs) {
        writer.writeShortString(blob.id);
        writer.writeShortString(blob.column);
        writer.writeUint32(blob.bytes.length);
        writer.writeBytes(hashesById[blob.id]!);
      }
    }
    if (writer.offset != _headerLength + recordsLength) {
      throw StateError('Backup archive preflight length did not match output.');
    }
    for (final prepared in preparedRows) {
      for (final blob in prepared.blobs) {
        writer.writeBytes(blob.bytes);
      }
    }
    if (writer.offset != output.length) {
      throw StateError('Backup archive media length did not match output.');
    }
    return output;
  }

  /// Parses authenticated archive plaintext. Call only after AES-GCM succeeds.
  static Future<BackupArchive> decode(
    Uint8List input, {
    int maxBytes = maxArchiveBytes,
    int maxSchemaVersion = latestSchemaVersion,
    int maxRows = maxRecords,
    int maxBlobs = maxMediaCount,
  }) async {
    if (input.length < _headerLength || input.length > maxBytes) {
      throw const FormatException('This backup has an invalid size.');
    }
    for (var index = 0; index < _magic.length; index++) {
      if (input[index] != _magic[index]) {
        throw const FormatException(
          'This is not a supported Clockrove archive.',
        );
      }
    }

    final header = ByteData.sublistView(input, 0, _headerLength);
    final formatVersion = header.getUint16(8, Endian.big);
    final headerLength = header.getUint16(10, Endian.big);
    final schemaVersion = header.getUint32(12, Endian.big);
    final stateVersion = header.getInt64(16, Endian.big);
    final capturedAt = header.getInt64(24, Endian.big);
    final rowCount = header.getUint32(32, Endian.big);
    final blobCount = header.getUint32(36, Endian.big);
    final recordsLength = header.getUint32(40, Endian.big);
    final mediaLength = header.getUint32(44, Endian.big);

    if (formatVersion != currentFormatVersion ||
        headerLength != _headerLength) {
      throw const FormatException('This backup format is not supported.');
    }
    _validateMetadata(
      schemaVersion: schemaVersion,
      stateVersion: stateVersion,
      capturedAtUtcMillis: capturedAt,
      maxBytes: maxBytes,
      maxRows: maxRows,
      maxBlobs: maxBlobs,
      maxSchemaVersion: maxSchemaVersion,
    );
    if (rowCount > maxRows ||
        blobCount > maxBlobs ||
        _headerLength + recordsLength + mediaLength != input.length) {
      throw const FormatException('This backup has invalid size metadata.');
    }

    final recordStart = _headerLength;
    final recordEnd = recordStart + recordsLength;
    final reader = _ArchiveReader(
      Uint8List.sublistView(input, recordStart, recordEnd),
    );
    final rows = <BackupArchiveRow>[];
    final descriptors = <_BlobDescriptor>[];
    final primaryKeys = <String, Set<String>>{};
    final blobIds = <String>{};
    final rowBlobDescriptors = <List<_BlobDescriptor>>[];

    for (var rowIndex = 0; rowIndex < rowCount; rowIndex++) {
      final rowStart = reader.offset;
      reader.beginRecord(rowStart, maxRecordBytes);
      if (reader.readByte() != 1) {
        throw const FormatException(
          'This backup contains an unknown record type.',
        );
      }
      final table = reader.readShortString(maximumBytes: maxRecordBytes);
      final schema = _tables[table];
      if (schema == null || schema.minSchemaVersion > schemaVersion) {
        throw FormatException(
          'The backup contains unsupported table "$table".',
        );
      }

      final valueCount = reader.readUint16();
      if (valueCount == 0) {
        throw const FormatException('A backup record has no fields.');
      }
      final values = <String, Object?>{};
      for (var field = 0; field < valueCount; field++) {
        final name = reader.readShortString(maximumBytes: maxRecordBytes);
        if (name.isEmpty || values.containsKey(name)) {
          throw const FormatException('A backup record repeats a field name.');
        }
        values[name] = reader.readValue(maximumStringBytes: maxRecordBytes);
        reader.ensureWithin(rowStart, maxRecordBytes);
      }

      final rowBlobCount = reader.readUint16();
      final rowBlobs = <_BlobDescriptor>[];
      final rowBlobColumns = <String>{};
      for (var blobIndex = 0; blobIndex < rowBlobCount; blobIndex++) {
        final id = reader.readShortString(maximumBytes: maxRecordBytes);
        final column = reader.readShortString(maximumBytes: maxRecordBytes);
        final length = reader.readUint32();
        final hash = reader.readBytes(32);
        if (id.isEmpty ||
            !blobIds.add(id) ||
            !rowBlobColumns.add(column) ||
            !schema.blobColumns.contains(column)) {
          throw const FormatException('A backup media reference is invalid.');
        }
        if (values.containsKey(column)) {
          throw const FormatException(
            'A backup media field must not also appear in row values.',
          );
        }
        final descriptor = _BlobDescriptor(
          id: id,
          column: column,
          length: length,
          hash: Uint8List.fromList(hash),
        );
        rowBlobs.add(descriptor);
        descriptors.add(descriptor);
        reader.ensureWithin(rowStart, maxRecordBytes);
      }

      final row = BackupArchiveRow(
        table: table,
        values: values,
        blobs: const [],
      );
      _validateRow(row, schemaVersion);
      final primaryKey = _primaryKey(row);
      if (!primaryKeys.putIfAbsent(table, () => {}).add(primaryKey)) {
        throw FormatException('The backup repeats a $table record.');
      }
      if (reader.offset - rowStart > maxRecordBytes) {
        throw const FormatException('A backup record is too large.');
      }
      rows.add(row);
      // Store row-local descriptors temporarily by parallel index.
      rowBlobDescriptors.add(rowBlobs);
      reader.endRecord();
    }
    if (!reader.isAtEnd || descriptors.length != blobCount) {
      throw const FormatException(
        'This backup has inconsistent record counts.',
      );
    }

    final mediaStart = recordEnd;
    var mediaOffset = mediaStart;
    final mediaEnd = mediaStart + mediaLength;
    for (var rowIndex = 0; rowIndex < rows.length; rowIndex++) {
      final blobs = <BackupArchiveBlob>[];
      for (final descriptor in rowBlobDescriptors[rowIndex]) {
        if (descriptor.length > mediaEnd - mediaOffset) {
          throw const FormatException('A backup media file is truncated.');
        }
        final bytes = Uint8List.fromList(
          Uint8List.sublistView(
            input,
            mediaOffset,
            mediaOffset + descriptor.length,
          ),
        );
        final digest = await const Sha256().hash(bytes);
        if (!_constantTimeBytesEqual(digest.bytes, descriptor.hash)) {
          throw const FormatException(
            'A backup media file failed its checksum.',
          );
        }
        mediaOffset += descriptor.length;
        blobs.add(
          BackupArchiveBlob(
            id: descriptor.id,
            column: descriptor.column,
            bytes: bytes,
          ),
        );
      }
      rows[rowIndex] = BackupArchiveRow(
        table: rows[rowIndex].table,
        values: Map.unmodifiable(rows[rowIndex].values),
        blobs: List.unmodifiable(blobs),
      );
    }
    if (mediaOffset != mediaEnd) {
      throw const FormatException(
        'This backup contains unreferenced media data.',
      );
    }

    _validateRevisionGraph(rows);
    return BackupArchive(
      formatVersion: formatVersion,
      schemaVersion: schemaVersion,
      stateVersion: stateVersion,
      capturedAtUtcMillis: capturedAt,
      rows: List.unmodifiable(rows),
    );
  }

  static void _validateMetadata({
    required int schemaVersion,
    required int stateVersion,
    required int capturedAtUtcMillis,
    required int maxBytes,
    required int maxRows,
    required int maxBlobs,
    int maxSchemaVersion = latestSchemaVersion,
  }) {
    if (schemaVersion < 1 ||
        schemaVersion > maxSchemaVersion ||
        schemaVersion > 0xffffffff ||
        stateVersion < 0 ||
        stateVersion > _maxInt64 ||
        capturedAtUtcMillis < 0 ||
        capturedAtUtcMillis > _maxInt64 ||
        maxBytes < _headerLength ||
        maxBytes > maxArchiveBytes ||
        maxRows < 0 ||
        maxRows > maxRecords ||
        maxBlobs < 0 ||
        maxBlobs > maxMediaCount) {
      throw const FormatException('This backup uses unsupported metadata.');
    }
  }

  static void _validateRow(BackupArchiveRow row, int schemaVersion) {
    final schema = _tables[row.table];
    if (schema == null || schema.minSchemaVersion > schemaVersion) {
      throw FormatException(
        'The backup contains unsupported table "${row.table}".',
      );
    }
    for (final key in row.values.keys) {
      if (key.isEmpty || key.length > 128 || key.contains('\u0000')) {
        throw const FormatException('A backup column name is invalid.');
      }
      final value = row.values[key];
      if (value != null &&
          value is! int &&
          value is! double &&
          value is! String) {
        throw const FormatException(
          'A backup record contains an unsupported value.',
        );
      }
      if (value is int && (value < _minInt64 || value > _maxInt64)) {
        throw const FormatException(
          'A backup integer is outside SQLite range.',
        );
      }
      if (value is double && !value.isFinite) {
        throw const FormatException(
          'A backup record contains an invalid number.',
        );
      }
    }
    for (final key in schema.primaryKey) {
      if (!row.values.containsKey(key) || row.values[key] == null) {
        throw FormatException(
          'A ${row.table} record is missing its primary key.',
        );
      }
    }
    final seenColumns = <String>{};
    for (final column in schema.blobColumns) {
      if (row.values.containsKey(column) && row.values[column] != null) {
        throw FormatException(
          'A ${row.table} media field must use a binary descriptor.',
        );
      }
    }
    for (final blob in row.blobs) {
      if (blob.id.isEmpty ||
          !seenColumns.add(blob.column) ||
          !schema.blobColumns.contains(blob.column) ||
          row.values.containsKey(blob.column)) {
        throw FormatException('A ${row.table} media reference is invalid.');
      }
    }
  }

  static String _primaryKey(BackupArchiveRow row) {
    final schema = _tables[row.table]!;
    final builder = BytesBuilder(copy: false);
    for (final column in schema.primaryKey) {
      _writeValue(builder, row.values[column]);
    }
    return base64Url.encode(builder.takeBytes());
  }

  static void _validateRevisionGraph(List<BackupArchiveRow> rows) {
    final revisions = <String, Map<String, Object?>>{};
    for (final row in rows.where((row) => row.table == 'entity_revisions')) {
      final id = row.values['id'];
      if (id is! String || id.isEmpty) {
        throw const FormatException(
          'The backup contains an invalid revision ID.',
        );
      }
      revisions[id] = row.values;
    }
    final parents = <String, Set<String>>{};
    for (final row in rows.where((row) => row.table == 'revision_parents')) {
      final revision = row.values['revision_id'];
      final parent = row.values['parent_id'];
      if (revision is! String ||
          parent is! String ||
          revision == parent ||
          !revisions.containsKey(revision) ||
          !revisions.containsKey(parent)) {
        throw const FormatException(
          'The backup contains an invalid revision link.',
        );
      }
      parents.putIfAbsent(revision, () => {}).add(parent);
    }
    final heads = <String>{};
    for (final row in rows.where((row) => row.table == 'entity_heads')) {
      final kind = row.values['kind'];
      final entity = row.values['entity_id'];
      final revision = row.values['revision_id'];
      final revisionRow = revision is String ? revisions[revision] : null;
      if (kind is! String ||
          entity is! String ||
          revisionRow == null ||
          revisionRow['kind'] != kind ||
          revisionRow['entity_id'] != entity) {
        throw const FormatException(
          'The backup contains an invalid revision head.',
        );
      }
      heads.add(revision);
    }
    // Kahn's algorithm checks the full DAG without recursive calls, so a deep
    // valid history cannot exhaust the Dart stack during archive preflight.
    final pendingParents = <String, int>{
      for (final revision in revisions.keys)
        revision: (parents[revision] ?? const <String>{}).length,
    };
    final children = <String, List<String>>{};
    for (final entry in parents.entries) {
      for (final parent in entry.value) {
        children.putIfAbsent(parent, () => []).add(entry.key);
      }
    }
    final ready = <String>[
      for (final entry in pendingParents.entries)
        if (entry.value == 0) entry.key,
    ];
    var consumed = 0;
    for (var index = 0; index < ready.length; index++) {
      final revision = ready[index];
      consumed++;
      for (final child in children[revision] ?? const <String>[]) {
        final remaining = pendingParents[child]! - 1;
        pendingParents[child] = remaining;
        if (remaining == 0) ready.add(child);
      }
    }
    if (consumed != revisions.length) {
      throw const FormatException('The backup contains a revision cycle.');
    }
    if (revisions.isNotEmpty && heads.isEmpty) {
      throw const FormatException(
        'The backup has revisions without entity heads.',
      );
    }
  }

  static void _writeValue(BytesBuilder builder, Object? value) {
    if (value == null) {
      builder.addByte(0);
    } else if (value is int) {
      builder.addByte(1);
      final bytes = ByteData(8)..setInt64(0, value, Endian.big);
      builder.add(bytes.buffer.asUint8List());
    } else if (value is double) {
      if (!value.isFinite) {
        throw const FormatException(
          'A backup record contains an invalid number.',
        );
      }
      builder.addByte(2);
      final bytes = ByteData(8)..setFloat64(0, value, Endian.big);
      builder.add(bytes.buffer.asUint8List());
    } else if (value is String) {
      builder.addByte(3);
      final bytes = utf8.encode(value);
      _writeUint32(builder, bytes.length);
      builder.add(bytes);
    } else {
      throw const FormatException(
        'A backup record contains an unsupported value.',
      );
    }
  }

  static void _writeShortString(BytesBuilder builder, String value) {
    final bytes = utf8.encode(value);
    if (bytes.length > 0xffff) {
      throw const FormatException('A backup field name is too large.');
    }
    _writeUint16(builder, bytes.length);
    builder.add(bytes);
  }

  static void _writeUint16(BytesBuilder builder, int value) {
    if (value < 0 || value > 0xffff) {
      throw const FormatException('A backup field count is out of range.');
    }
    final bytes = ByteData(2)..setUint16(0, value, Endian.big);
    builder.add(bytes.buffer.asUint8List());
  }

  static void _writeUint32(BytesBuilder builder, int value) {
    if (value < 0 || value > 0xffffffff) {
      throw const FormatException('A backup field is out of range.');
    }
    final bytes = ByteData(4)..setUint32(0, value, Endian.big);
    builder.add(bytes.buffer.asUint8List());
  }

  static bool _constantTimeBytesEqual(List<int> left, List<int> right) {
    if (left.length != right.length) return false;
    var difference = 0;
    for (var index = 0; index < left.length; index++) {
      difference |= left[index] ^ right[index];
    }
    return difference == 0;
  }

  static int _shortStringEncodedLength(String value) =>
      2 + _utf8Length(value, 0xffff - 2);

  static int _valueEncodedLength(Object? value) {
    if (value == null) return 1;
    if (value is int || value is double) return 9;
    if (value is String) return 5 + _utf8Length(value, maxRecordBytes);
    throw const FormatException(
      'A backup record contains an unsupported value.',
    );
  }

  static int _utf8Length(String value, int maximum) {
    var bytes = 0;
    for (var index = 0; index < value.length; index++) {
      final unit = value.codeUnitAt(index);
      if (unit <= 0x7f) {
        bytes++;
      } else if (unit <= 0x7ff) {
        bytes += 2;
      } else if (unit >= 0xd800 &&
          unit <= 0xdbff &&
          index + 1 < value.length &&
          value.codeUnitAt(index + 1) >= 0xdc00 &&
          value.codeUnitAt(index + 1) <= 0xdfff) {
        bytes += 4;
        index++;
      } else {
        // UTF-8 replaces unmatched UTF-16 surrogates with U+FFFD.
        bytes += 3;
      }
      if (bytes > maximum) {
        throw const FormatException('A backup string is too large.');
      }
    }
    return bytes;
  }

  static int _boundedAdd(int left, int right, int maximum) {
    if (right < 0 || left > maximum - right) {
      throw const FormatException(
        'The full backup exceeds the supported size. No media was omitted.',
      );
    }
    return left + right;
  }
}

final class _PreparedRow {
  const _PreparedRow(this.row, this.valueKeys, this.blobs, this.primaryKey);

  final BackupArchiveRow row;
  final List<String> valueKeys;
  final List<BackupArchiveBlob> blobs;
  final String primaryKey;
}

final class BackupTableSchema {
  const BackupTableSchema(
    this.name,
    this.primaryKey, {
    this.blobColumns = const {},
    this.minSchemaVersion = 1,
  });

  final String name;
  final List<String> primaryKey;
  final Set<String> blobColumns;
  final int minSchemaVersion;
}

final class BackupArchiveBlob {
  const BackupArchiveBlob({
    required this.id,
    required this.column,
    required this.bytes,
  });

  final String id;
  final String column;
  final Uint8List bytes;
}

final class BackupArchiveRow {
  const BackupArchiveRow({
    required this.table,
    required this.values,
    required this.blobs,
  });

  final String table;
  final Map<String, Object?> values;
  final List<BackupArchiveBlob> blobs;
}

final class BackupArchive {
  const BackupArchive({
    required this.formatVersion,
    required this.schemaVersion,
    required this.stateVersion,
    required this.capturedAtUtcMillis,
    required this.rows,
  });

  final int formatVersion;
  final int schemaVersion;
  final int stateVersion;
  final int capturedAtUtcMillis;
  final List<BackupArchiveRow> rows;
}

final class _BlobDescriptor {
  const _BlobDescriptor({
    required this.id,
    required this.column,
    required this.length,
    required this.hash,
  });

  final String id;
  final String column;
  final int length;
  final Uint8List hash;
}

final class _ArchiveReader {
  _ArchiveReader(this.bytes) : _data = ByteData.sublistView(bytes);

  final Uint8List bytes;
  final ByteData _data;
  int offset = 0;
  int? _recordLimit;
  bool _recordLimitIsHard = false;

  bool get isAtEnd => offset == bytes.length;

  void beginRecord(int start, int maximumBytes) {
    if (start != offset || _recordLimit != null) {
      throw StateError('Backup archive record reader is out of sequence.');
    }
    final candidate = start + maximumBytes;
    _recordLimitIsHard = candidate < bytes.length;
    _recordLimit = _recordLimitIsHard ? candidate : bytes.length;
  }

  void endRecord() {
    _recordLimit = null;
    _recordLimitIsHard = false;
  }

  int readByte() => readBytes(1)[0];

  int readUint16() {
    _require(2);
    final result = _data.getUint16(offset, Endian.big);
    offset += 2;
    return result;
  }

  int readUint32() {
    _require(4);
    final result = _data.getUint32(offset, Endian.big);
    offset += 4;
    return result;
  }

  String readShortString({
    int maximumBytes = BackupArchiveCodec.maxRecordBytes,
  }) {
    final length = readUint16();
    if (length > maximumBytes) {
      throw const FormatException('A backup record is too large.');
    }
    return utf8.decode(readBytes(length), allowMalformed: false);
  }

  Object? readValue({
    int maximumStringBytes = BackupArchiveCodec.maxRecordBytes,
  }) {
    switch (readByte()) {
      case 0:
        return null;
      case 1:
        _require(8);
        final result = _data.getInt64(offset, Endian.big);
        offset += 8;
        return result;
      case 2:
        _require(8);
        final result = _data.getFloat64(offset, Endian.big);
        offset += 8;
        if (!result.isFinite) {
          throw const FormatException(
            'A backup record contains an invalid number.',
          );
        }
        return result;
      case 3:
        final length = readUint32();
        if (length > maximumStringBytes) {
          throw const FormatException('A backup record is too large.');
        }
        return utf8.decode(readBytes(length), allowMalformed: false);
      default:
        throw const FormatException(
          'A backup record has an unknown value type.',
        );
    }
  }

  Uint8List readBytes(int length) {
    if (length < 0) {
      throw const FormatException('A backup field has an invalid length.');
    }
    _require(length);
    final start = offset;
    offset += length;
    return Uint8List.sublistView(bytes, start, offset);
  }

  void ensureWithin(int start, int maximum) {
    if (offset - start > maximum) {
      throw const FormatException('A backup record is too large.');
    }
  }

  void _require(int length) {
    if (_recordLimitIsHard && length > _recordLimit! - offset) {
      throw const FormatException('A backup record is too large.');
    }
    if (length > bytes.length - offset) {
      throw const FormatException('This backup is truncated.');
    }
  }
}

final class _ArchiveWriter {
  _ArchiveWriter(this.bytes, this.offset) : _data = ByteData.sublistView(bytes);

  final Uint8List bytes;
  final ByteData _data;
  int offset;

  void writeByte(int value) => bytes[offset++] = value;

  void writeUint16(int value) {
    _data.setUint16(offset, value, Endian.big);
    offset += 2;
  }

  void writeUint32(int value) {
    _data.setUint32(offset, value, Endian.big);
    offset += 4;
  }

  void writeShortString(String value) {
    final encoded = utf8.encode(value);
    writeUint16(encoded.length);
    writeBytes(encoded);
  }

  void writeValue(Object? value) {
    if (value == null) {
      writeByte(0);
    } else if (value is int) {
      writeByte(1);
      _data.setInt64(offset, value, Endian.big);
      offset += 8;
    } else if (value is double) {
      writeByte(2);
      _data.setFloat64(offset, value, Endian.big);
      offset += 8;
    } else if (value is String) {
      final encoded = utf8.encode(value);
      writeByte(3);
      writeUint32(encoded.length);
      writeBytes(encoded);
    } else {
      throw const FormatException(
        'A backup record contains an unsupported value.',
      );
    }
  }

  void writeBytes(List<int> value) {
    bytes.setRange(offset, offset + value.length, value);
    offset += value.length;
  }
}
