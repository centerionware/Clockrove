import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'backup_crypto_v2.dart';
import 'backup_file_api.dart';
import 'backup_import_api.dart';
import 'backup_restore_importer.dart';

typedef BackupRestorePreviewRunner =
    Future<BackupRestoreImportResult> Function({
      required BackupEncryptedSource encryptedEnvelope,
      required String password,
      BackupCryptoCancellationToken? cancellationToken,
    });

/// User-directed encrypted restore with bounded native conflict pages.
final class BackupRestoreScreen extends StatefulWidget {
  const BackupRestoreScreen({super.key, this.fileApi, this.previewBackup});

  final BackupFileApi? fileApi;
  final BackupRestorePreviewRunner? previewBackup;

  @override
  State<BackupRestoreScreen> createState() => _BackupRestoreScreenState();
}

final class _BackupRestoreScreenState extends State<BackupRestoreScreen> {
  final _password = TextEditingController();
  late final BackupFileApi _fileApi = widget.fileApi ?? BackupFileApi();
  late final BackupRestorePreviewRunner _previewBackup =
      widget.previewBackup ?? BackupRestoreImporter().decryptAndPreview;

  BackupEncryptedDocument? _document;
  BackupRestoreImportResult? _restore;
  BackupImportConflictPage? _page;
  final List<String?> _pageCursors = [null];
  int _pageIndex = 0;
  int _furthestReviewedPage = -1;
  BackupCryptoCancellationToken? _cancellationToken;
  String? _error;
  String? _message;
  String? _passwordError;
  bool _busy = false;
  bool _passwordVisible = false;
  bool _applied = false;
  bool _previewNeedsRefresh = false;

  @override
  void dispose() {
    _cancellationToken?.cancel();
    _password.clear();
    _password.dispose();
    final document = _document;
    if (document != null) unawaited(_closeAfterDispose(document.close));
    final restore = _restore;
    if (restore != null && !_applied) {
      unawaited(_closeAfterDispose(restore.close));
    }
    super.dispose();
  }

  Future<void> _closeAfterDispose(Future<Object?> Function() close) async {
    try {
      await close();
    } on Object {
      // The native stage expires and is removed by lifecycle cleanup.
    }
  }

  bool get _pageChoicesResolved =>
      _page != null && _page!.conflicts.every((conflict) => conflict.resolved);

  bool get _allConflictsReviewed =>
      _page?.done == true &&
      _pageChoicesResolved &&
      _furthestReviewedPage >= _pageIndex;

  bool get _canApply =>
      !_busy &&
      !_previewNeedsRefresh &&
      _restore != null &&
      _allConflictsReviewed &&
      // applyReady describes the initial preview, before these choices exist.
      // Native apply remains authoritative for version and transaction checks.
      _restore!.preview.localOpenSessionCount == 0;

