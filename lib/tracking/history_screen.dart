import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../business/business_api.dart';
import '../business/business_command.dart';
import '../business/report_screen.dart';
import 'history_api.dart';
import 'timer_providers.dart';

String historyUtc(int instant) {
  try {
    return '${DateTime.fromMillisecondsSinceEpoch(instant, isUtc: true).toIso8601String()} UTC';
  } on ArgumentError {
    return '$instant milliseconds since Unix epoch';
  }
}

class HistoryScreen extends ConsumerStatefulWidget {
  const HistoryScreen({super.key, required this.jobId, required this.jobName});
  final String jobId, jobName;
  @override
  ConsumerState<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends ConsumerState<HistoryScreen> with WidgetsBindingObserver {
  final _entries = <HistoryEntry>[];
  HistoryData? _page;
  bool _busy = false;
  String? _error;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() { WidgetsBinding.instance.removeObserver(this); super.dispose(); }
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load({bool more = false}) async {
    if (_busy) return;
    setState(() { _busy = true; _error = null; });
    try {
      final page = await ref.read(historyApiProvider).history(widget.jobId,
        before: more ? _page?.next : null);
      if (!mounted) return;
      if (more && _page?.version != page.version) {
        setState(() => _error = 'Work changed while loading. Refresh the list before loading more.');
      } else {
        setState(() {
          if (!more) _entries.clear();
          _entries.addAll(page.entries);
          _page = page;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _error = 'Work history could not be loaded. Your saved work is unchanged.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _edit([HistoryEntry? entry]) async {
    await Navigator.push<void>(context, MaterialPageRoute(builder: (_) => HistoryEditor(
      jobId: widget.jobId, sessionId: entry?.id,
    )));
    if (mounted) _load();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(timerVersionsProvider, (_, next) { if (next.hasValue) _load(); });
    return Scaffold(
    appBar: AppBar(title: const Text('Work history'), actions: [
      IconButton(tooltip: 'Refresh work history', onPressed: _busy ? null : () => _load(), icon: const Icon(Icons.refresh)),
      IconButton(tooltip: 'Change history, including deleted entries', icon: const Icon(Icons.history),
        onPressed: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => HistoryAuditScreen(jobId: widget.jobId)))),
    ]),
    body: ListView(padding: const EdgeInsets.all(20), children: [
      Text(widget.jobName, style: Theme.of(context).textTheme.headlineSmall),
      const SizedBox(height: 8),
      const Text('Recorded times below use UTC. Open an entry to edit in its saved timezone. Manual changes keep a local change history and never create GPS observations.'),
      const SizedBox(height: 16),
      FilledButton.icon(onPressed: _busy ? null : () => _edit(), icon: const Icon(Icons.add), label: const Text('Add work entry')),
      if (_error != null) Padding(padding: const EdgeInsets.symmetric(vertical: 16), child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
      if (_busy) const LinearProgressIndicator(),
      if (_page != null && _entries.isEmpty) const Padding(padding: EdgeInsets.symmetric(vertical: 32), child: Text('No work entries yet.')),
      for (final entry in _entries) Card(child: ListTile(
        leading: Icon(entry.end == null ? Icons.timer_outlined : entry.needsReview ? Icons.warning_amber_rounded : Icons.schedule),
        title: Text(entry.end == null ? 'Running · clock out before editing' : reportDuration('${entry.end! - entry.start}')),
        subtitle: Text('${historyUtc(entry.start)}\n${entry.end == null ? 'Still running' : historyUtc(entry.end!)}\n${entry.timezone} · ${entry.origin.toLowerCase()}${entry.needsReview ? ' · needs review' : ''}'),
        isThreeLine: true,
        trailing: const Icon(Icons.chevron_right),
        onTap: () => _edit(entry),
      )),
      if (_page?.next != null) OutlinedButton(onPressed: _busy ? null : () => _load(more: true), child: const Text('Load older entries')),
    ]),
  );
  }
}

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
  bool get _editable => widget.sessionId == null || (_original != null && _original!.end != null);
  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _start.dispose(); _end.dispose(); _zone.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() { _loading = true; _error = null; _reviewed = false; });
    try {
      final api = ref.read(historyApiProvider);
      int start, end;
      if (widget.sessionId == null) {
        final context = await api.context();
        final data = await api.history(widget.jobId, limit: 1);
        if (!mounted) return;
        _original = null; _version = data.version; _zone.text = context.timezone;
        start = context.now - 3600000; end = context.now;
      } else {
        final detail = await api.detail(widget.jobId, _sessionId);
        if (!mounted) return;
        _original = detail.entry; _version = detail.version;
        if (_original == null || _original!.end == null) {
          if (mounted) setState(() => _loading = false);
          return;
        }
        _zone.text = _original!.timezone;
        start = _original!.start; end = _original!.end!;
      }
      final bounds = await Future.wait([api.format(start, _zone.text), api.format(end, _zone.text)]);
      if (!mounted) return;
      _start.text = bounds[0].localTime; _end.text = bounds[1].localTime;
      await _resolve(preferredStart: start, preferredEnd: end);
    } catch (_) {
      if (mounted) setState(() => _error = 'The entry could not be loaded. Retry without changing saved work.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _changed(String _) => setState(() {
    _reviewed = false; _starts = []; _ends = []; _selectedStart = null; _selectedEnd = null;
  });

  Future<void> _resolve({int? preferredStart, int? preferredEnd}) async {
    if (_resolving || _pending) return;
    if (preferredStart == null && !(_form.currentState?.validate() ?? false)) return;
    setState(() { _resolving = true; _reviewed = false; _error = null; });
    try {
      final api = ref.read(historyApiProvider);
      final choices = await Future.wait([api.resolve(_start.text.trim(), _zone.text.trim()), api.resolve(_end.text.trim(), _zone.text.trim())]);
      if (!mounted) return;
      setState(() {
        _starts = choices[0]; _ends = choices[1];
        _selectedStart = _starts.where((c) => c.instant == preferredStart).firstOrNull ?? (_starts.length == 1 ? _starts.single : null);
        _selectedEnd = _ends.where((c) => c.instant == preferredEnd).firstOrNull ?? (_ends.length == 1 ? _ends.single : null);
        _reviewed = true;
        if (_starts.isEmpty || _ends.isEmpty) _error = 'This local time does not exist in the selected timezone. Choose a time outside the clock change or skipped date.';
      });
    } catch (_) {
      if (mounted) setState(() => _error = 'Check the full date, time and IANA timezone. Times must include seconds and three millisecond digits.');
    } finally {
      if (mounted) setState(() => _resolving = false);
    }
  }

  bool _sameEntry(HistoryEntry? a, HistoryEntry? b) => a?.id == b?.id && a?.start == b?.start && a?.end == b?.end && a?.timezone == b?.timezone && a?.offsetSeconds == b?.offsetSeconds && a?.origin == b?.origin && a?.needsReview == b?.needsReview;

  Future<void> _refreshVersion() async {
    setState(() { _resolving = true; _error = null; });
    try {
      final api = ref.read(historyApiProvider);
      if (widget.sessionId == null) {
        final data = await api.history(widget.jobId, limit: 1);
        if (mounted) setState(() => _version = data.version);
      } else {
        final detail = await api.detail(widget.jobId, _sessionId);
        if (!mounted) return;
        setState(() {
          if (_sameEntry(_original, detail.entry)) {
            _version = detail.version;
          } else {
            _error = 'The saved entry changed. Your draft is kept. Reload the saved entry and review it before making another correction.';
          }
        });
      }
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not check the saved entry. Your draft is kept.');
    } finally {
      if (mounted) setState(() => _resolving = false);
    }
  }

  Future<void> _reload() async {
    final confirmed = await showDialog<bool>(context: context, builder: (dialog) => AlertDialog(
      title: const Text('Reload saved entry?'), content: const Text('This replaces your unsaved draft with the current saved entry.'),
      actions: [TextButton(onPressed: () => Navigator.pop(dialog, false), child: const Text('Keep draft')),
        FilledButton(onPressed: () => Navigator.pop(dialog, true), child: const Text('Reload'))],
    ));
    if (confirmed == true && mounted) _load();
  }

  void _committed() {
    ref.invalidate(timerSnapshotProvider);
    Navigator.pop(context);
  }

  Future<void> _delete() async {
    bool pending = false;
    await showDialog<void>(context: context, barrierDismissible: false, builder: (dialog) => StatefulBuilder(builder: (dialog, update) => AlertDialog(
      title: Text('Delete work entry?', style: TextStyle(color: Theme.of(context).colorScheme.error)),
      content: const Text('This removes the work interval and its GPS points. Local change history is retained. Previously exported files and prepared invoices are unchanged.'),
      actions: [TextButton(onPressed: pending ? null : () => Navigator.pop(dialog), child: const Text('Cancel')),
        Theme(data: Theme.of(context).copyWith(colorScheme: Theme.of(context).colorScheme.copyWith(
          primary: Theme.of(context).colorScheme.error, onPrimary: Theme.of(context).colorScheme.onError)),
          child: BusinessCommand(label: 'Delete entry', onPendingChanged: (value) {
            update(() => pending = value);
            setState(() => _pending = value);
          }, create: () {
            final id = newBusinessId(); final version = _version!;
            return () => ref.read(historyApiProvider).delete(id, version, _sessionId);
          }, onCommitted: () { Navigator.pop(dialog); _committed(); }))],
    )));
  }

  Widget _choices(String label, List<HistoryTimeChoice> values, HistoryTimeChoice? selected, ValueChanged<HistoryTimeChoice?> change) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(label, style: Theme.of(context).textTheme.titleSmall),
      if (values.length > 1) const Text('This time occurs twice. Choose the intended UTC offset.'),
      if (values.length == 1) Text(values.single.label),
      if (values.length > 1) for (final value in values)
        Semantics(selected: selected == value, child: OutlinedButton.icon(
          icon: Icon(selected == value ? Icons.check_circle : Icons.radio_button_unchecked),
          label: Text(value.label), onPressed: _locked ? null : () => change(value),
        )),
      if (selected != null) Text(historyUtc(selected.instant)),
      const SizedBox(height: 16),
    ],
  );

