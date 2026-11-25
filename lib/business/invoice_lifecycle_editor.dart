import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'invoice_lifecycle_api.dart';
import 'payment_api.dart';
import 'report_api.dart';
import 'settings_validation_messages.dart';
import 'tax_rate.dart';

String _invoiceLifecycleFormatError(BuildContext context, FormatException e) =>
    switch (e.message) {
      'INVALID_REPLACEMENT_TOTALS' => AppMessage.invoiceLinePriceRequired.of(
        context,
      ),
      'REPLACEMENT_AMOUNT_TOO_LARGE' => AppMessage.quoteInvoiceTooLarge.of(
        context,
      ),
      'REPLACEMENT_BELOW_PAYMENT_CREDIT' =>
        AppMessage.invoiceUseRefreshedBalance.of(context),
      _ => localizedBusinessFormatError(context, e),
    };

class InvoiceLifecycleResult {
  const InvoiceLifecycleResult(this.replacementId);
  final String? replacementId;
}

class InvoiceLifecycleEditor extends ConsumerWidget {
  const InvoiceLifecycleEditor({
    super.key,
    required this.invoiceId,
    required this.replace,
  });
  final String invoiceId;
  final bool replace;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final result = ref.watch(invoiceLifecycleProvider(invoiceId));
    return result.when(
      skipLoadingOnReload: true,
      skipError: true,
      loading: () => Scaffold(
        appBar: AppBar(
          title: Text(AppMessage.invoiceCorrectionTitle.of(context)),
        ),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (_, _) => Scaffold(
        appBar: AppBar(
          title: Text(AppMessage.invoiceCorrectionTitle.of(context)),
        ),
        body: Center(
          child: TextButton(
            onPressed: () =>
                ref.invalidate(invoiceLifecycleProvider(invoiceId)),
            child: Text(AppMessage.retryInvoiceCorrection.of(context)),
          ),
        ),
      ),
      data: (data) => _LifecycleEditor(
        key: ValueKey(invoiceId),
        data: data,
        replace: replace,
        refreshFailed: result.hasError,
      ),
    );
  }
}

class _LineFields {
  _LineFields(this.original, int digits)
    : title = TextEditingController(text: original.title),
      detail = TextEditingController(
        text: original.quantity == null
            ? original.detail
            : original.detail.split('\n').skip(1).join('\n'),
      ),
      price = TextEditingController(
        text: money(original.unitPrice ?? original.amount, digits),
      ),
      quantity = TextEditingController(
        text: taskQuantity(original.quantity ?? '10000'),
      );
  final InvoiceEditLine original;
  final TextEditingController title, detail, price, quantity;
  bool included = true;
  void dispose() {
    title.dispose();
    detail.dispose();
    price.dispose();
    quantity.dispose();
  }

  InvoiceEditLine freeze(BuildContext context, int digits) {
    if (original.amount == null) return original;
    if (title.text.trim().isEmpty) {
      throw FormatException(
        AppMessage.invoiceLineDescriptionRequired.of(context),
      );
    }
    final unit = original.quantity != null;
    final priceMinor = unit
        ? parseMoney(price.text, digits)
        : parsePaymentMoney(price.text, digits);
    if (priceMinor == null) {
      throw FormatException(AppMessage.invoiceLinePriceRequired.of(context));
    }
    final units = unit ? parseTaskQuantity(quantity.text) : null;
    return InvoiceEditLine(
      original.index,
      title.text.trim(),
      unit
          ? '${AppMessage.invoiceQuantityPriceBasis.of(context)}\n${detail.text}'
          : priceMinor != original.amount && detail.text == original.detail
          ? '${AppMessage.invoiceAdjustedAmountBasis.of(context)}\n${detail.text}'
          : detail.text,
      unit ? taskAmount(priceMinor, units!) : priceMinor,
      quantity: units,
      unitPrice: unit ? priceMinor : null,
    );
  }
}

class _LifecycleEditor extends ConsumerStatefulWidget {
  const _LifecycleEditor({
    super.key,
    required this.data,
    required this.replace,
    required this.refreshFailed,
  });
  final InvoiceLifecycleData data;
  final bool replace, refreshFailed;
  @override
  ConsumerState<_LifecycleEditor> createState() => _LifecycleEditorState();
}

