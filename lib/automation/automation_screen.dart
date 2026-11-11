import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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

  String _conflict(String? code) => switch (code) {
    'SITE_CAPACITY_EXCEEDED' => 'Android can monitor up to 100 enabled sites. Disable a site before adding another.',
    'INVALID_GRACE' => 'Choose a whole number of minutes from 0 to 1,440.',
    'INVALID_SITE' => 'Check the site name, coordinates and radius.',
    'STALE_STATE' => 'Work changed while you were editing. Review the refreshed details and try again.',
    _ => 'This change could not be saved. Review the refreshed details and try again.',
  };
  Future<void> _write(Future<TimerOutcome> Function() action) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _pending = action;
      _notice = null;
    });
    try {
      final outcome = await action();
      if (!mounted) return;
      setState(() {
        _pending = null;
        _notice = outcome.committed ? null : _conflict(outcome.conflict);
      });
    } catch (_) {
      if (mounted) {
        setState(
          () => _notice =
              'This change could not be confirmed. Retry to check its result.',
        );
      }
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
        title: const Text('Recognize your work sites'),
        content: Text(
          'Clockrove uses precise location, including when the app is closed, to recognize arrivals and departures. '
          'Site monitoring continues while clocked out without keeping a travel history. Location history is recorded only during work. '
          'Choose “${state.backgroundLabel}” in Android settings. You can decline and keep using the manual timer.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Not now'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Continue'),
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
          () => _notice = 'Location access could not be changed. Open app settings or keep timing manually.',
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
        setState(
          () => _notice = 'Site monitoring could not be retried. You can keep using the manual timer.',
        );
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
          () => _notice = 'App settings could not be opened. You can keep using the manual timer.',
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
    await _write(
      () => result.remove
          ? api.removeSite(id, state.version, result.site.id)
          : api.saveSite(id, state.version, widget.jobId, result.site),
    );
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
        title: const Text('Keep these recorded times?'),
        content: const Text(
          'Recorded times stay as shown. Any pending departure is cleared and an ongoing timer keeps running. '
          'Site automation resets and waits for a fresh exit and entry.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Keep recorded times'),
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
    return '${labels.formatMediumDate(local)}, ${local.year} '
        '${labels.formatTimeOfDay(TimeOfDay.fromDateTime(local), alwaysUse24HourFormat: true)}:'
        '${local.second.toString().padLeft(2, '0')} (UTC$zone)';
  }

  String _health(AutomationSnapshot value) {
    if (!value.settings.enabled) return 'Site automation is off';
    if (value.sites.every((site) => !site.enabled)) {
      return 'Add or enable a work site';
    }
    if (!value.precise || !value.background) return 'Location access needed';
    if (value.reviews.isNotEmpty) return 'Work time needs review';
    return switch (value.health) {
      'REGISTERING' => 'Setting up site monitoring…',
      'READY' =>
        value.working
            ? 'Monitoring this work session'
            : value.suppressed
            ? 'Waiting for you to leave and return'
            : value.armed
            ? 'Watching for your next arrival'
            : 'Waiting for a fresh exit and entry',
      _ => 'Site monitoring needs attention',
    };
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
        title: const Text('Work sites'),
        actions: [
          IconButton(
            onPressed: _busy ? null : _refresh,
            tooltip: 'Refresh work sites',
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
                  const Text(
                    'Work sites could not be loaded. Your manual timer remains available.',
                  ),
                  TextButton(
                    onPressed: _refresh,
                    child: const Text('Try again'),
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
                        child: const Text('Retry change'),
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
                    _health(value),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Manual timing always stays available. Arrivals never switch a running job.',
                  ),
                  if (value.settings.enabled &&
                      value.precise &&
                      value.background &&
                      !const {'READY', 'REGISTERING'}.contains(value.health))
                    TextButton(
                      onPressed: _locked ? null : _retryMonitoring,
                      child: const Text('Retry site monitoring'),
                    ),
                ]),
                const SizedBox(height: 16),
                if (value.departed != null) ...[
                  _group([
                    Text(
                      'Departure pending',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text('Left site: ${_time(value.departed!)}'),
                    Text('Grace ends: ${_time(value.deadline!)}'),
                    const SizedBox(height: 8),
                    Text(
                      value.reviews.isNotEmpty
                          ? 'Automatic departure is paused while this work needs review. The timer keeps running; you can still clock out manually.'
                          : 'This work is provisional. Returning before grace ends keeps the timer running. Otherwise, recorded work ends at departure. Android may handle this later.',
                    ),
                  ]),
                  const SizedBox(height: 16),
                ],
                if (value.reviews.isNotEmpty) ...[
                  _group([
                    Text(
                      'Review recorded work',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      'Site monitoring was interrupted or an event arrived late. Check the recorded times below. Automatic departure is paused for affected ongoing work.',
                    ),
                    for (final review in value.reviews)
                      Padding(
                        padding: const EdgeInsets.only(top: 16),
                        child: Text(
                          'Start: ${_time(review.start)}\nEnd: ${review.end == null ? "Still running" : _time(review.end!)}',
                        ),
                      ),
                    const SizedBox(height: 12),
                    OutlinedButton(
                      onPressed: _locked ? null : () => _accept(value),
                      child: const Text('Keep recorded times'),
                    ),
                  ]),
                  const SizedBox(height: 16),
                ],
                if (!value.precise || !value.background) ...[
                  _group([
                    Text(
                      'Recognize sites in the background',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Site monitoring needs precise location and “${value.backgroundLabel}”. You can keep using the manual timer without these permissions.',
                    ),
                    const SizedBox(height: 12),
                    OutlinedButton(
                      onPressed: _locked ? null : () => _permission(value),
                      child: Text(
                        !value.precise
                            ? 'Grant precise location'
                            : 'Allow background location',
                      ),
                    ),
                    TextButton(
                      onPressed: _locked ? null : _openSettings,
                      child: const Text('App settings'),
                    ),
                  ]),
                  const SizedBox(height: 16),
                ],
                _group([
                  Text(
                    'Places',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (value.sites.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 16),
                      child: Text(
                        'Add the places where you work to use arrival and departure automation.',
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
                      subtitle: Text(
                        '${site.radius.toStringAsFixed(0)} m radius · ${site.enabled ? "Enabled" : "Disabled"}',
                      ),
                      trailing: const Icon(Icons.chevron_right_rounded),
                      enabled: !_locked,
                      onTap: () => _edit(value, site),
                    ),
                  OutlinedButton.icon(
                    onPressed: _locked ? null : () => _edit(value),
                    icon: const Icon(Icons.add_rounded),
                    label: const Text('Add work site'),
                  ),
                ]),
                const SizedBox(height: 16),
                _group([
                  Text(
                    'Automation',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Use work site automation'),
                    value: value.settings.enabled,
                    onChanged: _locked
                        ? null
                        : (enabled) async {
                            if (enabled &&
                                value.sites.every((site) => !site.enabled)) {
                              setState(
                                () => _notice =
                                    'Add or enable a work site first.',
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
                    title: const Text('Clock in on arrival'),
                    subtitle: const Text(
                      'After a confirmed exit and a fresh entry',
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
                    title: const Text('Clock out after leaving'),
                    subtitle: Text(
                      value.settings.automaticDeparture
                          ? 'Use the departure grace below'
                          : 'Paid travel: keep timing until manual clock-out',
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
                      title: const Text('Departure grace'),
                      subtitle: Text('${value.settings.graceMinutes} minutes'),
                      trailing: const Icon(Icons.chevron_right_rounded),
                      enabled: !_locked && value.settings.enabled,
                      onTap: () => _grace(value),
                    ),
                  const SizedBox(height: 8),
                  const Text(
                    'Changes reset site detection and clear any pending departure. A running timer keeps going.',
                  ),
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
          ? 'Work time needs review'
          : state?.departed != null
          ? 'Departure pending'
          : 'Work sites & automation',
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
                ? 'Work time needs review'
                : 'Departure pending',
          ),
          subtitle: Text(
            state.reviews.isNotEmpty
                ? 'Automatic departure is held. Manual clock-out still works.'
                : 'This session is provisional until departure is resolved.',
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
