import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/app_localizations.dart';
import '../../business/business_api.dart';
import '../../business/business_command.dart';
import '../../business/report_screen.dart';
import '../history_api.dart';
import '../timer_providers.dart';
import 'history_audit_screen.dart';
import 'history_editor_screen.dart';
import 'history_time_format.dart';

class HistoryScreen extends ConsumerStatefulWidget {
  const HistoryScreen({super.key, required this.jobId, required this.jobName});
  final String jobId, jobName;
  @override
  ConsumerState<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends ConsumerState<HistoryScreen>
    with WidgetsBindingObserver {
  final _entries = <HistoryEntry>[];
  HistoryData? _page;
  bool _busy = false;
  bool _refreshAgain = false;
  String? _error;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _load();
    }
  }

  Future<void> _load({bool more = false}) async {
    if (_busy) {
      if (!more) {
        _refreshAgain = true;
      }
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final page = await ref
          .read(historyApiProvider)
          .history(widget.jobId, before: more ? _page?.next : null);
      if (!mounted) {
        return;
      }
      if (more && _page?.version != page.version) {
        setState(
          () => _error = 'Work changed while loading. Refresh the list before loading more.',
        );
      } else {
        setState(() {
          if (!more) {
            _entries.clear();
          }
          _entries.addAll(page.entries);
          _page = page;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _error =
              'Work history could not be loaded. Your saved work is unchanged.',
        );
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        if (_refreshAgain) {
          _refreshAgain = false;
          _load();
        }
      }
    }
  }

  Future<void> _edit([HistoryEntry? entry]) async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute(
        builder: (_) =>
            HistoryEditor(jobId: widget.jobId, sessionId: entry?.id),
      ),
    );
    if (mounted) {
      _load();
    }
  }

  Future<void> _deleteGpsByRange() async {
    final api = ref.read(historyApiProvider);
    try {
      final initialContext = await api.context();
      if (!mounted) return;
      final localNow = DateTime.fromMillisecondsSinceEpoch(
        initialContext.now,
        isUtc: true,
      ).toLocal();
      final today = DateTime(localNow.year, localNow.month, localNow.day);
      final selected = await showDateRangePicker(
        context: context,
        firstDate: DateTime(1),
        lastDate: today,
        initialDateRange: DateTimeRange(
          start: DateTime(today.year, today.month, today.day - 6),
          end: today,
        ),
        helpText: 'Select GPS dates',
        saveText: 'Review',
        confirmText: 'Review',
      );
      if (selected == null || !mounted) return;

      // Construct both calendar boundaries in the device timezone. Building
      // the day after the inclusive end date handles 23/25-hour DST days.
      final start = DateTime(
        selected.start.year,
        selected.start.month,
        selected.start.day,
      ).millisecondsSinceEpoch;
      final end = DateTime(
        selected.end.year,
        selected.end.month,
        selected.end.day + 1,
      ).millisecondsSinceEpoch;
      final currentContext = await api.context();
      if (!mounted) return;
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (_) => _GpsRangeDeletionDialog(
          jobId: widget.jobId,
          jobName: widget.jobName,
          startDate: selected.start,
          endDate: selected.end,
          start: start,
          end: end,
          timezone: currentContext.timezone,
        ),
      );
      if (confirmed == true && mounted) {
        ref.invalidate(timerSnapshotProvider);
        _load();
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'GPS history could not be prepared. No data was deleted.',
            ),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(timerVersionsProvider, (_, next) {
      if (next.hasValue) {
        _load();
      }
    });
    return Scaffold(
      appBar: AppBar(
        title: const Text('Work history'),
        actions: [
          IconButton(
            tooltip: 'Refresh work history',
            onPressed: _busy ? null : () => _load(),
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: 'Change history, including deleted entries',
            icon: const Icon(Icons.history),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => HistoryAuditScreen(jobId: widget.jobId),
              ),
            ),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text(
            widget.jobName,
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text(
            'Recorded times below use UTC. Open an entry to edit in its saved timezone. Manual changes keep a local change history and never create GPS observations.',
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _busy ? null : () => _edit(),
            icon: const Icon(Icons.add),
            label: const Text('Add work entry'),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _busy ? null : _deleteGpsByRange,
            icon: const Icon(Icons.delete_sweep_outlined),
            label: const Text('Delete GPS by date range'),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          if (_busy) const LinearProgressIndicator(),
          if (_page != null && _entries.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 32),
              child: Text('No work entries yet.'),
            ),
          for (final entry in _entries)
            Card(
              child: ListTile(
                leading: Icon(
                  entry.end == null
                      ? Icons.timer_outlined
                      : entry.needsReview
                      ? Icons.warning_amber_rounded
                      : Icons.schedule,
                ),
                title: Text(
                  entry.end == null
                      ? 'Running · clock out before editing'
                      : reportDuration('${entry.end! - entry.start}'),
                ),
                subtitle: Text(
                  '${historyUtc(entry.start)}\n${entry.end == null ? 'Still running' : historyUtc(entry.end!)}\n${entry.timezone} · ${entry.origin.toLowerCase()}${entry.needsReview ? ' · needs review' : ''}',
                ),
                isThreeLine: true,
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _edit(entry),
              ),
            ),
          if (_page?.next != null)
            OutlinedButton(
              onPressed: _busy ? null : () => _load(more: true),
              child: const Text('Load older entries'),
            ),
        ],
      ),
    );
  }
}

