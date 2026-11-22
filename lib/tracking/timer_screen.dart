import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import '../l10n/app_strings.dart';
import 'timer_api.dart';
import 'timer_providers.dart';
import 'tracking_api.dart';
import '../automation/automation_screen.dart';
import '../business/job_screen.dart';
import '../business/settings_screen.dart';
import '../business/report_screen.dart';
import '../business/business_financial_report_screen.dart';
import '../business/archive_screen.dart';
import '../business/invoice_history_screen.dart';
import '../business/quote_screens.dart';
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

  Future<void> _newJob(TimerSnapshot displayed) async {
    var enteredName = '';
    final name = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppMessage.newJob.of(context)),
        content: TextField(
          onChanged: (value) => enteredName = value,
          autofocus: true,
          maxLength: 240,
          decoration: InputDecoration(
            labelText: AppMessage.jobName.of(context),
          ),
          textCapitalization: TextCapitalization.sentences,
          onSubmitted: (value) {
            if (value.trim().isNotEmpty) Navigator.pop(context, value.trim());
          },
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(AppMessage.cancel.of(context)),
          ),
          TextButton(
            onPressed: () {
              final value = enteredName.trim();
              if (value.isNotEmpty) Navigator.pop(context, value);
            },
            child: Text(AppMessage.create.of(context)),
          ),
        ],
      ),
    );
    if (name != null && mounted) {
      final current = ref.read(timerSnapshotProvider).value ?? displayed;
      await ref.read(timerActionsProvider.notifier).createJob(current, name);
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
                  running == null
                      ? AppMessage.idle.of(context)
                      : AppMessage.running.of(context),
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
              Text(
                AppMessage.chooseJob.of(context),
                textAlign: TextAlign.center,
              ),
            ] else ...[
              Text(
                running.jobName,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 20),
              Text(
                AppMessage.elapsed.of(context),
                style: Theme.of(context).textTheme.labelLarge,
              ),
              const SizedBox(height: 4),
              ElapsedDisplay(startUtc: running.startUtc),
              const SizedBox(height: 8),
              Text(
                AppMessage.started.format(context, {
                  'time': MaterialLocalizations.of(context).formatTimeOfDay(
                    TimeOfDay.fromDateTime(running.startUtc.toLocal()),
                  ),
                }),
                style: TextStyle(color: colors.onSurfaceVariant),
              ),
              const SizedBox(height: 24),
              FilledButton(
                onPressed: disabled
                    ? null
                    : () => ref
                          .read(timerActionsProvider.notifier)
                          .submit(state, TimerAction.stop),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(Icons.stop_rounded, size: 20),
                    const SizedBox(width: 8),
                    Text(AppMessage.clockOut.of(context)),
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
    final disabled = actions.working || actions.hasPending;
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
            tooltip: AppMessage.settingsMenu.of(context),
            icon: Icon(
              Icons.more_vert,
              semanticLabel: AppMessage.settingsMenu.of(context),
            ),
            onSelected: (value) => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => switch (value) {
                  'settings' => const SettingsScreen(),
                  'customers' => const CustomersScreen(),
                  'reports' => const ReportScreen(),
                  'business_reports' => const BusinessFinancialReportScreen(),
                  'invoices' => const InvoiceHistory(),
                  'quotes' => const QuotesScreen(),
                  _ => const ArchiveScreen(),
                },
              ),
            ),
            itemBuilder: (_) => [
              PopupMenuItem(
                value: 'settings',
                child: Text(AppMessage.settings.of(context)),
              ),
              PopupMenuItem(
                value: 'customers',
                child: Text(AppMessage.customers.of(context)),
              ),
              PopupMenuItem(
                value: 'reports',
                child: Text(AppMessage.reports.of(context)),
              ),
              PopupMenuItem(
                value: 'business_reports',
                child: Row(
                  children: [
                    const Icon(Icons.assessment_outlined),
                    const SizedBox(width: 12),
                    Text(AppMessage.taxFinances.of(context)),
                  ],
                ),
              ),
              PopupMenuItem(
                value: 'invoices',
                child: Text(AppMessage.invoices.of(context)),
              ),
              PopupMenuItem(
                value: 'quotes',
                child: Text(AppMessage.quotes.of(context)),
              ),
              PopupMenuItem(
                value: 'archive',
                child: Text(AppMessage.archivedJobs.of(context)),
              ),
            ],
          ),
          IconButton(
            tooltip: AppMessage.refresh.of(context),
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
                    child: Text(AppMessage.storageUnavailable.of(context)),
                  ),
                ),
              ),
              data: (state) => ListView(
                padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
                children: [
                  Text(
                    AppMessage.workday.of(context),
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
                  if (actions.hasPending && !actions.working)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Card(
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(AppMessage.uncertain.of(context)),
                              TextButton(
                                onPressed: () => ref
                                    .read(timerActionsProvider.notifier)
                                    .retry(),
                                child: Text(AppMessage.retry.of(context)),
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
                            ? AppMessage.jobCreateFailed.of(context)
                            : AppMessage.commandConflict.of(context),
                      ),
                    ),
                  const SizedBox(height: 28),
                  Text(
                    AppMessage.jobs.of(context),
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
                                    ? AppMessage.running.of(context)
                                    : state.running == null
                                    ? AppMessage.clockIn.of(context)
                                    : AppMessage.switchJob.of(context),
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
                            AppMessage.newJob.of(context),
                            style: TextStyle(
                              color: colors.primary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          enabled: !disabled,
                          onTap: disabled ? null : () => _newJob(state),
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
