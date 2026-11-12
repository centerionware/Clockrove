import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'invoice_lifecycle_api.dart';
import 'payment_api.dart';
import 'report_api.dart';
import 'tax_rate.dart';

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
        appBar: AppBar(title: const Text('Invoice correction')),
        body: const Center(child: CircularProgressIndicator()),
      ),
      error: (_, _) => Scaffold(
        appBar: AppBar(title: const Text('Invoice correction')),
        body: Center(
          child: TextButton(
            onPressed: () =>
                ref.invalidate(invoiceLifecycleProvider(invoiceId)),
            child: const Text('Retry invoice correction'),
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

  InvoiceEditLine freeze(int digits) {
    if (original.amount == null) return original;
    if (title.text.trim().isEmpty) {
      throw const FormatException('Every charged line needs a description.');
    }
    final unit = original.quantity != null;
    final priceMinor = unit
        ? parseMoney(price.text, digits)
        : parsePaymentMoney(price.text, digits);
    if (priceMinor == null) {
      throw const FormatException('Enter a price for every included line.');
    }
    final units = unit ? parseTaskQuantity(quantity.text) : null;
    return InvoiceEditLine(
      original.index,
      title.text.trim(),
      unit
          ? 'Quantity and unit price\n${detail.text}'
          : priceMinor != original.amount && detail.text == original.detail
          ? 'Adjusted amount. Original basis:\n${detail.text}'
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
      .map((line) => line.freeze(_invoice.digits))
      .toList();
  String _addedMinor(InvoiceBillingPreview? preview) {
    if (_added == null) return '0';
    if (preview == null ||
        preview.version != _invoice.version ||
        preview.problem != null ||
        preview.currency != _invoice.currency ||
        preview.subtotal == null) {
      throw const FormatException(
        'Refresh and review the added work before issuing a replacement.',
      );
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

  Future<void> _review(InvoiceBillingPreview? preview) async {
    FocusManager.instance.primaryFocus?.unfocus();
    if (!_form.currentState!.validate()) return;
    if (_pending || _stale || !_allowed || widget.refreshFailed) return;
    String summary;
    try {
      if (widget.replace) {
        final lines = _freeze();
        if (!lines.any((line) => line.amount != null) && _added == null) {
          throw const FormatException(
            'Include a charged line or actual unbilled work.',
          );
        }
        final totals = _totals(preview);
        summary =
            'Replacement total: ${_amount(totals.total)}\nExisting credit: ${_amount(totals.credit)}\nRemaining balance: ${_amount(totals.due)}\n\n'
            '${_released.length} work reservations will be released. All other reservations move to the replacement. Payments retain their original identity; no new cash is recorded.';
      } else {
        summary =
            'Cancel ${_invoice.number} and its ${_amount(_invoice.due)} unpaid balance. Its work reservations will be released for billing again.';
      }
    } on FormatException catch (error) {
      setState(() => _message = error.message);
      return;
    }
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          widget.replace
              ? 'Void and replace ${_invoice.number}?'
              : 'Void ${_invoice.number}?',
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
        content: SingleChildScrollView(
          child: Text(
            '$summary\n\nThis cannot be undone in place. The original invoice and its history remain saved.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Keep editing'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Confirm review'),
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
        previewError = error.message;
      }
    }
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.replace ? 'Void & replace' : 'Void invoice'),
          actions: [
            IconButton(
              tooltip: 'Refresh correction details',
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
            padding: const EdgeInsets.all(24),
            children: [
              Text(
                _invoice.number,
                style: Theme.of(context).textTheme.titleLarge,
              ),
              Text('${_invoice.jobName} · ${_invoice.customer}'),
              const SizedBox(height: 12),
              Text('Existing payment credit: ${_amount(_invoice.paid)}'),
              const SizedBox(height: 12),
              if (!_allowed)
                const Text(
                  'This invoice can no longer be corrected here. Return to its details and review its status.',
                ),
              if (_stale || widget.refreshFailed) ...[
                const Text(
                  'Invoice data changed or refresh failed. Your draft is kept. Refresh, then review the latest balance before continuing.',
                ),
                TextButton(
                  onPressed: _pending || widget.refreshFailed
                      ? null
                      : () => setState(() {
                          _version = _invoice.version;
                          _confirmed = false;
                        }),
                  child: const Text('Use refreshed balance'),
                ),
              ],
              if (widget.replace) ...[
                const Text(
                  'The original customer, currency, company and style are retained. Edit the new invoice below.',
                ),
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
                                ? const Text('Time detail · no separate charge')
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
                            _field(line.title, 'Line description'),
                            if (line.original.quantity != null)
                              _field(
                                line.quantity,
                                'Quantity',
                                numeric: true,
                                max: 32,
                                identifier:
                                    'invoice-correction-quantity-${line.original.index}',
                              ),
                            _field(
                              line.price,
                              line.original.quantity == null
                                  ? 'Line amount (${_invoice.currency})'
                                  : 'Unit price (${_invoice.currency})',
                              numeric: true,
                              max: 4101,
                              identifier:
                                  'invoice-correction-price-${line.original.index}',
                            ),
                            _field(
                              line.detail,
                              'Line details (printed)',
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
                    label: const Text('Add unbilled work'),
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
                    child: const Text('Remove added work'),
                  ),
                  if (previewResult!.isLoading) const LinearProgressIndicator(),
                  if (previewResult.hasError)
                    TextButton(
                      onPressed: _pending
                          ? null
                          : () => ref.invalidate(
                              invoiceBillingPreviewProvider(_added!),
                            ),
                      child: const Text('Retry added work'),
                    ),
                  if (preview != null) ...[
                    if (preview.problem != null)
                      Text(switch (preview.problem) {
                        'NO_BILLABLE_ITEMS' =>
                          'No unbilled work remains in those dates.',
                        'LEGACY_INVOICE_REVIEW_REQUIRED' => 'Review the billed work on older invoices before adding new work.',
                        'HOURLY_RATE_REQUIRED' => 'Set an hourly rate for the added work in job settings.',
                        'TASK_PRICES_REQUIRED' =>
                          'Set a unit price on every added task.',
                        _ => 'The added work could not be prepared. Review the job report and date range.',
                      }),
                    for (final line in preview.lines)
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(line.title),
                        subtitle: Text(line.detail),
                        trailing: Text(line.amount),
                      ),
                    if (preview.excluded.isNotEmpty)
                      const Text(
                        'Previously invoiced work is excluded from these additions.',
                      ),
                  ],
                ],
                const SizedBox(height: 16),
                _field(
                  _tax,
                  'Replacement tax (%)',
                  numeric: true,
                  max: 32,
                  identifier: 'invoice-correction-tax',
                ),
                if (widget.data.taxUnits == null)
                  const Text(
                    'Historical tax is unknown. Choose the replacement tax explicitly.',
                  ),
                ExpansionTile(
                  title: const Text('Review work reservations'),
                  children: [
                    const Text(
                      'Removing an invoice line keeps its work reserved. Release only the specific work you intend to bill again. Line titles are not used to guess source links.',
                    ),
                    for (final claim in widget.data.claims)
                      CheckboxListTile(
                        value: _released.contains(claim.id),
                        title: Text(claim.label),
                        subtitle: const Text('Release this reservation'),
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
                          Text('Subtotal: ${_amount(totals.subtotal)}'),
                          Text('Tax: ${_amount(totals.tax)}'),
                          Text('Replacement total: ${_amount(totals.total)}'),
                          Text('Credit: ${_amount(totals.credit)}'),
                          Text(
                            'Balance due: ${_amount(totals.due)}',
                            style: Theme.of(context).textTheme.titleLarge,
                          ),
                        ],
                      ),
                    ),
                  ),
                if (previewError != null) Text(previewError),
              ] else
                const Text(
                  'Voiding releases this invoice’s work for billing again. The original document is retained and will no longer be collectible.',
                ),
              const SizedBox(height: 20),
              Semantics(
                identifier: 'invoice-correction-reason',
                child: TextFormField(
                  controller: _reason,
                  enabled: !_pending,
                  minLines: 2,
                  maxLines: 4,
                  maxLength: 4000,
                  decoration: const InputDecoration(
                    labelText: 'Internal correction reason',
                  ),
                  onChanged: _changed,
                  validator: (value) => (value?.trim().length ?? 0) < 10
                      ? 'Explain the correction in at least 10 characters.'
                      : null,
                ),
              ),
              const Text(
                'The reason is retained in history and is not printed on the customer’s copy.',
              ),
              const SizedBox(height: 16),
              if (_message != null) Text(_message!),
              OutlinedButton(
                onPressed: eligible ? () => _review(preview) : null,
                child: const Text('Review correction'),
              ),
              if (_confirmed)
                const Text(
                  'Review confirmed. Confirm below to save this correction.',
                ),
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
                      ? 'Issue replacement invoice'
                      : 'Confirm void',
                  enabled: eligible && _confirmed,
                  onPendingChanged: (value) {
                    if (mounted) setState(() => _pending = value);
                  },
                  create: () {
                    if (!_confirmed ||
                        _stale ||
                        !_allowed ||
                        widget.refreshFailed) {
                      throw const FormatException(
                        'Refresh and review this correction first.',
                      );
                    }
                    if (!_form.currentState!.validate()) {
                      throw const FormatException(
                        'Review the correction reason.',
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
                    return () => api.replace(id, version, draft);
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
