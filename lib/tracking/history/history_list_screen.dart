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

String _historyEntrySummary(BuildContext context, HistoryEntry entry) {
  final start = historyUtc(context, entry.start);
  final end = entry.end == null
      ? AppMessage.historyRunningWhenSaved.of(context)
      : historyUtc(context, entry.end!);
  final origin = historyOriginLabel(context, entry.origin);
  final review = entry.needsReview
      ? ' · ${AppMessage.historyNeedsReview.of(context)}'
      : '';
  return '$start\n$end\n${entry.timezone} · $origin$review';
}

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
          () => _error = AppMessage.historyLoadChanged.of(context),
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
          () => _error = AppMessage.historyListLoadFailed.of(context),
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
        helpText: AppMessage.historyGpsSelectDates.of(context),
        saveText: AppMessage.historyGpsReview.of(context),
        confirmText: AppMessage.historyGpsReview.of(context),
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
          SnackBar(
            content: Text(AppMessage.historyGpsPrepareFailed.of(context)),
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
        title: Text(AppMessage.historyListTitle.of(context)),
        actions: [
          IconButton(
            tooltip: AppMessage.historyRefresh.of(context),
            onPressed: _busy ? null : () => _load(),
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: AppMessage.historyOpenAudit.of(context),
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
          Text(AppMessage.historyListGuidance.of(context)),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _busy ? null : () => _edit(),
            icon: const Icon(Icons.add),
            label: Text(AppMessage.historyAddWorkEntry.of(context)),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _busy ? null : _deleteGpsByRange,
            icon: const Icon(Icons.delete_sweep_outlined),
            label: Text(AppMessage.historyDeleteGpsByRange.of(context)),
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
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 32),
              child: Text(AppMessage.historyNoEntries.of(context)),
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
                      ? AppMessage.historyRunningClockOut.of(context)
                      : reportDuration(context, '${entry.end! - entry.start}'),
                ),
                subtitle: Text(_historyEntrySummary(context, entry)),
                isThreeLine: true,
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _edit(entry),
              ),
            ),
          if (_page?.next != null)
            OutlinedButton(
              onPressed: _busy ? null : () => _load(more: true),
              child: Text(AppMessage.historyLoadOlderEntries.of(context)),
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
          () => _error = AppMessage.historyPointCountFailed.of(context),
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
      MaterialLocalizations.of(context).formatMediumDate(value);

  @override
  Widget build(BuildContext context) {
    ref.listen(timerVersionsProvider, (_, next) {
      if (next.hasValue && !_pending) _refreshPreview();
    });
    final preview = _preview;
    final count = preview?.pointCount ?? 0;
    final label = count == 1
        ? AppMessage.historyOneGpsPoint.of(context)
        : AppMessage.historyManyGpsPoints.format(context, {
            'count': MaterialLocalizations.of(context).formatDecimal(count),
          });
    return PopScope(
      canPop: !_pending,
      child: AlertDialog(
        title: Text(AppMessage.historyDeleteGpsTitle.of(context)),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                AppMessage.historyGpsDateRange.format(context, {
                  'first': _calendarDate(widget.startDate),
                  'last': _calendarDate(widget.endDate),
                  'timezone': widget.timezone,
                }),
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
                      title: Text(AppMessage.historyThisJobOnly.format(context, {
                        'job': widget.jobName,
                      })),
                    ),
                    RadioListTile<String?>(
                      contentPadding: EdgeInsets.zero,
                      value: null,
                      enabled: !_loading && !_pending,
                      title: Text(AppMessage.allJobs.of(context)),
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
                  child: Text(AppMessage.historyRefreshPointCount.of(context)),
                ),
              ],
              if (!_loading && _error == null && preview != null) ...[
                Text(AppMessage.historyMatchingGpsPoints.format(context, {
                  'label': label,
                })),
                const SizedBox(height: 8),
                Text(
                  count == 0
                      ? AppMessage.historyGpsNoPointsWarning.of(context)
                      : AppMessage.historyGpsOnlyDeletion.of(context),
                ),
                const SizedBox(height: 8),
                Text(AppMessage.historyGpsBackupWarning.of(context)),
                TextButton(
                  onPressed: _loading || _pending ? null : _refreshPreview,
                  child: Text(AppMessage.historyRefreshPointCount.of(context)),
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
              label: AppMessage.historyDeleteGpsPoints.of(context),
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
