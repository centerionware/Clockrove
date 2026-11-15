import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'backup_crypto_v2.dart';
import 'backup_file_api.dart';
import 'backup_snapshot_exporter.dart';

typedef BackupSnapshotExportRunner =
    Future<BackupSnapshotStreamExport> Function({
      BackupCancellationCheck? isCancelled,
      BackupExportProgressCallback? onProgress,
    });

typedef BackupArchiveEncryptor = Future<BackupCryptoV2Result> Function({
  required String password,
  required Stream<List<int>> archive,
  required BackupTransactionalByteSink destination,
  bool Function()? isCancelled,
  BackupCryptoCancellationToken? cancellationToken,
});

/// Password-protected, user-directed backup export.
final class BackupExportScreen extends StatefulWidget {
  const BackupExportScreen({
    super.key,
    this.exportBackup,
    this.encryptArchive,
    this.fileApi,
  });

  final BackupSnapshotExportRunner? exportBackup;
  final BackupArchiveEncryptor? encryptArchive;
  final BackupFileApi? fileApi;

  @override
  State<BackupExportScreen> createState() => _BackupExportScreenState();
}

enum _BackupExportPhase {
  idle,
  reading,
  encrypting,
  choosing,
  transferring,
  writing,
  cancelling,
  saved,
  cancelled,
  failed,
}

final class _BackupSaveCompletionUnknown implements Exception {
  const _BackupSaveCompletionUnknown();
}

final class _BackupSaveIncomplete implements Exception {
  const _BackupSaveIncomplete();
}

final class _BackupExportScreenState extends State<BackupExportScreen> {
  final _password = TextEditingController();
  final _confirmation = TextEditingController();
  late final BackupSnapshotExportRunner _exportBackup =
      widget.exportBackup ?? BackupSnapshotExporter().exportStream;
  late final BackupArchiveEncryptor _encryptArchive =
      widget.encryptArchive ?? BackupCryptoV2.encrypt;
  late final BackupFileApi _fileApi = widget.fileApi ?? BackupFileApi();

  _BackupExportPhase _phase = _BackupExportPhase.idle;
  BackupSnapshotProgress? _snapshotProgress;
  int _transferredBytes = 0;
  int _expectedBytes = 0;
  int _openSessionCount = 0;
  String? _passwordError;
  String? _confirmationError;
  String? _message;
  bool _passwordVisible = false;
  bool _cancelRequested = false;
  bool _busy = false;
  bool _canCancel = true;
  BackupCryptoCancellationToken? _activeCancellationToken;

  @override
  void dispose() {
    _cancelRequested = true;
    _activeCancellationToken?.cancel();
    _password.dispose();
    _confirmation.dispose();
    super.dispose();
  }

  void _inputChanged() {
    if (_passwordError != null || _confirmationError != null) {
      setState(() {
        _passwordError = null;
        _confirmationError = null;
      });
    }
  }

  bool _validate() {
    final utf8Bytes = utf8.encode(_password.text).length;
    setState(() {
      _passwordError = _password.text.isEmpty
          ? 'Enter a password.'
          : utf8Bytes < 12
          ? 'Use a longer password (at least 12 UTF-8 bytes).'
          : utf8Bytes > 1024
          ? 'Password is too long. The maximum is 1,024 UTF-8 bytes.'
          : null;
      _confirmationError = _confirmation.text != _password.text
          ? 'The passwords do not match.'
          : null;
    });
    return _passwordError == null && _confirmationError == null;
  }

  void _setPhase(
    _BackupExportPhase phase, {
    String? message,
    BackupSnapshotProgress? snapshotProgress,
    int? transferredBytes,
    int? expectedBytes,
    int? openSessionCount,
    bool? canCancel,
  }) {
    if (!mounted) return;
    setState(() {
      _phase = phase;
      _message = message;
      if (snapshotProgress != null) _snapshotProgress = snapshotProgress;
      if (transferredBytes != null) _transferredBytes = transferredBytes;
      if (expectedBytes != null) _expectedBytes = expectedBytes;
      if (openSessionCount != null) _openSessionCount = openSessionCount;
      if (canCancel != null) _canCancel = canCancel;
    });
  }

  void _requestCancel() {
    if (!_busy || !_canCancel || _cancelRequested) return;
    _cancelRequested = true;
    _activeCancellationToken?.cancel();
    _setPhase(
      _BackupExportPhase.cancelling,
      message: 'Cancelling backup…',
      canCancel: false,
    );
  }

  void _throwIfCancelled() {
    if (_cancelRequested) throw const BackupExportCancelledException();
  }

