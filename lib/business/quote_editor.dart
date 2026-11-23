import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'quote_api.dart';
import 'report_api.dart';
import 'settings_api.dart';
import 'settings_validation_messages.dart';
import 'settings_screen.dart';
import 'tax_rate.dart';

class QuoteEditor extends ConsumerStatefulWidget {
  const QuoteEditor({super.key, this.quoteId});
  final String? quoteId;
  @override
  ConsumerState<QuoteEditor> createState() => _QuoteEditorState();
}

class _EditorData {
  const _EditorData(this.quote, this.business, this.jobs, this.digits);
  final QuoteData quote;
  final BusinessSettings business;
  final ReportContext jobs;
  final int digits;
}

class _QuoteEditorState extends ConsumerState<QuoteEditor> {
  late final _id = widget.quoteId ?? newBusinessId();
  Future<_EditorData> _load() async {
    final quote = await ref.read(quoteApiProvider).read(_id);
    final business = await ref.read(businessSettingsApiProvider).read();
    final jobs = await ref.read(reportApiProvider).context();
    final currency =
        (quote.draft ?? quote.latest?.draft)?.currency ??
        business.settings.currency;
    final digits = await ref
        .read(quoteApiProvider)
        .precision(currency, quoteId: _id);
    return _EditorData(quote, business, jobs, digits);
  }

  late Future<_EditorData> _data = _load();
  @override
  Widget build(BuildContext context) => FutureBuilder<_EditorData>(
    future: _data,
    builder: (context, snapshot) {
      if (snapshot.hasData) {
        return _QuoteForm(quoteId: _id, initial: snapshot.data!);
      }
      return Scaffold(
        appBar: AppBar(title: Text(AppMessage.quoteDraft.of(context))),
        body: Center(
          child: snapshot.hasError
              ? TextButton(
                  onPressed: () => setState(() {
                    _data = _load();
                  }),
                  child: Text(AppMessage.retryQuoteDraft.of(context)),
                )
              : const CircularProgressIndicator(),
        ),
      );
    },
  );
}

class _LineFields {
  _LineFields(QuoteLine? line, int digits)
    : id = line?.id ?? newBusinessId(),
      kind = line?.kind ?? 'ITEM',
      title = TextEditingController(text: line?.title ?? ''),
      detail = TextEditingController(text: line?.detail ?? ''),
      quantity = TextEditingController(
        text: line == null ? '1' : taskQuantity(line.quantity),
      ),
      price = TextEditingController(text: money(line?.unitPrice, digits));
  _LineFields.template(TaskTemplate template, int digits, String currency)
    : id = newBusinessId(),
      kind = 'ITEM',
      title = TextEditingController(text: template.title),
      detail = TextEditingController(text: template.description),
      quantity = TextEditingController(
        text: taskQuantity(template.quantityUnits),
      ),
      price = TextEditingController(
        text: template.currency == currency
            ? money(template.priceMinor, digits)
            : '',
      );
  final String id;
  String kind;
  final TextEditingController title, detail, quantity, price;
  void dispose() {
    title.dispose();
    detail.dispose();
    quantity.dispose();
    price.dispose();
  }

  QuoteLine capture(int digits) => QuoteLine(
    id,
    title.text.trim(),
    detail.text,
    kind,
    parseTaskQuantity(quantity.text),
    parseMoney(price.text, digits) ??
        (throw QuoteValidationException(AppMessage.quoteUnitPriceInvalid)),
  );
}

class _QuoteForm extends ConsumerStatefulWidget {
  const _QuoteForm({required this.quoteId, required this.initial});
  final String quoteId;
  final _EditorData initial;
  @override
  ConsumerState<_QuoteForm> createState() => _QuoteFormState();
}

