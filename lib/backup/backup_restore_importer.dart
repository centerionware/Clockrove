import 'dart:io';

import 'package:clockrove/backup/backup_archive.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:clockrove/backup/backup_import_api.dart';

final class BackupRestoreImportResult {
  const BackupRestoreImportResult({
    required this.preview,
    required this.metrics,
    required this.stagedRecordBytes,
    required this.nativeHandleClosed,
    required this.privateDirectoryPath,
  });

  final BackupImportPreview preview;
  final BackupArchiveReadMetrics metrics;

  /// Native JSON row bytes, including the per-row framing charge.
  final int stagedRecordBytes;

  final bool nativeHandleClosed;
  final String privateDirectoryPath;
}

/// Authenticates an encrypted file, streams its archive into native staging,
/// and returns the native preview. The one-shot stage and plaintext temp file
/// are always closed/discarded before this method completes.
final class BackupRestoreImporter {
  BackupRestoreImporter({BackupImportApi? api})
    : _api = api ?? BackupImportApi();

  final BackupImportApi _api;

  Future<BackupRestoreImportResult> decryptAndPreview({
    required File encryptedEnvelope,
    required String password,
    BackupCryptoCancellationToken? cancellationToken,
  }) async {
    final privateDirectory = await Directory.systemTemp.createTemp(
      'clockrove-restore-stage-',
    );
    final archiveFile = File('${privateDirectory.path}/authenticated.archive');
    BackupImportStarted? started;
    _BackupImportPageBuffer? pageBuffer;
    BackupImportPreview? preview;
    BackupArchiveReadMetrics? metrics;
    var nativeHandleClosed = true;
    Object? operationFailure;
    StackTrace? operationStack;
    Object? cleanupFailure;

    try {
      final authenticated = await BackupCryptoV2.decrypt(
        password: password,
        envelope: encryptedEnvelope.openRead(),
        privateArchive: _FileBackupTransactionalSink(archiveFile),
        cancellationToken: cancellationToken,
      );

      metrics = await BackupArchiveReader.stage(
        archiveFile: archiveFile,
        authenticatedArchiveBytes: authenticated.archiveBytes,
        authenticatedArchiveSha256: authenticated.archiveSha256,
        onHeader: (header) async {
          if (header.schemaVersion != currentBackupImportSchemaVersion ||
              header.recordCount > maxBackupImportRows ||
              header.mediaCount > maxBackupImportMediaCount) {
            throw const FormatException(
              'This backup exceeds the supported restore limits.',
            );
          }
          started = await _api.begin(
            archiveFormatVersion: header.formatVersion,
            schemaVersion: header.schemaVersion,
            archiveDigest: _digestHex(authenticated.archiveSha256),
            recordCount: header.recordCount,
            mediaCount: header.mediaCount,
            mediaBytes: header.mediaBytes,
          );
          pageBuffer = _BackupImportPageBuffer(_api, started!);
          return started!.maxBlobChunkBytes;
        },
        onRecord: (ordinal, record) => pageBuffer!.add(ordinal, record),
        onRecordsComplete: () => pageBuffer!.flush(),
        onMediaChunk: (blobId, offset, bytes) async {
          final handle = started;
          if (handle == null) {
            throw StateError('Backup import was not started.');
          }
          await _api.writeBlob(
            importId: handle.importId,
            blobId: blobId,
            offset: offset,
            bytes: bytes,
            maxChunkBytes: handle.maxBlobChunkBytes,
          );
        },
      );
      final handle = started;
      if (handle == null) throw StateError('Backup import was not started.');
      preview = await _api.finish(handle.importId);
      if (preview.recordCount != metrics.recordCount ||
          preview.mediaCount != metrics.mediaCount ||
          preview.mediaBytes != metrics.mediaBytes) {
        throw const FormatException(
          'The native preview does not match the authenticated archive.',
        );
      }
    } on Object catch (error, stackTrace) {
      operationFailure = error;
      operationStack = stackTrace;
    } finally {
      final handle = started;
      if (handle != null) {
        try {
          nativeHandleClosed = await _api.close(handle.importId);
        } on Object catch (error) {
          cleanupFailure ??= error;
        }
      }
      try {
        if (await privateDirectory.exists()) {
          await privateDirectory.delete(recursive: true);
        }
      } on Object catch (error) {
        cleanupFailure ??= error;
      }
    }

    if (operationFailure != null) {
      if (cleanupFailure != null) {
        throw BackupRestoreImportCleanupException(
          operationFailure: operationFailure,
          cleanupFailure: cleanupFailure,
        );
      }
      Error.throwWithStackTrace(operationFailure, operationStack!);
    }
    if (cleanupFailure != null) {
      throw BackupRestoreImportCleanupException(
        operationFailure: null,
        cleanupFailure: cleanupFailure,
      );
    }
    if (preview == null || metrics == null) {
      throw StateError('Backup preview did not complete.');
    }
    return BackupRestoreImportResult(
      preview: preview,
      metrics: metrics,
      stagedRecordBytes: preview.stagedRecordBytes,
      nativeHandleClosed: nativeHandleClosed,
      privateDirectoryPath: privateDirectory.path,
    );
  }

