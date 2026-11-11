import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_strings.dart';
import 'timer_api.dart';
import 'timer_providers.dart';
import 'tracking_api.dart';
import '../automation/automation_screen.dart';
import '../business/job_screen.dart';
import '../business/settings_screen.dart';
import '../business/report_screen.dart';
import '../business/archive_screen.dart';
import 'tracking_controls.dart';
import 'elapsed_display.dart';

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
      ref.invalidate(trackingStatusProvider);
      ref.invalidate(trackingHealthProvider);
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

  Widget _sessionCard(TimerSnapshot state, bool disabled) {
    final colors = Theme.of(context).colorScheme;
    final running = state.running;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Align(
              alignment: Alignment.centerLeft,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: colors.primary.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(24),
                ),
                child: Text(
                  running == null ? AppStrings.idle : AppStrings.running,
                  style: TextStyle(
                    color: colors.primary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 20),
            if (running == null) ...[
              Icon(Icons.timelapse_rounded, size: 52, color: colors.primary),
              const SizedBox(height: 16),
              const Text(AppStrings.chooseJob, textAlign: TextAlign.center),
            ] else ...[
              Text(
                running.jobName,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 20),
              Text(
                AppStrings.elapsed,
                style: Theme.of(context).textTheme.labelLarge,
              ),
              const SizedBox(height: 4),
              ElapsedDisplay(startUtc: running.startUtc),
              const SizedBox(height: 8),
              Text(
                '${AppStrings.started} ${MaterialLocalizations.of(context).formatTimeOfDay(TimeOfDay.fromDateTime(running.startUtc.toLocal()))}',
                style: TextStyle(color: colors.onSurfaceVariant),
              ),
              const SizedBox(height: 24),
              FilledButton(
                onPressed: disabled
                    ? null
                    : () => ref
                          .read(timerActionsProvider.notifier)
                          .submit(state, TimerAction.stop),
                child: const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.stop_rounded, size: 20),
                    SizedBox(width: 8),
                    Text(AppStrings.clockOut),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = ref.watch(timerSnapshotProvider);
    final actions = ref.watch(timerActionsProvider);
    final disabled = actions.working || actions.pending != null;
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                color: colors.primary,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(
                Icons.timelapse_rounded,
                color: colors.onPrimary,
                size: 24,
              ),
            ),
            const SizedBox(width: 12),
            const Text(AppStrings.appName),
          ],
        ),
        actions: [
          PopupMenuButton<String>(
            tooltip: 'App settings and customers',
            onSelected: (value) => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => switch (value) {
              'settings' => const SettingsScreen(), 'customers' => const CustomersScreen(), 'reports' => const ReportScreen(), _ => const ArchiveScreen(),
            })),
            itemBuilder: (_) => const [PopupMenuItem(value: 'settings', child: Text('Settings')), PopupMenuItem(value: 'customers', child: Text('Customers')), PopupMenuItem(value: 'reports', child: Text('Reports')), PopupMenuItem(value: 'archive', child: Text('Archived jobs'))],
          ),
          IconButton(
            tooltip: AppStrings.refresh,
            onPressed: () {
              ref.invalidate(timerSnapshotProvider);
              ref.invalidate(trackingStatusProvider);
            },
            icon: const Icon(Icons.refresh_rounded),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
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
                padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
                children: [
                  Text(
                    AppStrings.workday,
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    MaterialLocalizations.of(context)
                        .formatMediumDate(DateTime.now()),
                    style: TextStyle(color: colors.onSurfaceVariant),
                  ),
                  const SizedBox(height: 24),
                  _sessionCard(state, disabled),
                  if (state.running != null)
                    AutomationNotice(
                      jobId: state.running!.jobId,
                      jobName: state.running!.jobName,
                    ),
                  if (actions.working)
                    const Padding(
                      padding: EdgeInsets.only(top: 12),
                      child: LinearProgressIndicator(),
                    ),
                  if (actions.pending != null && !actions.working)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Card(
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Text(AppStrings.uncertain),
                              TextButton(
                                onPressed: () => ref
                                    .read(timerActionsProvider.notifier)
                                    .retry(),
                                child: const Text(AppStrings.retry),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  if (actions.conflict != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(
                        actions.conflict == 'JOB_CREATE_FAILED'
                            ? AppStrings.jobCreateFailed
                            : AppStrings.commandConflict,
                      ),
                    ),
                  const SizedBox(height: 28),
                  Text(
                    AppStrings.jobs,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 12),
                  Card(
                    child: Column(
                      children: [
                        for (final job in state.jobs) ...[
                          ListTile(
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 6,
                            ),
                            leading: Icon(
                              Icons.work_outline_rounded,
                              color: colors.primary,
                            ),
                            title: Text(job.name),
                            subtitle: JobSiteSubtitle(jobId: job.id),
                            onTap: disabled
                                ? null
                                : () => Navigator.of(context).push(
                                    MaterialPageRoute<void>(
                                      builder: (_) => JobScreen(
                                        jobId: job.id,
                                        jobName: job.name,
                                      ),
                                    ),
                                  ),
                            trailing: TextButton(
                              onPressed:
                                  disabled || state.running?.jobId == job.id
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
                                state.running?.jobId == job.id
                                    ? AppStrings.running
                                    : state.running == null
                                    ? AppStrings.clockIn
                                    : AppStrings.switchJob,
                              ),
                            ),
                          ),
                          const Divider(indent: 16, endIndent: 16),
                        ],
                        ListTile(
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 6,
                          ),
                          leading: Icon(
                            Icons.add_circle_outline_rounded,
                            color: colors.primary,
                          ),
                          title: Text(
                            AppStrings.newJob,
                            style: TextStyle(
                              color: colors.primary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          enabled: !disabled,
                          onTap: disabled ? null : _newJob,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),
                  const TrackingControls(),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
