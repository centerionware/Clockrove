import 'package:flutter/services.dart';

import 'backup_crypto_v2.dart';

const int maxEncryptedBackupBytes = BackupCryptoV2.maxEnvelopeBytes;
const int maxBackupDocumentChunkBytes = 192 * 1024;
const int maxBackupDocumentReadChunkBytes = 64 * 1024;

abstract interface class BackupEncryptedSource {
  int get totalBytes;

  Stream<List<int>> openRead();
}

/// Writes only encrypted backup bytes through an opaque native staging handle.
/// No filesystem path or storage permission crosses the Flutter boundary.
final class BackupFileApi {
  BackupFileApi({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('clockrove/backup-files/v1');

  final MethodChannel _channel;

  Future<BackupEncryptedDocument?> openBackupDocumentForImport() async {
    final raw = await _channel.invokeMethod<Object?>(
      'openBackupDocumentForImport',
    );
    if (raw == null) return null;
    final values = _stringMap(raw);
    final id = values['documentId'];
    final size = values['totalBytes'];
    if (id is! String ||
        id.isEmpty ||
        size is! int ||
        size < 1 ||
        size > maxEncryptedBackupBytes) {
      throw const FormatException('The selected backup document is invalid.');
    }
    return BackupEncryptedDocument._(this, id, size);
  }

  Future<Uint8List> readBackupDocumentChunk({
    required String documentId,
    required int offset,
    required int maxBytes,
  }) async {
    if (documentId.isEmpty ||
        offset < 0 ||
        maxBytes < 1 ||
        maxBytes > maxBackupDocumentReadChunkBytes ||
        offset > maxEncryptedBackupBytes - maxBytes) {
      throw const FormatException('The backup read request is invalid.');
    }
    final values = _stringMap(
      await _channel.invokeMethod<Object?>('readBackupDocumentChunk', {
        'documentId': documentId,
        'offset': offset,
        'maxBytes': maxBytes,
      }),
    );
    final returnedOffset = values['offset'];
    final rawBytes = values['bytes'];
    final bytes = rawBytes is Uint8List
        ? rawBytes
        : rawBytes is List<int>
        ? Uint8List.fromList(rawBytes)
        : null;
    if (returnedOffset != offset ||
        bytes == null ||
        bytes.length > maxBytes ||
        (bytes.isEmpty && offset < maxEncryptedBackupBytes)) {
      throw const FormatException('The backup read response is invalid.');
    }
    return bytes;
  }

  Future<bool> closeBackupDocumentImport(String documentId) async {
    if (documentId.isEmpty) return false;
    return await _channel.invokeMethod<bool>('closeBackupDocumentImport', {
          'documentId': documentId,
        }) ??
        false;
  }

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

final class BackupEncryptedDocument implements BackupEncryptedSource {
  BackupEncryptedDocument._(this._api, this.documentId, this.totalBytes);

  final BackupFileApi _api;
  final String documentId;

  @override
  final int totalBytes;

  bool _closed = false;

  bool get isClosed => _closed;

  @override
  Stream<List<int>> openRead() async* {
    if (_closed) throw StateError('The selected backup is closed.');
    var offset = 0;
    while (offset < totalBytes) {
      if (_closed) throw StateError('The selected backup is closed.');
      final maxBytes = (totalBytes - offset) < maxBackupDocumentReadChunkBytes
          ? totalBytes - offset
          : maxBackupDocumentReadChunkBytes;
      final bytes = await _api.readBackupDocumentChunk(
        documentId: documentId,
        offset: offset,
        maxBytes: maxBytes,
      );
      if (bytes.isEmpty || bytes.length > totalBytes - offset) {
        throw const FormatException('The backup file ended unexpectedly.');
      }
      yield bytes;
      offset += bytes.length;
    }
  }

  Future<bool> close() async {
    if (_closed) return false;
    final closed = await _api.closeBackupDocumentImport(documentId);
    _closed = true;
    return closed;
  }
}

Map<String, Object?> _stringMap(Object? value) {
  if (value is! Map) {
    throw const FormatException('The backup document response is invalid.');
  }
  final result = <String, Object?>{};
  for (final entry in value.entries) {
    if (entry.key is! String || result.containsKey(entry.key)) {
      throw const FormatException('The backup document response is invalid.');
    }
    result[entry.key as String] = entry.value;
  }
  return result;
}
