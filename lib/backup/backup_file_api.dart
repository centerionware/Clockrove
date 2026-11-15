import 'package:flutter/services.dart';

import 'backup_crypto.dart';

const int maxEncryptedBackupBytes = BackupCrypto.maxArchiveBytes + 72;
const int maxBackupDocumentChunkBytes = 192 * 1024;

/// Writes only encrypted backup bytes through an opaque native staging handle.
/// No filesystem path or storage permission crosses the Flutter boundary.
final class BackupFileApi {
  BackupFileApi({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('clockrove/backup-files/v1');

  final MethodChannel _channel;

  Future<String?> createBackupDocument({
    required int totalBytes,
    required String suggestedName,
  }) async {
    if (totalBytes < 1 || totalBytes > maxEncryptedBackupBytes) {
      throw const FormatException('The encrypted backup size is unsupported.');
    }
    final handle = await _channel.invokeMethod<String?>(
      'createBackupDocument',
      {'totalBytes': totalBytes, 'suggestedName': suggestedName},
    );
    if (handle != null && handle.isEmpty) {
      throw const FormatException('The backup document handle is invalid.');
    }
    return handle;
  }

  Future<int> writeBackupDocumentChunk({
    required String documentId,
    required int offset,
    required Uint8List bytes,
  }) async {
    if (documentId.isEmpty ||
        offset < 0 ||
        bytes.isEmpty ||
        bytes.length > maxBackupDocumentChunkBytes ||
        offset > maxEncryptedBackupBytes - bytes.length) {
      throw const FormatException('The encrypted backup chunk is invalid.');
    }
    final nextOffset = await _channel.invokeMethod<int>(
      'writeBackupDocumentChunk',
      {'documentId': documentId, 'offset': offset, 'bytes': bytes},
    );
    if (nextOffset != offset + bytes.length) {
      throw const FormatException('The backup file transfer did not advance.');
    }
    return nextOffset!;
  }

  Future<bool> finishBackupDocument(String documentId) async {
    if (documentId.isEmpty) {
      throw const FormatException('The backup document handle is invalid.');
    }
    return await _channel.invokeMethod<bool>('finishBackupDocument', {
          'documentId': documentId,
        }) ??
        false;
  }

  Future<void> cancelBackupDocument(String documentId) async {
    if (documentId.isEmpty) return;
    await _channel.invokeMethod<bool>('cancelBackupDocument', {
      'documentId': documentId,
    });
  }
}