  @override
  Widget build(BuildContext context) {
    final start = _selectedStart, end = _selectedEnd;
    final valid = _reviewed && start != null && end != null && end.instant > start.instant;
    return PopScope(canPop: !_pending, child: Scaffold(
      appBar: AppBar(title: Text(widget.sessionId == null ? 'Add work entry' : 'Work entry'), actions: [
        if (widget.sessionId != null) IconButton(tooltip: 'Entry change history', onPressed: _locked ? null : () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => HistoryRevisionsScreen(jobId: widget.jobId, sessionId: _sessionId))), icon: const Icon(Icons.history)),
      ]),
      body: _loading ? const Center(child: CircularProgressIndicator()) : !_editable ? ListView(padding: const EdgeInsets.all(20), children: [
        Text(_original == null ? 'This entry is no longer available. Its change history may still be viewed.' : 'This entry is running. Clock out before correcting or deleting it.'),
        TextButton(onPressed: _load, child: const Text('Refresh entry')),
      ]) : Form(key: _form, child: ListView(padding: const EdgeInsets.all(20), children: [
        const Text('Manual work entry', style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        const Text('Enter local times in the timezone below. Overlapping work is rejected across every job. Correcting times prunes GPS points outside the interval; extending work never creates observations.'),
        if (_original?.needsReview == true) const Padding(padding: EdgeInsets.only(top: 12), child: Text('This entry needs review after a tracking conflict. Saving a correction resolves its review flag.')),
        const SizedBox(height: 20),
        TextFormField(controller: _zone, enabled: !_locked, onChanged: _changed, decoration: const InputDecoration(labelText: 'IANA timezone', helperText: 'For example America/New_York or UTC'), validator: (v) => v == null || v.trim().isEmpty ? 'Enter a timezone' : null),
        const SizedBox(height: 16),
        for (final field in [(_start, 'Start local time'), (_end, 'End local time')]) Padding(padding: const EdgeInsets.only(bottom: 16), child: TextFormField(
          controller: field.$1, enabled: !_locked, onChanged: _changed, decoration: InputDecoration(labelText: field.$2, helperText: 'YYYY-MM-DDTHH:mm:ss.SSS'),
          validator: (v) => v != null && RegExp(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}$').hasMatch(v.trim()) ? null : 'Enter the full date and time, including milliseconds',
        )),
        OutlinedButton(onPressed: _locked ? null : () => _resolve(), child: Text(_resolving ? 'Checking times…' : 'Review times and UTC offsets')),
        const SizedBox(height: 16),
        if (_reviewed) ...[
          _choices('Start', _starts, start, (v) => setState(() => _selectedStart = v)),
          _choices('End', _ends, end, (v) => setState(() => _selectedEnd = v)),
          if (start != null && end != null) Text(end.instant > start.instant ? 'Duration · ${reportDuration('${end.instant - start.instant}')}' : 'End must be after start.', style: TextStyle(color: valid ? null : Theme.of(context).colorScheme.error)),
          const SizedBox(height: 16),
        ],
        if (_error != null) Padding(padding: const EdgeInsets.only(bottom: 16), child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
        BusinessCommand(label: widget.sessionId == null ? 'Save work entry' : 'Save correction', enabled: valid && !_locked && _version != null,
          onPendingChanged: (v) => setState(() => _pending = v),
          create: () {
            final id = newBusinessId(); final version = _version!;
            final draft = HistoryDraft(sessionId: _sessionId, jobId: widget.jobId, start: start!.instant, end: end!.instant, timezone: _zone.text.trim(), offsetSeconds: start.offsetSeconds);
            final api = ref.read(historyApiProvider);
            return () => widget.sessionId == null ? api.create(id, version, draft) : api.correct(id, version, draft);
          }, onCommitted: _committed),
        TextButton(onPressed: _locked ? null : _refreshVersion, child: const Text('Check for changes · keep draft')),
        if (widget.sessionId != null) ...[
          TextButton(onPressed: _locked ? null : _reload, child: const Text('Reload saved entry')),
          const SizedBox(height: 24),
          TextButton.icon(style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error), onPressed: _locked ? null : _delete,
            icon: const Icon(Icons.delete_outline), label: const Text('Delete work entry')),
        ],
      ])),
    ));
  }
}