class _GpsRangeDeletionDialog extends ConsumerStatefulWidget {
  const _GpsRangeDeletionDialog({
    required this.jobId,
    required this.jobName,
    required this.startDate,
    required this.endDate,
    required this.start,
    required this.end,
    required this.timezone,
  });
  final String jobId, jobName, timezone;
  final DateTime startDate, endDate;
  final int start, end;

  @override
  ConsumerState<_GpsRangeDeletionDialog> createState() =>
      _GpsRangeDeletionDialogState();
}

class _GpsRangeDeletionDialogState
    extends ConsumerState<_GpsRangeDeletionDialog> {
  String? _scopeJobId;
  LocationRangeDeletionPreview? _preview;
  bool _loading = false, _pending = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _scopeJobId = widget.jobId;
    _refreshPreview();
  }

  Future<void> _refreshPreview() async {
    if (_loading || _pending) return;
    final scope = _scopeJobId;
    setState(() {
      _loading = true;
      _preview = null;
      _error = null;
    });
    try {
      final preview = await ref
          .read(historyApiProvider)
          .previewLocationRangeDeletion(
            jobId: scope,
            start: widget.start,
            end: widget.end,
            timezone: widget.timezone,
          );
      if (mounted && scope == _scopeJobId) {
        setState(() => _preview = preview);
      }
    } catch (_) {
      if (mounted && scope == _scopeJobId) {
        setState(
          () => _error =
              'The point count could not be loaded. No data was deleted.',
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _selectScope(String? jobId) {
    if (_pending || jobId == _scopeJobId) return;
    setState(() {
      _scopeJobId = jobId;
      _preview = null;
    });
    _refreshPreview();
  }

  String _calendarDate(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-'
      '${value.month.toString().padLeft(2, '0')}-'
      '${value.day.toString().padLeft(2, '0')}';

  @override
  Widget build(BuildContext context) {
    ref.listen(timerVersionsProvider, (_, next) {
      if (next.hasValue && !_pending) _refreshPreview();
    });
    final preview = _preview;
    final count = preview?.pointCount ?? 0;
    final label = count == 1 ? '1 GPS point' : '$count GPS points';
    return PopScope(
      canPop: !_pending,
      child: AlertDialog(
        title: const Text('Delete GPS history?'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                '${_calendarDate(widget.startDate)} through '
                '${_calendarDate(widget.endDate)} · ${widget.timezone}',
              ),
              const SizedBox(height: 8),
              RadioGroup<String?>(
                groupValue: _scopeJobId,
                onChanged: (value) {
                  if (!_loading && !_pending) {
                    _selectScope(value);
                  }
                },
                child: Column(
                  children: [
                    RadioListTile<String?>(
                      contentPadding: EdgeInsets.zero,
                      value: widget.jobId,
                      enabled: !_loading && !_pending,
                      title: Text('This job only · ${widget.jobName}'),
                    ),
                    RadioListTile<String?>(
                      contentPadding: EdgeInsets.zero,
                      value: null,
                      enabled: !_loading && !_pending,
                      title: const Text('All jobs'),
                    ),
                  ],
                ),
              ),
              if (_loading) const LinearProgressIndicator(),
              if (_error != null) ...[
                Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
                TextButton(
                  onPressed: _loading || _pending ? null : _refreshPreview,
                  child: const Text('Refresh point count'),
                ),
              ],
              if (!_loading && _error == null && preview != null) ...[
                Text('Matching saved GPS points: $label.'),
                const SizedBox(height: 8),
                Text(
                  count == 0
                      ? 'No points match now. A deletion marker will still be saved so an older backup cannot silently restore points in these dates.'
                      : 'Only GPS points are removed. Work entries and timesheets remain.',
                ),
                const SizedBox(height: 8),
                const Text(
                  'Existing exported files and backup files are not erased. Older backups that contain these points will require an explicit restore choice.',
                ),
                TextButton(
                  onPressed: _loading || _pending ? null : _refreshPreview,
                  child: const Text('Refresh point count'),
                ),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: _pending ? null : () => Navigator.pop(context, false),
            child: Text(AppMessage.cancel.of(context)),
          ),
          Theme(
            data: Theme.of(context).copyWith(
              colorScheme: Theme.of(context).colorScheme.copyWith(
                primary: Theme.of(context).colorScheme.error,
                onPrimary: Theme.of(context).colorScheme.onError,
              ),
            ),
            child: BusinessCommand(
              label: 'Delete GPS points',
              enabled: !_loading && !_pending && preview != null,
              onPendingChanged: (value) {
                if (mounted) setState(() => _pending = value);
                if (!value) _refreshPreview();
              },
              create: () {
                final capturedPreview = _preview!;
                final capturedScope = _scopeJobId;
                final id = newBusinessId();
                return () => ref
                    .read(historyApiProvider)
                    .deleteLocationRange(
                      id,
                      capturedPreview.version,
                      jobId: capturedScope,
                      start: widget.start,
                      end: widget.end,
                      timezone: widget.timezone,
                    );
              },
              onCommitted: () => Navigator.pop(context, true),
            ),
          ),
        ],
      ),
    );
  }
}
