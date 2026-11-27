import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'backup/backup_crypto_v2.dart';
import 'backup/backup_snapshot_exporter.dart';

const _autoBackupChannel = MethodChannel('clockrove/auto-backup/v1');
const _maxChunkBytes = 192 * 1024;

/// Runs in a short-lived headless Flutter engine created by [AutoBackupWorker].
/// Errors are reduced to bounded codes; the native worker records the status.
@pragma('vm:entry-point')
Future<void> autoBackupMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  String? runId;
  BackupSnapshotStreamExport? snapshot;
  try {
    final session = await _autoBackupChannel.invokeMapMethod<String, Object?>(
      'beginSession',
    );
    if (session == null) throw const FormatException('AUTO_SESSION_MISSING');
    final encrypted = session['encrypt'] as bool? ?? false;
    final password = session['password'] as String? ?? '';
    snapshot = await BackupSnapshotExporter().exportStream();
    final expectedBytes = encrypted
        ? BackupCryptoV2.envelopeLengthForArchiveBytes(
            snapshot.archiveByteLength,
          )
        : snapshot.archiveByteLength;
    final started = await _autoBackupChannel.invokeMapMethod<String, Object?>(
      'startDocument',
      {
        'totalBytes': expectedBytes,
        'encrypted': encrypted,
        'capturedAtUtcMillis': snapshot.capturedAtUtcMillis,
      },
    );
    runId = started?['runId'] as String?;
    if (runId == null) throw const FormatException('AUTO_RUN_MISSING');
    final sink = _AutoBackupSink(
      channel: _autoBackupChannel,
      runId: runId,
      expectedBytes: expectedBytes,
    );
    if (encrypted) {
      await BackupCryptoV2.encrypt(
        password: password,
        archive: snapshot.archiveStream,
        destination: sink,
      );
    } else {
      await for (final chunk in snapshot.archiveStream) {
        for (var offset = 0; offset < chunk.length; offset += _maxChunkBytes) {
          final end = offset + _maxChunkBytes;
          await sink.write(
            chunk.sublist(offset, end < chunk.length ? end : chunk.length),
          );
        }
      }
      await sink.commit();
    }
    runId = null;
    await _finished(ok: true, retry: false, code: 'SUCCESS');
  } on Object catch (error) {
    if (runId != null) {
      try {
        await _autoBackupChannel.invokeMethod<void>('cancelDocument', {
          'runId': runId,
        });
      } on Object {
        // The worker host also removes a partial document on detach.
      }
    }
    await _finished(
      ok: false,
      retry: _isRetryable(error),
      code: _failureCode(error),
    );
  } finally {
    try {
      await snapshot?.close();
    } on Object {
      // Native closes an abandoned snapshot on detach or idle expiry.
    }
  }
}

Future<void> _finished({
  required bool ok,
  required bool retry,
  required String code,
}) async {
  try {
    await _autoBackupChannel.invokeMethod<void>('autoBackupFinished', {
      'ok': ok,
      'retry': retry,
      'code': code,
    });
  } on Object {
    // The worker timeout is the final fallback when the channel is gone.
  }
}

const _retryableCodes = {
  'AUTO_BACKUP_UNAVAILABLE',
  'FOLDER_READ_FAILED',
  'FOLDER_WRITE_FAILED',
  'FOLDER_UNAVAILABLE',
  'STORAGE_UNAVAILABLE',
  'TIMEOUT',
};

const _knownCodes = {
  'DISABLED',
  'FOLDER_REQUIRED',
  'PASSWORD_REQUIRED',
  'SETTINGS_CHANGED',
  'SIZE_MISMATCH',
  'OFFSET_MISMATCH',
  'NO_RUN',
  'BUSY',
  'INVALID_ARGUMENT',
  ..._retryableCodes,
};

String _failureCode(Object error) {
  if (error is PlatformException) {
    return _knownCodes.contains(error.code) ? error.code : 'PLATFORM';
  }
  if (error is BackupCryptoV2CancelledException) return 'CANCELLED';
  if (error is BackupCryptoV2CleanupException) return 'CRYPTO_CLEANUP';
  if (error is FormatException) return 'FORMAT';
  if (error is StateError) return 'STATE';
  return 'FAILED';
}

bool _isRetryable(Object error) =>
    error is PlatformException && _retryableCodes.contains(error.code);

final class _AutoBackupSink implements BackupTransactionalByteSink {
  _AutoBackupSink({
    required this.channel,
    required this.runId,
    required this.expectedBytes,
  });

  final MethodChannel channel;
  final String runId;
  final int expectedBytes;
  int _written = 0;
  bool _terminal = false;

  @override
  Future<void> write(List<int> bytes) async {
    if (_terminal || bytes.isEmpty || bytes.length > _maxChunkBytes) {
      throw const FormatException('AUTO_CHUNK_INVALID');
    }
    final next = await channel.invokeMethod<int>('writeChunk', {
      'runId': runId,
      'offset': _written,
      'bytes': Uint8List.fromList(bytes),
    });
    if (next != _written + bytes.length) {
      throw const FormatException('AUTO_OFFSET_MISMATCH');
    }
    _written = next!;
    if (_written > expectedBytes) {
      throw const FormatException('AUTO_SIZE_MISMATCH');
    }
  }

  @override
  Future<void> commit() async {
    if (_terminal)
      throw StateError('The automatic backup is already terminal.');
    _terminal = true;
    final result = await channel.invokeMapMethod<String, Object?>(
      'finishDocument',
      {'runId': runId},
    );
    if (result == null) throw const FormatException('AUTO_FINISH_MISSING');
  }

  @override
  Future<void> discard() async {
    if (_terminal) return;
    _terminal = true;
    try {
      await channel.invokeMethod<void>('cancelDocument', {'runId': runId});
    } on Object {
      // The worker host deletes the partial file on detach.
    }
  }
}