  Future<void> _createBackup() async {
    if (_busy || !_validate()) return;

    _cancelRequested = false;
    _busy = true;
    _snapshotProgress = null;
    _transferredBytes = 0;
    _expectedBytes = 0;
    _openSessionCount = 0;
    _setPhase(_BackupExportPhase.reading, message: 'Reading your saved work…');

    BackupSnapshotStreamExport? snapshot;
    BackupCryptoCancellationToken? cancellationToken;
    _BackupFileSink? destination;
    String? documentId;
    var documentSaved = false;
    try {
      snapshot = await _exportBackup(
        isCancelled: () => _cancelRequested,
        onProgress: (progress) {
          if (_cancelRequested || _phase != _BackupExportPhase.reading) return;
          _setPhase(
            _BackupExportPhase.reading,
            message: 'Reading your saved work…',
            snapshotProgress: progress,
          );
        },
      );
      _throwIfCancelled();
      _setPhase(
        _BackupExportPhase.choosing,
        message: 'Choose where to save the encrypted backup.',
        openSessionCount: snapshot.openSessionCount,
        expectedBytes: BackupCryptoV2.envelopeLengthForArchiveBytes(
          snapshot.archiveByteLength,
        ),
        transferredBytes: 0,
        canCancel: false,
      );
      documentId = await _fileApi.createBackupDocument(
        totalBytes: _expectedBytes,
        suggestedName: _suggestedName(),
      );
      if (documentId == null) {
        _setPhase(
          _BackupExportPhase.cancelled,
          message: 'Save cancelled. No complete backup was saved.',
        );
        return;
      }
      _throwIfCancelled();

      cancellationToken = BackupCryptoCancellationToken();
      _activeCancellationToken = cancellationToken;
      destination = _BackupFileSink(
        api: _fileApi,
        documentId: documentId,
        expectedBytes: _expectedBytes,
        onWrite: (transferred) {
          final commitStarted = destination?.commitStarted ?? false;
          _setPhase(
            commitStarted
                ? _BackupExportPhase.writing
                : _BackupExportPhase.encrypting,
            message: commitStarted
                ? 'Finishing save…'
                : 'Encrypting and saving backup…',
            transferredBytes: transferred,
            expectedBytes: _expectedBytes,
            canCancel: !commitStarted && !_cancelRequested,
          );
        },
        onCommitStarted: () => _setPhase(
          _BackupExportPhase.writing,
          message: 'Finishing save…',
          canCancel: false,
        ),
        finish: () => _finishWithRetry(documentId!),
      );
      _setPhase(
        _BackupExportPhase.encrypting,
        message: 'Encrypting and saving backup…',
        canCancel: true,
      );
      await _encryptArchive(
        password: _password.text,
        archive: snapshot.archiveStream,
        destination: destination,
        isCancelled: () => _cancelRequested,
        cancellationToken: cancellationToken,
      );
      documentSaved = destination.committed;
      if (!documentSaved) throw const _BackupSaveIncomplete();
      _setPhase(
        _BackupExportPhase.saved,
        message: 'Encrypted backup saved.',
        canCancel: false,
      );
    } on BackupExportCancelledException {
      _setPhase(
        _BackupExportPhase.cancelled,
        message: 'Backup cancelled. No complete backup file was saved.',
        canCancel: false,
      );
    } on BackupCryptoV2CancelledException {
      _setPhase(
        _BackupExportPhase.cancelled,
        message: 'Backup cancelled. No complete backup file was saved.',
        canCancel: false,
      );
    } on _BackupSaveCompletionUnknown {
      _setPhase(
        _BackupExportPhase.failed,
        message: 'The save may have completed. Check the chosen folder before trying again.',
        canCancel: false,
      );
    } on _BackupSaveIncomplete {
      _setPhase(
        _BackupExportPhase.failed,
        message: 'Could not complete the save. Remove any incomplete file and try again.',
        canCancel: false,
      );
    } on PlatformException catch (error) {
      _setPhase(
        _BackupExportPhase.failed,
        message: _platformFailureMessage(error),
        canCancel: false,
      );
    } on Object {
      _setPhase(
        _BackupExportPhase.failed,
        message:
            'Could not create the backup. Your saved work was not changed.',
        canCancel: false,
      );
    } finally {
      _activeCancellationToken = null;
      if (documentId != null && !documentSaved) {
        try {
          await _fileApi.cancelBackupDocument(documentId);
        } on Object {
          // The native bridge also expires abandoned encrypted staging files.
        }
      }
      Object? snapshotCleanupFailure;
      try {
        await snapshot?.close();
      } on Object catch (error) {
        snapshotCleanupFailure = error;
      }
      if (snapshotCleanupFailure != null) {
        _setPhase(
          _BackupExportPhase.failed,
          message: 'Could not clean up the backup snapshot. Check the save location before trying again.',
          canCancel: false,
        );
      }
      if (mounted) {
        setState(() {
          _busy = false;
          _canCancel = true;
        });
      } else {
        _busy = false;
      }
      if (mounted) {
        _password.clear();
        _confirmation.clear();
      }
    }
  }