class _LifecycleEditorState extends ConsumerState<_LifecycleEditor> {
  final _form = GlobalKey<FormState>();
  final _reason = TextEditingController();
  late final TextEditingController _tax;
  late final List<_LineFields> _lines;
  late int _version;
  final _released = <String>{};
  bool _pending = false, _confirmed = false;
  String? _replacementId, _message;
  ReportSelection? _added;
  InvoicePayments get _invoice => widget.data.invoice;
  bool get _stale => _version != _invoice.version;
  bool get _allowed =>
      _invoice.status != 'VOID' &&
      (_invoice.due != '0' || _invoice.total == '0') &&
      (widget.replace || _invoice.paid == '0');
  @override
  void initState() {
    super.initState();
    _version = _invoice.version;
    _tax = TextEditingController(
      text: widget.data.taxUnits == null
          ? ''
          : taxPercent(widget.data.taxUnits!),
    );
    _lines = widget.data.lines
        .map((line) => _LineFields(line, _invoice.digits))
        .toList();
  }

  @override
  void dispose() {
    _reason.dispose();
    _tax.dispose();
    for (final line in _lines) {
      line.dispose();
    }
    super.dispose();
  }

  void _changed([String? _]) => setState(() {
    _confirmed = false;
    _message = null;
  });
  String _amount(String minor) =>
      '${_invoice.currency} ${money(minor, _invoice.digits)}';
  List<InvoiceEditLine> _freeze() => _lines
      .where((line) => line.included)
      .map((line) => line.freeze(context, _invoice.digits))
      .toList();
  String _addedMinor(InvoiceBillingPreview? preview) {
    if (_added == null) return '0';
    if (preview == null ||
        preview.version != _invoice.version ||
        preview.problem != null ||
        preview.currency != _invoice.currency ||
        preview.subtotal == null) {
      throw FormatException(AppMessage.invoiceRefreshAddedWork.of(context));
    }
    return parsePaymentMoney(preview.subtotal!, _invoice.digits)!;
  }

  ({String subtotal, String tax, String total, String credit, String due})
  _totals(InvoiceBillingPreview? preview) => replacementTotals(
    _freeze(),
    _addedMinor(preview),
    parseTaxPercent(_tax.text),
    _invoice.paid,
  );
  String _date(DateTime date) =>
      '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
  Future<void> _chooseWork() async {
    final today = DateTime.parse(_invoice.localNow);
    final date = DateTime.utc(today.year, today.month, today.day);
    final range = await showDateRangePicker(
      context: context,
      firstDate: DateTime.utc(1),
      lastDate: DateTime.utc(9999, 12, 31),
      initialDateRange: DateTimeRange(start: date, end: date),
    );
    if (!mounted || range == null) return;
    setState(() {
      _added = ReportSelection(
        _date(range.start),
        _date(range.end),
        _invoice.timezone,
        [_invoice.jobId],
      );
      _confirmed = false;
      _message = null;
    });
  }

  String _amountLine(AppMessage label, String amount) => AppMessage
      .invoiceAmountLine
      .format(context, {'label': label.of(context), 'amount': _amount(amount)});

