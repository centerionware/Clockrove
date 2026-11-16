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
  });

  final BackupImportPreview preview;
  final BackupArchiveReadMetrics metrics;

  /// Native JSON row bytes, including the per-row framing charge.
  final int stagedRecordBytes;

  final bool nativeHandleClosed;
}

/// Authenticates an encrypted file, then streams a second authenticated pass
/// through a bounded in-memory parser into the hidden native import stage.
/// Plaintext archive bytes are never written to a file or accumulated in a
/// whole-archive buffer. Preview is exposed only after the second pass commits.
final class BackupRestoreImporter {
  BackupRestoreImporter({BackupImportApi? api})
    : _api = api ?? BackupImportApi();

  final BackupImportApi _api;

  Future<BackupRestoreImportResult> decryptAndPreview({
    required File encryptedEnvelope,
    required String password,
    BackupCryptoCancellationToken? cancellationToken,
  }) async {
    // The native stage's identity includes the archive digest. Authenticate a
    // first bounded pass to learn that digest without retaining plaintext, then
    // authenticate and stream a second pass into native staging.
    final authenticated = await BackupCryptoV2.decrypt(
      password: password,
      envelope: encryptedEnvelope.openRead(),
      privateArchive: _DiscardingAuthenticatedSink(),
      cancellationToken: cancellationToken,
    );

    final plaintext = BackupArchivePlaintextChannel();
    BackupImportStarted? started;
    _BackupImportPageBuffer? pageBuffer;
    BackupImportPreview? preview;
    BackupArchiveReadMetrics? metrics;
    var nativeHandleClosed = true;
    Object? operationFailure;
    StackTrace? operationStack;
    Object? cleanupFailure;

    final parseFuture = BackupArchiveReader.stage(
      plaintext: plaintext,
      authenticatedArchiveBytes: authenticated.archiveBytes,
      authenticatedArchiveSha256: authenticated.archiveSha256,
      onHeader: (header) async {
        if (header.schemaVersion != currentBackupImportSchemaVersion ||
            header.recordCount > maxBackupImportRows ||
            header.mediaCount > maxBackupImportMediaCount ||
            header.mediaBytes > maxBackupImportMediaBytes) {
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

    try {
      final streamed = await BackupCryptoV2.decrypt(
        password: password,
        envelope: encryptedEnvelope.openRead(),
        privateArchive: _BackupArchiveParserSink(plaintext, parseFuture),
        cancellationToken: cancellationToken,
      );
      metrics = await parseFuture;
      if (streamed.archiveBytes != authenticated.archiveBytes ||
          !_sameDigest(streamed.archiveSha256, authenticated.archiveSha256) ||
          metrics.archiveBytes != streamed.archiveBytes ||
          !_sameDigest(metrics.archiveSha256, streamed.archiveSha256)) {
        throw const FormatException(
          'The authenticated backup changed between verification passes.',
        );
      }
      final handle = started;
      if (handle == null) throw StateError('Backup import was not started.');
      preview = await _api.finish(handle.importId);
      if (preview.recordCount != metrics.recordCount ||
          preview.mediaCount != metrics.mediaCount ||
          preview.mediaBytes != metrics.mediaBytes ||
          preview.stagedRecordBytes + preview.mediaBytes >
              maxBackupImportStagedBytes) {
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
    );
  }

  String _digestHex(List<int> bytes) =>
      bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();

  bool _sameDigest(List<int> left, List<int> right) {
    if (left.length != right.length) return false;
    var difference = 0;
    for (var index = 0; index < left.length; index++) {
      difference |= left[index] ^ right[index];
    }
    return difference == 0;
  }
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

final class _DiscardingAuthenticatedSink
    implements BackupTransactionalByteSink {
  bool _completed = false;

  @override
  Future<void> write(List<int> bytes) async {
    if (_completed) throw StateError('The verification sink is closed.');
  }

  @override
  Future<void> commit() async {
    if (_completed) throw StateError('The verification sink is closed.');
    _completed = true;
  }

  @override
  Future<void> discard() async {
    _completed = true;
  }
}

final class _BackupArchiveParserSink implements BackupTransactionalByteSink {
  _BackupArchiveParserSink(this.plaintext, this.parseFuture);

  final BackupArchivePlaintextChannel plaintext;
  final Future<BackupArchiveReadMetrics> parseFuture;
  bool _completed = false;

  @override
  Future<void> write(List<int> bytes) async {
    if (_completed) throw StateError('The plaintext parser sink is closed.');
    await plaintext.add(bytes);
  }

  @override
  Future<void> commit() async {
    if (_completed) throw StateError('The plaintext parser sink is closed.');
    _completed = true;
    await plaintext.close();
    await parseFuture;
  }

  @override
  Future<void> discard() async {
    if (_completed) return;
    _completed = true;
    await plaintext.close();
    try {
      await parseFuture;
    } on Object {
      // Preserve the envelope/authentication failure that caused discard.
    }
  }
}
