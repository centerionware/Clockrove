import 'package:clockrove/backup/backup_archive.dart';
import 'package:clockrove/backup/backup_crypto_v2.dart';
import 'package:clockrove/backup/backup_file_api.dart';
import 'package:clockrove/backup/backup_import_api.dart';
import 'package:crypto/crypto.dart' show sha256;
import 'package:flutter/services.dart';

/// Stable, developer-facing reason a restore attempt stopped. Codes are short
/// ASCII tokens and never contain file contents, paths or personal data, so the
/// owner can copy one into a bug report.
final class BackupRestoreReason implements Exception {
  const BackupRestoreReason(this.code);

  final String code;

  @override
  String toString() => 'BackupRestoreReason($code)';
}

/// Short reason shown next to a failed restore so a report names the exact gate.
String backupRestoreFailureCode(Object error) => switch (error) {
  BackupRestoreReason(:final code) => code,
  BackupUnlockException() => 'unlock',
  BackupCryptoV2CancelledException() => 'cancelled',
  BackupRestoreImportCleanupException() ||
  BackupCryptoV2CleanupException() => 'cleanup',
  FormatException() => 'format',
  PlatformException(:final code) => 'native-$code',
  StateError() => 'state',
  _ => 'unknown',
};

/// Native validation detail for a failed restore. The native layer reports the
/// first failing graph edge, table and projected row so a report is actionable;
/// keep it on one bounded line and only expose it for platform failures.
String? backupRestoreFailureDetail(Object error) {
  if (error is! PlatformException) return null;
  final message = error.message?.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (message == null || message.isEmpty) return null;
  return message.length <= 300 ? message : '${message.substring(0, 297)}...';
}

final class BackupRestoreImportResult {
  BackupRestoreImportResult({
    required this.preview,
    required this.metrics,
    required this.stagedRecordBytes,
    required this.api,
  });

  final BackupImportPreview preview;
  final BackupArchiveReadMetrics metrics;
  final BackupImportApi api;
  bool _closed = false;

  /// Native JSON row bytes, including the per-row framing charge.
  final int stagedRecordBytes;

  bool get nativeHandleClosed => _closed;

  /// The same transport used to create this stage. Keep it with the preview so
  /// callers can page conflicts, resolve choices, apply, and then close it.
  /// Discards this one-shot preview stage and its private files.
  Future<bool> close() async {
    if (_closed) return false;
    final closed = await api.close(preview.importId);
    _closed = true;
    return closed;
  }
}

/// Authenticates an encrypted file, then streams a second authenticated pass
/// through a bounded in-memory parser into the hidden native import stage.
/// Plaintext archive bytes are never written to a file or accumulated in a
/// whole-archive buffer. The sealed native preview handle stays open for
/// conflict paging/resolution and apply until the caller closes the result or
/// the native idle expiry removes it.
/// Detects whether a selected file is a plaintext archive or CLKROVE2 envelope.
Future<bool> isPlainBackupArchive(BackupEncryptedSource source) async {
  final prefix = await source
      .openRead()
      .expand((chunk) => chunk)
      .take(8)
      .toList();
  const magic = [0x43, 0x4c, 0x4b, 0x41, 0x52, 0x43, 0x48, 0x31];
  if (prefix.length != magic.length) return false;
  for (var index = 0; index < magic.length; index++) {
    if (prefix[index] != magic[index]) return false;
  }
  return true;
}

final class BackupRestoreImporter {
  BackupRestoreImporter({BackupImportApi? api})
    : _api = api ?? BackupImportApi();

  final BackupImportApi _api;

