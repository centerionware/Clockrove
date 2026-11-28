import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import 'backup_restore_password_field.dart';

class BackupRestoreSourceControls extends StatelessWidget {
  const BackupRestoreSourceControls({
    super.key,
    required this.busy,
    required this.hasDocument,
    required this.hasRestore,
    required this.plainArchive,
    required this.totalBytes,
    required this.passwordController,
    required this.passwordVisible,
    required this.passwordError,
    required this.onTogglePassword,
    required this.onChoose,
    required this.onPreview,
  });

  final bool busy;
  final bool hasDocument;
  final bool hasRestore;
  final bool plainArchive;
  final int? totalBytes;
  final TextEditingController passwordController;
  final bool passwordVisible;
  final AppMessage? passwordError;
  final VoidCallback onTogglePassword;
  final VoidCallback onChoose;
  final VoidCallback onPreview;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text(AppMessage.restoreChooseAndProtect.of(context)),
      const SizedBox(height: 16),
      OutlinedButton.icon(
        key: const ValueKey('restore-choose-backup'),
        onPressed: busy ? null : onChoose,
        icon: const Icon(Icons.folder_open),
        label: Text(
          hasRestore
              ? AppMessage.chooseAnotherBackup.of(context)
              : !hasDocument
              ? AppMessage.chooseBackupFile.of(context)
              : AppMessage.chooseDifferentBackup.of(context),
        ),
      ),
      if (hasDocument) ...[
        const SizedBox(height: 8),
        Text(
          AppMessage.selectedBackupFile.format(context, {
            'size': ((totalBytes ?? 0) / (1024 * 1024)).toStringAsFixed(1),
          }),
          key: const ValueKey('restore-selected-file'),
        ),
      ],
      if (!hasRestore) ...[
        const SizedBox(height: 12),
        if (plainArchive)
          Text(
            AppMessage.restorePlainNoPassword.of(context),
            key: const ValueKey('restore-plain-notice'),
          )
        else
          BackupRestorePasswordField(
            controller: passwordController,
            enabled: !busy && hasDocument,
            visible: passwordVisible,
            onToggle: onTogglePassword,
            errorText: passwordError,
          ),
        const SizedBox(height: 12),
        FilledButton.icon(
          key: const ValueKey('restore-preview'),
          onPressed: busy || !hasDocument ? null : onPreview,
          icon: const Icon(Icons.preview),
          label: Text(
            plainArchive
                ? AppMessage.previewPlainBackup.of(context)
                : AppMessage.authenticateAndPreview.of(context),
          ),
        ),
      ],
    ],
  );
}