  Future<bool> _finishWithRetry(String documentId) async {
    try {
      return await _fileApi.finishBackupDocument(documentId);
    } on Exception {
      try {
        return await _fileApi.finishBackupDocument(documentId);
      } on Exception {
        throw const _BackupSaveCompletionUnknown();
      }
    }
  }

  String _suggestedName() {
    final now = DateTime.now().toUtc();
    final date =
        '${now.year.toString().padLeft(4, '0')}'
        '${now.month.toString().padLeft(2, '0')}'
        '${now.day.toString().padLeft(2, '0')}';
    return 'Clockrove-backup-$date.clockrove';
  }

  String _platformFailureMessage(
    PlatformException error,
  ) => switch (error.code) {
    'NO_DOCUMENT_PICKER' => 'No file picker is available. Install or enable a file manager and try again.',
    'DOCUMENT_PICKER_OPEN' =>
      'Finish the other open file action, then try again.',
    'BACKUP_SAVE_TOO_LARGE' =>
      'This backup is too large to save on this device.',
    _ =>
      'Could not save the encrypted backup. Your saved work was not changed.',
  };

  double? get _progressValue => switch (_phase) {
    _BackupExportPhase.reading => _snapshotProgress?.fraction,
    _BackupExportPhase.encrypting when _expectedBytes > 0 =>
      (_transferredBytes / _expectedBytes).clamp(0, 1).toDouble(),
    _BackupExportPhase.transferring when _expectedBytes > 0 =>
      (_transferredBytes / _expectedBytes).clamp(0, 1).toDouble(),
    _BackupExportPhase.reading ||
    _BackupExportPhase.encrypting ||
    _BackupExportPhase.choosing ||
    _BackupExportPhase.transferring ||
    _BackupExportPhase.writing ||
    _BackupExportPhase.cancelling => null,
    _ => null,
  };

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final showProgress = _busy && _phase != _BackupExportPhase.choosing;
    return Scaffold(
      appBar: AppBar(title: const Text('Create encrypted backup')),
      body: ListView(
        key: const ValueKey('backup-export-list'),
        padding: const EdgeInsets.all(20),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.lock_outline, color: colors.primary),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          'Your backup contains private work, location history, '
                          'invoices, receipts, tasks and attached photos.',
                          style: Theme.of(context).textTheme.bodyLarge,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'Choose a password you can keep safe. Clockrove cannot '
                    'recover it if you forget it. The password is not saved in the app.',
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Export does not stop a running timer. It is included as '
                    'open work and will not start automatically if restored.',
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 20),
          TextField(
            key: const ValueKey('backup-password'),
            controller: _password,
            enabled: !_busy,
            obscureText: !_passwordVisible,
            autofillHints: const [AutofillHints.newPassword],
            textInputAction: TextInputAction.next,
            onChanged: (_) => _inputChanged(),
            decoration: InputDecoration(
              labelText: 'Backup password',
              helperText: 'At least 12 UTF-8 bytes; maximum 1,024 bytes.',
              errorText: _passwordError,
              suffixIcon: IconButton(
                tooltip: _passwordVisible ? 'Hide password' : 'Show password',
                onPressed: _busy
                    ? null
                    : () =>
                          setState(() => _passwordVisible = !_passwordVisible),
                icon: Icon(
                  _passwordVisible ? Icons.visibility_off : Icons.visibility,
                ),
              ),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('backup-password-confirmation'),
            controller: _confirmation,
            enabled: !_busy,
            obscureText: !_passwordVisible,
            textInputAction: TextInputAction.done,
            onChanged: (_) => _inputChanged(),
            onSubmitted: (_) => _createBackup(),
            decoration: InputDecoration(
              labelText: 'Confirm password',
              errorText: _confirmationError,
            ),
          ),
          if (_openSessionCount > 0) ...[
            const SizedBox(height: 16),
            Card(
              color: colors.secondaryContainer,
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Text(
                  'This backup includes $_openSessionCount open '
                  '${_openSessionCount == 1 ? 'timer' : 'timers'}. Your timer will keep running.',
                ),
              ),
            ),
          ],
          if (showProgress) ...[
            const SizedBox(height: 24),
            LinearProgressIndicator(value: _progressValue),
            const SizedBox(height: 10),
            Text(_phaseMessage),
            if (_phase == _BackupExportPhase.reading &&
                _snapshotProgress != null)
              Text(
                '${_snapshotProgress!.recordsRead} of '
                '${_snapshotProgress!.totalRecords} records · '
                '${_snapshotProgress!.mediaBytesRead} of '
                '${_snapshotProgress!.totalMediaBytes} media bytes',
              ),
            if (_phase == _BackupExportPhase.transferring)
              Text('$_transferredBytes of $_expectedBytes encrypted bytes'),
            if (_phase == _BackupExportPhase.encrypting &&
                _transferredBytes > 0)
              Text('$_transferredBytes of $_expectedBytes encrypted bytes'),
          ],
          if (_phase == _BackupExportPhase.choosing) ...[
            const SizedBox(height: 20),
            const LinearProgressIndicator(),
            const SizedBox(height: 10),
            const Text('Use Back in the Android file picker to cancel.'),
          ],
          if (_message case final message?) ...[
            const SizedBox(height: 20),
            Semantics(
              liveRegion: true,
              child: Text(
                message,
                key: const ValueKey('backup-export-message'),
                style: TextStyle(
                  color: _phase == _BackupExportPhase.failed
                      ? colors.error
                      : colors.onSurface,
                ),
              ),
            ),
          ],
          const SizedBox(height: 24),
          if (!_busy)
            FilledButton.icon(
              key: const ValueKey('create-encrypted-backup'),
              onPressed: _createBackup,
              icon: const Icon(Icons.download_outlined),
              label: const Text('Create encrypted backup'),
            )
          else if (_canCancel)
            OutlinedButton.icon(
              key: const ValueKey('cancel-backup-export'),
              onPressed: _requestCancel,
              icon: const Icon(Icons.close),
              label: const Text('Cancel backup'),
            ),
        ],
      ),
    );
  }

  String get _phaseMessage => switch (_phase) {
    _BackupExportPhase.reading => 'Reading your saved work…',
    _BackupExportPhase.encrypting => 'Encrypting and saving backup…',
    _BackupExportPhase.transferring => 'Saving encrypted backup…',
    _BackupExportPhase.writing => 'Finishing save…',
    _BackupExportPhase.cancelling => 'Cancelling backup…',
    _ => _message ?? '',
  };
}

