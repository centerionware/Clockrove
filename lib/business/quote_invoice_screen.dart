import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'invoice_detail_screen.dart';
import 'legacy_invoice_screen.dart';
import 'quote_api.dart';
import 'quote_invoice_api.dart';
import 'quote_invoice_format.dart';
import 'quote_invoice_review_dialog.dart';
import 'quote_scope_api.dart';
import 'quote_scope_screen.dart';
import 'settings_validation_messages.dart';
import 'tax_rate.dart';

AppMessage quoteInvoiceProblem(String? code) => switch (code) {
  'QUOTE_INVOICE_MAPPING_REQUIRED' => AppMessage.quoteInvoiceMappingRequired,
  'QUOTE_ACCEPTANCE_REQUIRED' => AppMessage.quoteAcceptanceRequired,
  'QUOTE_HISTORICAL_REVIEW_REQUIRED' =>
    AppMessage.quoteHistoricalReviewRequired,
  'QUOTE_INVOICE_SOURCES_CHANGED' => AppMessage.quoteInvoiceSourcesChanged,
  'NO_BILLABLE_ITEMS' => AppMessage.quoteInvoiceNoBillableItems,
  'LEGACY_INVOICE_REVIEW_REQUIRED' => AppMessage.quoteInvoiceLegacyReview,
  'TASK_PRICES_REQUIRED' ||
  'HOURLY_RATE_REQUIRED' => AppMessage.quoteInvoiceCurrentPricesMissing,
  'JOB_UNAVAILABLE' => AppMessage.quoteInvoiceJobUnavailable,
  'QUOTE_SCOPE_KIND_CHANGED' ||
  'QUOTE_JOB_MODE_MISMATCH' ||
  'QUOTE_SCOPE_SPLIT_REQUIRED' => AppMessage.quoteInvoiceScopeMismatch,
  'CURRENCY_MISMATCH' ||
  'QUOTE_CURRENCY_PRECISION_CHANGED' => AppMessage.quoteInvoiceCurrencyMismatch,
  'INVALID_REPORT_RANGE' => AppMessage.quoteInvoiceInvalidRange,
  'INVOICE_TOO_LARGE' => AppMessage.quoteInvoiceTooLarge,
  _ => AppMessage.quoteInvoiceReviewUnavailable,
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
        appBar: AppBar(title: Text(AppMessage.invoiceActualWork.of(context))),
        body: Center(
          child: snapshot.hasError
              ? TextButton(
                  onPressed: () => setState(() {
                    _setup = _load();
                  }),
                  child: Text(AppMessage.retryQuoteInvoiceSetup.of(context)),
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
      throw FormatException(
        AppMessage.quoteInvoiceDateRangeInvalid.of(context),
      );
    }
    if (_job == null || zone.isEmpty || zone.length > 100) {
      throw FormatException(
        AppMessage.quoteInvoiceJobTimezoneRequired.of(context),
      );
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
        setState(() => _message = localizedBusinessFormatError(context, error));
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _readFailed = true;
          _message = AppMessage.quoteInvoiceAvailableWorkReadFailed.of(context);
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
          _message = AppMessage.quoteInvoiceScopeReadFailed.of(context);
        });
      }
    }
  }

  QuoteInvoiceChoices _choices() {
    if (!_ready) {
      throw FormatException(
        AppMessage.quoteInvoiceLoadBeforeReview.of(context),
      );
    }
    if (_context!.historical && !_historical) {
      throw FormatException(
        AppMessage.quoteInvoiceAcknowledgeHistory.of(context),
      );
    }
    if (_missingSelections) {
      throw FormatException(AppMessage.quoteInvoiceSourcesStale.of(context));
    }
    if (_tasks.isEmpty && _times.isEmpty) {
      throw FormatException(
        AppMessage.quoteInvoiceSelectActualWork.of(context),
      );
    }
    if (_context!.mode == 'HOURLY' && !_timeAllocation) {
      throw FormatException(
        AppMessage.quoteInvoiceConfirmTimeBelongs.of(context),
      );
    }
    if (_currentPrices && !_currentAvailable) {
      throw FormatException(
        AppMessage.quoteInvoiceCurrentPriceRequired.of(context),
      );
    }
    if (_note.text.length > 2000 || _note.text.contains('\u0000')) {
      throw FormatException(
        AppMessage.quoteInvoicePrivateNoteTooLong.of(context),
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
          _message = AppMessage.quoteInvoiceDataChanged.of(context);
        });
        return;
      }
      if (result.problem != null) {
        setState(
          () => _message = quoteInvoiceProblem(result.problem).of(context),
        );
        return;
      }
      if (result.total == null ||
          result.subtotal == null ||
          result.tax == null ||
          result.taxUnits == null ||
          result.digits != data.digits ||
          result.currency != data.currency) {
        throw FormatException(
          AppMessage.quoteInvoiceCalculationUnverified.of(context),
        );
      }
      setState(() => _reading = false);
      // Keep focus restoration from reopening the soft keyboard when the
      // confirmation dialog closes and obscuring the prepare action.
      FocusManager.instance.primaryFocus?.unfocus();
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => QuoteInvoiceReviewDialog(
          request: request,
          choices: choices,
          data: data,
          result: result,
        ),
      );
      if (mounted && confirmed == true) {
        setState(() {
          _reviewed = _Reviewed(request, choices, result);
        });
      }
    } on FormatException catch (error) {
      if (mounted) {
        setState(() => _message = localizedBusinessFormatError(context, error));
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _readFailed = true;
          _message = AppMessage.quoteInvoiceCalculationReadFailed.of(context);
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
          title: Text(AppMessage.invoiceActualWork.of(context)),
          actions: [
            IconButton(
              tooltip: AppMessage.reloadQuoteWork.of(context),
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
                AppMessage.quoteInvoiceRevisionRef.format(context, {
                  'number': _scope.issued.number,
                  'revision': widget.revision.toString(),
                }),
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              Text(AppMessage.quoteInvoiceChooseActualWork.of(context)),
              if (_reading) const LinearProgressIndicator(),
              if (_message != null)
                Text(
                  _message!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              if (_jobs.isEmpty)
                Text(AppMessage.quoteInvoicePlanAcceptedScope.of(context)),
              OutlinedButton.icon(
                onPressed: _enabled ? _plan : null,
                icon: const Icon(Icons.playlist_add_check),
                label: Text(AppMessage.reviewAcceptedScope.of(context)),
              ),
              const SizedBox(height: 12),
              Text(
                AppMessage.quoteInvoiceMappedJob.of(context),
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 4),
              DropdownButtonFormField<String>(
                key: ValueKey('invoice-job/$_job'),
                initialValue: _job,
                isExpanded: true,
                decoration: InputDecoration(
                  hintText: AppMessage.quoteInvoiceChooseJob.of(context),
                ),
                items: [
                  for (final id in jobIds)
                    DropdownMenuItem(
                      value: id,
                      child: Text(
                        jobName[id] ??
                            AppMessage.quoteInvoiceRemovedJob.of(context),
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
                AppMessage.quoteInvoiceFirstDate.of(context),
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 4),
              Semantics(
                identifier: 'quote-invoice-first',
                child: TextFormField(
                  controller: _first,
                  enabled: _enabled,
                  decoration: InputDecoration(
                    hintText: AppMessage.dateIsoHint.of(context),
                  ),
                  onChanged: (_) => setState(() => _changed(source: true)),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                AppMessage.quoteInvoiceLastDate.of(context),
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 4),
              Semantics(
                identifier: 'quote-invoice-last',
                child: TextFormField(
                  controller: _last,
                  enabled: _enabled,
                  decoration: InputDecoration(
                    hintText: AppMessage.dateIsoHint.of(context),
                  ),
                  onChanged: (_) => setState(() => _changed(source: true)),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                AppMessage.quoteInvoiceTimezone.of(context),
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 4),
              Semantics(
                identifier: 'quote-invoice-zone',
                child: TextFormField(
                  controller: _zone,
                  enabled: _enabled,
                  decoration: InputDecoration(
                    hintText: AppMessage.ianaTimezoneHint.of(context),
                  ),
                  onChanged: (_) => setState(() => _changed(source: true)),
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: _enabled && _job != null ? _load : null,
                child: Text(AppMessage.loadQuoteAvailableWork.of(context)),
              ),
              if (_dirty && data != null)
                Text(AppMessage.quoteInvoiceChangedReload.of(context)),
              if (data?.problem != null)
                Text(
                  quoteInvoiceProblem(data!.problem).of(context),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              if (data != null && accepted != null) ...[
                const SizedBox(height: 16),
                Text(
                  data.jobName,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                if (data.archived)
                  Text(AppMessage.quoteInvoiceArchivedJob.of(context)),
                Text(
                  AppMessage.quoteInvoiceAcceptedCustomer.format(context, {
                    'customer': accepted.customer.isEmpty
                        ? AppMessage.quoteInvoiceCustomerNotSpecified.of(
                            context,
                          )
                        : accepted.customer,
                  }),
                ),
                Text(
                  [
                    accepted.customerAddress,
                    accepted.email,
                  ].where((v) => v.isNotEmpty).join('\n'),
                ),
                Text(
                  AppMessage.quoteInvoiceCurrentCustomer.format(context, {
                    'customer': data.currentCustomer.isEmpty
                        ? AppMessage.quoteInvoiceCustomerNotSpecified.of(
                            context,
                          )
                        : data.currentCustomer,
                  }),
                ),
                Text(data.currentContact),
                SwitchListTile(
                  title: Text(
                    AppMessage.quoteInvoiceBillCurrentParty.of(context),
                  ),
                  subtitle: Text(
                    AppMessage.quoteInvoiceKeepAcceptedParty.of(context),
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
                  AppMessage.quoteInvoiceTaxComparison.format(context, {
                    'accepted': taxPercent(accepted.draft.taxUnits),
                    'current': taxPercent(data.currentTax),
                  }),
                ),
                SwitchListTile(
                  title: Text(AppMessage.useCurrentJobTax.of(context)),
                  value: _currentTax,
                  onChanged: !_enabled
                      ? null
                      : (value) => setState(() {
                          _currentTax = value;
                          _changed();
                        }),
                ),
                SwitchListTile(
                  title: Text(AppMessage.useCurrentPrices.of(context)),
                  subtitle: Text(
                    _currentAvailable
                        ? AppMessage.quoteInvoiceAcceptedPricesHelp.of(context)
                        : AppMessage.quoteInvoiceCurrentPricesMissingHelp.of(
                            context,
                          ),
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
                    title: Text(
                      AppMessage.quoteInvoiceHistoricalReviewed.of(context),
                    ),
                    onChanged: !_enabled
                        ? null
                        : (value) => setState(() {
                            _historical = value == true;
                            _changed();
                          }),
                  ),
                Text(
                  AppMessage.quoteInvoiceActualWorkTitle.of(context),
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                Text(AppMessage.quoteInvoiceActualWorkRules.of(context)),
                if (data.mode == 'HOURLY') ...[
                  Text(
                    AppMessage.quoteInvoiceEstimatedRate.format(context, {
                      'hours': taskQuantity(data.estimatedHours),
                      'currency': data.currency,
                      'rate': money(data.acceptedRate, data.digits),
                    }),
                  ),
                  Text(
                    AppMessage.quoteInvoiceCurrentRate.format(context, {
                      'currency': data.currency,
                      'rate': data.currentRate == null
                          ? AppMessage.quoteInvoiceRateUnset.of(context)
                          : money(data.currentRate, data.digits),
                    }),
                  ),
                  Text(
                    AppMessage.quoteInvoiceSelectedDuration.format(context, {
                      'duration': quoteInvoiceDurationLabel(
                        context,
                        data.times
                            .where((time) => _times.contains(time.key))
                            .fold(
                              BigInt.zero,
                              (sum, time) =>
                                  sum + BigInt.parse(time.durationMillis),
                            )
                            .toString(),
                      ),
                    }),
                  ),
                ],
                for (final task in data.tasks)
                  CheckboxListTile(
                    key: ValueKey('invoice-task/${task.id}'),
                    contentPadding: EdgeInsets.zero,
                    title: Text(task.title),
                    subtitle: Text(
                      AppMessage.quoteInvoiceTaskDetails.format(context, {
                        'actual': taskQuantity(task.actualQuantity),
                        'estimated': taskQuantity(task.estimatedQuantity),
                        'currency': data.currency,
                        'accepted': money(task.acceptedPrice, data.digits),
                        'current': task.currentPrice == null
                            ? AppMessage.quoteInvoiceRateUnset.of(context)
                            : money(task.currentPrice, data.digits),
                        'completed': task.completedLabel,
                      }),
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
                    subtitle: Text(
                      quoteInvoiceDurationLabel(context, time.durationMillis),
                    ),
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
                  Text(AppMessage.quoteInvoiceNoEligibleActualWork.of(context)),
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
                    child: Text(
                      AppMessage.quoteInvoiceClearUnavailable.of(context),
                    ),
                  ),
                if (data.mode == 'HOURLY')
                  CheckboxListTile(
                    value: _timeAllocation,
                    title: Text(
                      AppMessage.quoteInvoiceActualTimeBelongs.of(context),
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
                    title: Text(
                      AppMessage.quoteInvoiceAlreadyBilled.format(context, {
                        'number': item.number,
                      }),
                    ),
                    subtitle: Text(
                      item.title.isEmpty
                          ? AppMessage.reportFinalizedTime.of(context)
                          : item.title,
                    ),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: !_enabled ? null : () => _invoice(item.invoiceId),
                  ),
                for (final item in data.legacy)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(
                      AppMessage.quoteInvoiceReviewOlderBilled.format(context, {
                        'number': item.number,
                      }),
                    ),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: !_enabled
                        ? null
                        : () => _invoice(item.invoiceId, legacy: true),
                  ),
                if (data.unfinalized.isNotEmpty)
                  Text(
                    AppMessage.quoteInvoiceUnfinalizedExcluded.format(context, {
                      'count': MaterialLocalizations.of(context)
                          .formatDecimal(data.unfinalized.length),
                    }),
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
                  decoration: InputDecoration(
                    labelText: AppMessage.quoteInvoicePrivateNote.of(context),
                    helperText: AppMessage.quoteInvoicePrivateNoteHelp.of(
                      context,
                    ),
                  ),
                  onChanged: (_) => setState(_changed),
                ),
              ),
              OutlinedButton(
                onPressed: _ready && _context!.legacy.isEmpty ? _review : null,
                child: Text(AppMessage.reviewActualWorkInvoice.of(context)),
              ),
              BusinessCommand(
                key: ValueKey(
                  'quote-invoice/${widget.quoteId}/${widget.revision}',
                ),
                label: AppMessage.prepareReviewedInvoice.of(context),
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
                      AppMessage.quoteInvoiceReviewBeforePrepare.of(context),
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
