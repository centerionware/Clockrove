import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';

/// Stable restore code plus the first native validation detail, kept copyable
/// so a failed restore can be reported without reproducing the whole archive.
final class BackupRestoreErrorDetails extends StatelessWidget {
  const BackupRestoreErrorDetails({super.key, required this.code, this.detail});

  final String code;
  final String? detail;

  @override
  Widget build(BuildContext context) {
    final detail = this.detail;
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SelectableText(
            AppMessage.restoreErrorCode.format(context, {'code': code}),
            key: const ValueKey('restore-error-code'),
            style: Theme.of(context).textTheme.bodySmall,
          ),
          if (detail != null) ...[
            const SizedBox(height: 4),
            SelectableText(
              AppMessage.restoreErrorDetails.format(context, {
                'detail': detail,
              }),
              key: const ValueKey('restore-error-details'),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ],
      ),
    );
  }
}
