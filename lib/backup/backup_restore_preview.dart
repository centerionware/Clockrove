import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import 'backup_import_api.dart';

final class BackupRestorePreviewSummary extends StatelessWidget {
  const BackupRestorePreviewSummary({super.key, required this.preview});

  final BackupImportPreview preview;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            AppMessage.restorePreview.of(context),
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 12),
          _RestoreCountLine(
            label: AppMessage.restoreRecordCount,
            count: preview.recordCount,
          ),
          _RestoreCountLine(
            label: AppMessage.restoreAdditions,
            count: preview.addedCount,
          ),
          _RestoreCountLine(
            label: AppMessage.restoreChanges,
            count: preview.changedCount,
          ),
          _RestoreCountLine(
            label: AppMessage.restoreDeletionMarkers,
            count: preview.deletedCount,
          ),
          _RestoreCountLine(
            label: AppMessage.restoreUnchanged,
            count: preview.unchangedCount,
          ),
          _RestoreCountLine(
            label: AppMessage.restoreConflicts,
            count: preview.conflictCount,
          ),
          _RestoreCountLine(
            label: AppMessage.restoreReferenceConflicts,
            count: preview.unresolvedReferenceCount,
          ),
          _RestoreCountLine(
            label: AppMessage.restoreOverlapConflicts,
            count: preview.timeOverlapCount,
          ),
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(AppMessage.restoreDeletionCountNote.of(context)),
          ),
          _RestoreCountLine(
            label: AppMessage.restoreMediaFiles,
            count: preview.mediaCount,
          ),
          _RestoreCountLine(
            label: AppMessage.restoreMediaData,
            value: AppMessage.restoreMebibytes.format(context, {
              'size': (preview.mediaBytes / (1024 * 1024)).toStringAsFixed(1),
            }),
          ),
          const SizedBox(height: 12),
          Text(AppMessage.restoreDeviceSettingsExcluded.of(context)),
        ],
      ),
    ),
  );
}

final class _RestoreCountLine extends StatelessWidget {
  const _RestoreCountLine({required this.label, this.count, this.value});

  final AppMessage label;
  final int? count;
  final String? value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(
      children: [
        Expanded(child: Text(label.of(context))),
        Text(
          value ?? MaterialLocalizations.of(context).formatDecimal(count ?? 0),
        ),
      ],
    ),
  );
}