final class _BackupFileSink implements BackupTransactionalByteSink {
  _BackupFileSink({
    required BackupFileApi api,
    required this.documentId,
    required this.expectedBytes,
    required this.onWrite,
    required this.onCommitStarted,
    required this.finish,
  }) : _api = api;

  final BackupFileApi _api;
  final String documentId;
  final int expectedBytes;
  final void Function(int transferredBytes) onWrite;
  final void Function() onCommitStarted;
  final Future<bool> Function() finish;
  BytesBuilder _pending = BytesBuilder(copy: false);
  int _pendingBytes = 0;
  int _writtenBytes = 0;
  bool _commitStarted = false;
  bool _committed = false;
  bool _discarded = false;

  bool get committed => _committed;

  bool get commitStarted => _commitStarted;

  @override
  Future<void> write(List<int> bytes) async {
    if (_commitStarted || _committed || _discarded) {
      throw StateError('The backup file destination is already terminal.');
    }
    if (bytes.isEmpty ||
        bytes.length > expectedBytes - _writtenBytes - _pendingBytes) {
      throw const FormatException(
        'The encrypted backup exceeded its preflight size.',
      );
    }
    _pending.add(bytes);
    _pendingBytes += bytes.length;
    if (_pendingBytes >= maxBackupDocumentChunkBytes) await _flush();
  }

  @override
  Future<void> commit() async {
    if (_commitStarted || _committed || _discarded) {
      throw StateError('The backup file destination is already terminal.');
    }
    _commitStarted = true;
    onCommitStarted();
    await _flush();
    if (_writtenBytes != expectedBytes) {
      throw const FormatException(
        'The encrypted backup did not match its preflight size.',
      );
    }
    if (!await finish()) throw const _BackupSaveIncomplete();
    _committed = true;
  }

  @override
  Future<void> discard() async {
    if (_committed || _discarded) return;
    _discarded = true;
    _pending = BytesBuilder(copy: false);
    _pendingBytes = 0;
    await _api.cancelBackupDocument(documentId);
  }

  Future<void> _flush() async {
    if (_pendingBytes == 0) return;
    final bytes = _pending.takeBytes();
    _pending = BytesBuilder(copy: false);
    _pendingBytes = 0;
    var offset = 0;
    while (offset < bytes.length) {
      final end = (offset + maxBackupDocumentChunkBytes) < bytes.length
          ? offset + maxBackupDocumentChunkBytes
          : bytes.length;
      _writtenBytes = await _api.writeBackupDocumentChunk(
        documentId: documentId,
        offset: _writtenBytes,
        bytes: Uint8List.sublistView(bytes, offset, end),
      );
      offset = end;
      onWrite(_writtenBytes);
    }
  }
}
