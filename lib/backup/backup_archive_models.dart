part of 'backup_archive.dart';

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