  String _digestHex(List<int> bytes) =>
      bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();
}

final class _BackupImportPageBuffer {
  _BackupImportPageBuffer(this.api, this.started);

  final BackupImportApi api;
  final BackupImportStarted started;
  final List<Map<String, Object?>> _records = [];
  int _nextOrdinal = 0;
  int _encodedRecordBytes = 0;

  Future<void> add(int ordinal, Map<String, Object?> record) async {
    if (ordinal != _nextOrdinal + _records.length) {
      throw const FormatException('Backup rows are out of sequence.');
    }
    final recordBytes = api.encodedAppendRecordBytes(record);
    final pageLimit = started.maxPageBytes < maxBackupImportRequestBytes
        ? started.maxPageBytes
        : maxBackupImportRequestBytes;
    const requestEnvelopeAllowance = 512;
    if (recordBytes + requestEnvelopeAllowance > pageLimit) {
      throw const FormatException(
        'A backup record exceeds the supported import page size.',
      );
    }
    if (_records.isNotEmpty &&
        (_records.length >= maxBackupImportPageRecords ||
            _encodedRecordBytes + recordBytes + requestEnvelopeAllowance >
                pageLimit)) {
      await flush();
    }
    _records.add(record);
    _encodedRecordBytes += recordBytes;
  }

  Future<void> flush() async {
    if (_records.isEmpty) return;
    final actualBytes = api.encodedAppendPageBytes(
      importId: started.importId,
      firstOrdinal: _nextOrdinal,
      records: _records,
    );
    final pageLimit = started.maxPageBytes < maxBackupImportRequestBytes
        ? started.maxPageBytes
        : maxBackupImportRequestBytes;
    if (actualBytes > pageLimit) {
      throw const FormatException(
        'The backup import page exceeds its encoded size limit.',
      );
    }
    final next = await api.appendPage(
      importId: started.importId,
      firstOrdinal: _nextOrdinal,
      records: List<Map<String, Object?>>.unmodifiable(_records),
    );
    _records.clear();
    _encodedRecordBytes = 0;
    _nextOrdinal = next;
  }
}

final class BackupRestoreImportCleanupException implements Exception {
  const BackupRestoreImportCleanupException({
    required this.operationFailure,
    required this.cleanupFailure,
  });

  final Object? operationFailure;
  final Object cleanupFailure;
}

final class _FileBackupTransactionalSink
    implements BackupTransactionalByteSink {
  _FileBackupTransactionalSink(this.file);

  final File file;
  RandomAccessFile? _output;
  bool _completed = false;

  @override
  Future<void> write(List<int> bytes) async {
    if (_completed) throw StateError('The private archive sink is closed.');
    final output = _output ??= await file.open(mode: FileMode.writeOnly);
    await output.writeFrom(bytes);
  }

  @override
  Future<void> commit() async {
    if (_completed) throw StateError('The private archive sink is closed.');
    await _output?.flush();
    await _output?.close();
    _output = null;
    _completed = true;
  }

  @override
  Future<void> discard() async {
    if (_completed) return;
    _completed = true;
    await _output?.close();
    _output = null;
    if (await file.exists()) await file.delete();
  }
}
