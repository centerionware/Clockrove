import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'invoice_detail_screen.dart';
import 'legacy_invoice_screen.dart';
import 'quote_api.dart';
import 'quote_invoice_api.dart';
import 'quote_scope_api.dart';
import 'quote_scope_screen.dart';
import 'report_screen.dart' show reportDuration;
import 'tax_rate.dart';

String quoteInvoiceProblem(String? code) => switch (code) {
  'QUOTE_INVOICE_MAPPING_REQUIRED' => 'Link this job’s scope to the accepted revision before preparing its invoice.',
  'QUOTE_ACCEPTANCE_REQUIRED' => 'Record explicit local acceptance of this revision before invoicing its work.',
  'QUOTE_HISTORICAL_REVIEW_REQUIRED' =>
    'Review and acknowledge this historical accepted revision.',
  'QUOTE_INVOICE_SOURCES_CHANGED' => 'Selected work changed or was already invoiced. Refresh available work and review the remaining sources.',
  'NO_BILLABLE_ITEMS' => 'Select completed billable tasks or finalized time that has not already been invoiced.',
  'LEGACY_INVOICE_REVIEW_REQUIRED' =>
    'Review the job’s older billed work before preparing another invoice.',
  'TASK_PRICES_REQUIRED' || 'HOURLY_RATE_REQUIRED' => 'Current prices are missing. Use accepted prices or set current prices before reviewing.',
  'JOB_UNAVAILABLE' => 'This job was removed. Existing invoices remain in related history; removed work cannot be invoiced again.',
  'QUOTE_SCOPE_KIND_CHANGED' ||
  'QUOTE_JOB_MODE_MISMATCH' ||
  'QUOTE_SCOPE_SPLIT_REQUIRED' => 'Review the accepted scope and choose a compatible job for each billing group.',
  'CURRENCY_MISMATCH' || 'QUOTE_CURRENCY_PRECISION_CHANGED' => 'This job’s currency or precision differs from the accepted quote. Review a compatible agreement before invoicing.',
  'INVALID_REPORT_RANGE' =>
    'Review the date range and timezone, then load available work again.',
  'INVOICE_TOO_LARGE' =>
    'Select less work or a shorter date range before preparing this invoice.',
  _ => 'This invoice review is unavailable. Refresh the work and review your selections.',
};

class _Setup {
  const _Setup(this.scope, this.calendar);
  final QuoteScopeData scope;
  final QuoteData calendar;
}

class QuoteInvoiceScreen extends ConsumerStatefulWidget {
  const QuoteInvoiceScreen({
    super.key,
    required this.quoteId,
    required this.revision,
    this.jobId,
  });
  final String quoteId;
  final int revision;
  final String? jobId;
  @override
  ConsumerState<QuoteInvoiceScreen> createState() => _QuoteInvoiceScreenState();
}

class _QuoteInvoiceScreenState extends ConsumerState<QuoteInvoiceScreen> {
  Future<_Setup> _load() async {
    final values = await Future.wait<Object>([
      ref.read(quoteScopeApiProvider).read(widget.quoteId, widget.revision),
      ref.read(quoteApiProvider).read(widget.quoteId),
    ]);
    return _Setup(values[0] as QuoteScopeData, values[1] as QuoteData);
  }

  late Future<_Setup> _setup = _load();
  @override
  Widget build(BuildContext context) => FutureBuilder<_Setup>(
    future: _setup,
    builder: (context, snapshot) {
      if (snapshot.hasData) {
        return _QuoteInvoiceForm(
          quoteId: widget.quoteId,
          revision: widget.revision,
          initialJob: widget.jobId,
          setup: snapshot.data!,
        );
      }
      return Scaffold(
        appBar: AppBar(title: const Text('Invoice actual work')),
        body: Center(
          child: snapshot.hasError
              ? TextButton(
                  onPressed: () => setState(() {
                    _setup = _load();
                  }),
                  child: const Text('Retry invoice setup'),
                )
              : const CircularProgressIndicator(),
        ),
      );
    },
  );
}

class _Reviewed {
  const _Reviewed(this.request, this.choices, this.calculation);
  final QuoteInvoiceRequest request;
  final QuoteInvoiceChoices choices;
  final QuoteInvoiceCalculation calculation;
}