  Future<void> _chooseBackup() async {
    if (_busy) return;
    final previousRestore = _restore;
    final previousDocument = _document;
    setState(() {
      _busy = true;
      _error = null;
      _message = null;
    });
    BackupEncryptedDocument? selected;
    try {
      if (previousDocument != null) {
        await previousDocument.close();
        if (mounted) setState(() => _document = null);
      }
      selected = await _fileApi.openBackupDocumentForImport();
      if (!mounted) {
        if (selected != null) await selected.close();
        return;
      }
      if (selected == null) {
        setState(() {
          _message = previousRestore == null
              ? 'File selection cancelled.'
              : 'File selection cancelled. The existing preview is still open.';
        });
        return;
      }
      if (previousRestore != null) {
        try {
          await previousRestore.close();
        } on Object {
          await selected.close();
          rethrow;
        }
      }
      if (!mounted) {
        await selected.close();
        return;
      }
      setState(() {
        _document = selected;
        _password.clear();
        _passwordError = null;
        _restore = null;
        _page = null;
        _previewNeedsRefresh = false;
        _pageCursors
          ..clear()
          ..add(null);
        _pageIndex = 0;
        _furthestReviewedPage = -1;
        _message = 'Encrypted backup selected. Enter its password to preview.';
      });
    } on Object catch (error) {
      if (selected != null && !identical(selected, _document)) {
        try {
          await selected.close();
        } on Object {
          // The input stage expires and is removed by native lifecycle cleanup.
        }
      }
      if (mounted) setState(() => _error = _safeError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  bool _validatePassword() {
    final byteLength = utf8.encode(_password.text).length;
    setState(() {
      _passwordError = byteLength < 12
          ? 'Enter the backup password (at least 12 UTF-8 bytes).'
          : byteLength > 1024
          ? 'The password is longer than the supported limit.'
          : null;
    });
    return _passwordError == null;
  }

  Future<void> _createPreview() async {
    final document = _document;
    if (_busy || document == null || !_validatePassword()) return;
    final token = BackupCryptoCancellationToken();
    _cancellationToken = token;
    setState(() {
      _busy = true;
      _error = null;
      _message = 'Authenticating and checking the encrypted backup…';
    });
    BackupRestoreImportResult? restore;
    try {
      restore = await _previewBackup(
        encryptedEnvelope: document,
        password: _password.text,
        cancellationToken: token,
      );
      if (mounted) {
        setState(() {
          _restore = restore;
          _previewNeedsRefresh = false;
          _page = null;
          _pageCursors
            ..clear()
            ..add(null);
          _pageIndex = 0;
          _furthestReviewedPage = -1;
          _message = 'Backup authenticated. Review its contents and choices.';
        });
      } else {
        await restore.close();
      }
    } on Object catch (error) {
      if (mounted) {
        setState(() {
          _error = _safeError(error);
          _message = null;
        });
      }
    } finally {
      if (restore != null || !mounted) {
        try {
          await document.close();
        } on Object {
          // Keep the handle so dispose or a later picker action can retry cleanup.
        }
      }
      if (identical(_document, document) && document.isClosed && mounted) {
        setState(() => _document = null);
      }
      _cancellationToken = null;
      if (mounted) {
        _password.clear();
        setState(() => _busy = false);
      }
    }
    if (restore != null && mounted && identical(_restore, restore)) {
      await _loadConflictPage(0);
    }
  }

  void _cancelPreview() {
    _cancellationToken?.cancel();
    if (mounted) {
      setState(() {
        _message = 'Cancelling preview. No local records have been changed.';
      });
    }
  }

  Future<void> _loadConflictPage(int index) async {
    final restore = _restore;
    if (_busy || restore == null || index < 0 || index >= _pageCursors.length) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final page = await restore.api.readConflicts(
        importId: restore.preview.importId,
        previewToken: restore.preview.previewToken,
        cursor: _pageCursors[index],
      );
      if (!mounted || !identical(_restore, restore)) return;
      setState(() {
        _page = page;
        _pageIndex = index;
      });
      _recordPageReview(index, page);
    } on Object catch (error) {
      if (mounted) setState(() => _error = _safeError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _recordPageReview(int index, BackupImportConflictPage page) {
    final complete = page.conflicts.every((conflict) => conflict.resolved);
    if (complete && index == _furthestReviewedPage + 1) {
      _furthestReviewedPage = index;
    }
    if (complete && index == 0 && _furthestReviewedPage < 0) {
      _furthestReviewedPage = 0;
    }
  }

  Future<void> _resolve(BackupImportConflict conflict, String choice) async {
    final restore = _restore;
    final page = _page;
    if (_busy || restore == null || page == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await restore.api.resolveConflict(
        importId: restore.preview.importId,
        previewToken: restore.preview.previewToken,
        expectedLocalStateVersion: restore.preview.localStateVersion,
        conflictId: conflict.conflictId,
        choice: choice,
      );
      if (mounted && identical(_restore, restore)) {
        final locallyUpdated = BackupImportConflictPage(
          importId: page.importId,
          previewToken: page.previewToken,
          nextCursor: page.nextCursor,
          done: page.done,
          conflicts: [
            for (final item in page.conflicts)
              if (item.conflictId == conflict.conflictId)
                _resolvedConflict(item, choice)
              else
                item,
          ],
        );
        setState(() {
          _page = locallyUpdated;
        });
        _recordPageReview(_pageIndex, locallyUpdated);
      }
      final updated = await restore.api.readConflicts(
        importId: restore.preview.importId,
        previewToken: restore.preview.previewToken,
        cursor: _pageCursors[_pageIndex],
      );
      if (mounted && identical(_restore, restore)) {
        setState(() => _page = updated);
        _recordPageReview(_pageIndex, updated);
      }
    } on Object catch (error) {
      if (mounted) setState(() => _error = _safeError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _nextConflictPage() async {
    final page = _page;
    if (_busy || page == null || page.done || !_pageChoicesResolved) return;
    final cursor = page.nextCursor;
    if (cursor == null) return;
    final nextIndex = _pageIndex + 1;
    if (_pageCursors.length == nextIndex) _pageCursors.add(cursor);
    await _loadConflictPage(nextIndex);
  }

  Future<void> _apply() async {
    final restore = _restore;
    if (!_canApply || restore == null) return;
    final preview = restore.preview;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Apply this backup?'),
        content: Text(
          'Clockrove will merge the reviewed records in one database transaction. '
          'If validation fails, the transaction is rolled back. '
          '${preview.openSessionCount > 0 ? 'Open/provisional sessions in the backup will be omitted; no end time will be invented and no timer will start. ' : ''}'
          'Automation stays off, and map credentials and device registrations are not imported.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Apply reviewed merge'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() {
      _busy = true;
      _error = null;
      _message = 'Applying the reviewed merge…';
    });
    try {
      await restore.api.apply(
        importId: preview.importId,
        previewToken: preview.previewToken,
        expectedLocalStateVersion: preview.localStateVersion,
      );
      _applied = true;
      try {
        await restore.close();
      } on Object {
        // The native transaction has committed; native stage cleanup is retried.
      }
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on Object catch (error) {
      if (mounted) {
        setState(() {
          if (_requiresFreshPreview(error)) {
            _previewNeedsRefresh = true;
            _error = 'No records were restored because this preview is no longer current.';
            _message = 'Discard this preview and select the backup again to review current data before restoring.';
          } else {
            _error = _safeError(error);
            _message = 'The merge could not be confirmed. Check current records before retrying.';
          }
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _discardPreview() async {
    final restore = _restore;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      if (restore != null) await restore.close();
      final document = _document;
      if (document != null) await document.close();
      if (!mounted) return;
      setState(() {
        _restore = null;
        _document = null;
        _page = null;
        _previewNeedsRefresh = false;
        _password.clear();
        _message = 'Preview discarded. No local records were changed.';
        _pageCursors
          ..clear()
          ..add(null);
        _pageIndex = 0;
        _furthestReviewedPage = -1;
      });
    } on Object catch (error) {
      if (mounted) setState(() => _error = _safeError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final restore = _restore;
    final preview = restore?.preview;
    return PopScope(
      canPop: !_busy,
      child: Scaffold(
        appBar: AppBar(title: const Text('Restore encrypted backup')),
        body: CustomScrollView(
          slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
              sliver: SliverList.list(
                children: [
                  const Text(
                    'Choose a Clockrove backup and enter its password. The file is read into private encrypted staging; the password is never saved. Clockrove cannot recover it.',
                  ),
                  const SizedBox(height: 16),
                  OutlinedButton.icon(
                    key: const ValueKey('restore-choose-backup'),
                    onPressed: _busy ? null : _chooseBackup,
                    icon: const Icon(Icons.folder_open),
                    label: Text(
                      restore != null
                          ? 'Choose another backup'
                          : _document == null
                          ? 'Choose encrypted backup'
                          : 'Choose a different backup',
                    ),
                  ),
                  if (_document != null) ...[
                    const SizedBox(height: 8),
                    Text(
                      'Selected encrypted file · ${(_document!.totalBytes / (1024 * 1024)).toStringAsFixed(1)} MiB',
                      key: const ValueKey('restore-selected-file'),
                    ),
                  ],
                  if (restore == null) ...[
                    const SizedBox(height: 12),
                    TextField(
                      key: const ValueKey('restore-password'),
                      controller: _password,
                      enabled: !_busy && _document != null,
                      obscureText: !_passwordVisible,
                      autocorrect: false,
                      enableSuggestions: false,
                      decoration: InputDecoration(
                        labelText: 'Backup password',
                        errorText: _passwordError,
                        suffixIcon: IconButton(
                          tooltip: _passwordVisible
                              ? 'Hide password'
                              : 'Show password',
                          onPressed: () => setState(
                            () => _passwordVisible = !_passwordVisible,
                          ),
                          icon: Icon(
                            _passwordVisible
                                ? Icons.visibility_off
                                : Icons.visibility,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      key: const ValueKey('restore-preview'),
                      onPressed: _busy || _document == null
                          ? null
                          : _createPreview,
                      icon: const Icon(Icons.preview),
                      label: const Text('Authenticate and preview'),
                    ),
                  ],
                  if (_busy) ...[
                    const SizedBox(height: 12),
                    const LinearProgressIndicator(),
                    if (_cancellationToken != null)
                      TextButton.icon(
                        key: const ValueKey('restore-cancel-preview'),
                        onPressed: _cancelPreview,
                        icon: const Icon(Icons.cancel_outlined),
                        label: const Text('Cancel preview'),
                      ),
                  ],
                  if (_message != null) ...[
                    const SizedBox(height: 12),
                    Text(_message!, key: const ValueKey('restore-message')),
                  ],
                  if (_error != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      _error!,
                      key: const ValueKey('restore-error'),
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ],
                  if (preview != null) ...[
                    const SizedBox(height: 20),
                    _previewSummary(preview),
                    if (preview.openSessionCount > 0) ...[
                      const SizedBox(height: 12),
                      const _NoticeCard(
                        icon: Icons.timer_off_outlined,
                        text: 'This backup contains open/provisional sessions. The available restore choice is to omit them. Restore will not clock them out, invent an end time, or start a timer.',
                      ),
                    ],
                    if (preview.localOpenSessionCount > 0) ...[
                      const SizedBox(height: 12),
                      const _NoticeCard(
                        icon: Icons.pause_circle_outline,
                        isError: true,
                        text: 'A timer is running on this device. Stop it, discard this preview, and create a fresh preview before restoring. The native transaction also rejects an open local timer.',
                      ),
                    ],
                    if (_previewNeedsRefresh) ...[
                      const SizedBox(height: 12),
                      const _NoticeCard(
                        icon: Icons.refresh,
                        isError: true,
                        text: 'This preview cannot be applied again. Discard it and select the backup to create a fresh preview of current data.',
                      ),
                    ],
                    if (_page != null && _page!.conflicts.isNotEmpty) ...[
                      const SizedBox(height: 20),
                      Text(
                        'Conflicts · page ${_pageIndex + 1}',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 4),
                      const Text(
                        'Review each proposed choice. You can revisit reviewed pages before applying.',
                      ),
                    ],
                  ],
                ],
              ),
            ),
            if (_page != null && _page!.conflicts.isNotEmpty)
              SliverPadding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                sliver: SliverList.builder(
                  itemCount: _page!.conflicts.length,
                  itemBuilder: (context, index) => _ConflictCard(
                    key: ValueKey(_page!.conflicts[index].conflictId),
                    conflict: _page!.conflicts[index],
                    enabled: !_busy,
                    onChoice: (choice) =>
                        _resolve(_page!.conflicts[index], choice),
                  ),
                ),
              ),
            if (restore != null)
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 32),
                sliver: SliverList.list(
                  children: [
                    if (_page != null && _page!.done && _allConflictsReviewed)
                      const _NoticeCard(
                        icon: Icons.fact_check_outlined,
                        text: 'All conflict pages have been reviewed. Applying sends the choices to the native repository, which checks the local version and commits atomically.',
                      ),
                    if (_page != null && !_page!.done)
                      FilledButton.tonalIcon(
                        key: const ValueKey('restore-next-conflict-page'),
                        onPressed: _busy || !_pageChoicesResolved
                            ? null
                            : _nextConflictPage,
                        icon: const Icon(Icons.navigate_next),
                        label: const Text('Continue to next conflict page'),
                      ),
                    if (_pageIndex > 0)
                      TextButton.icon(
                        onPressed: _busy
                            ? null
                            : () => _loadConflictPage(_pageIndex - 1),
                        icon: const Icon(Icons.navigate_before),
                        label: const Text('Previous conflict page'),
                      ),
                    if (restore.preview.localOpenSessionCount == 0 &&
                        _page?.done == true)
                      FilledButton.icon(
                        key: const ValueKey('restore-apply'),
                        onPressed: _canApply ? _apply : null,
                        icon: const Icon(Icons.merge_type),
                        label: const Text('Apply reviewed merge'),
                      ),
                    if (restore.preview.localOpenSessionCount > 0 ||
                        (_page != null && _page!.done && !_pageChoicesResolved))
                      const Padding(
                        padding: EdgeInsets.only(top: 8),
                        child: Text(
                          'Resolve every conflict and stop any running local timer to enable apply.',
                        ),
                      ),
                    TextButton.icon(
                      key: const ValueKey('restore-discard-preview'),
                      onPressed: _busy ? null : _discardPreview,
                      icon: const Icon(Icons.delete_outline),
                      label: const Text('Discard preview'),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _previewSummary(BackupImportPreview preview) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Restore preview',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 12),
          _CountLine(label: 'Records in backup', count: preview.recordCount),
          _CountLine(label: 'Proposed additions', count: preview.addedCount),
          _CountLine(
            label: 'Existing records changed',
            count: preview.changedCount,
          ),
          _CountLine(label: 'Deletion markers', count: preview.deletedCount),
          _CountLine(label: 'Unchanged records', count: preview.unchangedCount),
          _CountLine(
            label: 'Conflicts to review',
            count: preview.conflictCount,
          ),
          _CountLine(
            label: 'Reference conflicts in backup',
            count: preview.unresolvedReferenceCount,
          ),
          _CountLine(
            label: 'Time overlap conflicts in backup',
            count: preview.timeOverlapCount,
          ),
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: Text(
              'Deletion markers count backup tombstones, not every record they affect.',
            ),
          ),
          _CountLine(label: 'Media files', count: preview.mediaCount),
          _CountLine(
            label: 'Media data',
            value:
                '${(preview.mediaBytes / (1024 * 1024)).toStringAsFixed(1)} MiB',
          ),
          const SizedBox(height: 12),
          const Text(
            'Restore merges saved records. It does not start timers, enable geofences, or import map credentials and device registrations.',
          ),
        ],
      ),
    ),
  );
}

final class _ConflictCard extends StatelessWidget {
  const _ConflictCard({
    super.key,
    required this.conflict,
    required this.enabled,
    required this.onChoice,
  });

  final BackupImportConflict conflict;
  final bool enabled;
  final ValueChanged<String> onChoice;

  @override
  Widget build(BuildContext context) {
    final selected = conflict.selectedChoice;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _conflictTitle(conflict),
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 4),
            Text('${_humanize(conflict.table)} · ${_humanize(conflict.kind)}'),
            if (conflict.localSummary.isNotEmpty ||
                conflict.backupSummary.isNotEmpty) ...[
              const SizedBox(height: 12),
              _SummaryPair(
                local: conflict.localSummary,
                backup: conflict.backupSummary,
              ),
            ],
            if (conflict.allowedChoices.isEmpty)
              const Padding(
                padding: EdgeInsets.only(top: 12),
                child: Text(
                  'This app cannot resolve this conflict. Applying is blocked; discard this preview.',
                ),
              )
            else ...[
              const SizedBox(height: 8),
              RadioGroup<String>(
                groupValue: selected ?? '',
                onChanged: (choice) {
                  if (enabled && choice != null) onChoice(choice);
                },
                child: Column(
                  children: [
                    for (final choice in conflict.allowedChoices)
                      RadioListTile<String>(
                        contentPadding: EdgeInsets.zero,
                        value: choice,
                        enabled: enabled,
                        title: Text(_choiceLabel(choice)),
                      ),
                  ],
                ),
              ),
              if (!conflict.resolved)
                Text(
                  'Choose how to handle this item before continuing.',
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
            ],
          ],
        ),
      ),
    );
  }
}

final class _SummaryPair extends StatelessWidget {
  const _SummaryPair({required this.local, required this.backup});

  final Map<String, Object?> local;
  final Map<String, Object?> backup;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _SummaryColumn(title: 'On this device', values: local),
      const SizedBox(height: 8),
      _SummaryColumn(title: 'In backup', values: backup),
    ],
  );
}

final class _SummaryColumn extends StatelessWidget {
  const _SummaryColumn({required this.title, required this.values});

  final String title;
  final Map<String, Object?> values;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(8),
    ),
    child: Padding(
      padding: const EdgeInsets.all(10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.labelLarge),
          for (final entry in values.entries)
            Text('${_humanize(entry.key)}: ${_scalarText(entry.value)}'),
        ],
      ),
    ),
  );
}

final class _CountLine extends StatelessWidget {
  const _CountLine({required this.label, this.count, this.value});

  final String label;
  final int? count;
  final String? value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(
      children: [
        Expanded(child: Text(label)),
        Text(value ?? '${count ?? 0}'),
      ],
    ),
  );
}

BackupImportConflict _resolvedConflict(
  BackupImportConflict conflict,
  String choice,
) => BackupImportConflict(
  conflictId: conflict.conflictId,
  kind: conflict.kind,
  table: conflict.table,
  primaryKey: conflict.primaryKey,
  localIdentifier: conflict.localIdentifier,
  localSummary: conflict.localSummary,
  backupSummary: conflict.backupSummary,
  allowedChoices: conflict.allowedChoices,
  defaultChoice: conflict.defaultChoice,
  resolved: true,
  selectedChoice: choice,
);

final class _NoticeCard extends StatelessWidget {
  const _NoticeCard({
    required this.icon,
    required this.text,
    this.isError = false,
  });

  final IconData icon;
  final String text;
  final bool isError;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final color = isError ? colors.error : colors.primary;
    return Card(
      color: isError ? colors.errorContainer : colors.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: color),
            const SizedBox(width: 10),
            Expanded(child: Text(text)),
          ],
        ),
      ),
    );
  }
}

String _choiceLabel(String choice) => switch (choice) {
  'KEEP_LOCAL' => 'Keep the version on this device',
  'MERGE_KEEP_LOCAL_CONTENT' =>
    'Keep local content and preserve both revision parents',
  'MERGE_USE_BACKUP_CONTENT' =>
    'Use backup content and preserve both revision parents',
  'MERGE_IDENTICAL_CONTENT' =>
    'Keep identical content and merge revision history',
  'OMIT_BACKUP_RECORD' => 'Omit this backup record',
  'OMIT_OPEN_SESSION' => 'Omit this open session',
  'OMIT_BACKUP_SESSION' => 'Omit this backup session',
  'PRESERVE_DELETION' =>
    'Keep the local deletion; omit the covered backup point',
  'PRESERVE_LOCAL_DELETION' => 'Keep the local deletion',
  'APPLY_BACKUP_DELETION' => 'Apply the deletion recorded in the backup',
  'RESTORE_BACKUP_CONTENT' => 'Restore the content in this backup',
  _ => _humanize(choice),
};

String _conflictTitle(BackupImportConflict conflict) {
  if (conflict.kind == 'OPEN_SESSION') return 'Open session in backup';
  if (conflict.kind == 'SESSION_OVERLAP') return 'Overlapping work interval';
  if (conflict.kind == 'GPS_DELETION_COVERAGE') return 'Deleted GPS history';
  if (conflict.kind == 'DEPENDENT_ON_CONFLICT') {
    return 'Record depends on a skipped item';
  }
  return '${_humanize(conflict.table)} conflict';
}

String _humanize(String value) => value
    .replaceAll('_', ' ')
    .split(' ')
    .where((word) => word.isNotEmpty)
    .map((word) => '${word[0].toUpperCase()}${word.substring(1).toLowerCase()}')
    .join(' ');

String _scalarText(Object? value) {
  if (value == null) return '—';
  if (value is bool) return value ? 'Yes' : 'No';
  if (value is num || value is String) return '$value';
  return '—';
}

String _safeError(Object error) {
  if (error is BackupCryptoV2CancelledException) {
    return 'Preview cancelled. No local records were changed.';
  }
  if (error is FormatException) return error.message.toString();
  if (error is BackupRestoreImportCleanupException ||
      error is BackupCryptoV2CleanupException) {
    return 'Restore staging could not be cleaned up. Close Clockrove and reopen it before trying again.';
  }
  return 'The backup could not be processed. Check the selected file and try again.';
}

bool _requiresFreshPreview(Object error) =>
    error is PlatformException &&
    const {'PREVIEW_STALE', 'LOCAL_TIMER_RUNNING'}.contains(error.code);
