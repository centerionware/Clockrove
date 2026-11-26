import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../help/help_screen.dart';
import '../l10n/app_localizations.dart';
import 'backup_crypto_v2.dart';
import 'backup_file_api.dart';
import 'backup_import_api.dart';
import 'backup_restore_error_details.dart';
import 'backup_restore_importer.dart';
import 'backup_restore_preview.dart';

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
  AppMessage? _error;
  String? _errorCode;
  String? _errorDetail;
  AppMessage? _message;
  AppMessage? _passwordError;
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
      _errorCode = null;
      _errorDetail = null;
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
              ? AppMessage.restoreFileCancelled
              : AppMessage.restoreFileCancelledWithPreview;
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
        _message = AppMessage.restoreFileSelected;
      });
    } on Object catch (error) {
      if (selected != null && !identical(selected, _document)) {
        try {
          await selected.close();
        } on Object {
          // The input stage expires and is removed by native lifecycle cleanup.
        }
      }
      if (mounted) {
        setState(() {
          _error = _safeError(error);
          _errorCode = backupRestoreFailureCode(error);
          _errorDetail = backupRestoreFailureDetail(error);
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  bool _validatePassword() {
    final byteLength = utf8.encode(_password.text).length;
    setState(() {
      _passwordError = byteLength < 12
          ? AppMessage.restorePasswordShort
          : byteLength > 1024
          ? AppMessage.restorePasswordLong
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
      _errorCode = null;
      _errorDetail = null;
      _message = AppMessage.restoreAuthenticating;
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
          _message = AppMessage.restoreAuthenticated;
        });
      } else {
        await restore.close();
      }
    } on Object catch (error) {
      if (mounted) {
        setState(() {
          _error = _safeError(error);
          _errorCode = backupRestoreFailureCode(error);
          _errorDetail = backupRestoreFailureDetail(error);
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
        _message = AppMessage.restoreCancelling;
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
      _errorCode = null;
      _errorDetail = null;
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
      if (mounted) {
        setState(() {
          _error = _safeError(error);
          _errorCode = backupRestoreFailureCode(error);
          _errorDetail = backupRestoreFailureDetail(error);
        });
      }
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
      _errorCode = null;
      _errorDetail = null;
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
      if (mounted) {
        setState(() {
          _error = _safeError(error);
          _errorCode = backupRestoreFailureCode(error);
          _errorDetail = backupRestoreFailureDetail(error);
        });
      }
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
        scrollable: true,
        title: Text(AppMessage.applyBackupQuestion.of(dialogContext)),
        content: Text(
          [
            AppMessage.restoreMergeTransaction.of(dialogContext),
            if (preview.openSessionCount > 0)
              AppMessage.restoreOpenSessionsOmitted.of(dialogContext),
            AppMessage.restoreDeviceSettingsExcluded.of(dialogContext),
          ].join(' '),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(AppMessage.cancel.of(dialogContext)),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(AppMessage.applyReviewedMerge.of(dialogContext)),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() {
      _busy = true;
      _error = null;
      _errorCode = null;
      _errorDetail = null;
      _message = AppMessage.restoreApplying;
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
            _error = AppMessage.restorePreviewStale;
            _errorCode = null;
            _errorDetail = null;
            _message = AppMessage.restoreReviewAgain;
          } else {
            _error = _safeError(error);
            _errorCode = backupRestoreFailureCode(error);
            _errorDetail = backupRestoreFailureDetail(error);
            _message = AppMessage.restoreMergeUnconfirmed;
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
      _errorCode = null;
      _errorDetail = null;
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
        _message = AppMessage.restorePreviewDiscarded;
        _pageCursors
          ..clear()
          ..add(null);
        _pageIndex = 0;
        _furthestReviewedPage = -1;
      });
    } on Object catch (error) {
      if (mounted) {
        setState(() {
          _error = _safeError(error);
          _errorCode = backupRestoreFailureCode(error);
          _errorDetail = backupRestoreFailureDetail(error);
        });
      }
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
        appBar: AppBar(
          title: Text(AppMessage.restoreBackup.of(context)),
          actions: [HelpButton(topic: HelpTopic.backups, enabled: !_busy)],
        ),
        body: CustomScrollView(
          slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
              sliver: SliverList.list(
                children: [
                  Text(AppMessage.restoreChooseAndProtect.of(context)),
                  const SizedBox(height: 16),
                  OutlinedButton.icon(
                    key: const ValueKey('restore-choose-backup'),
                    onPressed: _busy ? null : _chooseBackup,
                    icon: const Icon(Icons.folder_open),
                    label: Text(
                      restore != null
                          ? AppMessage.chooseAnotherBackup.of(context)
                          : _document == null
                          ? AppMessage.chooseEncryptedBackup.of(context)
                          : AppMessage.chooseDifferentBackup.of(context),
                    ),
                  ),
                  if (_document != null) ...[
                    const SizedBox(height: 8),
                    Text(
                      AppMessage.selectedEncryptedFile.format(context, {
                        'size': (_document!.totalBytes / (1024 * 1024))
                            .toStringAsFixed(1),
                      }),
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
                        labelText: AppMessage.backupPassword.of(context),
                        errorText: _passwordError?.of(context),
                        suffixIcon: IconButton(
                          tooltip: _passwordVisible
                              ? AppMessage.hidePassword.of(context)
                              : AppMessage.showPassword.of(context),
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
                      label: Text(
                        AppMessage.authenticateAndPreview.of(context),
                      ),
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
                        label: Text(AppMessage.cancelPreview.of(context)),
                      ),
                  ],
                  if (_message != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      _message!.of(context),
                      key: const ValueKey('restore-message'),
                    ),
                  ],
                  if (_error case final error?) ...[
                    const SizedBox(height: 12),
                    Text(
                      error.of(context),
                      key: const ValueKey('restore-error'),
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                    if (_errorCode case final code?)
                      BackupRestoreErrorDetails(
                        code: code,
                        detail: _errorDetail,
                      ),
                  ],
                  if (preview != null) ...[
                    const SizedBox(height: 20),
                    BackupRestorePreviewSummary(preview: preview),
                    if (preview.openSessionCount > 0) ...[
                      const SizedBox(height: 12),
                      _NoticeCard(
                        icon: Icons.timer_off_outlined,
                        text: AppMessage.restoreOpenSessionsOmitted.of(context),
                      ),
                    ],
                    if (preview.localOpenSessionCount > 0) ...[
                      const SizedBox(height: 12),
                      _NoticeCard(
                        icon: Icons.pause_circle_outline,
                        isError: true,
                        text: AppMessage.restoreLocalTimerBlocking.of(context),
                      ),
                    ],
                    if (_previewNeedsRefresh) ...[
                      const SizedBox(height: 12),
                      _NoticeCard(
                        icon: Icons.refresh,
                        isError: true,
                        text: AppMessage.restorePreviewAlreadyStale.of(context),
                      ),
                    ],
                    if (_page != null && _page!.conflicts.isNotEmpty) ...[
                      const SizedBox(height: 20),
                      Text(
                        AppMessage.restoreConflictPage.format(context, {
                          'page': '${_pageIndex + 1}',
                        }),
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 4),
                      Text(AppMessage.restoreReviewChoices.of(context)),
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
                      _NoticeCard(
                        icon: Icons.fact_check_outlined,
                        text: AppMessage.restoreAllConflictsReviewed.of(
                          context,
                        ),
                      ),
                    if (_page != null && !_page!.done)
                      FilledButton.tonalIcon(
                        key: const ValueKey('restore-next-conflict-page'),
                        onPressed: _busy || !_pageChoicesResolved
                            ? null
                            : _nextConflictPage,
                        icon: const Icon(Icons.navigate_next),
                        label: Text(
                          AppMessage.restoreNextConflictPage.of(context),
                        ),
                      ),
                    if (_pageIndex > 0)
                      TextButton.icon(
                        onPressed: _busy
                            ? null
                            : () => _loadConflictPage(_pageIndex - 1),
                        icon: const Icon(Icons.navigate_before),
                        label: Text(
                          AppMessage.restorePreviousConflictPage.of(context),
                        ),
                      ),
                    if (restore.preview.localOpenSessionCount == 0 &&
                        _page?.done == true)
                      FilledButton.icon(
                        key: const ValueKey('restore-apply'),
                        onPressed: _canApply ? _apply : null,
                        icon: const Icon(Icons.merge_type),
                        label: Text(AppMessage.applyReviewedMerge.of(context)),
                      ),
                    if (restore.preview.localOpenSessionCount > 0 ||
                        (_page != null && _page!.done && !_pageChoicesResolved))
                      Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: Text(
                          AppMessage.restoreResolveBeforeApply.of(context),
                        ),
                      ),
                    TextButton.icon(
                      key: const ValueKey('restore-discard-preview'),
                      onPressed: _busy ? null : _discardPreview,
                      icon: const Icon(Icons.delete_outline),
                      label: Text(AppMessage.discardPreview.of(context)),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
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
              _conflictTitle(context, conflict),
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 4),
            if (conflict.localSummary.isNotEmpty ||
                conflict.backupSummary.isNotEmpty) ...[
              const SizedBox(height: 12),
              _SummaryPair(
                local: conflict.localSummary,
                backup: conflict.backupSummary,
              ),
            ],
            if (conflict.allowedChoices.isEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(AppMessage.restoreUnsupportedConflict.of(context)),
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
                        title: Text(_choiceLabel(context, choice)),
                      ),
                  ],
                ),
              ),
              if (!conflict.resolved)
                Text(
                  AppMessage.restoreChooseItem.of(context),
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
      _SummaryColumn(title: AppMessage.restoreOnDevice, values: local),
      const SizedBox(height: 8),
      _SummaryColumn(title: AppMessage.restoreInBackup, values: backup),
    ],
  );
}

final class _SummaryColumn extends StatelessWidget {
  const _SummaryColumn({required this.title, required this.values});

  final AppMessage title;
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
          Text(
            title.of(context),
            style: Theme.of(context).textTheme.labelLarge,
          ),
          for (final entry in values.entries)
            Text(
              '${_summaryFieldLabel(context, entry.key)}: ${_summaryValueText(context, entry.key, entry.value)}',
            ),
        ],
      ),
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

String _choiceLabel(BuildContext context, String choice) {
  final message = switch (choice) {
    'KEEP_LOCAL' => AppMessage.restoreKeepLocal,
    'MERGE_KEEP_LOCAL_CONTENT' => AppMessage.restoreMergeLocal,
    'MERGE_USE_BACKUP_CONTENT' => AppMessage.restoreMergeBackup,
    'MERGE_IDENTICAL_CONTENT' => AppMessage.restoreMergeIdentical,
    'OMIT_BACKUP_RECORD' => AppMessage.restoreOmitRecord,
    'OMIT_OPEN_SESSION' => AppMessage.restoreOmitOpenSession,
    'OMIT_BACKUP_SESSION' => AppMessage.restoreOmitBackupSession,
    'PRESERVE_DELETION' => AppMessage.restorePreserveGpsDeletion,
    'PRESERVE_LOCAL_DELETION' => AppMessage.restorePreserveLocalDeletion,
    'APPLY_BACKUP_DELETION' => AppMessage.restoreApplyBackupDeletion,
    'RESTORE_BACKUP_CONTENT' => AppMessage.restoreUseBackupContent,
    _ => null,
  };
  return message?.of(context) ??
      AppMessage.restoreUnrecognizedChoice.format(context, {'code': choice});
}

String _conflictTitle(BuildContext context, BackupImportConflict conflict) {
  final message = switch (conflict.kind) {
    'OPEN_SESSION' => AppMessage.restoreOpenSessionConflict,
    'SESSION_OVERLAP' => AppMessage.restoreOverlappingInterval,
    'GPS_DELETION_COVERAGE' => AppMessage.restoreDeletedGps,
    'DEPENDENT_ON_CONFLICT' => AppMessage.restoreSkippedDependency,
    _ => null,
  };
  return message?.of(context) ?? AppMessage.restoreRecordConflict.of(context);
}

String _summaryFieldLabel(BuildContext context, String field) =>
    switch (field) {
      'identifier' => AppMessage.restoreSummaryFieldIdentifier.of(context),
      'id' => AppMessage.restoreSummaryFieldId.of(context),
      'name' => AppMessage.restoreSummaryFieldName.of(context),
      'job_name' => AppMessage.restoreSummaryFieldJobName.of(context),
      'customer_name' => AppMessage.restoreSummaryFieldCustomerName.of(context),
      'number' => AppMessage.restoreSummaryFieldNumber.of(context),
      'title' => AppMessage.restoreSummaryFieldTitle.of(context),
      'start_ms' => AppMessage.restoreSummaryFieldStartMs.of(context),
      'end_ms' => AppMessage.restoreSummaryFieldEndMs.of(context),
      'occurred_ms' => AppMessage.restoreSummaryFieldOccurredMs.of(context),
      'amount_minor' => AppMessage.restoreSummaryFieldAmountMinor.of(context),
      'currency' => AppMessage.restoreSummaryFieldCurrency.of(context),
      'kind' => AppMessage.restoreSummaryFieldKind.of(context),
      'category' => AppMessage.restoreSummaryFieldCategory.of(context),
      _ => AppMessage.restoreSummaryFieldIdentifier.of(context),
    };

String _summaryValueText(BuildContext context, String field, Object? value) {
  if (value == null) return '—';
  if (value is bool) {
    return (value ? AppMessage.yes : AppMessage.no).of(context);
  }
  if (value is num) {
    return MaterialLocalizations.of(context).formatDecimal(value.toInt());
  }
  if (value is String) {
    if (field == 'identifier') {
      final message = switch (value) {
        'ON_DEVICE' => AppMessage.restoreOnDevice,
        'LOCAL_DELETION' => AppMessage.restorePreserveLocalDeletion,
        'BACKUP_RECORD' => AppMessage.restoreInBackup,
        'GPS_DELETION' ||
        'GPS_LOCAL_POINTS' ||
        'GPS_BACKUP_POINTS' => AppMessage.restoreDeletedGps,
        'GPS_DELETION_UNAVAILABLE' => AppMessage.actualCostLinkUnavailable,
        _ => null,
      };
      if (message != null) return message.of(context);
    }
    if (field == 'kind') {
      final message = switch (value) {
        'ITEM' => AppMessage.quoteChargeSupplies,
        'HOURS' => AppMessage.quoteChargeLabor,
        'HOURLY' => AppMessage.quoteScopeHourlyMode,
        'TASK' => AppMessage.billingPerTask,
        _ => null,
      };
      if (message != null) return message.of(context);
      return AppMessage.restoreSummaryFieldKind.of(context);
    }
    if (field == 'category') {
      final message = switch (value) {
        'MATERIALS' => AppMessage.actualCostMaterials,
        'SUBCONTRACT_LABOR' => AppMessage.actualCostSubcontractLabor,
        'OTHER_DIRECT' => AppMessage.actualCostOtherDirect,
        'OVERHEAD' => AppMessage.actualCostOverhead,
        _ => null,
      };
      if (message != null) return message.of(context);
      return AppMessage.restoreSummaryFieldCategory.of(context);
    }
    return value;
  }
  return '—';
}

AppMessage _safeError(Object error) => switch (error) {
  BackupCryptoV2CancelledException() => AppMessage.restoreCancelled,
  BackupUnlockException() => AppMessage.restoreUnlockFailed,
  BackupRestoreImportCleanupException() ||
  BackupCryptoV2CleanupException() => AppMessage.restoreCleanupFailed,
  _ => AppMessage.restoreFailed,
};

bool _requiresFreshPreview(Object error) =>
    error is PlatformException &&
    const {'PREVIEW_STALE', 'LOCAL_TIMER_RUNNING'}.contains(error.code);