class _QuoteInvoiceForm extends ConsumerStatefulWidget {
  const _QuoteInvoiceForm({
    required this.quoteId,
    required this.revision,
    required this.initialJob,
    required this.setup,
  });
  final String quoteId;
  final int revision;
  final String? initialJob;
  final _Setup setup;
  @override
  ConsumerState<_QuoteInvoiceForm> createState() => _QuoteInvoiceFormState();
}

class _QuoteInvoiceFormState extends ConsumerState<_QuoteInvoiceForm> {
  final _form = GlobalKey<FormState>();
  final _note = TextEditingController();
  late final TextEditingController _first, _last, _zone;
  late QuoteScopeData _scope;
  QuoteInvoiceContext? _context;
  String? _job, _message;
  bool _reading = false, _pending = false, _dirty = true, _readFailed = false;
  bool _currentPrices = false,
      _currentTax = false,
      _currentParty = false,
      _historical = false,
      _timeAllocation = false;
  final _tasks = <String>{}, _times = <String>{};
  _Reviewed? _reviewed;
  bool get _enabled => !_reading && !_pending;
  Set<String> get _jobs =>
      _scope.bindings.map((binding) => binding.jobId).toSet();
  bool get _ready =>
      _enabled &&
      !_dirty &&
      !_readFailed &&
      _context?.problem == null &&
      _context?.issued != null;
  bool get _missingSelections =>
      _tasks.any((id) => !_context!.tasks.any((task) => task.id == id)) ||
      _times.any((key) => !_context!.times.any((time) => time.key == key));
  bool get _currentAvailable => _context!.mode == 'HOURLY'
      ? _context!.currentRate != null
      : _context!.tasks
            .where((task) => _tasks.contains(task.id))
            .every((task) => task.currentPrice != null);
  @override
  void initState() {
    super.initState();
    _scope = widget.setup.scope;
    _first = TextEditingController(text: widget.setup.calendar.today);
    _last = TextEditingController(text: widget.setup.calendar.today);
    _zone = TextEditingController(text: widget.setup.calendar.timezone);
    _job = widget.initialJob ?? _jobs.firstOrNull;
    if (_job != null) {
      _load();
    }
  }

  @override
  void dispose() {
    _first.dispose();
    _last.dispose();
    _zone.dispose();
    _note.dispose();
    super.dispose();
  }

  void _changed({bool source = false}) {
    _reviewed = null;
    _message = null;
    if (source) {
      _dirty = true;
    }
  }

