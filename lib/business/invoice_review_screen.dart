import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';
import 'invoice_screen.dart';
import 'report_api.dart';
import 'tax_rate.dart';
import 'legacy_invoice_screen.dart';

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
  Future<void> _reviewLegacy(InvoiceCoverageLink invoice) async {
    await Navigator.push<bool>(
      context,
      MaterialPageRoute<bool>(
        builder: (_) => LegacyInvoiceScreen(invoiceId: invoice.invoiceId),
      ),
    );
    if (mounted) _refresh();
  }

  AppMessage _problem(String? value) => switch (value) {
    'LEGACY_INVOICE_REVIEW_REQUIRED' => AppMessage.invoiceAddedReviewOlder,
    'NO_BILLABLE_ITEMS' => AppMessage.invoiceAddedNoWork,
    'HOURLY_RATE_REQUIRED' => AppMessage.invoiceAddedHourlyRate,
    'TASK_PRICES_REQUIRED' => AppMessage.invoiceAddedTaskPrices,
    'INVOICE_TOO_LARGE' => AppMessage.quoteInvoiceTooLarge,
    'INVALID_TAX_RATE' => AppMessage.validationTaxBounds,
    _ => AppMessage.invoiceAddedPrepareFailed,
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
            value == null
                ? AppMessage.invoiceReviewUnavailableAmount.of(context)
                : '$currency $value',
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
          title: Text(AppMessage.invoiceReviewTitle.of(context)),
          actions: [
            IconButton(
              tooltip: AppMessage.invoiceReviewRefresh.of(context),
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
                    child: Text(AppMessage.invoiceReviewRetry.of(context)),
                  ),
                ),
                data: (data) => ListView(
                  padding: const EdgeInsets.all(24),
                  children: [
                    if (preview.hasError) ...[
                      Text(AppMessage.invoiceReviewRefreshKept.of(context)),
                      TextButton(
                        onPressed: _pending ? null : _refresh,
                        child: Text(AppMessage.invoiceReviewRetry.of(context)),
                      ),
                    ],
                    Text(
                      data.jobName,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      AppMessage.invoiceReviewDateRange.format(context, {
                        'first': widget.selection.firstDate,
                        'last': widget.selection.lastDate,
                        'timezone': widget.selection.timezone,
                      }),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      data.legacy.isEmpty
                          ? AppMessage.invoiceReviewEligibleGuidance.of(context)
                          : AppMessage.invoiceReviewLegacyGuidance.of(context),
                    ),
                    const SizedBox(height: 20),
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: Column(
                          children: [
                            _amount(
                              AppMessage.invoiceSubtotal.of(context),
                              data.subtotal,
                              data.currency,
                            ),
                            _amount(
                              data.taxUnits >= 0 && data.taxUnits <= 1000000
                                  ? AppMessage.invoiceReviewTaxRate.format(
                                      context,
                                      {
                                        'rate': taxPercent(data.taxUnits)
                                            .toString(),
                                      },
                                    )
                                  : AppMessage.invoiceTax.of(context),
                              data.tax,
                              data.currency,
                            ),
                            const Divider(),
                            _amount(
                              AppMessage.invoiceTotal.of(context),
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
                          _problem(data.problem).of(context),
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                      ),
                    BusinessCommand(
                      key: const ValueKey('prepare-invoice'),
                      label: AppMessage.invoiceReviewCreate.of(context),
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
                        AppMessage.invoiceReviewOlderTitle.of(context),
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      for (final invoice in data.legacy)
                        ListTile(
                          title: Text(invoice.number),
                          subtitle: Text(
                            AppMessage.invoiceReviewCoverage.of(context),
                          ),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: _pending ? null : () => _reviewLegacy(invoice),
                        ),
                    ],
                    if (data.excluded.isNotEmpty) ...[
                      const SizedBox(height: 28),
                      Text(
                        AppMessage.invoiceReviewAlreadyInvoiced.of(context),
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      Text(AppMessage.invoiceReviewExcluded.of(context)),
                      for (final item in data.excluded)
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          title: Text(
                            '${item.title.isEmpty ? AppMessage.reportFinalizedTime.of(context) : item.title} · ${item.number}',
                          ),
                          subtitle: Text(item.detail),
                          trailing: const Icon(Icons.open_in_new),
                          onTap: _pending ? null : () => _open(item),
                        ),
                    ],
                    const SizedBox(height: 28),
                    Text(
                      data.legacy.isEmpty
                          ? AppMessage.invoiceReviewWorkToInvoice.of(context)
                          : AppMessage.invoiceReviewWorkToReview.of(context),
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    if (data.lines.isEmpty &&
                        data.problem != 'INVOICE_TOO_LARGE')
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        child: Text(
                          AppMessage.invoiceReviewNoEligibleWork.of(context),
                        ),
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
