import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'invoice_screen.dart';
import 'report_api.dart';
import 'tax_rate.dart';

class InvoiceReviewScreen extends ConsumerStatefulWidget {
  const InvoiceReviewScreen({
    super.key,
    required this.selection,
    this.onPendingChanged,
  });
  final ReportSelection selection;
  final ValueChanged<bool>? onPendingChanged;
  @override
  ConsumerState<InvoiceReviewScreen> createState() =>
      _InvoiceReviewScreenState();
}

class _InvoiceReviewScreenState extends ConsumerState<InvoiceReviewScreen> {
  bool _pending = false;
  String? _invoiceId;
  void _refresh() =>
      ref.invalidate(invoiceBillingPreviewProvider(widget.selection));
  void _open(InvoiceCoverageLink invoice) => Navigator.push(
    context,
    MaterialPageRoute<void>(
      builder: (_) => InvoiceScreen(invoiceId: invoice.invoiceId),
    ),
  );
  String? _problem(String? value) => switch (value) {
    null => null,
    'LEGACY_INVOICE_REVIEW_REQUIRED' => 'Review the billed work on the older invoices below before creating another invoice for this job.',
    'NO_BILLABLE_ITEMS' => 'No unbilled work remains in these dates. Previously invoiced work is excluded.',
    'HOURLY_RATE_REQUIRED' =>
      'Set an hourly rate in Settings or this job’s settings first.',
    'TASK_PRICES_REQUIRED' =>
      'Set prices in this job’s currency for the unbilled completed tasks.',
    'INVOICE_TOO_LARGE' =>
      'Choose a shorter date range before preparing this invoice.',
    'INVALID_TAX_RATE' =>
      'Correct the tax percentage in Settings or this job’s settings.',
    _ =>
      'The invoice cannot be prepared yet. Review this job’s billing settings.',
  };
  Widget _amount(
    String label,
    String? value,
    String currency, {
    bool emphasized = false,
  }) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(child: Text(label)),
        Flexible(
          child: Text(
            value == null ? 'Not available' : '$currency $value',
            textAlign: TextAlign.end,
            style: emphasized ? Theme.of(context).textTheme.titleLarge : null,
          ),
        ),
      ],
    ),
  );
  @override
  Widget build(BuildContext context) {
    final preview = ref.watch(invoiceBillingPreviewProvider(widget.selection));
    return PopScope(
      canPop: !_pending,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Review invoice'),
          actions: [
            IconButton(
              tooltip: 'Refresh invoice preview',
              onPressed: _pending ? null : _refresh,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: preview.when(
                skipLoadingOnReload: true,
                skipError: true,
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (_, _) => Center(
                  child: FilledButton(
                    onPressed: _refresh,
                    child: const Text('Retry invoice preview'),
                  ),
                ),
                data: (data) => ListView(
                  padding: const EdgeInsets.all(24),
                  children: [
                    if (preview.hasError) ...[
                      const Text(
                        'Could not refresh the preview. Your current operation is kept.',
                      ),
                      TextButton(
                        onPressed: _pending ? null : _refresh,
                        child: const Text('Retry invoice preview'),
                      ),
                    ],
                    Text(
                      data.jobName,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '${widget.selection.firstDate} — ${widget.selection.lastDate} · ${widget.selection.timezone}',
                    ),
                    const SizedBox(height: 16),
                    Text(
                      data.legacy.isEmpty
                          ? 'Only unbilled finalized work or completed billable tasks are included. Creating the invoice reserves this work even if PDF export or email is interrupted.'
                          : 'Older invoices need source review before unbilled amounts can be calculated. No new invoice can be created until they are reconciled.',
                    ),
                    const SizedBox(height: 20),
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: Column(
                          children: [
                            _amount(
                              'Unbilled subtotal',
                              data.subtotal,
                              data.currency,
                            ),
                            _amount(
                              data.taxUnits >= 0 && data.taxUnits <= 1000000
                                  ? 'Tax (${taxPercent(data.taxUnits)}%)'
                                  : 'Tax',
                              data.tax,
                              data.currency,
                            ),
                            const Divider(),
                            _amount(
                              'Invoice total',
                              data.total,
                              data.currency,
                              emphasized: true,
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),
                    if (data.problem != null)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 16),
                        child: Text(
                          _problem(data.problem)!,
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                      ),
                    BusinessCommand(
                      key: const ValueKey('prepare-invoice'),
                      label: 'Create invoice',
                      enabled: data.problem == null,
                      onPendingChanged: (value) {
                        setState(() => _pending = value);
                        widget.onPendingChanged?.call(value);
                      },
                      create: () {
                        final id = newBusinessId(),
                            invoice = newBusinessId(),
                            selection = widget.selection,
                            version = data.version;
                        _invoiceId = invoice;
                        final api = ref.read(reportApiProvider);
                        return () =>
                            api.prepare(id, version, invoice, selection);
                      },
                      onCommitted: () => Navigator.pushReplacement(
                        context,
                        MaterialPageRoute<void>(
                          builder: (_) => InvoiceScreen(invoiceId: _invoiceId!),
                        ),
                      ),
                    ),
                    if (data.legacy.isNotEmpty) ...[
                      const SizedBox(height: 28),
                      Text(
                        'Older invoices to review',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      for (final invoice in data.legacy)
                        ListTile(
                          title: Text(invoice.number),
                          subtitle: const Text('Source coverage needs review'),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: _pending ? null : () => _open(invoice),
                        ),
                    ],
                    if (data.excluded.isNotEmpty) ...[
                      const SizedBox(height: 28),
                      Text(
                        'Already invoiced',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const Text(
                        'These items are excluded from the new invoice, including items on unpaid invoices.',
                      ),
                      for (final item in data.excluded)
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          title: Text('${item.title} · ${item.number}'),
                          subtitle: Text(item.detail),
                          trailing: const Icon(Icons.open_in_new),
                          onTap: _pending ? null : () => _open(item),
                        ),
                    ],
                    const SizedBox(height: 28),
                    Text(
                      data.legacy.isEmpty
                          ? 'Work to invoice'
                          : 'Work to review',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    if (data.lines.isEmpty &&
                        data.problem != 'INVOICE_TOO_LARGE')
                      const Padding(
                        padding: EdgeInsets.symmetric(vertical: 16),
                        child: Text('No eligible work in this selection.'),
                      ),
                    for (final line in data.lines)
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(line.title),
                        subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(line.detail),
                            if (line.amount.isNotEmpty)
                              Text(
                                '${data.currency} ${line.amount}',
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
