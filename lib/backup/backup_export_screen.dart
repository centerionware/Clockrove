import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'backup_crypto.dart';
import 'backup_file_api.dart';
import 'backup_snapshot_exporter.dart';

typedef BackupSnapshotExportRunner = Future<BackupSnapshotExport> Function({
  BackupCancellationCheck? isCancelled,
  BackupExportProgressCallback? onProgress,
});

typedef BackupArchiveEncryptor = Future<Uint8List> Function({
  required String password,
  required Uint8List archive,
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
      widget.exportBackup ?? BackupSnapshotExporter().export;
  late final BackupArchiveEncryptor _encryptArchive =
      widget.encryptArchive ?? BackupCrypto.encrypt;
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

  @override
  void dispose() {
    _cancelRequested = true;
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

    BackupSnapshotExport? snapshot;
    Uint8List? encrypted;
    String? documentId;
    var documentSaved = false;
    try {
      snapshot = await _exportBackup(
        isCancelled: () => _cancelRequested,
        onProgress: (progress) {
          if (_cancelRequested) return;
          _setPhase(
            _BackupExportPhase.reading,
            message: 'Reading your saved work…',
            snapshotProgress: progress,
          );
        },
      );
      _throwIfCancelled();
      _setPhase(
        _BackupExportPhase.encrypting,
        message: 'Encrypting backup on this device…',
        openSessionCount: snapshot.openSessionCount,
      );
      encrypted = await _encryptArchive(
        password: _password.text,
        archive: snapshot.archiveBytes,
      );
      snapshot.archiveBytes.fillRange(0, snapshot.archiveBytes.length, 0);
      snapshot = null;
      if (mounted) {
        _password.clear();
        _confirmation.clear();
      }
      _throwIfCancelled();

      _setPhase(
        _BackupExportPhase.choosing,
        message: 'Choose where to save the encrypted backup.',
        expectedBytes: encrypted.length,
        canCancel: false,
      );
      documentId = await _fileApi.createBackupDocument(
        totalBytes: encrypted.length,
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

      _setPhase(
        _BackupExportPhase.transferring,
        message: 'Saving encrypted backup…',
        canCancel: true,
        transferredBytes: 0,
      );
      var offset = 0;
      while (offset < encrypted.length) {
        _throwIfCancelled();
        final end = (offset + maxBackupDocumentChunkBytes) < encrypted.length
            ? offset + maxBackupDocumentChunkBytes
            : encrypted.length;
        final bytes = Uint8List.sublistView(encrypted, offset, end);
        offset = await _fileApi.writeBackupDocumentChunk(
          documentId: documentId,
          offset: offset,
          bytes: bytes,
        );
        _throwIfCancelled();
        _setPhase(
          _BackupExportPhase.transferring,
          message: 'Saving encrypted backup…',
          transferredBytes: offset,
        );
      }
      _throwIfCancelled();

      _setPhase(
        _BackupExportPhase.writing,
        message: 'Finishing save…',
        canCancel: false,
      );
      documentSaved = await _finishWithRetry(documentId);
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
      if (documentId != null && !documentSaved) {
        try {
          await _fileApi.cancelBackupDocument(documentId);
        } on Object {
          // The native bridge also expires abandoned encrypted staging files.
        }
      }
      snapshot?.archiveBytes.fillRange(0, snapshot.archiveBytes.length, 0);
      encrypted?.fillRange(0, encrypted.length, 0);
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
    _BackupExportPhase.encrypting => 'Encrypting backup on this device…',
    _BackupExportPhase.transferring => 'Saving encrypted backup…',
    _BackupExportPhase.writing => 'Finishing save…',
    _BackupExportPhase.cancelling => 'Cancelling backup…',
    _ => _message ?? '',
  };
}
