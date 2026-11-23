import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/app_localizations.dart';
import '../../business/business_api.dart';
import '../../business/business_command.dart';
import '../../business/report_screen.dart';
import '../history_api.dart';
import '../timer_providers.dart';
import 'history_revisions_screen.dart';
import 'history_time_format.dart';

class HistoryEditor extends ConsumerStatefulWidget {
  const HistoryEditor({super.key, required this.jobId, this.sessionId});
  final String jobId;
  final String? sessionId;
  @override
  ConsumerState<HistoryEditor> createState() => _HistoryEditorState();
}

class _HistoryEditorState extends ConsumerState<HistoryEditor> {
  final _form = GlobalKey<FormState>();
  final _start = TextEditingController();
  final _end = TextEditingController();
  final _zone = TextEditingController();
  late final _sessionId = widget.sessionId ?? newBusinessId();
  HistoryEntry? _original;
  int? _version;
  List<HistoryTimeChoice> _starts = [], _ends = [];
  HistoryTimeChoice? _selectedStart, _selectedEnd;
  bool _loading = true, _resolving = false, _pending = false, _reviewed = false;
  String? _error;
  bool get _locked => _loading || _resolving || _pending;
  bool get _editable =>
      widget.sessionId == null || (_original != null && _original!.end != null);
  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _start.dispose();
    _end.dispose();
    _zone.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
      _reviewed = false;
    });
    try {
      final api = ref.read(historyApiProvider);
      int start, end;
      if (widget.sessionId == null) {
        final context = await api.context();
        final data = await api.history(widget.jobId, limit: 1);
        if (!mounted) {
          return;
        }
        _original = null;
        _version = data.version;
        _zone.text = context.timezone;
        start = context.now - 3600000;
        end = context.now;
      } else {
        final detail = await api.detail(widget.jobId, _sessionId);
        if (!mounted) {
          return;
        }
        _original = detail.entry;
        _version = detail.version;
        if (_original == null || _original!.end == null) {
          if (mounted) {
            setState(() => _loading = false);
          }
          return;
        }
        _zone.text = _original!.timezone;
        start = _original!.start;
        end = _original!.end!;
      }
      final bounds = await Future.wait([
        api.format(start, _zone.text),
        api.format(end, _zone.text),
      ]);
      if (!mounted) {
        return;
      }
      _start.text = bounds[0].localTime;
      _end.text = bounds[1].localTime;
      await _resolve(preferredStart: start, preferredEnd: end);
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = AppMessage.historyEditorLoadFailed.of(context),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _pick(TextEditingController field, {required bool date}) async {
    // This calendar value is an editor aid, never an observed timestamp. Native
    // resolution still validates the chosen local time and any UTC ambiguity.
    final parsed = DateTime.tryParse('${field.text.trim()}Z');
    final value = parsed != null && parsed.year >= 1 && parsed.year <= 9999
        ? parsed
        : DateTime.now().toUtc();
    DateTime? changed;
    if (date) {
      final selected = await showDatePicker(
        context: context,
        initialDate: value,
        firstDate: DateTime(1),
        lastDate: DateTime(9999, 12, 31),
      );
      if (selected != null) {
        changed = DateTime.utc(
          selected.year,
          selected.month,
          selected.day,
          value.hour,
          value.minute,
          value.second,
          value.millisecond,
        );
      }
    } else {
      final selected = await showTimePicker(
        context: context,
        initialTime: TimeOfDay(hour: value.hour, minute: value.minute),
      );
      if (selected != null) {
        changed = DateTime.utc(
          value.year,
          value.month,
          value.day,
          selected.hour,
          selected.minute,
          value.second,
          value.millisecond,
        );
      }
    }
    if (!mounted || changed == null) {
      return;
    }
    field.text = changed.toIso8601String().replaceAll('Z', '');
    _changed(field.text);
  }

  void _changed(String _) => setState(() {
    _reviewed = false;
    _starts = [];
    _ends = [];
    _selectedStart = null;
    _selectedEnd = null;
  });

  Future<void> _resolve({int? preferredStart, int? preferredEnd}) async {
    if (_resolving || _pending) {
      return;
    }
    if (preferredStart == null && !(_form.currentState?.validate() ?? false)) {
      return;
    }
    setState(() {
      _resolving = true;
      _reviewed = false;
      _error = null;
    });
    try {
      final api = ref.read(historyApiProvider);
      final choices = await Future.wait([
        api.resolve(_start.text.trim(), _zone.text.trim()),
        api.resolve(_end.text.trim(), _zone.text.trim()),
      ]);
      if (!mounted) {
        return;
      }
      setState(() {
        _starts = choices[0];
        _ends = choices[1];
        _selectedStart =
            _starts.where((c) => c.instant == preferredStart).firstOrNull ??
            (_starts.length == 1 ? _starts.single : null);
        _selectedEnd =
            _ends.where((c) => c.instant == preferredEnd).firstOrNull ??
            (_ends.length == 1 ? _ends.single : null);
        _reviewed = true;
        if (_starts.isEmpty || _ends.isEmpty) {
          _error = AppMessage.paymentSkippedTime.of(context);
        }
      });
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = AppMessage.legacyInvalidLocalDateTime.of(context),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _resolving = false);
      }
    }
  }

  bool _sameEntry(HistoryEntry? a, HistoryEntry? b) =>
      a?.id == b?.id &&
      a?.start == b?.start &&
      a?.end == b?.end &&
      a?.timezone == b?.timezone &&
      a?.offsetSeconds == b?.offsetSeconds &&
      a?.origin == b?.origin &&
      a?.needsReview == b?.needsReview;

  Future<void> _refreshVersion() async {
    setState(() {
      _resolving = true;
      _error = null;
    });
    try {
      final api = ref.read(historyApiProvider);
      if (widget.sessionId == null) {
        final data = await api.history(widget.jobId, limit: 1);
        if (mounted) {
          setState(() => _version = data.version);
        }
      } else {
        final detail = await api.detail(widget.jobId, _sessionId);
        if (!mounted) {
          return;
        }
        setState(() {
          if (_sameEntry(_original, detail.entry)) {
            _version = detail.version;
          } else {
            _error = AppMessage.historySavedEntryChanged.of(context);
          }
        });
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _error = AppMessage.historyCheckSavedEntryFailed.of(context),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _resolving = false);
      }
    }
  }

  Future<void> _reload() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: Text(AppMessage.historyReloadTitle.of(dialog)),
        content: Text(AppMessage.historyReloadWarning.of(dialog)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialog, false),
            child: Text(AppMessage.historyKeepDraft.of(dialog)),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialog, true),
            child: Text(AppMessage.historyReload.of(dialog)),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) {
      _load();
    }
  }

  void _committed() {
    ref.invalidate(timerSnapshotProvider);
    Navigator.pop(context);
  }

  Future<void> _delete() async {
    bool pending = false;
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialog) => StatefulBuilder(
        builder: (dialog, update) => AlertDialog(
          title: Text(
            AppMessage.historyDeleteTitle.of(dialog),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
          content: Text(AppMessage.historyDeleteWarning.of(dialog)),
          actions: [
            TextButton(
              onPressed: pending ? null : () => Navigator.pop(dialog),
              child: Text(AppMessage.cancel.of(dialog)),
            ),
            Theme(
              data: Theme.of(context).copyWith(
                colorScheme: Theme.of(context).colorScheme.copyWith(
                  primary: Theme.of(context).colorScheme.error,
                  onPrimary: Theme.of(context).colorScheme.onError,
                ),
              ),
              child: BusinessCommand(
                label: AppMessage.historyDeleteWorkEntry.of(dialog),
                onPendingChanged: (value) {
                  update(() => pending = value);
                  setState(() => _pending = value);
                },
                create: () {
                  final id = newBusinessId();
                  final version = _version!;
                  return () => ref
                      .read(historyApiProvider)
                      .delete(id, version, _sessionId);
                },
                onCommitted: () {
                  Navigator.pop(dialog);
                  _committed();
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _choices(
    String label,
    List<HistoryTimeChoice> values,
    HistoryTimeChoice? selected,
    ValueChanged<HistoryTimeChoice?> change,
  ) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text(label, style: Theme.of(context).textTheme.titleSmall),
      if (values.length > 1)
        Text(AppMessage.legacyAmbiguousTime.of(context)),
      if (values.length == 1) Text(values.single.label),
      if (values.length > 1)
        for (final value in values)
          Semantics(
            selected: selected == value,
            child: OutlinedButton.icon(
              icon: Icon(
                selected == value
                    ? Icons.check_circle
                    : Icons.radio_button_unchecked,
              ),
              label: Text(value.label),
              onPressed: _locked ? null : () => change(value),
            ),
          ),
      if (selected != null) Text(historyUtc(context, selected.instant)),
      const SizedBox(height: 16),
    ],
  );

  @override
  Widget build(BuildContext context) {
    final start = _selectedStart, end = _selectedEnd;
    final valid =
        _reviewed &&
        start != null &&
        end != null &&
        end.instant > start.instant;
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: Text(
            widget.sessionId == null
                ? AppMessage.historyAddWorkEntry.of(context)
                : AppMessage.historyWorkEntry.of(context),
          ),
          actions: [
            if (widget.sessionId != null)
              IconButton(
                tooltip: AppMessage.historyAuditTitle.of(context),
                onPressed: _locked
                    ? null
                    : () => Navigator.push(
                        context,
                        MaterialPageRoute<void>(
                          builder: (_) => HistoryRevisionsScreen(
                            jobId: widget.jobId,
                            sessionId: _sessionId,
                          ),
                        ),
                      ),
                icon: const Icon(Icons.history),
              ),
          ],
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : !_editable
            ? ListView(
                padding: const EdgeInsets.all(20),
                children: [
                  Text(
                    _original == null
                        ? AppMessage.historyEntryUnavailable.of(context)
                        : AppMessage.historyEntryRunning.of(context),
                  ),
                  TextButton(
                    onPressed: _load,
                    child: Text(AppMessage.historyRefreshEntry.of(context)),
                  ),
                ],
              )
            : Form(
                key: _form,
                child: ListView(
                  padding: const EdgeInsets.all(20),
                  children: [
                    Text(
                      AppMessage.historyManualWorkEntry.of(context),
                      style: TextStyle(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 8),
                    Text(AppMessage.historyEditorGuidance.of(context)),
                    if (_original?.needsReview == true)
                      Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: Text(
                          AppMessage.historyEntryNeedsReview.of(context),
                        ),
                      ),
                    const SizedBox(height: 20),
                    TextFormField(
                      controller: _zone,
                      enabled: !_locked,
                      onChanged: _changed,
                      decoration: InputDecoration(
                        labelText: AppMessage.ianaTimezoneHint.of(context),
                        helperText: AppMessage.timezoneFieldHelp.of(context),
                      ),
                      validator: (v) => v == null || v.trim().isEmpty
                          ? AppMessage.historyEnterTimezone.of(context)
                          : null,
                    ),
                    const SizedBox(height: 16),
                    for (final field in [
                      (_start, AppMessage.historyStartLocalTime.of(context)),
                      (_end, AppMessage.historyEndLocalTime.of(context)),
                    ])
                      Padding(
                        padding: const EdgeInsets.only(bottom: 16),
                        child: TextFormField(
                          controller: field.$1,
                          enabled: !_locked,
                          onChanged: _changed,
                          decoration: InputDecoration(
                            labelText: field.$2,
                            helperText:
                                AppMessage.legacyLocalTimestampFormat.of(context),
                            suffixIcon: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                IconButton(
                                  tooltip:
                                  AppMessage.historyChooseDate.format(context, {
                                    'label': field.$2,
                                  }),
                                  onPressed: _locked
                                      ? null
                                      : () => _pick(field.$1, date: true),
                                  icon: const Icon(
                                    Icons.calendar_today_outlined,
                                  ),
                                ),
                                IconButton(
                                  tooltip:
                                  AppMessage.historyChooseClockTime.format(context, {
                                    'label': field.$2,
                                  }),
                                  onPressed: _locked
                                      ? null
                                      : () => _pick(field.$1, date: false),
                                  icon: const Icon(Icons.access_time),
                                ),
                              ],
                            ),
                          ),
                          validator: (v) =>
                              v != null &&
                                  RegExp(
                                    r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}$',
                                  ).hasMatch(v.trim())
                              ? null
                              : AppMessage.historyEnterFullTimestamp.of(context),
                        ),
                      ),
                    OutlinedButton(
                      onPressed: _locked ? null : () => _resolve(),
                      child: Text(
                        _resolving
                            ? AppMessage.legacyResolvingTimes.of(context)
                            : AppMessage.legacyReviewTimes.of(context),
                      ),
                    ),
                    const SizedBox(height: 16),
                    if (_reviewed) ...[
                      _choices(
                        AppMessage.legacyStart.of(context),
                        _starts,
                        start,
                        (v) => setState(() => _selectedStart = v),
                      ),
                      _choices(
                        AppMessage.legacyEnd.of(context),
                        _ends,
                        end,
                        (v) => setState(() => _selectedEnd = v),
                      ),
                      if (start != null && end != null)
                        Text(
                          end.instant > start.instant
                              ? AppMessage.historyDuration.format(context, {
                                  'duration': reportDuration(
                                    context,
                                    '${end.instant - start.instant}',
                                  ),
                                })
                              : AppMessage.historyEndAfterStart.of(context),
                          style: TextStyle(
                            color: valid
                                ? null
                                : Theme.of(context).colorScheme.error,
                          ),
                        ),
                      const SizedBox(height: 16),
                    ],
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 16),
                        child: Text(
                          _error!,
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                      ),
                    BusinessCommand(
                      label: widget.sessionId == null
                          ? AppMessage.historySaveWorkEntry.of(context)
                          : AppMessage.historySaveCorrection.of(context),
                      enabled: valid && !_locked && _version != null,
                      onPendingChanged: (v) => setState(() => _pending = v),
                      create: () {
                        final id = newBusinessId();
                        final version = _version!;
                        final draft = HistoryDraft(
                          sessionId: _sessionId,
                          jobId: widget.jobId,
                          start: start!.instant,
                          end: end!.instant,
                          timezone: _zone.text.trim(),
                          offsetSeconds: start.offsetSeconds,
                        );
                        final api = ref.read(historyApiProvider);
                        return () => widget.sessionId == null
                            ? api.create(id, version, draft)
                            : api.correct(id, version, draft);
                      },
                      onCommitted: _committed,
                    ),
                    TextButton(
                      onPressed: _locked ? null : _refreshVersion,
                      child: Text(AppMessage.historyCheckKeepDraft.of(context)),
                    ),
                    if (widget.sessionId != null) ...[
                      TextButton(
                        onPressed: _locked ? null : _reload,
                        child: Text(AppMessage.historyReloadSavedEntry.of(context)),
                      ),
                      const SizedBox(height: 24),
                      TextButton.icon(
                        style: TextButton.styleFrom(
                          foregroundColor: Theme.of(context).colorScheme.error,
                        ),
                        onPressed: _locked ? null : _delete,
                        icon: const Icon(Icons.delete_outline),
                        label: Text(AppMessage.historyDeleteWorkEntry.of(context)),
                      ),
                    ],
                  ],
                ),
              ),
      ),
    );
  }
}
