import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'job_screen.dart';
import 'lifecycle_api.dart';

class ArchiveScreen extends ConsumerWidget {
  const ArchiveScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(
      title: const Text('Archived jobs'),
      actions: [
        IconButton(
          tooltip: 'Refresh archive eligibility',
          onPressed: () => ref.invalidate(archiveProvider),
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ref
        .watch(archiveProvider)
        .when(
          skipLoadingOnReload: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: TextButton(
              onPressed: () => ref.invalidate(archiveProvider),
              child: const Text('Retry archived jobs'),
            ),
          ),
          data: (data) => ListView(
            padding: const EdgeInsets.all(20),
            children: [
              const Padding(
                padding: EdgeInsets.only(bottom: 20),
                child: Text(
                  'Archived jobs keep their history. Restore a job to clock in and use its automation again. Permanent deletion becomes available after 30 continuous days archived.',
                ),
              ),
              if (data.jobs.isEmpty)
                const Center(child: Text('No archived jobs.')),
              for (final job in data.jobs)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          leading: const Icon(Icons.archive_outlined),
                          title: Text(job.name),
                          subtitle: Text(
                            job.canDelete ? 'Eligible for permanent deletion' : 'History retained · deletion is not yet available',
                          ),
                          onTap: () => Navigator.push(
                            context,
                            MaterialPageRoute<void>(
                              builder: (_) =>
                                  JobScreen(jobId: job.id, jobName: job.name),
                            ),
                          ),
                        ),
                        BusinessCommand(
                          label: 'Restore job',
                          create: () {
                            final id = newBusinessId();
                            final version = data.version;
                            return () => ref
                                .read(lifecycleApiProvider)
                                .restore(id, version, job.id);
                          },
                          onCommitted: () => ref.invalidate(archiveProvider),
                        ),
                        if (job.canDelete)
                          TextButton.icon(
                            style: TextButton.styleFrom(
                              foregroundColor: Theme.of(context)
                                  .colorScheme
                                  .error,
                            ),
                            icon: const Icon(Icons.delete_forever_outlined),
                            label: const Text('Permanently delete job'),
                            onPressed: () => showDialog<void>(
                              context: context,
                              barrierDismissible: false,
                              builder: (dialog) => AlertDialog(
                                title: Text(
                                  'Permanently delete ${job.name}?',
                                  style: TextStyle(
                                    color: Theme.of(context).colorScheme.error,
                                  ),
                                ),
                                content: const Text(
                                  'This cannot be undone. Work history, GPS, the board and in-app invoices will be deleted. Independent reusable task templates and customers are kept. Copies you already exported remain outside Clockrove.',
                                ),
                                actions: [
                                  Theme(
                                    data: Theme.of(context).copyWith(
                                      colorScheme: Theme.of(context).colorScheme
                                          .copyWith(
                                            primary: Theme.of(context)
                                                .colorScheme
                                                .error,
                                            onPrimary: Theme.of(context)
                                                .colorScheme
                                                .onError,
                                          ),
                                    ),
                                    child: BusinessCommand(
                                      label: 'Delete permanently',
                                      create: () {
                                        final id = newBusinessId();
                                        return () => ref
                                            .read(lifecycleApiProvider)
                                            .delete(id, data.version, job.id);
                                      },
                                      onCommitted: () {
                                        ref.invalidate(archiveProvider);
                                        Navigator.pop(dialog);
                                      },
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
  );
}