class _QuoteFormState extends ConsumerState<_QuoteForm> {
  final _form = GlobalKey<FormState>();
  late int _version, _digits;
  late String _currency;
  late _EditorData _data;
  late final TextEditingController _title, _tax, _until, _terms, _notes;
  late final List<_LineFields> _lines;
  String? _customer, _job, _notice;
  bool _pending = false, _reading = false;
  Map<String, int> get _currencies => {
    'USD': 2,
    'EUR': 2,
    'GBP': 2,
    'CAD': 2,
    'AUD': 2,
    'NZD': 2,
    'CHF': 2,
    'JPY': 0,
    'INR': 2,
    'ZAR': 2,
    'SEK': 2,
    'NOK': 2,
    'DKK': 2,
    'KWD': 3,
    _data.business.settings.currency: _data.business.settings.digits,
    if (_data.quote.latest != null)
      _data.quote.latest!.draft.currency: _data.quote.latest!.digits,
    _currency: _digits,
  };
  bool get _enabled => !_pending && !_reading;

  Future<TaskTemplate?> _chooseTaskTemplate() async {
    final search = TextEditingController();
    var results = ref.read(businessApiProvider).templates('');
    final selected = await showModalBottomSheet<TaskTemplate>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheetContext) => StatefulBuilder(
        builder: (context, updateSheet) => SafeArea(
          child: SizedBox(
            height: MediaQuery.sizeOf(context).height * .78,
            child: Padding(
              padding: EdgeInsets.fromLTRB(
                20,
                8,
                20,
                16 + MediaQuery.viewInsetsOf(context).bottom,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    AppMessage.quoteChooseTaskTemplate.of(context),
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  Text(AppMessage.quoteTemplateSeparateCopy.of(context)),
                  const SizedBox(height: 12),
                  TextField(
                    controller: search,
                    decoration: InputDecoration(
                      prefixIcon: Icon(Icons.search),
                      labelText: AppMessage.quoteSearchSavedTasks.of(context),
                    ),
                    onChanged: (value) => updateSheet(() {
                      results = ref.read(businessApiProvider).templates(value);
                    }),
                  ),
                  const SizedBox(height: 12),
                  Expanded(
                    child: FutureBuilder<List<TaskTemplate>>(
                      future: results,
                      builder: (context, snapshot) {
                        if (snapshot.hasError) {
                          return Center(
                            child: TextButton(
                              onPressed: () => updateSheet(() {
                                results = ref
                                    .read(businessApiProvider)
                                    .templates(search.text);
                              }),
                              child: Text(
                                AppMessage.quoteRetryTaskTemplates.of(context),
                              ),
                            ),
                          );
                        }
                        if (!snapshot.hasData) {
                          return const Center(
                            child: CircularProgressIndicator(),
                          );
                        }
                        final templates = snapshot.data!;
                        if (templates.isEmpty) {
                          return Center(
                            child: Text(
                              AppMessage.quoteNoTaskTemplates.of(context),
                            ),
                          );
                        }
                        return ListView.builder(
                          itemCount: templates.length,
                          itemBuilder: (context, index) {
                            final template = templates[index];
                            return ListTile(
                              leading: const Icon(Icons.checklist_outlined),
                              title: Text(template.title),
                              subtitle: Text(
                                [
                                  template.source ??
                                      AppMessage.quoteSavedTask.of(context),
                                  template.chargeType == 'SUPPLIES'
                                      ? AppMessage.quoteChargeSupplies.of(
                                          context,
                                        )
                                      : template.chargeType == 'LABOR'
                                      ? AppMessage.quoteChargeLabor.of(context)
                                      : AppMessage.quoteChargeUnclassified.of(
                                          context,
                                        ),
                                  AppMessage.quoteTemplateQuantity.format(
                                    context,
                                    {
                                      'quantity': taskQuantity(
                                        template.quantityUnits,
                                      ),
                                    },
                                  ),
                                  if (template.priceMinor == null)
                                    AppMessage.quoteTemplatePriceUnset.of(
                                      context,
                                    )
                                  else
                                    AppMessage.quoteTemplateEach.format(
                                      context,
                                      {
                                        'currency': template.currency,
                                        'amount': money(
                                          template.priceMinor,
                                          _currencies[template.currency] ??
                                              _digits,
                                        ),
                                      },
                                    ),
                                ].join(' · '),
                              ),
                              onTap: () =>
                                  Navigator.pop(sheetContext, template),
                            );
                          },
                        );
                      },
                    ),
                  ),
                  TextButton(
                    onPressed: () => Navigator.pop(sheetContext),
                    child: Text(AppMessage.cancel.of(context)),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    search.dispose();
    return selected;
  }

  Future<void> _addTaskTemplate() async {
    if (!_enabled || _lines.length >= 2000) return;
    final template = await _chooseTaskTemplate();
    if (!mounted || template == null) return;
    final line = _LineFields.template(template, _digits, _currency);
    setState(() {
      _lines.add(line);
      _notice = template.currency == _currency || template.priceMinor == null
          ? AppMessage.quoteTemplateAddedEditable.of(context)
          : AppMessage.quoteTemplateAddedCurrencyNotice.format(context, {
              'currency': template.currency,
              'quote_currency': _currency,
            });
    });
  }

  @override
  void initState() {
    super.initState();
    _data = widget.initial;
    _version = _data.quote.version;
    final source = _data.quote.draft ?? _data.quote.latest?.draft;
    _currency = source?.currency ?? _data.business.settings.currency;
    _digits = _data.digits;
    _title = TextEditingController(text: source?.title ?? '');
    _tax = TextEditingController(
      text: taxPercent(
        source?.taxUnits ?? _data.business.settings.defaultTaxUnits,
      ),
    );
    _until = TextEditingController(text: source?.validUntil ?? '');
    _terms = TextEditingController(text: source?.terms ?? '');
    _notes = TextEditingController(text: source?.notes ?? '');
    _customer = source?.customerId;
    _job = source?.jobId;
    _lines = source == null
        ? [_LineFields(null, _digits)]
        : source.lines.map((line) => _LineFields(line, _digits)).toList();
  }

  @override
  void dispose() {
    for (final controller in [_title, _tax, _until, _terms, _notes]) {
      controller.dispose();
    }
    for (final line in _lines) {
      line.dispose();
    }
    super.dispose();
  }

  String? _bounded(String? text, int limit, {bool mandatory = false}) {
    final value = text ?? '';
    if (mandatory && value.trim().isEmpty) {
      return AppMessage.quoteRequiredValue.of(context);
    }
    if (value.length > limit || value.contains('\u0000')) {
      return AppMessage.quoteFieldCharacterLimit.format(context, {
        'limit': limit.toString(),
      });
    }
    return null;
  }

  String? _date(String? text) {
    final value = text?.trim() ?? '';
    if (value.isEmpty) {
      return null;
    }
    final date = DateTime.tryParse(value);
    if (!RegExp(r'^[0-9]{4}-[0-9]{2}-[0-9]{2}$').hasMatch(value) ||
        value.startsWith('0000') ||
        date == null ||
        '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}' !=
            value) {
      return AppMessage.quoteInvalidCalendarDate.of(context);
    }
    return null;
  }

  QuoteDraft _capture() {
    final problems = <String?>[
      _bounded(_title.text, 240, mandatory: true),
      _date(_until.text),
      _bounded(_terms.text, 20000),
      _bounded(_notes.text, 20000),
      for (final line in _lines) ...[
        _bounded(line.title.text, 500, mandatory: true),
        _bounded(line.detail.text, 12000),
      ],
    ];
    final problem = problems.whereType<String>().firstOrNull;
    if (problem != null) {
      throw FormatException(problem);
    }
    return QuoteDraft(
      id: widget.quoteId,
      title: _title.text.trim(),
      currency: _currency,
      taxUnits: parseTaxPercent(_tax.text),
      customerId: _customer,
      jobId: _job,
      validUntil: _until.text.trim().isEmpty ? null : _until.text.trim(),
      terms: _terms.text,
      notes: _notes.text,
      lines: _lines.map((line) => line.capture(_digits)).toList(),
    );
  }

  Future<void> _refresh() async {
    if (!_enabled) {
      return;
    }
    setState(() => _reading = true);
    try {
      final current = await ref.read(quoteApiProvider).read(widget.quoteId);
      final business = await ref.read(businessSettingsApiProvider).read();
      final jobs = await ref.read(reportApiProvider).context();
      final digits = await ref
          .read(quoteApiProvider)
          .precision(_currency, quoteId: widget.quoteId);
      if (!mounted) {
        return;
      }
      final keep = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(AppMessage.quoteReviewRefreshTitle.of(context)),
          content: Text(
            AppMessage.quoteRefreshReviewContent.format(context, {
              'revision': AppMessage.quoteRevisionValue.format(context, {
                'revision':
                    current.latest?.revision.toString() ??
                    AppMessage.quoteNoneValue.of(context),
              }),
              'draft': AppMessage.quoteDraftTitleValue.format(context, {
                'title':
                    current.draft?.title ??
                    AppMessage.quoteNoneValue.of(context),
              }),
            }),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(AppMessage.cancel.of(context)),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(AppMessage.quoteKeepDraftRefresh.of(context)),
            ),
          ],
        ),
      );
      if (mounted && keep == true) {
        setState(() {
          _version = current.version;
          _data = _EditorData(current, business, jobs, digits);
          _digits = digits;
          _notice = AppMessage.quoteRefreshDraftSuccess.of(context);
        });
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _notice = AppMessage.quoteRefreshDraftFailed.of(context),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _reading = false);
      }
    }
  }

  void _changeCurrency(String currency, int digits) {
    if (currency == _currency) {
      return;
    }
    _currency = currency;
    _digits = digits;
    for (final line in _lines) {
      line.price.clear();
    }
    _notice = AppMessage.quoteCurrencyChanged.format(context, {
      'currency': currency,
    });
  }

  Future<void> _linkJob(String? id) async {
    if (id == null) {
      setState(() => _job = null);
      return;
    }
    setState(() => _reading = true);
    try {
      final job = await ref.read(businessSettingsApiProvider).job(id);
      final digits = await ref
          .read(quoteApiProvider)
          .precision(job.currency, quoteId: widget.quoteId);
      if (!mounted) {
        return;
      }
      setState(() {
        _job = id;
        _changeCurrency(job.currency, digits);
        _tax.text = taxPercent(job.effectiveTaxUnits);
        _customer = job.customerId;
        for (final line in _lines) {
          if (line.kind == 'HOURS' &&
              line.price.text.trim().isEmpty &&
              job.effectiveRate != null) {
            line.price.text = money(job.effectiveRate, job.digits);
          }
        }
        _notice = AppMessage.quoteLinkJobCopied.of(context);
      });
    } catch (_) {
      if (mounted) {
        setState(() => _notice = AppMessage.quoteLinkJobFailed.of(context));
      }
    } finally {
      if (mounted) {
        setState(() => _reading = false);
      }
    }
  }

  Widget _text(
    TextEditingController controller,
    String label,
    int limit, {
    int lines = 1,
    String? Function(String?)? validator,
    bool decimal = false,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: Semantics(
      identifier:
          'quote-editor-${label.toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '-')}',
      child: TextFormField(
        controller: controller,
        enabled: _enabled,
        maxLines: lines,
        maxLength: limit,
        maxLengthEnforcement: MaxLengthEnforcement.none,
        keyboardType: decimal
            ? const TextInputType.numberWithOptions(decimal: true)
            : null,
        decoration: InputDecoration(labelText: label),
        onChanged: (_) => setState(() {}),
        validator: validator ?? (value) => _bounded(value, limit),
      ),
    ),
  );
  Widget _line(_LineFields line, int index) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  AppMessage.quoteLineLabel.format(context, {
                    'number': (index + 1).toString(),
                  }),
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              IconButton(
                tooltip: AppMessage.quoteRemoveLine.format(context, {
                  'number': (index + 1).toString(),
                }),
                onPressed: !_enabled || _lines.length == 1
                    ? null
                    : () {
                        setState(() => _lines.remove(line));
                        WidgetsBinding.instance.addPostFrameCallback(
                          (_) => line.dispose(),
                        );
                      },
                icon: const Icon(Icons.delete_outline),
              ),
            ],
          ),
          DropdownButtonFormField<String>(
            initialValue: line.kind,
            decoration: InputDecoration(
              labelText: AppMessage.lineType.of(context),
            ),
            items: [
              DropdownMenuItem(
                value: 'ITEM',
                child: Text(AppMessage.fixedPriceItem.of(context)),
              ),
              DropdownMenuItem(
                value: 'HOURS',
                child: Text(AppMessage.estimatedHours.of(context)),
              ),
            ],
            onChanged: !_enabled
                ? null
                : (value) {
                    if (value != null) {
                      setState(() => line.kind = value);
                    }
                  },
          ),
          const SizedBox(height: 16),
          _text(
            line.title,
            AppMessage.quoteLineDescription.of(context),
            500,
            validator: (value) => _bounded(value, 500, mandatory: true),
          ),
          _text(
            line.quantity,
            line.kind == 'HOURS'
                ? AppMessage.estimatedHours.of(context)
                : AppMessage.quoteQuantity.of(context),
            32,
            decimal: true,
            validator: (value) {
              try {
                parseTaskQuantity(value ?? '');
                return null;
              } on FormatException catch (error) {
                return AppMessage.quoteQuantityInvalid.of(context);
              }
            },
          ),
          _text(
            line.price,
            AppMessage.quoteUnitPriceField.format(context, {
              'currency': _currency,
              'hour_suffix': line.kind == 'HOURS'
                  ? AppMessage.quotePerHourSuffix.of(context)
                  : '',
            }),
            4101,
            decimal: true,
            validator: (value) {
              try {
                return parseMoney(value ?? '', _digits) == null
                    ? AppMessage.quoteUnitPriceInvalid.of(context)
                    : null;
              } on FormatException {
                return AppMessage.quoteUnitPriceInvalid.of(context);
              }
            },
          ),
          _text(
            line.detail,
            AppMessage.quoteLineDetails.of(context),
            12000,
            lines: 3,
          ),
        ],
      ),
    ),
  );
  Widget _totals() {
    try {
      final value = quoteTotals(
        _lines.map((line) => line.capture(_digits)).toList(),
        parseTaxPercent(_tax.text),
      );
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                AppMessage.invoiceAmountLine.format(context, {
                  'label': AppMessage.invoiceSubtotal.of(context),
                  'amount': '$_currency ${money(value.subtotal, _digits)}',
                }),
              ),
              Text(
                AppMessage.invoiceAmountLine.format(context, {
                  'label': AppMessage.invoiceTax.of(context),
                  'amount': '$_currency ${money(value.tax, _digits)}',
                }),
              ),
              const SizedBox(height: 8),
              Text(
                AppMessage.invoiceAmountLine.format(context, {
                  'label': AppMessage.quoteEstimatedTotalLabel.of(context),
                  'amount': '$_currency ${money(value.total, _digits)}',
                }),
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ],
          ),
        ),
      );
    } on FormatException {
      return Text(AppMessage.quoteTotalsPreviewRequired.of(context));
    }
  }

  @override
  Widget build(BuildContext context) {
    final customers =
        ref.watch(businessSettingsProvider).value?.customers ??
        _data.business.customers;
    final jobs = _data.jobs.jobs;
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: Text(AppMessage.quoteDraft.of(context)),
          actions: [
            IconButton(
              tooltip: AppMessage.quoteDetailRefresh.of(context),
              onPressed: _enabled ? _refresh : null,
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
                _data.quote.latest == null
                    ? AppMessage.quotePrepareEstimate.of(context)
                    : AppMessage.quoteReviseNumber.format(context, {
                        'number': _data.quote.latest!.number,
                      }),
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              Text(AppMessage.quoteDraftSavingGuidance.of(context)),
              const SizedBox(height: 20),
              if (_reading) const LinearProgressIndicator(),
              if (_notice != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Text(_notice!),
                ),
              _text(
                _title,
                AppMessage.quoteTitleLabel.of(context),
                240,
                validator: (value) => _bounded(value, 240, mandatory: true),
              ),
              DropdownButtonFormField<String>(
                key: ValueKey('customer/$_customer/$_reading'),
                initialValue: _customer,
                isExpanded: true,
                decoration: InputDecoration(
                  labelText: AppMessage.optionalCustomer.of(context),
                ),
                items: [
                  DropdownMenuItem<String>(
                    value: null,
                    child: Text(AppMessage.noCustomer.of(context)),
                  ),
                  if (_customer != null &&
                      !customers.any((customer) => customer.id == _customer))
                    DropdownMenuItem(
                      value: _customer,
                      child: Text(
                        AppMessage.quoteCustomerUnavailable.of(context),
                      ),
                    ),
                  for (final customer in customers.where(
                    (customer) =>
                        !customer.archived || customer.id == _customer,
                  ))
                    DropdownMenuItem(
                      value: customer.id,
                      child: Text(
                        customer.name,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                onChanged: !_enabled
                    ? null
                    : (value) => setState(() => _customer = value),
              ),
              TextButton.icon(
                onPressed: () => Navigator.push(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => const CustomersScreen(),
                  ),
                ),
                icon: const Icon(Icons.people_outline),
                label: Text(AppMessage.manageCustomers.of(context)),
              ),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                key: ValueKey('job/$_job/$_reading'),
                initialValue: _job,
                isExpanded: true,
                decoration: InputDecoration(
                  labelText: AppMessage.optionalLinkedJob.of(context),
                ),
                items: [
                  DropdownMenuItem<String>(
                    value: null,
                    child: Text(AppMessage.noLinkedJob.of(context)),
                  ),
                  if (_job != null && !jobs.any((job) => job.id == _job))
                    DropdownMenuItem(
                      value: _job,
                      child: Text(AppMessage.quoteDeletedJobUnlink.of(context)),
                    ),
                  for (final job in jobs)
                    DropdownMenuItem(
                      value: job.id,
                      child: Text(
                        '${job.name}${job.archived ? ' · ${AppMessage.archived.of(context)}' : ''}',
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                onChanged: !_enabled ? null : _linkJob,
              ),
              Text(AppMessage.quoteLinkJobPriceGuidance.of(context)),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                key: ValueKey('currency/$_currency'),
                initialValue: _currency,
                decoration: InputDecoration(
                  labelText: AppMessage.quoteCurrency.of(context),
                ),
                items: _currencies.keys
                    .map(
                      (currency) => DropdownMenuItem(
                        value: currency,
                        child: Text(currency),
                      ),
                    )
                    .toList(),
                onChanged: !_enabled
                    ? null
                    : (value) {
                        if (value != null) {
                          final digits = _currencies[value]!;
                          setState(() => _changeCurrency(value, digits));
                        }
                      },
              ),
              const SizedBox(height: 16),
              for (var index = 0; index < _lines.length; index++)
                KeyedSubtree(
                  key: ValueKey(_lines[index].id),
                  child: _line(_lines[index], index),
                ),
              OutlinedButton.icon(
                onPressed: !_enabled || _lines.length >= 2000
                    ? null
                    : () => setState(
                        () => _lines.add(_LineFields(null, _digits)),
                      ),
                icon: const Icon(Icons.add),
                label: Text(AppMessage.addQuoteLine.of(context)),
              ),
              OutlinedButton.icon(
                onPressed: !_enabled || _lines.length >= 2000
                    ? null
                    : _addTaskTemplate,
                icon: const Icon(Icons.playlist_add),
                label: Text(AppMessage.addLineFromTemplate.of(context)),
              ),
              const SizedBox(height: 20),
              _text(
                _tax,
                AppMessage.quoteTaxPercentage.of(context),
                32,
                decimal: true,
                validator: (value) =>
                    settingsTaxInputError(context, value ?? ''),
              ),
              _totals(),
              const SizedBox(height: 20),
              _text(
                _until,
                AppMessage.quoteValidUntilOptional.of(context),
                10,
                validator: _date,
              ),
              _text(
                _terms,
                AppMessage.quotePrintedTermsOptional.of(context),
                20000,
                lines: 4,
              ),
              _text(
                _notes,
                AppMessage.quotePrintedNotesOptional.of(context),
                20000,
                lines: 4,
              ),
              BusinessCommand(
                key: ValueKey('save-quote/${widget.quoteId}'),
                label: AppMessage.quoteSaveDraft.of(context),
                enabled: _enabled,
                onPendingChanged: (value) => setState(() => _pending = value),
                create: () {
                  final valid = _form.currentState!.validate();
                  if (!valid) {
                    throw FormatException(
                      AppMessage.quoteReviewHighlightedFields.of(context),
                    );
                  }
                  final draft = _capture();
                  quoteTotals(draft.lines, draft.taxUnits);
                  final id = newBusinessId(),
                      version = _version,
                      api = ref.read(quoteApiProvider);
                  return () => api.save(id, version, draft);
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
