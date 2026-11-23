import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import '../help/help_screen.dart';
import '../tracking/timer_api.dart';
import '../tracking/timer_providers.dart';
import 'automation_api.dart';
import 'site_editor.dart';

class AutomationScreen extends ConsumerStatefulWidget {
  const AutomationScreen({
    super.key,
    required this.jobId,
    required this.jobName,
  });
  final String jobId;
  final String jobName;
  @override
  ConsumerState<AutomationScreen> createState() => _AutomationScreenState();
}

class _AutomationScreenState extends ConsumerState<AutomationScreen>
    with WidgetsBindingObserver {
  bool _busy = false;
  String? _notice;
  Future<TimerOutcome> Function()? _pending;
  bool get _locked => _busy || _pending != null;
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
    if (state == AppLifecycleState.resumed) _refresh();
  }

  void _refresh() {
    ref.invalidate(automationSnapshotProvider(widget.jobId));
  }

  String _conflict(BuildContext context, String? code) => switch (code) {
    'SITE_CAPACITY_EXCEEDED' => AppMessage.automationCapacityError.of(context),
    'INVALID_GRACE' => AppMessage.automationInvalidGrace.of(context),
    'INVALID_SITE' => AppMessage.automationInvalidSite.of(context),
    'STALE_STATE' => AppMessage.automationStaleState.of(context),
    _ => AppMessage.automationSaveFailed.of(context),
  };
  void _feedback(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<bool> _write(Future<TimerOutcome> Function() action) async {
    if (_busy) return false;
    setState(() {
      _busy = true;
      _pending = action;
      _notice = null;
    });
    try {
      final outcome = await action();
      if (!mounted) return outcome.committed;
      setState(() {
        _pending = null;
        _notice = outcome.committed
            ? null
            : _conflict(context, outcome.conflict);
      });
      return outcome.committed;
    } catch (_) {
      if (mounted) {
        setState(() => _notice = AppMessage.commandUnconfirmed.of(context));
      }
      return false;
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        _refresh();
        ref.invalidate(timerSnapshotProvider);
      }
    }
  }

  Future<void> _saveSettings(
    AutomationSnapshot state,
    SiteSettings settings,
  ) async {
    if (_locked) return;
    final id = newAutomationId();
    final api = ref.read(automationApiProvider);
    await _write(
      () => api.saveSettings(id, state.version, widget.jobId, settings),
    );
  }

  Future<void> _permission(AutomationSnapshot state) async {
    if (_locked) return;
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppMessage.automationPermissionTitle.of(context)),
        content: Text(
          AppMessage.automationPermissionBody.format(context, {
            'background': state.backgroundLabel,
          }),
        ),
        actions: [
          HelpButton(topic: HelpTopic.automation, enabled: !_busy),
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.notNow.of(context)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppMessage.continueLabel.of(context)),
          ),
        ],
      ),
    );
    if (accepted != true || !mounted) return;
    setState(() {
      _busy = true;
      _notice = null;
    });
    try {
      final api = ref.read(automationApiProvider);
      if (!state.precise) {
        await api.requestPrecise();
      } else {
        await api.requestBackground();
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _notice = AppMessage.automationLocationAccessFailed.of(context),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        _refresh();
      }
    }
  }

  Future<void> _retryMonitoring() async {
    if (_locked) return;
    setState(() {
      _busy = true;
      _notice = null;
    });
    try {
      await ref.read(automationApiProvider).retryMonitoring();
    } catch (_) {
      if (mounted) {
        setState(() => _notice = AppMessage.automationRetryFailed.of(context));
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        _refresh();
      }
    }
  }

  Future<void> _openSettings() async {
    if (_locked) return;
    setState(() {
      _busy = true;
      _notice = null;
    });
    try {
      await ref.read(automationApiProvider).openSettings();
    } catch (_) {
      if (mounted) {
        setState(
          () => _notice = AppMessage.automationOpenSettingsFailed.of(context),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        _refresh();
      }
    }
  }

  Future<void> _edit(AutomationSnapshot state, [WorkSite? site]) async {
    if (_locked) return;
    final result = await Navigator.of(context).push<SiteEditResult>(
      MaterialPageRoute(builder: (_) => SiteEditor(site: site)),
    );
    if (result == null || !mounted) return;
    final api = ref.read(automationApiProvider);
    final id = newAutomationId();
    final saved = await _write(
      () => result.remove
          ? api.removeSite(id, state.version, result.site.id)
          : api.saveSite(
              id,
              state.version,
              widget.jobId,
              result.site,
              currentFix: result.currentPosition,
            ),
    );
    if (saved &&
        !result.remove &&
        result.usedCurrentLocation &&
        result.site.enabled &&
        state.settings.enabled &&
        state.settings.autoStart) {
      await _offerClockInNow();
    }
  }

  Future<void> _offerClockInNow() async {
    TimerSnapshot timer;
    try {
      timer = await ref.read(timerApiProvider).snapshot();
    } catch (_) {
      if (!mounted) return;
      _feedback(AppMessage.automationCurrentTimerReadFailed.of(context));
      return;
    }
    if (!mounted) return;
    if (timer.running != null) {
      _feedback(
        timer.running!.jobId == widget.jobId
            ? AppMessage.automationJobAlreadyRunning.of(context)
            : AppMessage.automationOtherJobRunning.of(context),
      );
      return;
    }

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppMessage.automationClockInNowTitle.of(context)),
        content: Text(AppMessage.automationClockInNowBody.of(context)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.notNow.of(context)),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppMessage.clockIn.of(context)),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final actions = ref.read(timerActionsProvider);
    if (actions.working || actions.hasPending) {
      _feedback(AppMessage.automationTimerActionPending.of(context));
      return;
    }
    await ref
        .read(timerActionsProvider.notifier)
        .submit(timer, TimerAction.start, jobId: widget.jobId);
    if (!mounted) return;
    final outcome = ref.read(timerActionsProvider);
    if (outcome.pending != null || outcome.pendingJob != null) {
      _feedback(AppMessage.automationClockInUnconfirmed.of(context));
    } else if (outcome.conflict != null) {
      _feedback(AppMessage.automationClockInConflict.of(context));
    } else {
      _feedback(
        AppMessage.automationClockInSuccess.format(context, {
          'job': widget.jobName,
        }),
      );
    }
    ref.invalidate(timerSnapshotProvider);
    ref.invalidate(automationSnapshotProvider(widget.jobId));
  }

  Future<void> _grace(AutomationSnapshot state) async {
    final minutes = await showDialog<int>(
      context: context,
      builder: (_) =>
          DepartureGraceDialog(minutes: state.settings.graceMinutes),
    );
    if (minutes != null && mounted) {
      await _saveSettings(
        state,
        state.settings.copyWith(graceMinutes: minutes),
      );
    }
  }

  Future<void> _accept(AutomationSnapshot state) async {
    final approved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppMessage.automationKeepTimesTitle.of(context)),
        content: Text(AppMessage.automationKeepTimesHelp.of(context)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.cancel.of(context)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppMessage.keepRecordedTimes.of(context)),
          ),
        ],
      ),
    );
    if (approved != true || !mounted) return;
    final id = newAutomationId();
    final api = ref.read(automationApiProvider);
    await _write(() => api.acceptReview(id, state.version, widget.jobId));
  }

  String _time(DateTime instant) {
    final local = instant.toLocal();
    final offset = local.timeZoneOffset.inMinutes;
    final zone =
        '${offset < 0 ? "−" : "+"}${(offset.abs() ~/ 60).toString().padLeft(2, '0')}:${(offset.abs() % 60).toString().padLeft(2, '0')}';
    final labels = MaterialLocalizations.of(context);
    final utcOffset = AppMessage.timeUtcOffset.format(context, {
      'offset': zone,
    });
    return '${labels.formatMediumDate(local)}, ${local.year} '
        '${labels.formatTimeOfDay(TimeOfDay.fromDateTime(local), alwaysUse24HourFormat: true)}:'
        '${local.second.toString().padLeft(2, '0')}$utcOffset';
  }

  String _health(BuildContext context, AutomationSnapshot value) {
    if (!value.settings.enabled) {
      return AppMessage.automationStatusOff.of(context);
    }
    if (value.sites.every((site) => !site.enabled)) {
      return AppMessage.automationStatusAddSite.of(context);
    }
    if (!value.precise || !value.background) {
      return AppMessage.automationStatusLocationNeeded.of(context);
    }
    if (value.reviews.isNotEmpty) {
      return AppMessage.automationReviewTitle.of(context);
    }
    return switch (value.health) {
      'REGISTERING' => AppMessage.automationStatusRegistering.of(context),
      'READY' =>
        value.working
            ? AppMessage.automationStatusWorking.of(context)
            : value.suppressed
            ? AppMessage.automationStatusWaitReturn.of(context)
            : value.armed
            ? AppMessage.automationStatusWatchArrival.of(context)
            : AppMessage.automationStatusFreshEntry.of(context),
      _ => AppMessage.automationStatusAttention.of(context),
    };
  }

  String _siteSubtitle(WorkSite site) {
    final status = AppMessage.automationSiteRadiusStatus.format(context, {
      'radius': site.radius.toStringAsFixed(0),
      'status': site.enabled
          ? AppMessage.automationEnabled.of(context)
          : AppMessage.automationDisabled.of(context),
    });
    if (site.enabled && site.radius < 100) {
      return '$status · ${AppMessage.automationSiteRadiusWarning.of(context)}';
    }
    return status;
  }

  Widget _group(List<Widget> children) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: children,
      ),
    ),
  );
  @override
  Widget build(BuildContext context) {
    final state = ref.watch(automationSnapshotProvider(widget.jobId));
    return Scaffold(
      appBar: AppBar(
        title: Text(AppMessage.automationTitle.of(context)),
        actions: [
          IconButton(
            onPressed: _busy ? null : _refresh,
            tooltip: AppMessage.automationRefresh.of(context),
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720),
          child: state.when(
            skipLoadingOnReload: true,
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (_, _) => Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(AppMessage.automationLoadFailed.of(context)),
                  TextButton(
                    onPressed: _refresh,
                    child: Text(AppMessage.automationTryAgain.of(context)),
                  ),
                ],
              ),
            ),
            data: (value) => ListView(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
              children: [
                Text(
                  widget.jobName,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 20),
                if (_busy) const LinearProgressIndicator(),
                if (_notice != null)
                  _group([
                    Text(_notice!),
                    if (_pending != null)
                      TextButton(
                        onPressed: _busy ? null : () => _write(_pending!),
                        child: Text(
                          AppMessage.automationRetryChange.of(context),
                        ),
                      ),
                  ]),
                _group([
                  Icon(
                    Icons.radar_rounded,
                    color: Theme.of(context).colorScheme.primary,
                    size: 28,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    _health(context, value),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  Text(AppMessage.automationManualSafety.of(context)),
                  if (value.settings.enabled &&
                      value.precise &&
                      value.background &&
                      value.sites.any((site) => site.enabled) &&
                      value.health != 'REGISTERING')
                    TextButton(
                      onPressed: _locked ? null : _retryMonitoring,
                      child: Text(
                        value.health == 'READY'
                            ? AppMessage.automationRefreshMonitoring.of(context)
                            : AppMessage.automationRetryMonitoring.of(context),
                      ),
                    ),
                ]),
                const SizedBox(height: 16),
                if (value.departed != null) ...[
                  _group([
                    Text(
                      AppMessage.automationDeparturePending.of(context),
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      AppMessage.automationLeftSite.format(context, {
                        'time': _time(value.departed!),
                      }),
                    ),
                    Text(
                      AppMessage.automationGraceEnds.format(context, {
                        'time': _time(value.deadline!),
                      }),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      value.reviews.isNotEmpty
                          ? AppMessage.automationDeparturePausedReview.of(
                              context,
                            )
                          : AppMessage.automationProvisionalDeparture.of(
                              context,
                            ),
                    ),
                  ]),
                  const SizedBox(height: 16),
                ],
                if (value.reviews.isNotEmpty) ...[
                  _group([
                    Text(
                      AppMessage.automationReviewTitle.of(context),
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(AppMessage.automationReviewIntro.of(context)),
                    for (final review in value.reviews)
                      Padding(
                        padding: const EdgeInsets.only(top: 16),
                        child: Text(
                          AppMessage.automationReviewInterval.format(context, {
                            'start': _time(review.start),
                            'end': review.end == null
                                ? AppMessage.automationStillRunning.of(context)
                                : _time(review.end!),
                          }),
                        ),
                      ),
                    const SizedBox(height: 12),
                    OutlinedButton(
                      onPressed: _locked ? null : () => _accept(value),
                      child: Text(AppMessage.keepRecordedTimes.of(context)),
                    ),
                  ]),
                  const SizedBox(height: 16),
                ],
                if (!value.precise || !value.background) ...[
                  _group([
                    Text(
                      AppMessage.automationBackgroundTitle.of(context),
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      AppMessage.automationBackgroundHelp.format(context, {
                        'background': value.backgroundLabel,
                      }),
                    ),
                    const SizedBox(height: 12),
                    OutlinedButton(
                      onPressed: _locked ? null : () => _permission(value),
                      child: Text(
                        !value.precise
                            ? AppMessage.automationGrantPrecise.of(context)
                            : AppMessage.automationAllowBackground.of(context),
                      ),
                    ),
                    TextButton(
                      onPressed: _locked ? null : _openSettings,
                      child: Text(AppMessage.settings.of(context)),
                    ),
                  ]),
                  const SizedBox(height: 16),
                ],
                _group([
                  Text(
                    AppMessage.automationPlaces.of(context),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (value.sites.isEmpty)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      child: Text(
                        AppMessage.automationAddPlacesHelp.of(context),
                      ),
                    ),
                  for (final site in value.sites)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(
                        site.enabled
                            ? Icons.location_on_outlined
                            : Icons.location_off_outlined,
                      ),
                      title: Text(site.name),
                      subtitle: Text(_siteSubtitle(site)),
                      trailing: const Icon(Icons.chevron_right_rounded),
                      enabled: !_locked,
                      onTap: () => _edit(value, site),
                    ),
                  OutlinedButton.icon(
                    onPressed: _locked ? null : () => _edit(value),
                    icon: const Icon(Icons.add_rounded),
                    label: Text(AppMessage.automationAddSite.of(context)),
                  ),
                ]),
                const SizedBox(height: 16),
                _group([
                  Text(
                    AppMessage.automationSectionTitle.of(context),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(
                      AppMessage.automationUseSiteAutomation.of(context),
                    ),
                    value: value.settings.enabled,
                    onChanged: _locked
                        ? null
                        : (enabled) async {
                            if (enabled &&
                                value.sites.every((site) => !site.enabled)) {
                              setState(
                                () => _notice = AppMessage.automationNeedSite
                                    .of(context),
                              );
                              return;
                            }
                            if (enabled &&
                                (!value.precise || !value.background)) {
                              await _permission(value);
                              return;
                            }
                            await _saveSettings(
                              value,
                              value.settings.copyWith(enabled: enabled),
                            );
                          },
                  ),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(
                      AppMessage.automationClockInArrival.of(context),
                    ),
                    subtitle: Text(
                      AppMessage.automationAfterExitEntry.of(context),
                    ),
                    value: value.settings.autoStart,
                    onChanged: _locked || !value.settings.enabled
                        ? null
                        : (v) => _saveSettings(
                            value,
                            value.settings.copyWith(autoStart: v),
                          ),
                  ),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(
                      AppMessage.automationClockOutDeparture.of(context),
                    ),
                    subtitle: Text(
                      value.settings.automaticDeparture
                          ? AppMessage.automationUseGrace.of(context)
                          : AppMessage.automationPaidTravel.of(context),
                    ),
                    value: value.settings.automaticDeparture,
                    onChanged: _locked || !value.settings.enabled
                        ? null
                        : (v) => _saveSettings(
                            value,
                            value.settings.copyWith(automaticDeparture: v),
                          ),
                  ),
                  if (value.settings.automaticDeparture)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(AppMessage.automationGraceTitle.of(context)),
                      subtitle: Text(
                        AppMessage.automationGraceMinutes.format(context, {
                          'minutes': '${value.settings.graceMinutes}',
                        }),
                      ),
                      trailing: const Icon(Icons.chevron_right_rounded),
                      enabled: !_locked && value.settings.enabled,
                      onTap: () => _grace(value),
                    ),
                  const SizedBox(height: 8),
                  Text(AppMessage.automationResetHelp.of(context)),
                ]),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class JobSiteSubtitle extends ConsumerWidget {
  const JobSiteSubtitle({super.key, required this.jobId});
  final String jobId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(automationSnapshotProvider(jobId)).asData?.value;
    return Text(
      state?.reviews.isNotEmpty == true
          ? AppMessage.automationReviewTitle.of(context)
          : state?.departed != null
          ? AppMessage.automationDeparturePending.of(context)
          : AppMessage.worksitesAutomation.of(context),
    );
  }
}

class AutomationNotice extends ConsumerWidget {
  const AutomationNotice({
    super.key,
    required this.jobId,
    required this.jobName,
  });
  final String jobId;
  final String jobName;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(automationSnapshotProvider(jobId)).asData?.value;
    if (state == null || (state.departed == null && state.reviews.isEmpty)) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Card(
        child: ListTile(
          leading: const Icon(Icons.schedule_outlined),
          title: Text(
            state.reviews.isNotEmpty
                ? AppMessage.automationReviewTitle.of(context)
                : AppMessage.automationDeparturePending.of(context),
          ),
          subtitle: Text(
            state.reviews.isNotEmpty
                ? AppMessage.automationBannerManualHeld.of(context)
                : AppMessage.automationBannerProvisional.of(context),
          ),
          trailing: const Icon(Icons.chevron_right_rounded),
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => AutomationScreen(jobId: jobId, jobName: jobName),
            ),
          ),
        ),
      ),
    );
  }
}
