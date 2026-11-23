import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'job_screen.dart';
import 'quote_scope_api.dart';
import 'quote_api.dart';
import 'settings_validation_messages.dart';
import 'tax_rate.dart';

class QuoteScopeFormScreen extends ConsumerStatefulWidget {
  const QuoteScopeFormScreen({
    super.key,
    required this.initial,
    required this.groupKey,
    this.createNewJob = false,
  });
  final QuoteScopeData initial;
  final String groupKey;
  final bool createNewJob;
  @override
  ConsumerState<QuoteScopeFormScreen> createState() => _ScopeFormState();
}

class _ScopeFormState extends ConsumerState<QuoteScopeFormScreen> {
  final _form = GlobalKey<FormState>();
  final _note = TextEditingController();
  final _name = TextEditingController();
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
  bool _nameLocalized = false;
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
      return AppMessage.quoteScopeJobUnavailable.of(context);
    }
    if (target.archived) {
      return AppMessage.quoteScopeUnarchiveJob.of(context);
    }
    if (target.currency != _data.issued.draft.currency ||
        target.digits != _data.issued.digits) {
      return AppMessage.quoteScopeCurrencyMismatch.of(context);
    }
    if (target.mode != _group.mode) {
      return (_group.mode == 'TASK'
              ? AppMessage.quoteScopeTaskRateJobRequired
              : AppMessage.quoteScopeHourlyJobRequired)
          .of(context);
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
      return AppMessage.quoteScopeHourlyConflict.of(context);
    }
    return null;
  }

  @override
  void initState() {
    super.initState();
    _data = widget.initial;
    _newJobId = newBusinessId();
    for (final line in _group.lines) {
      _titles[line.id] = TextEditingController(text: line.title);
      _newTaskIds[line.id] = newBusinessId();
    }
    final groupIds = _group.lines.map((line) => line.id).toSet();
    final prior = _data.bindings
        .where((binding) => groupIds.contains(binding.lineId))
        .firstOrNull;
    final primary = _data.issued.draft.jobId;
    _targetId = widget.createNewJob
        ? null
        : prior?.jobId ??
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
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_nameLocalized) return;
    _name.text = AppMessage.quoteScopeNewJobTitle.format(context, {
      'title': _data.issued.draft.title,
      'group':
          (_group.kind == 'ITEM'
                  ? AppMessage.quoteScopeTasksSuffix
                  : AppMessage.quoteScopeHourlySuffix)
              .of(context),
    });
    _nameLocalized = true;
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
          _message = AppMessage.quoteScopeDataReadFailed.of(context);
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
      throw FormatException(
        AppMessage.quoteScopeRefreshBeforeApply.of(context),
      );
    }
    if (_data.issued.decision != 'ACCEPTED') {
      throw FormatException(
        AppMessage.quoteScopeAcceptanceRequired.of(context),
      );
    }
    final problem = _incompatible;
    if (problem != null) {
      throw FormatException(problem);
    }
    if (_selected.isEmpty) {
      throw FormatException(AppMessage.quoteScopeSelectLines.of(context));
    }
    if (_data.historical && !_historical) {
      throw FormatException(
        AppMessage.quoteScopeHistoricalReviewRequired.of(context),
      );
    }
    if (_jobDiffers && !_differences) {
      throw FormatException(
        AppMessage.quoteScopeJobDifferencesReviewRequired.of(context),
      );
    }
    if (_targetId == null &&
        (_name.text.trim().isEmpty ||
            _name.text.length > 240 ||
            _name.text.contains('\u0000'))) {
      throw FormatException(AppMessage.quoteScopeJobNameLimit.of(context));
    }
    if (_note.text.length > 2000 || _note.text.contains('\u0000')) {
      throw FormatException(
        AppMessage.quoteScopePrivateNoteLimit.format(context, {
          'limit': '2000',
        }),
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
          throw FormatException(
            AppMessage.quoteScopeLineAlreadyInOtherJob.of(context),
          );
        }
        final task = _task(binding.taskId);
        if (task == null) {
          throw FormatException(
            AppMessage.quoteScopeRetainedTaskRemoved.of(context),
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
          throw FormatException(
            AppMessage.quoteScopeChooseAvailableTask.of(context),
          );
        }
        final title = task?.title ?? _titles[line.id]!.text.trim();
        if (title.isEmpty || title.length > 240 || title.contains('\u0000')) {
          throw FormatException(AppMessage.quoteScopeTaskNameLimit.of(context));
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
      throw FormatException(AppMessage.quoteScopeAlreadyLinked.of(context));
    }
    if (items.map((item) => item.taskId).toSet().length != items.length) {
      throw FormatException(
        AppMessage.quoteScopeDistinctTaskIdentities.of(context),
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
      // Keep focus restoration from reopening the note keyboard after the
      // confirmation dialog closes and covering the plan action.
      FocusManager.instance.primaryFocus?.unfocus();
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(AppMessage.quoteScopeApplyReviewTitle.of(context)),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  AppMessage.quoteInvoiceRevisionRef.format(context, {
                    'number': _data.issued.number,
                    'revision': draft.revision.toString(),
                  }),
                ),
                Text(
                  AppMessage.quoteScopeJobLabel.format(context, {
                    'job': draft.newJobName ?? target!.name,
                  }),
                ),
                Text(
                  AppMessage.quoteScopeModeAndTax.format(context, {
                    'mode':
                        (_group.mode == 'TASK'
                                ? AppMessage.quoteScopeTaskRateMode
                                : AppMessage.quoteScopeHourlyMode)
                            .of(context),
                    'currency': _data.issued.draft.currency,
                    'rate': taxPercent(_data.issued.draft.taxUnits).toString(),
                  }),
                ),
                if (_group.rate != null)
                  Text(
                    AppMessage.quoteScopeAgreedHourlyRate.format(context, {
                      'rate':
                          '${_data.issued.draft.currency} ${money(_group.rate, _data.issued.digits)}',
                    }),
                  ),
                for (final item in draft.items)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      '${item.title}\n${(item.existing ? AppMessage.quoteScopeRetainedTaskAction : AppMessage.quoteScopeNewTaskAction).of(context)}',
                    ),
                  ),
                const SizedBox(height: 12),
                if (draft.note.isNotEmpty)
                  Text(
                    AppMessage.quoteScopePrivateNote.format(context, {
                      'note': draft.note,
                    }),
                  ),
                Text(AppMessage.quoteScopeApplySafety.of(context)),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(AppMessage.keepEditing.of(context)),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(AppMessage.confirmReview.of(context)),
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
        setState(
          () => _message = error.message == 'QUOTE_SCOPE_UNSUPPORTED_LINE'
              ? AppMessage.quoteScopeRefreshBeforeApply.of(context)
              : localizedBusinessFormatError(context, error),
        );
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
                AppMessage.quoteScopeLineValue.format(context, {
                  'quantity': taskQuantity(line.quantity),
                  'unit': line.kind == 'HOURS'
                      ? AppMessage.estimatedHours.of(context)
                      : AppMessage.quoteUnitsUnit.of(context),
                  'currency': _data.issued.draft.currency,
                  'price': money(line.unitPrice, _data.issued.digits),
                  'suffix': line.kind == 'HOURS'
                      ? AppMessage.quotePerHourSuffix.of(context)
                      : ' ${AppMessage.quoteScopeEachSuffix.of(context)}',
                }),
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
                    ? AppMessage.quoteScopeOtherJobPlan.of(context)
                    : task == null
                    ? AppMessage.quoteScopeRemovedTaskProvenance.of(context)
                    : AppMessage.quoteScopeExistingPlan.format(context, {
                        'title': task.title,
                        'status':
                            (task.completed == null
                                    ? AppMessage.quoteScopeNotCompleted
                                    : AppMessage.quoteScopeCompleted)
                                .of(context),
                      }),
              ),
              Text(
                AppMessage.quoteScopeFirstPlannedRevision.format(context, {
                  'revision': binding.originalRevision.toString(),
                  'status':
                      (_data.linkedLines.contains(line.id)
                              ? AppMessage.quoteScopeLinkedStatus
                              : AppMessage.quoteScopeNeedsReviewStatus)
                          .of(context),
                }),
              ),
            ] else ...[
              if (_data.target != null)
                DropdownButtonFormField<String>(
                  key: ValueKey(
                    'task/${line.id}/${_targetId ?? ''}/${_existing[line.id] ?? ''}',
                  ),
                  initialValue: _existing[line.id] ?? '',
                  isExpanded: true,
                  decoration: InputDecoration(
                    labelText: AppMessage.quoteScopeCreateOrLinkTask.of(
                      context,
                    ),
                  ),
                  items: [
                    DropdownMenuItem(
                      value: '',
                      child: Text(
                        AppMessage.quoteScopeNewPlanningTask.of(context),
                      ),
                    ),
                    if (_existing[line.id] != null &&
                        _task(_existing[line.id]) == null)
                      DropdownMenuItem(
                        value: _existing[line.id],
                        enabled: false,
                        child: Text(
                          AppMessage.quoteScopeSelectedTaskRemoved.of(context),
                        ),
                      ),
                    for (final candidate in _data.tasks)
                      DropdownMenuItem(
                        value: candidate.id,
                        enabled: candidate.ownerQuoteId == null,
                        child: Text(
                          '${candidate.title} · ${(candidate.ownerQuoteId != null
                              ? AppMessage.quoteScopeTaskAlreadyAssigned
                              : candidate.completed == null
                              ? AppMessage.quoteScopeTaskOpen
                              : AppMessage.quoteScopeCompleted).of(context)}',
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
                  decoration: InputDecoration(
                    labelText: AppMessage.quoteScopePlannedTaskName.of(context),
                  ),
                  onChanged: (_) => setState(_changed),
                ),
              if (task != null && _data.target != null)
                Text(
                  AppMessage.quoteScopeCurrentUnitPrice.format(context, {
                    'currency': _data.target!.currency,
                    'price': task.price == null
                        ? AppMessage.quoteInvoiceRateUnset.of(context)
                        : money(task.price, _data.target!.digits),
                    'quantity': taskQuantity(task.quantity),
                  }),
                ),
              if (task != null)
                Text(
                  AppMessage.quoteScopeCurrentTaskStatus.format(context, {
                    'completion':
                        (task.completed == null
                                ? AppMessage.quoteScopeNotCompleted
                                : AppMessage.quoteScopeCompleted)
                            .of(context),
                    'billability':
                        (task.billable
                                ? AppMessage.quoteScopeBillable
                                : AppMessage.quoteScopeNotBillable)
                            .of(context),
                  }),
                ),
              if (line.kind == 'HOURS')
                Text(AppMessage.quoteScopeHourlyChecklist.of(context)),
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
          title: Text(AppMessage.quoteScopeReviewTitle.of(context)),
          actions: [
            IconButton(
              tooltip: AppMessage.quoteScopeRefreshReview.of(context),
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
                AppMessage.quoteInvoiceRevisionRef.format(context, {
                  'number': _data.issued.number,
                  'revision': _data.issued.revision.toString(),
                }),
                style: Theme.of(context).textTheme.titleLarge,
              ),
              Text(
                _group.kind == 'ITEM'
                    ? AppMessage.quoteScopeFixedPricePlanning.of(context)
                    : AppMessage.quoteScopeHourlyPlanning.of(context),
              ),
              Text(AppMessage.quoteScopeReviewApplyGuidance.of(context)),
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
                decoration: InputDecoration(
                  labelText: AppMessage.quoteScopePlanningJob.of(context),
                ),
                items: [
                  DropdownMenuItem(
                    value: '',
                    child: Text(
                      AppMessage.quoteScopeCreateCompatibleJob.of(context),
                    ),
                  ),
                  if (_targetId != null &&
                      !_data.jobs.any((job) => job.id == _targetId))
                    DropdownMenuItem(
                      value: _targetId,
                      enabled: false,
                      child: Text(
                        AppMessage.quoteScopeDeletedJobRetained.of(context),
                      ),
                    ),
                  for (final job in _data.jobs)
                    DropdownMenuItem(
                      value: job.id,
                      child: Text(
                        '${job.name}${job.archived ? ' · ${AppMessage.archived.of(context)}' : ''}',
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
                  decoration: InputDecoration(
                    labelText: AppMessage.quoteScopeNewJobName.of(context),
                  ),
                  onChanged: (_) => setState(_changed),
                ),
              const SizedBox(height: 12),
              Text(
                AppMessage.quoteScopeAcceptedTerms.format(context, {
                  'currency': source.currency,
                  'rate': taxPercent(source.taxUnits).toString(),
                }),
              ),
              Text(
                AppMessage.quoteInvoiceAcceptedCustomer.format(context, {
                  'customer': _data.issued.customer.isEmpty
                      ? AppMessage.quoteInvoiceCustomerNotSpecified.of(context)
                      : _data.issued.customer,
                }),
              ),
              if (target != null) ...[
                Text(
                  AppMessage.quoteInvoiceCurrentCustomer.format(context, {
                    'customer':
                        target.customerName ??
                        AppMessage.quoteInvoiceCustomerNotSpecified.of(context),
                  }),
                ),
                Text(
                  AppMessage.quoteScopeCurrentJob.format(context, {
                    'mode':
                        (target.mode == 'TASK'
                                ? AppMessage.quoteScopeTaskRateMode
                                : AppMessage.quoteScopeHourlyMode)
                            .of(context),
                    'currency': target.currency,
                    'rate': taxPercent(target.taxUnits).toString(),
                  }),
                ),
                if (target.rate != null)
                  Text(
                    AppMessage.quoteScopeCurrentHourlyRate.format(context, {
                      'currency': target.currency,
                      'rate': money(target.rate, target.digits),
                    }),
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
                  label: Text(AppMessage.quoteScopeOpenExistingJob.of(context)),
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
                  title: Text(
                    AppMessage.quoteScopeReviewJobDifference.of(context),
                  ),
                  subtitle: Text(AppMessage.quoteScopeJobUnchanged.of(context)),
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
                    AppMessage.quoteScopeHistoricalReviewed.format(context, {
                      'revision': _data.latestRevision.toString(),
                    }),
                  ),
                ),
              for (final line in _group.lines) _line(line),
              Semantics(
                identifier: 'quote-scope-note',
                child: TextFormField(
                  controller: _note,
                  enabled: _enabled,
                  minLines: 2,
                  maxLines: 5,
                  maxLength: 2000,
                  maxLengthEnforcement: MaxLengthEnforcement.none,
                  decoration: InputDecoration(
                    labelText: AppMessage.quoteScopeNoteLabel.of(context),
                    helperText: AppMessage.quoteScopeNoteHelp.of(context),
                  ),
                  onChanged: (_) => setState(_changed),
                ),
              ),
              OutlinedButton(
                onPressed: _enabled && !_readFailed && _incompatible == null
                    ? _review
                    : null,
                child: Text(
                  AppMessage.quoteScopeReviewSelectedPlan.of(context),
                ),
              ),
              BusinessCommand(
                key: ValueKey(
                  'scope/${_data.issued.id}/${_data.issued.revision}/${widget.groupKey}',
                ),
                label: AppMessage.quoteScopeApplyReviewedPlan.of(context),
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
                    throw FormatException(
                      AppMessage.quoteScopeReviewCurrentPlanRequired.of(
                        context,
                      ),
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