class HistoryAuditScreen extends ConsumerStatefulWidget {
  const HistoryAuditScreen({super.key, required this.jobId});
  final String jobId;
  @override
  ConsumerState<HistoryAuditScreen> createState() => _HistoryAuditScreenState();
}

class _HistoryAuditScreenState extends ConsumerState<HistoryAuditScreen> {
  final _events = <HistoryEvent>[];
  HistoryAudit? _page;
  bool _busy = false;
  String? _error;
  @override
  void initState() { super.initState(); _load(); }
  Future<void> _load({bool more = false}) async {
    if (_busy) return;
    setState(() { _busy = true; _error = null; });
    try {
      final page = await ref.read(historyApiProvider).audit(widget.jobId, before: more ? _page?.next : null);
      if (!mounted) return;
      setState(() {
        if (more && _page?.version != page.version) {
          _error = 'Work changed. Refresh change history before loading more.';
        } else {
          if (!more) _events.clear();
          _events.addAll(page.events); _page = page;
        }
      });
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not load change history. Retry to read saved changes.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
  @override
  Widget build(BuildContext context) => Scaffold(appBar: AppBar(title: const Text('Change history'), actions: [
    IconButton(tooltip: 'Refresh changes', onPressed: _busy ? null : () => _load(), icon: const Icon(Icons.refresh)),
  ]), body: ListView(padding: const EdgeInsets.all(20), children: [
    const Text('Includes deleted work entries. Changes are ordered by when they were saved locally. This history is an audit aid, not tamper-proof evidence.'),
    if (_busy) const LinearProgressIndicator(),
    if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
    if (_page != null && _events.isEmpty) const Padding(padding: EdgeInsets.all(24), child: Text('No saved changes yet.')),
    for (final event in _events) _RevisionTile(revision: event.revision, onTap: () => Navigator.push(context, MaterialPageRoute<void>(builder: (_) => HistoryRevisionsScreen(jobId: widget.jobId, sessionId: event.sessionId)))),
    if (_page?.next != null) OutlinedButton(onPressed: _busy ? null : () => _load(more: true), child: const Text('Load older changes')),
  ]));
}

class HistoryRevisionsScreen extends ConsumerStatefulWidget {
  const HistoryRevisionsScreen({super.key, required this.jobId, required this.sessionId});
  final String jobId, sessionId;
  @override
  ConsumerState<HistoryRevisionsScreen> createState() => _HistoryRevisionsScreenState();
}

class _HistoryRevisionsScreenState extends ConsumerState<HistoryRevisionsScreen> {
  late Future<List<HistoryRevision>> _data = ref.read(historyApiProvider).revisions(widget.jobId, widget.sessionId);
  @override
  Widget build(BuildContext context) => Scaffold(appBar: AppBar(title: const Text('Entry changes')),
    body: FutureBuilder(future: _data, builder: (context, snapshot) {
      if (snapshot.hasError) return Center(child: TextButton(onPressed: () => setState(() => _data = ref.read(historyApiProvider).revisions(widget.jobId, widget.sessionId)), child: const Text('Retry entry changes')));
      if (!snapshot.hasData) return const Center(child: CircularProgressIndicator());
      return ListView(padding: const EdgeInsets.all(20), children: [
        const Text('Corrections and deletions keep their local revision history. Prepared invoices and exported copies keep the values captured when they were created.'),
        if (snapshot.data!.isEmpty) const Text('No retained changes for this entry.'),
        for (final revision in snapshot.data!) _RevisionTile(revision: revision),
      ]);
    }),
  );
}

class _RevisionTile extends StatelessWidget {
  const _RevisionTile({required this.revision, this.onTap});
  final HistoryRevision revision;
  final VoidCallback? onTap;
  String _summary() {
    try {
      final data = jsonDecode(revision.payload ?? '{}') as Map<String, dynamic>;
      final start = data['start_ms'], end = data['end_ms'];
      return start is int ? '${historyUtc(start)}\n${end is int ? historyUtc(end) : 'Running when saved'}\n${data['timezone'] ?? ''}' : revision.deleted ? 'Work entry removed' : 'No retained time details';
    } catch (_) { return 'Saved change details unavailable'; }
  }
  @override
  Widget build(BuildContext context) => Card(child: ListTile(
    leading: Icon(revision.deleted ? Icons.delete_outline : Icons.edit_outlined),
    title: Text(revision.origin.replaceAll('_', ' ').toLowerCase()),
    subtitle: Text('${historyUtc(revision.changed)}\n${_summary()}'),
    trailing: onTap == null ? null : const Icon(Icons.chevron_right), onTap: onTap,
  ));
}