  bool _date(String value) {
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(value)) {
      return false;
    }
    final parsed = DateTime.tryParse(value);
    return parsed != null && parsed.toIso8601String().substring(0, 10) == value;
  }

  QuoteInvoiceRequest _request() {
    final first = _first.text.trim(),
        last = _last.text.trim(),
        zone = _zone.text.trim();
    if (!_date(first) || !_date(last) || first.compareTo(last) > 0) {
      throw const FormatException(
        'Enter valid dates as YYYY-MM-DD with the end on or after the start.',
      );
    }
    if (_job == null || zone.isEmpty || zone.length > 100) {
      throw const FormatException('Choose a mapped job and a report timezone.');
    }
    return QuoteInvoiceRequest(
      quoteId: widget.quoteId,
      revision: widget.revision,
      jobId: _job!,
      firstDate: first,
      lastDate: last,
      timezone: zone,
    );
  }

  Future<void> _load() async {
    if (!_enabled) {
      return;
    }
    try {
      final request = _request();
      setState(() {
        _reading = true;
        _reviewed = null;
        _message = null;
      });
      final data = await ref.read(quoteInvoiceApiProvider).read(request);
      if (mounted) {
        setState(() {
          _context = data;
          _dirty = false;
          _readFailed = false;
          _historical = false;
          _timeAllocation = false;
        });
      }
    } on FormatException catch (error) {
      if (mounted) {
        setState(() => _message = error.message);
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _readFailed = true;
          _message = 'Available work could not be read. Your selections and note are kept. Load available work before reviewing.';
        });
      }
    } finally {
      if (mounted) {
        setState(() => _reading = false);
      }
    }
  }

  Future<void> _plan() async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(
        builder: (_) => QuoteScopeScreen(
          quoteId: widget.quoteId,
          revision: widget.revision,
        ),
      ),
    );
    if (!mounted) {
      return;
    }
    setState(() {
      _reading = true;
      _dirty = true;
      _reviewed = null;
    });
    try {
      final scope = await ref
          .read(quoteScopeApiProvider)
          .read(widget.quoteId, widget.revision);
      if (!mounted) {
        return;
      }
      setState(() {
        _scope = scope;
        _job ??= _jobs.firstOrNull;
        _reading = false;
      });
      if (_job != null) {
        await _load();
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _reading = false;
          _readFailed = true;
          _message = 'The updated scope could not be read. Reopen scope review and try again; your invoice draft is kept.';
        });
      }
    }
  }

  QuoteInvoiceChoices _choices() {
    if (!_ready) {
      throw const FormatException(
        'Load available work before reviewing this invoice.',
      );
    }
    if (_context!.historical && !_historical) {
      throw const FormatException(
        'Acknowledge the historical accepted revision.',
      );
    }
    if (_missingSelections) {
      throw const FormatException(
        'Some selected sources changed or were invoiced. Clear unavailable selections and review what remains.',
      );
    }
    if (_tasks.isEmpty && _times.isEmpty) {
      throw const FormatException(
        'Select actual work to include in this invoice.',
      );
    }
    if (_context!.mode == 'HOURLY' && !_timeAllocation) {
      throw const FormatException(
        'Confirm that the selected real job time belongs to this quote.',
      );
    }
    if (_currentPrices && !_currentAvailable) {
      throw const FormatException(
        'Current prices are missing. Choose accepted prices or update the job/task prices.',
      );
    }
    if (_note.text.trim().length < 10 ||
        _note.text.length > 2000 ||
        _note.text.contains('\u0000')) {
      throw const FormatException(
        'Add a private review note of 10–2000 characters.',
      );
    }
    return QuoteInvoiceChoices(
      taskIds: _tasks.toList(),
      times: _context!.times
          .where((time) => _times.contains(time.key))
          .toList(),
      note: _note.text.trim(),
      currentPrices: _currentPrices,
      currentTax: _currentTax,
      currentParty: _currentParty,
      historicalReviewed: _historical,
    );
  }

  Future<void> _review() async {
    _form.currentState!.validate();
    try {
      final request = _request(), choices = _choices(), data = _context!;
      setState(() {
        _reading = true;
        _reviewed = null;
        _message = null;
      });
      final result = await ref
          .read(quoteInvoiceApiProvider)
          .calculate(request, choices);
      if (!mounted) {
        return;
      }
      if (result.version != data.version) {
        setState(() {
          _dirty = true;
          _message = 'Data changed. Load available work, review its latest values, and confirm again. Your selections and note are kept.';
        });
        return;
      }
      if (result.problem != null) {
        setState(() => _message = quoteInvoiceProblem(result.problem));
        return;
      }
      if (result.total == null ||
          result.subtotal == null ||
          result.tax == null ||
          result.taxUnits == null ||
          result.digits != data.digits ||
          result.currency != data.currency) {
        throw const FormatException(
          'The calculated invoice could not be verified. Load available work and review again.',
        );
      }
      setState(() => _reading = false);
      final accepted = data.issued!;
      // Keep focus restoration from reopening the soft keyboard when the
      // confirmation dialog closes and obscuring the prepare action.
      FocusManager.instance.primaryFocus?.unfocus();
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Prepare this invoice?'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${accepted.number} · Revision ${accepted.revision}'),
                Text(data.jobName),
                Text(
                  '${request.firstDate} — ${request.lastDate} · ${request.timezone}',
                ),
                Text(
                  'Customer: ${result.customer.isEmpty ? 'Not specified' : result.customer}',
                ),
                Text(
                  'Email: ${result.email.isEmpty ? 'Not set' : result.email}',
                ),
                Text(
                  'Prices: ${choices.currentPrices ? 'current values, explicitly revised' : 'accepted agreement'}',
                ),
                Text(
                  'Tax: ${taxPercent(result.taxUnits!)}% · ${choices.currentTax ? 'current job tax' : 'accepted agreement'}',
                ),
                for (final task in data.tasks.where(
                  (task) => choices.taskIds.contains(task.id),
                ))
                  Text(
                    '${task.title}\n${taskQuantity(task.actualQuantity)} × ${data.currency} ${money(choices.currentPrices ? task.currentPrice : task.acceptedPrice, data.digits)}',
                  ),
                for (final time in choices.times)
                  Text(
                    '${time.label}\n${reportDuration(time.durationMillis)} actual finalized work',
                  ),
                const SizedBox(height: 12),
                Text(
                  'Subtotal ${data.currency} ${money(result.subtotal, data.digits)}',
                ),
                Text('Tax ${data.currency} ${money(result.tax, data.digits)}'),
                Text(
                  'Invoice total ${data.currency} ${money(result.total, data.digits)}',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                Text(
                  'Whole quote estimate ${data.currency} ${accepted.total}. This invoice includes only selected actual work; quantities, duration and per-invoice tax can differ.',
                ),
                const SizedBox(height: 12),
                Text('Private review note\n${choices.note}'),
                const Text(
                  'This allocates the selected actual work to this quote and reserves it against duplicate billing. It creates no additional work or payment. Emailing is a separate action.',
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
              child: const Text('Confirm invoice review'),
            ),
          ],
        ),
      );
      if (mounted && confirmed == true) {
        setState(() {
          _reviewed = _Reviewed(request, choices, result);
        });
      }
    } on FormatException catch (error) {
      if (mounted) {
        setState(() => _message = error.message);
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _readFailed = true;
          _message = 'Invoice calculation could not be read. Your draft is kept. Load available work and review again.';
        });
      }
    } finally {
      if (mounted) {
        setState(() => _reading = false);
      }
    }
  }

  Future<void> _invoice(String id, {bool legacy = false}) async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(
        builder: (_) => legacy
            ? LegacyInvoiceScreen(invoiceId: id)
            : InvoiceDetailScreen(invoiceId: id),
      ),
    );
    if (mounted) {
      await _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final data = _context, accepted = data?.issued;
    final jobName = {for (final job in _scope.jobs) job.id: job.name};
    final jobIds = {..._jobs, ?_job};
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Invoice actual work'),
          actions: [
            IconButton(
              tooltip: 'Reload available quote work',
              onPressed: _enabled && _job != null ? _load : null,
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
                '${_scope.issued.number} · Revision ${widget.revision}',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const Text(
                'Choose completed tasks or finalized time. Estimates do not create worked hours, task completion or payment.',
              ),
              if (_reading) const LinearProgressIndicator(),
              if (_message != null)
                Text(
                  _message!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              if (_jobs.isEmpty)
                const Text(
                  'Plan and link the accepted scope to a job before invoicing its actual work.',
                ),
              OutlinedButton.icon(
                onPressed: _enabled ? _plan : null,
                icon: const Icon(Icons.playlist_add_check),
                label: const Text('Review accepted scope'),
              ),
              const SizedBox(height: 12),
              Text('Mapped job', style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 4),
              DropdownButtonFormField<String>(
                key: ValueKey('invoice-job/$_job'),
                initialValue: _job,
                isExpanded: true,
                decoration: const InputDecoration(hintText: 'Choose a job'),
                items: [
                  for (final id in jobIds)
                    DropdownMenuItem(
                      value: id,
                      child: Text(
                        jobName[id] ?? 'Removed job · history retained',
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                onChanged: !_enabled
                    ? null
                    : (value) => setState(() {
                        _job = value;
                        _changed(source: true);
                      }),
              ),
              const SizedBox(height: 12),
              Text(
                'First date (YYYY-MM-DD)',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 4),
              Semantics(
                identifier: 'quote-invoice-first',
                child: TextFormField(
                  controller: _first,
                  enabled: _enabled,
                  decoration: const InputDecoration(hintText: 'YYYY-MM-DD'),
                  onChanged: (_) => setState(() => _changed(source: true)),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                'Last date (YYYY-MM-DD)',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 4),
              Semantics(
                identifier: 'quote-invoice-last',
                child: TextFormField(
                  controller: _last,
                  enabled: _enabled,
                  decoration: const InputDecoration(hintText: 'YYYY-MM-DD'),
                  onChanged: (_) => setState(() => _changed(source: true)),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                'Report timezone',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 4),
              Semantics(
                identifier: 'quote-invoice-zone',
                child: TextFormField(
                  controller: _zone,
                  enabled: _enabled,
                  decoration: const InputDecoration(hintText: 'IANA timezone'),
                  onChanged: (_) => setState(() => _changed(source: true)),
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: _enabled && _job != null ? _load : null,
                child: const Text('Load available work'),
              ),
              if (_dirty && data != null)
                const Text(
                  'The job, dates or timezone changed. Load available work before reviewing.',
                ),
              if (data?.problem != null)
                Text(
                  quoteInvoiceProblem(data!.problem),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              if (data != null && accepted != null) ...[
                const SizedBox(height: 16),
                Text(
                  data.jobName,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                if (data.archived)
                  const Text(
                    'Archived job · historical performed work remains invoiceable',
                  ),
                Text(
                  'Accepted customer: ${accepted.customer.isEmpty ? 'Not specified' : accepted.customer}',
                ),
                Text(
                  [
                    accepted.customerAddress,
                    accepted.email,
                  ].where((v) => v.isNotEmpty).join('\n'),
                ),
                Text(
                  'Current customer: ${data.currentCustomer.isEmpty ? 'Not specified' : data.currentCustomer}',
                ),
                Text(data.currentContact),
                SwitchListTile(
                  title: const Text('Bill the current job customer instead'),
                  subtitle: const Text(
                    'Off keeps the accepted customer and contact snapshot.',
                  ),
                  value: _currentParty,
                  onChanged: !_enabled
                      ? null
                      : (value) => setState(() {
                          _currentParty = value;
                          _changed();
                        }),
                ),
                Text(
                  'Accepted tax ${taxPercent(accepted.draft.taxUnits)}% · Current job tax ${taxPercent(data.currentTax)}%',
                ),
                SwitchListTile(
                  title: const Text('Use current job tax instead'),
                  value: _currentTax,
                  onChanged: !_enabled
                      ? null
                      : (value) => setState(() {
                          _currentTax = value;
                          _changed();
                        }),
                ),
                SwitchListTile(
                  title: const Text('Use current prices instead'),
                  subtitle: Text(
                    _currentAvailable
                        ? 'Off keeps accepted unit/hourly prices.'
                        : 'Some selected current prices are unset; accepted prices remain available.',
                  ),
                  value: _currentPrices,
                  onChanged: !_enabled
                      ? null
                      : (value) => setState(() {
                          _currentPrices = value;
                          _changed();
                        }),
                ),
                if (data.historical)
                  CheckboxListTile(
                    value: _historical,
                    title: const Text(
                      'I reviewed this historical accepted revision',
                    ),
                    onChanged: !_enabled
                        ? null
                        : (value) => setState(() {
                            _historical = value == true;
                            _changed();
                          }),
                  ),
                Text(
                  'Actual work',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const Text(
                  'Only completed billable tasks linked to this revision, or finalized time for its hourly job, can be selected. Unmapped and incomplete tasks are not included.',
                ),
                if (data.mode == 'HOURLY') ...[
                  Text(
                    'Estimated ${taskQuantity(data.estimatedHours)} hours · Agreed rate ${data.currency} ${money(data.acceptedRate, data.digits)}/hour',
                  ),
                  Text(
                    'Current rate ${data.currency} ${data.currentRate == null ? 'unset' : money(data.currentRate, data.digits)}/hour',
                  ),
                  Text(
                    'Selected actual duration: ${reportDuration(data.times.where((time) => _times.contains(time.key)).fold(BigInt.zero, (sum, time) => sum + BigInt.parse(time.durationMillis)).toString())}',
                  ),
                ],
                for (final task in data.tasks)
                  CheckboxListTile(
                    key: ValueKey('invoice-task/${task.id}'),
                    contentPadding: EdgeInsets.zero,
                    title: Text(task.title),
                    subtitle: Text(
                      'Actual ${taskQuantity(task.actualQuantity)} · Estimated ${taskQuantity(task.estimatedQuantity)}\nAccepted ${data.currency} ${money(task.acceptedPrice, data.digits)} each · Current ${task.currentPrice == null ? 'unset' : money(task.currentPrice, data.digits)}\nCompleted ${task.completedLabel}',
                    ),
                    value: _tasks.contains(task.id),
                    onChanged: !_ready
                        ? null
                        : (value) => setState(() {
                            if (value == true) {
                              _tasks.add(task.id);
                            } else {
                              _tasks.remove(task.id);
                            }
                            _changed();
                          }),
                  ),
                for (final time in data.times)
                  CheckboxListTile(
                    key: ValueKey('invoice-time/${time.key}'),
                    contentPadding: EdgeInsets.zero,
                    title: Text(time.label),
                    subtitle: Text(reportDuration(time.durationMillis)),
                    value: _times.contains(time.key),
                    onChanged: !_ready
                        ? null
                        : (value) => setState(() {
                            if (value == true) {
                              _times.add(time.key);
                            } else {
                              _times.remove(time.key);
                            }
                            _changed();
                          }),
                  ),
                if (data.tasks.isEmpty && data.times.isEmpty)
                  const Text(
                    'No eligible actual work in these dates. No invoice will be created from the estimate.',
                  ),
                if (_missingSelections)
                  OutlinedButton(
                    onPressed: !_enabled
                        ? null
                        : () => setState(() {
                            _tasks.removeWhere(
                              (id) => !data.tasks.any((task) => task.id == id),
                            );
                            _times.removeWhere(
                              (key) =>
                                  !data.times.any((time) => time.key == key),
                            );
                            _changed();
                          }),
                    child: const Text('Clear unavailable selections'),
                  ),
                if (data.mode == 'HOURLY')
                  CheckboxListTile(
                    value: _timeAllocation,
                    title: const Text(
                      'The selected actual job time belongs to this quote',
                    ),
                    onChanged: !_enabled
                        ? null
                        : (value) => setState(() {
                            _timeAllocation = value == true;
                            _changed();
                          }),
                  ),
                for (final item in data.excluded)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text('Already billed · ${item.number}'),
                    subtitle: Text(item.title),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: !_enabled ? null : () => _invoice(item.invoiceId),
                  ),
                for (final item in data.legacy)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text('Review older billed work · ${item.number}'),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: !_enabled
                        ? null
                        : () => _invoice(item.invoiceId, legacy: true),
                  ),
                if (data.unfinalized.isNotEmpty)
                  Text(
                    '${data.unfinalized.length} active, provisional or review-needed time entries excluded. Finalize/review the actual work before invoicing.',
                  ),
              ],
              Semantics(
                identifier: 'quote-invoice-note',
                child: TextFormField(
                  controller: _note,
                  enabled: _enabled,
                  minLines: 2,
                  maxLines: 5,
                  maxLength: 2000,
                  maxLengthEnforcement: MaxLengthEnforcement.none,
                  decoration: const InputDecoration(
                    labelText: 'Private invoice review note',
                  ),
                  onChanged: (_) => setState(_changed),
                ),
              ),
              OutlinedButton(
                onPressed: _ready && _context!.legacy.isEmpty ? _review : null,
                child: const Text('Review actual-work invoice'),
              ),
              BusinessCommand(
                key: ValueKey(
                  'quote-invoice/${widget.quoteId}/${widget.revision}',
                ),
                label: 'Prepare reviewed invoice',
                enabled: _ready && _reviewed != null,
                onPendingChanged: (value) => setState(() {
                  _pending = value;
                  if (!value) {
                    _reviewed = null;
                  }
                }),
                create: () {
                  final reviewed = _reviewed;
                  if (reviewed == null || !_ready) {
                    throw const FormatException(
                      'Review this invoice before preparing it.',
                    );
                  }
                  final api = ref.read(quoteInvoiceApiProvider),
                      operation = newBusinessId(),
                      invoice = newBusinessId();
                  return () async {
                    final outcome = await api.prepare(
                      operation,
                      reviewed.calculation.version,
                      invoice,
                      reviewed.request,
                      reviewed.choices,
                    );
                    if (outcome.committed) {
                      _preparedInvoice = invoice;
                    }
                    return outcome;
                  };
                },
                onCommitted: () {
                  final invoice = _preparedInvoice!;
                  Navigator.pushReplacement<void, void>(
                    context,
                    MaterialPageRoute<void>(
                      builder: (_) => InvoiceDetailScreen(invoiceId: invoice),
                    ),
                  );
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  String? _preparedInvoice;
}
