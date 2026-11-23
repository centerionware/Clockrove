import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'job_screen.dart';
import 'lifecycle_api.dart';

class ArchiveScreen extends ConsumerWidget {
  const ArchiveScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(
      title: Text(AppMessage.archivedJobs.of(context)),
      actions: [
        IconButton(
          tooltip: AppMessage.archiveRefresh.of(context),
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
              child: Text(AppMessage.archiveRetry.of(context)),
            ),
          ),
          data: (data) => ListView(
            padding: const EdgeInsets.all(20),
            children: [
              Padding(
                padding: const EdgeInsets.only(bottom: 20),
                child: Text(AppMessage.archiveExplanation.of(context)),
              ),
              if (data.jobs.isEmpty)
                Center(child: Text(AppMessage.archiveEmpty.of(context))),
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
                            (job.canDelete
                                    ? AppMessage.archiveCanDelete
                                    : AppMessage.archiveCannotDelete)
                                .of(context),
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
                          label: AppMessage.restoreJob.of(context),
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
                            label: Text(
                              AppMessage.permanentlyDeleteJob.of(context),
                            ),
                            onPressed: () => showDialog<void>(
                              context: context,
                              barrierDismissible: false,
                              builder: (dialog) => AlertDialog(
                                scrollable: true,
                                title: Text(
                                  AppMessage.confirmDeleteJob.format(dialog, {
                                    'job': job.name,
                                  }),
                                  style: TextStyle(
                                    color: Theme.of(context).colorScheme.error,
                                  ),
                                ),
                                content: Text(
                                  AppMessage.deleteJobWarning.of(dialog),
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
                                      label: AppMessage.deletePermanently.of(
                                        dialog,
                                      ),
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
