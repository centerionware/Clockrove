import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'job_screen.dart';
import 'quote_scope_api.dart';
import 'quote_api.dart';
import 'tax_rate.dart';

class QuoteScopeScreen extends ConsumerStatefulWidget {
  const QuoteScopeScreen({
    super.key,
    required this.quoteId,
    required this.revision,
  });
  final String quoteId;
  final int revision;
  @override
  ConsumerState<QuoteScopeScreen> createState() => _QuoteScopeScreenState();
}

class _QuoteScopeScreenState extends ConsumerState<QuoteScopeScreen> {
  late Future<QuoteScopeData> _data = ref
      .read(quoteScopeApiProvider)
      .read(widget.quoteId, widget.revision);
  void _refresh() => setState(() {
    _data = ref
        .read(quoteScopeApiProvider)
        .read(widget.quoteId, widget.revision);
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Plan accepted scope'),
      actions: [
        IconButton(
          tooltip: 'Refresh scope groups',
          onPressed: _refresh,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: FutureBuilder<QuoteScopeData>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(
            child: TextButton(
              onPressed: _refresh,
              child: const Text('Retry accepted scope'),
            ),
          );
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final data = snapshot.data!, source = snapshot.data!.issued.draft;
        if (data.issued.decision != 'ACCEPTED') {
          return const Center(
            child: Text(
              'Record explicit local acceptance against this revision before planning its scope.',
            ),
          );
        }
        return ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text(
              '${data.issued.number} · Revision ${data.issued.revision}',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            Text(source.title),
            const SizedBox(height: 12),
            const Text(
              'Review each compatible group. Fixed-price items use a task-rate job; estimated hours use an hourly job. Different agreed hourly rates have separate groups.',
            ),
            const Text(
              'Planning creates no worked hours, completion, invoice or payment. Previously billed work remains excluded from new invoices.',
            ),
            if (data.historical)
              Text(
                'This is a historical accepted revision. Latest issued revision: ${data.latestRevision}.',
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            const SizedBox(height: 16),
            for (final group in quoteScopeGroups(source))
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        group.kind == 'ITEM'
                            ? 'Fixed-price items'
                            : 'Estimated hours',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      if (group.rate != null)
                        Text(
                          'Agreed rate ${source.currency} ${money(group.rate, data.issued.digits)}/hour',
                        ),
                      for (final line in group.lines)
                        Text(
                          '${line.title} · ${taskQuantity(line.quantity)} ${group.kind == 'HOURS' ? 'estimated hours' : 'units'}',
                        ),
                      const SizedBox(height: 12),
                      FilledButton(
                        onPressed: () async {
                          await Navigator.push<bool>(
                            context,
                            MaterialPageRoute<bool>(
                              builder: (_) => _ScopeFormScreen(
                                initial: data,
                                groupKey: group.key,
                              ),
                            ),
                          );
                          if (mounted) {
                            _refresh();
                          }
                        },
                        child: const Text('Review group plan'),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        );
      },
    ),
  );
}

class _ScopeFormScreen extends ConsumerStatefulWidget {
  const _ScopeFormScreen({required this.initial, required this.groupKey});
  final QuoteScopeData initial;
  final String groupKey;
  @override
  ConsumerState<_ScopeFormScreen> createState() => _ScopeFormState();
}

class _ScopeFormState extends ConsumerState<_ScopeFormScreen> {
  final _form = GlobalKey<FormState>();
  final _note = TextEditingController();
  late final TextEditingController _name;
  late QuoteScopeData _data;
  late final String _newJobId;
  String? _targetId, _message;
  final _titles = <String, TextEditingController>{};
  final _newTaskIds = <String, String>{};
  final _existing = <String, String>{};
  final _selected = <String>{};
  bool _pending = false,
      _reading = false,
      _readFailed = false,
      _historical = false,
      _differences = false;
  QuoteScopeDraft? _reviewed;
  int? _reviewedVersion;
  QuoteScopeGroup get _group =>
      quoteScopeGroups(_data.issued.draft)
          .singleWhere((group) => group.key == widget.groupKey);
  QuoteScopeBinding? _binding(String line) =>
      _data.bindings.where((binding) => binding.lineId == line).firstOrNull;
  QuoteScopeTask? _task(String? id) =>
      _data.tasks.where((task) => task.id == id).firstOrNull;
  bool get _enabled => !_pending && !_reading;
  bool get _jobDiffers {
    final target = _data.target;
    return target != null &&
        (target.customerId != _data.issued.draft.customerId ||
            target.taxUnits != _data.issued.draft.taxUnits ||
            _group.kind == 'HOURS' && target.rate != _group.rate);
  }

  String? get _incompatible {
    final target = _data.target;
    if (_targetId == null) {
      return null;
    }
    if (target == null) {
      return 'This job is unavailable. Retained mappings cannot recreate deleted work.';
    }
    if (target.archived) {
      return 'Unarchive this job before creating or linking planned scope.';
    }
    if (target.currency != _data.issued.draft.currency ||
        target.digits != _data.issued.digits) {
      return 'The job currency or precision differs from this accepted quote. Choose a compatible job.';
    }
    if (target.mode != _group.mode) {
      return 'Choose a ${_group.mode == 'TASK' ? 'task-rate' : 'hourly'} job for this group.';
    }
    final lines = {for (final line in _data.issued.draft.lines) line.id: line};
    if (_group.kind == 'HOURS' &&
        _data.bindings.any(
          (binding) =>
              binding.jobId == _targetId &&
              binding.kind == 'HOURS' &&
              lines[binding.lineId] != null &&
              (lines[binding.lineId]!.kind != 'HOURS' ||
                  lines[binding.lineId]!.unitPrice != _group.rate),
        )) {
      return 'This quote already maps a different hourly group to this job. Choose another compatible job.';
    }
    return null;
  }

  @override
  void initState() {
    super.initState();
    _data = widget.initial;
    _newJobId = newBusinessId();
    _name = TextEditingController(
      text:
          '${_data.issued.draft.title} · ${_group.kind == 'ITEM' ? 'tasks' : 'hourly work'}',
    );
    for (final line in _group.lines) {
      _titles[line.id] = TextEditingController(text: line.title);
      _newTaskIds[line.id] = newBusinessId();
    }
    final groupIds = _group.lines.map((line) => line.id).toSet();
    final prior = _data.bindings
        .where((binding) => groupIds.contains(binding.lineId))
        .firstOrNull;
    final primary = _data.issued.draft.jobId;
    _targetId =
        prior?.jobId ??
        _data.jobs
            .where(
              (job) =>
                  job.id == primary &&
                  job.mode == _group.mode &&
                  job.currency == _data.issued.draft.currency &&
                  !job.archived,
            )
            .firstOrNull
            ?.id;
    for (final line in _group.lines) {
      final binding = _binding(line.id);
      if (binding == null ||
          binding.jobId == _targetId && !_data.linkedLines.contains(line.id)) {
        _selected.add(line.id);
      }
    }
    if (_targetId != null) {
      _load(_targetId);
    }
  }

  @override
  void dispose() {
    _name.dispose();
    _note.dispose();
    for (final controller in _titles.values) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _load(String? job) async {
    if (!_enabled) {
      return;
    }
    setState(() {
      _reading = true;
      _reviewed = null;
      _reviewedVersion = null;
    });
    try {
      final data = await ref
          .read(quoteScopeApiProvider)
          .read(_data.issued.id, _data.issued.revision, jobId: job);
      if (!mounted) {
        return;
      }
      setState(() {
        if (_targetId != job) {
          _existing.clear();
        }
        _data = data;
        _targetId = job;
        _historical = false;
        _differences = false;
        _readFailed = false;
        _message = null;
        _selected.removeWhere(
          (line) =>
              _binding(line) != null &&
              (_binding(line)!.jobId != job ||
                  _task(_binding(line)!.taskId) == null),
        );
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _readFailed = true;
          _message = 'Current scope data could not be read. Your entered names, task choices and note are kept. Refresh before applying.';
        });
      }
    } finally {
      if (mounted) {
        setState(() => _reading = false);
      }
    }
  }

  void _changed() {
    _reviewed = null;
    _reviewedVersion = null;
  }

  QuoteScopeDraft _capture() {
    if (_readFailed || _reading) {
      throw const FormatException(
        'Refresh current scope data before reviewing or applying.',
      );
    }
    if (_data.issued.decision != 'ACCEPTED') {
      throw const FormatException(
        'This revision needs explicit local acceptance.',
      );
    }
    final problem = _incompatible;
    if (problem != null) {
      throw FormatException(problem);
    }
    if (_selected.isEmpty) {
      throw const FormatException(
        'Select unplanned lines or a retained plan needing this revision link.',
      );
    }
    if (_data.historical && !_historical) {
      throw const FormatException(
        'Review and acknowledge the historical revision.',
      );
    }
    if (_jobDiffers && !_differences) {
      throw const FormatException(
        'Review the existing job differences before confirming.',
      );
    }
    if (_targetId == null &&
        (_name.text.trim().isEmpty ||
            _name.text.length > 240 ||
            _name.text.contains('\u0000'))) {
      throw const FormatException('Enter a job name of up to 240 characters.');
    }
    if (_note.text.trim().length < 10 ||
        _note.text.length > 2000 ||
        _note.text.contains('\u0000')) {
      throw const FormatException(
        'Add a private review note of 10–2000 characters.',
      );
    }
    final items = <QuoteScopeItem>[];
    var changes = false;
    for (final line in _group.lines.where(
      (line) => _selected.contains(line.id),
    )) {
      final binding = _binding(line.id);
      if (binding != null) {
        if (binding.jobId != _targetId) {
          throw const FormatException(
            'This line already has a plan in another job. Reuse that plan.',
          );
        }
        final task = _task(binding.taskId);
        if (task == null) {
          throw const FormatException(
            'This retained task was removed. Add a new quote line for additional work; do not recreate deleted identities.',
          );
        }
        items.add(
          QuoteScopeItem(line.id, binding.taskId, task.title, existing: true),
        );
        changes = changes || !_data.linkedLines.contains(line.id);
      } else {
        final selected = _existing[line.id];
        final task = _task(selected);
        if (selected != null && (task == null || task.ownerQuoteId != null)) {
          throw const FormatException(
            'Choose an available, unassigned existing task.',
          );
        }
        final title = task?.title ?? _titles[line.id]!.text.trim();
        if (title.isEmpty || title.length > 240 || title.contains('\u0000')) {
          throw const FormatException(
            'Review each planned task name; use 1–240 characters.',
          );
        }
        items.add(
          QuoteScopeItem(
            line.id,
            selected ?? _newTaskIds[line.id]!,
            title,
            existing: selected != null,
          ),
        );
        changes = true;
      }
    }
    if (!changes) {
      throw const FormatException(
        'These plans are already linked to this revision. Open the existing job instead.',
      );
    }
    if (items.map((item) => item.taskId).toSet().length != items.length) {
      throw const FormatException(
        'Each quote line needs a distinct task identity.',
      );
    }
    return QuoteScopeDraft(
      quoteId: _data.issued.id,
      revision: _data.issued.revision,
      jobId: _targetId ?? _newJobId,
      items: items,
      note: _note.text.trim(),
      newJobName: _targetId == null ? _name.text.trim() : null,
      historicalReviewed: _historical,
      jobDifferencesReviewed: _differences,
    );
  }

  Future<void> _review() async {
    _form.currentState!.validate();
    try {
      final draft = _capture(), version = _data.version, target = _data.target;
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Apply this reviewed plan?'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${_data.issued.number} · Revision ${draft.revision}'),
                Text('Job: ${draft.newJobName ?? target!.name}'),
                Text(
                  '${_group.mode == 'TASK' ? 'Task-rate' : 'Hourly'} · ${_data.issued.draft.currency} · tax ${taxPercent(_data.issued.draft.taxUnits)}%',
                ),
                if (_group.rate != null)
                  Text(
                    'Agreed hourly rate ${money(_group.rate, _data.issued.digits)}',
                  ),
                for (final item in draft.items)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      '${item.title}\n${item.existing ? 'Link retained work; keep its price and completion unchanged.' : 'Create a new uncompleted planning task.'}',
                    ),
                  ),
                const SizedBox(height: 12),
                Text('Private review note\n${draft.note}'),
                const Text(
                  'This creates no worked hours, completion, invoice, payment or billable reservation. Existing work stays unchanged. Previously billed work cannot be charged again.',
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Keep editing'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Confirm review'),
            ),
          ],
        ),
      );
      if (mounted && confirmed == true) {
        setState(() {
          _reviewed = draft;
          _reviewedVersion = version;
          _message = null;
        });
      }
    } on FormatException catch (error) {
      if (mounted) {
        setState(() => _message = error.message);
      }
    }
  }

  Widget _line(QuoteLine line) {
    final binding = _binding(line.id), selected = _selected.contains(line.id);
    final task = _task(binding?.taskId ?? _existing[line.id]);
    final otherJob = binding != null && binding.jobId != _targetId;
    return Card(
      key: ValueKey(line.id),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(line.title),
              subtitle: Text(
                '${taskQuantity(line.quantity)} ${line.kind == 'HOURS' ? 'estimated hours' : 'units'} × ${_data.issued.draft.currency} ${money(line.unitPrice, _data.issued.digits)}${line.kind == 'HOURS' ? '/hour' : ' each'}',
              ),
              value: selected,
              onChanged:
                  !_enabled || otherJob || binding != null && task == null
                  ? null
                  : (value) => setState(() {
                      if (value == true) {
                        _selected.add(line.id);
                      } else {
                        _selected.remove(line.id);
                      }
                      _changed();
                    }),
            ),
            if (binding != null) ...[
              Text(
                otherJob
                    ? 'Already planned in another job. Reuse its retained plan.'
                    : task == null
                    ? 'Task removed · provenance retained; no automatic recreation.'
                    : 'Existing plan: ${task.title} · ${task.completed == null ? 'not completed' : 'completed'}',
              ),
              Text(
                'First planned from revision ${binding.originalRevision}${_data.linkedLines.contains(line.id) ? ' · linked to this revision' : ' · this revision needs a reviewed link'}',
              ),
            ] else ...[
              if (_data.target != null)
                DropdownButtonFormField<String>(
                  key: ValueKey(
                    'task/${line.id}/${_targetId ?? ''}/${_existing[line.id] ?? ''}',
                  ),
                  initialValue: _existing[line.id] ?? '',
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Create or link task',
                  ),
                  items: [
                    const DropdownMenuItem(
                      value: '',
                      child: Text('Create a new planning task'),
                    ),
                    if (_existing[line.id] != null &&
                        _task(_existing[line.id]) == null)
                      DropdownMenuItem(
                        value: _existing[line.id],
                        enabled: false,
                        child: const Text(
                          'Selected task removed · choose another task',
                        ),
                      ),
                    for (final candidate in _data.tasks)
                      DropdownMenuItem(
                        value: candidate.id,
                        enabled: candidate.ownerQuoteId == null,
                        child: Text(
                          '${candidate.title} · ${candidate.ownerQuoteId != null
                              ? 'already assigned'
                              : candidate.completed == null
                              ? 'open'
                              : 'completed'}',
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                  ],
                  onChanged: !_enabled || !selected
                      ? null
                      : (value) => setState(() {
                          if (value == null || value.isEmpty) {
                            _existing.remove(line.id);
                          } else {
                            _existing[line.id] = value;
                          }
                          _changed();
                        }),
                ),
              if (_existing[line.id] == null)
                TextFormField(
                  controller: _titles[line.id],
                  enabled: _enabled && selected,
                  maxLength: 240,
                  maxLengthEnforcement: MaxLengthEnforcement.none,
                  decoration: const InputDecoration(
                    labelText: 'Planned task name',
                  ),
                  onChanged: (_) => setState(_changed),
                ),
              if (task != null && _data.target != null)
                Text(
                  'Current unit price ${_data.target!.currency} ${task.price == null ? 'unset' : money(task.price, _data.target!.digits)} · quantity ${taskQuantity(task.quantity)}',
                ),
              if (task != null)
                Text(
                  'Current task: ${task.completed == null ? 'not completed' : 'completed'}, ${task.billable ? 'billable' : 'not billable'}. Linking does not change these values.',
                ),
              if (line.kind == 'HOURS')
                const Text(
                  'The estimate becomes a nonbillable checklist. Actual hourly work must be clocked on the job.',
                ),
            ],
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final source = _data.issued.draft, target = _data.target;
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Review scope plan'),
          actions: [
            IconButton(
              tooltip: 'Refresh scope review data',
              onPressed: _enabled ? () => _load(_targetId) : null,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: Form(
          key: _form,
          child: ListView(
            padding: EdgeInsets.fromLTRB(
              24,
              16,
              24,
              24 + MediaQuery.paddingOf(context).bottom,
            ),
            children: [
              Text(
                '${_data.issued.number} · Revision ${_data.issued.revision}',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              Text(
                _group.kind == 'ITEM'
                    ? 'Fixed-price planning scope'
                    : 'Hourly planning scope',
              ),
              const Text(
                'Existing work and issued quotes remain unchanged. Review the job and each selected line before applying.',
              ),
              if (_reading) const LinearProgressIndicator(),
              if (_message != null)
                Text(
                  _message!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                key: ValueKey('job/${_targetId ?? ''}/$_reading'),
                initialValue: _targetId ?? '',
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Planning job'),
                items: [
                  const DropdownMenuItem(
                    value: '',
                    child: Text('Create a new compatible job'),
                  ),
                  if (_targetId != null &&
                      !_data.jobs.any((job) => job.id == _targetId))
                    DropdownMenuItem(
                      value: _targetId,
                      enabled: false,
                      child: const Text('Deleted job · retained scope only'),
                    ),
                  for (final job in _data.jobs)
                    DropdownMenuItem(
                      value: job.id,
                      child: Text(
                        '${job.name}${job.archived ? ' · archived' : ''}',
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                onChanged: !_enabled
                    ? null
                    : (value) =>
                          _load(value == null || value.isEmpty ? null : value),
              ),
              if (_targetId == null)
                TextFormField(
                  controller: _name,
                  enabled: _enabled,
                  maxLength: 240,
                  maxLengthEnforcement: MaxLengthEnforcement.none,
                  decoration: const InputDecoration(labelText: 'New job name'),
                  onChanged: (_) => setState(_changed),
                ),
              const SizedBox(height: 12),
              Text(
                'Agreed currency ${source.currency} · tax ${taxPercent(source.taxUnits)}%',
              ),
              Text(
                'Accepted customer: ${_data.issued.customer.isEmpty ? 'Not specified' : _data.issued.customer}',
              ),
              if (target != null) ...[
                Text(
                  'Current customer: ${target.customerName ?? 'Not specified'}',
                ),
                Text(
                  'Current job: ${target.mode == 'TASK' ? 'task-rate' : 'hourly'}, ${target.currency}, tax ${taxPercent(target.taxUnits)}%',
                ),
                if (target.rate != null)
                  Text(
                    'Current hourly rate ${target.currency} ${money(target.rate, target.digits)}',
                  ),
                OutlinedButton.icon(
                  onPressed: _pending
                      ? null
                      : () => Navigator.push<void>(
                          context,
                          MaterialPageRoute<void>(
                            builder: (_) => JobScreen(
                              jobId: target.id,
                              jobName: target.name,
                            ),
                          ),
                        ),
                  icon: const Icon(Icons.view_kanban_outlined),
                  label: const Text('Open existing job'),
                ),
              ],
              if (_incompatible != null)
                Text(
                  _incompatible!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              if (_jobDiffers)
                CheckboxListTile(
                  value: _differences,
                  onChanged: !_enabled
                      ? null
                      : (value) => setState(() {
                          _differences = value == true;
                          _changed();
                        }),
                  title: const Text(
                    'I reviewed the job’s different customer, tax or hourly rate',
                  ),
                  subtitle: const Text(
                    'Existing job settings and work will stay unchanged. The accepted agreement is retained separately.',
                  ),
                ),
              if (_data.historical)
                CheckboxListTile(
                  value: _historical,
                  onChanged: !_enabled
                      ? null
                      : (value) => setState(() {
                          _historical = value == true;
                          _changed();
                        }),
                  title: Text(
                    'I reviewed this historical accepted revision; latest issued revision is ${_data.latestRevision}',
                  ),
                ),
              for (final line in _group.lines) _line(line),
              Semantics(identifier: 'quote-scope-note', child: TextFormField(
                controller: _note,
                enabled: _enabled,
                minLines: 2,
                maxLines: 5,
                maxLength: 2000,
                maxLengthEnforcement: MaxLengthEnforcement.none,
                decoration: const InputDecoration(
                  labelText: 'Private scope review note',
                ),
                onChanged: (_) => setState(_changed),
              )),
              OutlinedButton(
                onPressed: _enabled && !_readFailed && _incompatible == null
                    ? _review
                    : null,
                child: const Text('Review selected plan'),
              ),
              BusinessCommand(
                key: ValueKey(
                  'scope/${_data.issued.id}/${_data.issued.revision}/${widget.groupKey}',
                ),
                label: 'Apply reviewed plan',
                enabled:
                    _enabled &&
                    !_readFailed &&
                    _reviewed != null &&
                    _reviewedVersion == _data.version,
                onPendingChanged: (value) => setState(() => _pending = value),
                create: () {
                  final draft = _reviewed, version = _reviewedVersion;
                  if (_readFailed ||
                      _reading ||
                      draft == null ||
                      version == null ||
                      version != _data.version) {
                    throw const FormatException(
                      'Review the current selected plan before applying.',
                    );
                  }
                  final id = newBusinessId(),
                      api = ref.read(quoteScopeApiProvider);
                  return () => api.apply(id, version, draft);
                },
                onCommitted: () => Navigator.pop(context, true),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
