import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'lifecycle_api.dart';

Future<bool?> confirmJobDeletion(
  BuildContext context,
  LifecycleApi api,
  int version,
  String jobId,
  String name,
) => showDialog<bool>(
  context: context,
  barrierDismissible: false,
  builder: (dialog) => AlertDialog(
    scrollable: true,
    title: Text(
      AppMessage.confirmDeleteJob.format(dialog, {'job': name}),
      style: TextStyle(color: Theme.of(dialog).colorScheme.error),
    ),
    content: Text(AppMessage.deleteJobWarning.of(dialog)),
    actions: [
      TextButton(
        onPressed: () => Navigator.maybePop(dialog),
        child: Text(AppMessage.keepJob.of(dialog)),
      ),
      Theme(
        data: Theme.of(dialog).copyWith(
          colorScheme: Theme.of(dialog).colorScheme.copyWith(
            primary: Theme.of(dialog).colorScheme.error,
            onPrimary: Theme.of(dialog).colorScheme.onError,
          ),
        ),
        child: BusinessCommand(
          label: AppMessage.deletePermanently.of(dialog),
          create: () {
            final id = newBusinessId();
            return () => api.delete(id, version, jobId);
          },
          onCommitted: () => Navigator.pop(dialog, true),
        ),
      ),
    ],
  ),
);
