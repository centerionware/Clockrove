import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_strings.dart';
import 'timer_api.dart';
import 'timer_providers.dart';

class TimerScreen extends ConsumerStatefulWidget {
  const TimerScreen({super.key});

  @override
  ConsumerState<TimerScreen> createState() => _TimerScreenState();
}

class _TimerScreenState extends ConsumerState<TimerScreen>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      ref.invalidate(timerVersionsProvider);
      ref.invalidate(timerSnapshotProvider);
    }
  }

  Future<void> _newJob() async {
    var enteredName = '';
    final name = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text(AppStrings.newJob),
        content: TextField(
          onChanged: (value) => enteredName = value,
          autofocus: true,
          decoration: const InputDecoration(labelText: AppStrings.jobName),
          textCapitalization: TextCapitalization.sentences,
          onSubmitted: (value) {
            if (value.trim().isNotEmpty) Navigator.pop(context, value.trim());
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text(AppStrings.cancel),
          ),
          TextButton(
            onPressed: () {
              final value = enteredName.trim();
              if (value.isNotEmpty) Navigator.pop(context, value);
            },
            child: const Text(AppStrings.create),
          ),
        ],
      ),
    );
    if (name != null && mounted) {
      await ref.read(timerActionsProvider.notifier).createJob(name);
    }
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = ref.watch(timerSnapshotProvider);
    final actions = ref.watch(timerActionsProvider);
    final disabled = actions.working || actions.pending != null;
    return Scaffold(
      appBar: AppBar(
        title: const Text(AppStrings.appName),
        actions: [
          IconButton(
            tooltip: AppStrings.refresh,
            onPressed: () => ref.invalidate(timerSnapshotProvider),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: snapshot.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, stack) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: TextButton(
                onPressed: () => ref.invalidate(timerSnapshotProvider),
                child: const Text(AppStrings.storageUnavailable),
              ),
            ),
          ),
          data: (state) => ListView(
            padding: const EdgeInsets.all(16),
            children: [
              const Text(AppStrings.prototype),
              const SizedBox(height: 16),
              Text(
                state.running?.jobName ?? AppStrings.idle,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              if (state.running != null) ...[
                Text(
                  '${AppStrings.started} ${state.running!.startUtc.toLocal()}',
                ),
                FilledButton(
                  onPressed: disabled
                      ? null
                      : () => ref
                            .read(timerActionsProvider.notifier)
                            .submit(state, TimerAction.stop),
                  child: const Text(AppStrings.clockOut),
                ),
              ],
              if (actions.working) const LinearProgressIndicator(),
              if (actions.pending != null && !actions.working) ...[
                const Text(AppStrings.uncertain),
                TextButton(
                  onPressed: () =>
                      ref.read(timerActionsProvider.notifier).retry(),
                  child: const Text(AppStrings.retry),
                ),
              ],
              if (actions.conflict != null)
                Text(
                  actions.conflict == 'JOB_CREATE_FAILED'
                      ? AppStrings.jobCreateFailed
                      : AppStrings.commandConflict,
                ),
              const SizedBox(height: 24),
              Text(
                AppStrings.jobs,
                style: Theme.of(context).textTheme.titleLarge,
              ),
              for (final job in state.jobs)
                ListTile(
                  title: Text(job.name),
                  trailing: TextButton(
                    onPressed: disabled || state.running?.jobId == job.id
                        ? null
                        : () => ref
                              .read(timerActionsProvider.notifier)
                              .submit(
                                state,
                                state.running == null
                                    ? TimerAction.start
                                    : TimerAction.switchJob,
                                jobId: job.id,
                              ),
                    child: Text(
                      state.running == null
                          ? AppStrings.clockIn
                          : AppStrings.switchJob,
                    ),
                  ),
                ),
              ListTile(
                leading: const Icon(Icons.add),
                title: const Text(AppStrings.newJob),
                enabled: !disabled,
                onTap: disabled ? null : _newJob,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
