import 'package:flutter/material.dart';

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
    title: Text(
      'Permanently delete $name?',
      style: TextStyle(color: Theme.of(dialog).colorScheme.error),
    ),
    content: const Text(
      'This cannot be undone. Work history, GPS and the board will be deleted. Issued invoices and billing history are kept, along with reusable task templates and customers. Copies you already exported remain outside Clockrove.',
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.maybePop(dialog),
        child: const Text('Keep job'),
      ),
      Theme(
        data: Theme.of(dialog).copyWith(
          colorScheme: Theme.of(dialog).colorScheme.copyWith(
            primary: Theme.of(dialog).colorScheme.error,
            onPrimary: Theme.of(dialog).colorScheme.onError,
          ),
        ),
        child: BusinessCommand(
          label: 'Delete permanently',
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