  Future<void> _review(InvoiceBillingPreview? preview) async {
    FocusManager.instance.primaryFocus?.unfocus();
    if (!_form.currentState!.validate()) return;
    if (_pending || _stale || !_allowed || widget.refreshFailed) return;
    String summary;
    try {
      if (widget.replace) {
        final lines = _freeze();
        if (!lines.any((line) => line.amount != null) && _added == null) {
          throw FormatException(
            AppMessage.invoiceReplacementNeedsWork.of(context),
          );
        }
        final totals = _totals(preview);
        summary = [
          AppMessage.invoiceReplacementReviewAmounts.format(context, {
            'total': _amount(totals.total),
            'credit': _amount(totals.credit),
            'due': _amount(totals.due),
          }),
          AppMessage.invoiceReplacementReviewClaims.format(context, {
            'count': MaterialLocalizations.of(context)
                .formatDecimal(_released.length),
          }),
        ].join('\n\n');
      } else {
        summary = AppMessage.invoiceVoidReviewSummary.format(context, {
          'number': _invoice.number,
          'due': _amount(_invoice.due),
        });
      }
    } on FormatException catch (error) {
      setState(() => _message = _invoiceLifecycleFormatError(context, error));
      return;
    }
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          (widget.replace
                  ? AppMessage.invoiceReviewReplaceTitle
                  : AppMessage.invoiceReviewVoidTitle)
              .format(context, {'number': _invoice.number}),
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
        content: SingleChildScrollView(
          child: Text(
            '$summary\n\n${AppMessage.invoiceReviewPermanent.of(context)}',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppMessage.keepEditing.of(context)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppMessage.confirmReview.of(context)),
          ),
        ],
      ),
    );
    if (mounted && accepted == true) setState(() => _confirmed = true);
  }

  Widget _field(
    TextEditingController controller,
    String label, {
    int max = 500,
    bool numeric = false,
    int lines = 1,
    String? identifier,
  }) => Semantics(
    identifier: identifier,
    child: Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextFormField(
        controller: controller,
        enabled: !_pending,
        maxLength: max,
        maxLengthEnforcement: numeric
            ? MaxLengthEnforcement.none
            : MaxLengthEnforcement.enforced,
        minLines: lines,
        maxLines: lines + 2,
        keyboardType: numeric
            ? const TextInputType.numberWithOptions(decimal: true)
            : TextInputType.multiline,
        decoration: InputDecoration(labelText: label),
        onChanged: _changed,
      ),
    ),
  );
  @override
  Widget build(BuildContext context) {
    final previewResult = _added == null
        ? null
        : ref.watch(invoiceBillingPreviewProvider(_added!));
    final preview = previewResult?.asData?.value;
    final eligible =
        !_pending &&
        !_stale &&
        _allowed &&
        !widget.refreshFailed &&
        !(previewResult?.hasError ?? false);
    String? previewError;
    ({String subtotal, String tax, String total, String credit, String due})?
    totals;
    if (widget.replace) {
      try {
        totals = _totals(preview);
      } on FormatException catch (error) {
        previewError = _invoiceLifecycleFormatError(context, error);
      }
    }
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: Text(
            (widget.replace
                    ? AppMessage.voidAndReplaceInvoice
                    : AppMessage.voidInvoice)
                .of(context),
          ),
          actions: [
            IconButton(
              tooltip: AppMessage.refreshInvoiceCorrection.of(context),
              onPressed: _pending
                  ? null
                  : () => ref.invalidate(
                      invoiceLifecycleProvider(_invoice.invoiceId),
                    ),
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: Form(
          key: _form,
          child: ListView(
            padding: EdgeInsets.fromLTRB(
              24,
              24,
              24,
              24 + MediaQuery.paddingOf(context).bottom,
            ),
            children: [
              Text(
                _invoice.number,
                style: Theme.of(context).textTheme.titleLarge,
              ),
              Text('${_invoice.jobName} · ${_invoice.customer}'),
              const SizedBox(height: 12),
              Text(
                _amountLine(
                  AppMessage.invoiceExistingPaymentCredit,
                  _invoice.paid,
                ),
              ),
              const SizedBox(height: 12),
              if (!_allowed)
                Text(AppMessage.invoiceCorrectionUnavailable.of(context)),
              if (_stale || widget.refreshFailed) ...[
                Text(AppMessage.invoiceCorrectionStale.of(context)),
                TextButton(
                  onPressed: _pending || widget.refreshFailed
                      ? null
                      : () => setState(() {
                          _version = _invoice.version;
                          _confirmed = false;
                        }),
                  child: Text(
                    AppMessage.invoiceUseRefreshedBalance.of(context),
                  ),
                ),
              ],
              if (widget.replace) ...[
                Text(AppMessage.invoiceReplacementRetainedDetails.of(context)),
                const SizedBox(height: 20),
                for (final line in _lines)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          CheckboxListTile(
                            contentPadding: EdgeInsets.zero,
                            value: line.included,
                            title: Text(line.original.title),
                            subtitle: line.original.amount == null
                                ? Text(
                                    AppMessage.invoiceLineTimeNoCharge.of(
                                      context,
                                    ),
                                  )
                                : null,
                            onChanged: _pending
                                ? null
                                : (value) => setState(() {
                                    line.included = value!;
                                    _confirmed = false;
                                  }),
                          ),
                          if (line.included &&
                              line.original.amount != null) ...[
                            _field(
                              line.title,
                              AppMessage.invoiceLineDescription.of(context),
                            ),
                            if (line.original.quantity != null)
                              _field(
                                line.quantity,
                                AppMessage.invoiceLineQuantity.of(context),
                                numeric: true,
                                max: 32,
                                identifier:
                                    'invoice-correction-quantity-${line.original.index}',
                              ),
                            _field(
                              line.price,
                              (line.original.quantity == null
                                      ? AppMessage.invoiceLineAmount
                                      : AppMessage.invoiceLineUnitPrice)
                                  .format(context, {
                                    'currency': _invoice.currency,
                                  }),
                              numeric: true,
                              max: 4101,
                              identifier:
                                  'invoice-correction-price-${line.original.index}',
                            ),
                            _field(
                              line.detail,
                              AppMessage.invoiceLinePrintedDetails.of(context),
                              max: 12000,
                              lines: 2,
                            ),
                          ] else if (line.included)
                            SelectableText(line.original.detail),
                        ],
                      ),
                    ),
                  ),
                const SizedBox(height: 16),
                if (!_invoice.jobDeleted)
                  OutlinedButton.icon(
                    onPressed: _pending ? null : _chooseWork,
                    icon: const Icon(Icons.playlist_add),
                    label: Text(AppMessage.invoiceAddUnbilledWork.of(context)),
                  ),
                if (_added != null) ...[
                  Text(
                    '${_added!.firstDate} — ${_added!.lastDate} · ${_added!.timezone}',
                  ),
                  TextButton(
                    onPressed: _pending
                        ? null
                        : () => setState(() {
                            _added = null;
                            _confirmed = false;
                          }),
                    child: Text(AppMessage.invoiceRemoveAddedWork.of(context)),
                  ),
                  if (previewResult!.isLoading) const LinearProgressIndicator(),
                  if (previewResult.hasError)
                    TextButton(
                      onPressed: _pending
                          ? null
                          : () => ref.invalidate(
                              invoiceBillingPreviewProvider(_added!),
                            ),
                      child: Text(AppMessage.invoiceRetryAddedWork.of(context)),
                    ),
                  if (preview != null) ...[
                    if (preview.problem != null)
                      Text(
                        (switch (preview.problem) {
                          'NO_BILLABLE_ITEMS' => AppMessage.invoiceAddedNoWork,
                          'LEGACY_INVOICE_REVIEW_REQUIRED' =>
                            AppMessage.invoiceAddedReviewOlder,
                          'HOURLY_RATE_REQUIRED' =>
                            AppMessage.invoiceAddedHourlyRate,
                          'TASK_PRICES_REQUIRED' =>
                            AppMessage.invoiceAddedTaskPrices,
                          _ => AppMessage.invoiceAddedPrepareFailed,
                        }).of(context),
                      ),
                    for (final line in preview.lines)
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(line.title),
                        subtitle: Text(line.detail),
                        trailing: Text(line.amount),
                      ),
                    if (preview.excluded.isNotEmpty)
                      Text(AppMessage.invoiceAddedExcludesBilled.of(context)),
                  ],
                ],
                const SizedBox(height: 16),
                _field(
                  _tax,
                  AppMessage.invoiceReplacementTax.of(context),
                  numeric: true,
                  max: 32,
                  identifier: 'invoice-correction-tax',
                ),
                if (widget.data.taxUnits == null)
                  Text(AppMessage.invoiceHistoricalTaxUnknown.of(context)),
                ExpansionTile(
                  title: Text(AppMessage.invoiceReviewReservations.of(context)),
                  children: [
                    Text(AppMessage.invoiceReservationHelp.of(context)),
                    for (final claim in widget.data.claims)
                      CheckboxListTile(
                        value: _released.contains(claim.id),
                        title: Text(claim.label),
                        subtitle: Text(
                          AppMessage.invoiceReleaseReservation.of(context),
                        ),
                        onChanged: _pending
                            ? null
                            : (value) => setState(() {
                                if (value!) {
                                  _released.add(claim.id);
                                } else {
                                  _released.remove(claim.id);
                                }
                                _confirmed = false;
                              }),
                      ),
                  ],
                ),
                if (totals != null)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Text(
                            _amountLine(
                              AppMessage.invoiceSubtotal,
                              totals.subtotal,
                            ),
                          ),
                          Text(_amountLine(AppMessage.invoiceTax, totals.tax)),
                          Text(
                            _amountLine(
                              AppMessage.invoiceReplacementTotal,
                              totals.total,
                            ),
                          ),
                          Text(
                            _amountLine(
                              AppMessage.invoiceCredit,
                              totals.credit,
                            ),
                          ),
                          Text(
                            _amountLine(
                              AppMessage.invoiceBalanceDue,
                              totals.due,
                            ),
                            style: Theme.of(context).textTheme.titleLarge,
                          ),
                        ],
                      ),
                    ),
                  ),
                if (previewError != null) Text(previewError),
              ] else
                Text(AppMessage.invoiceVoidReleaseHelp.of(context)),
              const SizedBox(height: 20),
              Semantics(
                identifier: 'invoice-correction-reason',
                child: TextFormField(
                  controller: _reason,
                  enabled: !_pending,
                  minLines: 2,
                  maxLines: 4,
                  maxLength: 4000,
                  decoration: InputDecoration(
                    labelText: AppMessage.invoiceInternalCorrectionReason.of(
                      context,
                    ),
                  ),
                  onChanged: _changed,
                  validator: (value) => (value?.trim().length ?? 0) < 10
                      ? AppMessage.paymentCorrectionMinLength.of(context)
                      : null,
                ),
              ),
              Text(AppMessage.invoiceCorrectionPrivacyHelp.of(context)),
              const SizedBox(height: 16),
              if (_message != null) Text(_message!),
              OutlinedButton(
                onPressed: eligible ? () => _review(preview) : null,
                child: Text(AppMessage.reviewInvoiceCorrection.of(context)),
              ),
              if (_confirmed)
                Text(AppMessage.invoiceReviewConfirmed.of(context)),
              const SizedBox(height: 12),
              FilledButtonTheme(
                key: const ValueKey('invoice-lifecycle-submit'),
                data: FilledButtonThemeData(
                  style: FilledButton.styleFrom(
                    backgroundColor: Theme.of(context).colorScheme.error,
                    foregroundColor: Theme.of(context).colorScheme.onError,
                  ),
                ),
                child: BusinessCommand(
                  key: const ValueKey('invoice-lifecycle-command'),
                  label: widget.replace
                      ? AppMessage.issueReplacementInvoice.of(context)
                      : AppMessage.confirmInvoiceVoid.of(context),
                  enabled: eligible && _confirmed,
                  onPendingChanged: (value) {
                    if (mounted) setState(() => _pending = value);
                  },
                  create: () {
                    if (!_confirmed ||
                        _stale ||
                        !_allowed ||
                        widget.refreshFailed) {
                      throw FormatException(
                        AppMessage.invoiceRefreshCorrectionFirst.of(context),
                      );
                    }
                    if (!_form.currentState!.validate()) {
                      throw FormatException(
                        AppMessage.invoiceReviewCorrectionReason.of(context),
                      );
                    }
                    final api = ref.read(invoiceLifecycleApiProvider),
                        id = newBusinessId(),
                        version = _version;
                    if (!widget.replace) {
                      final draft = InvoiceVoidDraft(
                        _invoice.invoiceId,
                        _reason.text,
                        _invoice.timezone,
                      );
                      return () => api.voidInvoice(id, version, draft);
                    }
                    _totals(preview);
                    _replacementId = newBusinessId();
                    final draft = InvoiceReplacementDraft(
                      invoiceId: _invoice.invoiceId,
                      replacementId: _replacementId!,
                      lines: _freeze(),
                      taxUnits: parseTaxPercent(_tax.text),
                      reason: _reason.text,
                      timezone: _invoice.timezone,
                      releasedClaims: _released.toList(),
                      addedWork: _added,
                    );
                    final languageTag = Localizations.localeOf(context)
                        .toLanguageTag();
                    return () => api.replace(
                      id,
                      version,
                      draft,
                      languageTag: languageTag,
                    );
                  },
                  onCommitted: () {
                    ref.invalidate(invoicePaymentsProvider(_invoice.invoiceId));
                    ref.invalidate(
                      invoiceLifecycleProvider(_invoice.invoiceId),
                    );
                    Navigator.pop(
                      context,
                      InvoiceLifecycleResult(_replacementId),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