  /// Stages a plaintext archive without a password. The source is hashed once,
  /// then streamed through the same bounded parser used by encrypted restore.
  Future<BackupRestoreImportResult> previewPlainArchive({
    required BackupEncryptedSource archive,
    BackupCryptoCancellationToken? cancellationToken,
  }) async {
    if (archive.totalBytes < 1 ||
        archive.totalBytes > maxEncryptedBackupBytes) {
      throw const BackupRestoreReason('file-size');
    }
    final digest = await sha256.bind(archive.openRead()).first;
    final plaintext = BackupArchivePlaintextChannel();
    BackupImportStarted? started;
    _BackupImportPageBuffer? pageBuffer;
    BackupImportPreview? preview;
    BackupArchiveReadMetrics? metrics;
    var retainPreviewHandle = false;
    Object? operationFailure;
    StackTrace? operationStack;
    Object? cleanupFailure;

    final parseFuture = BackupArchiveReader.stage(
      plaintext: plaintext,
      authenticatedArchiveBytes: archive.totalBytes,
      authenticatedArchiveSha256: digest.bytes,
      onHeader: (header) async {
        if (header.schemaVersion != currentBackupImportSchemaVersion) {
          throw BackupRestoreReason(
            'schema-${header.schemaVersion}-of-$currentBackupImportSchemaVersion',
          );
        }
        if (header.recordCount > maxBackupImportRows) {
          throw const BackupRestoreReason('row-limit');
        }
        if (header.mediaCount > maxBackupImportMediaCount) {
          throw const BackupRestoreReason('media-count-limit');
        }
        if (header.mediaBytes > maxBackupImportMediaBytes) {
          throw const BackupRestoreReason('media-bytes-limit');
        }
        started = await _api.begin(
          archiveFormatVersion: header.formatVersion,
          schemaVersion: header.schemaVersion,
          archiveDigest: _digestHex(digest.bytes),
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
      await for (final chunk in archive.openRead()) {
        if (cancellationToken?.isCancelled ?? false) {
          throw const BackupCryptoV2CancelledException();
        }
        for (
          var offset = 0;
          offset < chunk.length;
          offset += BackupArchivePlaintextChannel.maxChunkBytes
        ) {
          final end = offset + BackupArchivePlaintextChannel.maxChunkBytes;
          await plaintext.add(
            chunk.sublist(offset, end < chunk.length ? end : chunk.length),
          );
        }
      }
      await plaintext.close();
      metrics = await parseFuture;
      if (metrics.archiveBytes != archive.totalBytes ||
          !_sameDigest(metrics.archiveSha256, digest.bytes)) {
        throw const BackupRestoreReason('archive-changed');
      }
      final handle = started;
      if (handle == null) throw StateError('Backup import was not started.');
      if (cancellationToken?.isCancelled ?? false) {
        throw const BackupCryptoV2CancelledException();
      }
      preview = await _api.finish(handle.importId);
      if (cancellationToken?.isCancelled ?? false) {
        throw const BackupCryptoV2CancelledException();
      }
      if (preview.recordCount != metrics.recordCount ||
          preview.mediaCount != metrics.mediaCount ||
          preview.mediaBytes != metrics.mediaBytes ||
          preview.stagedRecordBytes + preview.mediaBytes >
              maxBackupImportStagedBytes) {
        throw const BackupRestoreReason('stage-mismatch');
      }
      retainPreviewHandle = true;
    } on Object catch (error, stackTrace) {
      operationFailure = error;
      operationStack = stackTrace;
      try {
        await plaintext.close();
      } on Object {
        // The native stage expiry also removes an abandoned preview.
      }
      try {
        await parseFuture;
      } on Object {
        // Preserve the source/parser failure that stopped this attempt.
      }
    } finally {
      final handle = started;
      if (handle != null && !retainPreviewHandle) {
        try {
          await _api.close(handle.importId);
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
      api: _api,
    );
  }

  Future<BackupRestoreImportResult> decryptAndPreview({
    required BackupEncryptedSource encryptedEnvelope,
    required String password,
    BackupCryptoCancellationToken? cancellationToken,
  }) async {
    if (encryptedEnvelope.totalBytes < 1 ||
        encryptedEnvelope.totalBytes > maxEncryptedBackupBytes) {
      throw const BackupRestoreReason('file-size');
    }
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
    var retainPreviewHandle = false;
    Object? operationFailure;
    StackTrace? operationStack;
    Object? cleanupFailure;

    final parseFuture = BackupArchiveReader.stage(
      plaintext: plaintext,
      authenticatedArchiveBytes: authenticated.archiveBytes,
      authenticatedArchiveSha256: authenticated.archiveSha256,
      onHeader: (header) async {
        // Name the exact gate that rejected the archive so a failure report is
        // actionable instead of a generic "could not be processed".
        if (header.schemaVersion != currentBackupImportSchemaVersion) {
          throw BackupRestoreReason(
            'schema-${header.schemaVersion}-of-$currentBackupImportSchemaVersion',
          );
        }
        if (header.recordCount > maxBackupImportRows) {
          throw const BackupRestoreReason('row-limit');
        }
        if (header.mediaCount > maxBackupImportMediaCount) {
          throw const BackupRestoreReason('media-count-limit');
        }
        if (header.mediaBytes > maxBackupImportMediaBytes) {
          throw const BackupRestoreReason('media-bytes-limit');
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
        throw const BackupRestoreReason('archive-changed');
      }
      final handle = started;
      if (handle == null) throw StateError('Backup import was not started.');
      if (cancellationToken?.isCancelled ?? false) {
        throw const BackupCryptoV2CancelledException();
      }
      preview = await _api.finish(handle.importId);
      if (cancellationToken?.isCancelled ?? false) {
        throw const BackupCryptoV2CancelledException();
      }
      if (preview.recordCount != metrics.recordCount ||
          preview.mediaCount != metrics.mediaCount ||
          preview.mediaBytes != metrics.mediaBytes ||
          preview.stagedRecordBytes + preview.mediaBytes >
              maxBackupImportStagedBytes) {
        throw const BackupRestoreReason('stage-mismatch');
      }
      retainPreviewHandle = true;
    } on Object catch (error, stackTrace) {
      operationFailure = error;
      operationStack = stackTrace;
    } finally {
      final handle = started;
      if (handle != null && !retainPreviewHandle) {
        try {
          await _api.close(handle.importId);
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
      api: _api,
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
