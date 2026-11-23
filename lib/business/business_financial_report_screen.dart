import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'actual_cost_labels.dart';
import 'business_api.dart';
import 'invoice_detail_screen.dart';
import 'report_api.dart';
import 'report_screen.dart' show reportDate;

class BusinessFinancialReportScreen extends ConsumerStatefulWidget {
  const BusinessFinancialReportScreen({
    super.key,
    this.jobId,
    this.initialJobIds,
  });
  final String? jobId;
  final List<String>? initialJobIds;
  @override
  ConsumerState<BusinessFinancialReportScreen> createState() =>
      _BusinessFinancialReportScreenState();
}

class _BusinessFinancialReportScreenState
    extends ConsumerState<BusinessFinancialReportScreen> {
  ReportContext? _contextData;
  BusinessReportSelection? _selection;
  String? _loadError;
  bool _exporting = false;
  int _invoiceLimit = 100, _cashLimit = 100, _costLimit = 100;

  @override
  void initState() {
    super.initState();
    _loadContext();
  }

  Future<void> _loadContext() async {
    try {
      final value = await ref.read(reportApiProvider).context();
      if (!mounted) return;
      final now = DateTime.now();
      setState(() {
        _contextData = value;
        _loadError = null;
        _selection ??= BusinessReportSelection(
          reportDate(DateTime(now.year, now.month)),
          reportDate(now),
          value.timezone,
          jobs:
              widget.initialJobIds ??
              (widget.jobId == null ? const [] : [widget.jobId!]),
        );
      });
    } catch (_) {
      if (mounted) {
        setState(
          () => _loadError = AppMessage.businessReportLoadFailed.of(context),
        );
      }
    }
  }

  void _update(BusinessReportSelection value) => setState(() {
    _selection = value;
    _invoiceLimit = 100;
    _cashLimit = 100;
    _costLimit = 100;
  });

  Future<void> _range() async {
    final selection = _selection;
    if (selection == null) return;
    final range = await showDateRangePicker(
      context: context,
      firstDate: DateTime(1900),
      lastDate: DateTime(9999),
      initialDateRange: DateTimeRange(
        start: DateTime.parse(selection.firstDate),
        end: DateTime.parse(selection.lastDate),
      ),
    );
    if (range != null && mounted) {
      _update(
        BusinessReportSelection(
          reportDate(range.start),
          reportDate(range.end),
          selection.timezone,
          jobs: selection.jobs,
          customers: selection.customers,
        ),
      );
    }
  }

  Future<void> _jobs() async {
    final selection = _selection;
    final jobs = _contextData?.jobs;
    if (selection == null || jobs == null) return;
    final selected = selection.jobs.toSet();
    final result = await showDialog<List<String>>(
      context: context,
      builder: (dialog) => StatefulBuilder(
        builder: (dialog, updateDialog) => AlertDialog(
          title: Text(AppMessage.reportJobs.of(context)),
          content: SizedBox(
            width: 360,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(AppMessage.businessEveryJob.of(context)),
                  ),
                  for (final job in jobs)
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(job.name),
                      subtitle: job.archived
                          ? Text(AppMessage.archived.of(context))
                          : null,
                      value: selected.contains(job.id),
                      onChanged: (value) => updateDialog(() {
                        if (value == true) {
                          selected.add(job.id);
                        } else {
                          selected.remove(job.id);
                        }
                      }),
                    ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialog, <String>[]),
              child: Text(AppMessage.allJobs.of(context)),
            ),
            FilledButton(
              onPressed: selected.isEmpty
                  ? null
                  : () => Navigator.pop(dialog, selected.toList()),
              child: Text(AppMessage.apply.of(context)),
            ),
          ],
        ),
      ),
    );
    if (result != null && mounted) {
      _update(
        BusinessReportSelection(
          selection.firstDate,
          selection.lastDate,
          selection.timezone,
          jobs: result,
          customers: selection.customers,
        ),
      );
    }
  }

  Future<void> _customers() async {
    final selection = _selection;
    final customers = _contextData?.customers;
    if (selection == null || customers == null) return;
    final selected = selection.customers.toSet();
    final result = await showDialog<List<String>>(
      context: context,
      builder: (dialog) => StatefulBuilder(
        builder: (dialog, updateDialog) => AlertDialog(
          title: Text(AppMessage.reportCustomers.of(context)),
          content: SizedBox(
            width: 360,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(AppMessage.businessEveryCustomer.of(context)),
                  ),
                  for (final customer in customers)
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(customer.name),
                      subtitle: customer.archived
                          ? Text(AppMessage.archived.of(context))
                          : null,
                      value: selected.contains(customer.id),
                      onChanged: (value) => updateDialog(() {
                        if (value == true) {
                          selected.add(customer.id);
                        } else {
                          selected.remove(customer.id);
                        }
                      }),
                    ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialog, <String>[]),
              child: Text(AppMessage.reportAllCustomers.of(context)),
            ),
            FilledButton(
              onPressed: selected.isEmpty
                  ? null
                  : () => Navigator.pop(dialog, selected.toList()),
              child: Text(AppMessage.apply.of(context)),
            ),
          ],
        ),
      ),
    );
    if (result != null && mounted) {
      _update(
        BusinessReportSelection(
          selection.firstDate,
          selection.lastDate,
          selection.timezone,
          jobs: selection.jobs,
          customers: result,
        ),
      );
    }
  }

  Future<void> _export(
    BusinessFinancialReport report, {
    required bool pdf,
  }) async {
    if (_exporting) return;
    setState(() => _exporting = true);
    try {
      final saved = await ref
          .read(reportApiProvider)
          .saveBusinessReport(_selection!, report.version, pdf: pdf);
      if (mounted) {
        final message = switch ((saved, pdf)) {
          (true, true) => AppMessage.reportPdfSaved,
          (true, false) => AppMessage.reportCsvSaved,
          _ => AppMessage.reportExportCancelled,
        };
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(message.of(context))));
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              (pdf
                      ? AppMessage.reportPdfExportFailed
                      : AppMessage.reportCsvExportFailed)
                  .of(context),
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final selection = _selection;
    return Scaffold(
      appBar: AppBar(
        title: Text(AppMessage.businessReport.of(context)),
        actions: [
          IconButton(
            tooltip: AppMessage.refreshBusinessReport.of(context),
            onPressed: selection == null
                ? _loadContext
                : () => ref.invalidate(businessReportProvider(selection)),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: selection == null
          ? Center(
              child: _loadError == null
                  ? const CircularProgressIndicator()
                  : TextButton(
                      onPressed: _loadContext,
                      child: Text(AppMessage.retryReportFilters.of(context)),
                    ),
            )
          : ref
                .watch(businessReportProvider(selection))
                .when(
                  skipLoadingOnReload: true,
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (_, _) => Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(AppMessage.businessReportLoadFailed.of(context)),
                          FilledButton(
                            onPressed: () => ref.invalidate(
                              businessReportProvider(selection),
                            ),
                            child: Text(AppMessage.retryReport.of(context)),
                          ),
                        ],
                      ),
                    ),
                  ),
                  data: (report) => _content(selection, report),
                ),
    );
  }

  Widget _content(
    BusinessReportSelection selection,
    BusinessFinancialReport report,
  ) {
    final summaries = report.summaries;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            OutlinedButton.icon(
              onPressed: _range,
              icon: const Icon(Icons.date_range),
              label: Text(
                AppMessage.dateRangeLabel.format(context, {
                  'first': MaterialLocalizations.of(context)
                      .formatCompactDate(DateTime.parse(selection.firstDate)),
                  'last': MaterialLocalizations.of(context)
                      .formatCompactDate(DateTime.parse(selection.lastDate)),
                }),
              ),
            ),
            OutlinedButton.icon(
              onPressed: _jobs,
              icon: const Icon(Icons.work_outline),
              label: Text(
                selection.jobs.isEmpty
                    ? AppMessage.allJobs.of(context)
                    : AppMessage.businessSelectedJobs.format(context, {
                        'count': MaterialLocalizations.of(context)
                            .formatDecimal(selection.jobs.length),
                      }),
              ),
            ),
            OutlinedButton.icon(
              onPressed: _customers,
              icon: const Icon(Icons.people_outline),
              label: Text(
                selection.customers.isEmpty
                    ? AppMessage.reportAllCustomers.of(context)
                    : AppMessage.businessSelectedCustomers.format(context, {
                        'count': MaterialLocalizations.of(context)
                            .formatDecimal(selection.customers.length),
                      }),
              ),
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Text(
            AppMessage.businessReportDateBasis.format(context, {
              'timezone': selection.timezone,
              'date': selection.lastDate,
            }),
          ),
        ),
        if (summaries.isEmpty)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Text(AppMessage.businessReportNoActivity.of(context)),
            ),
          ),
        for (final summary in summaries) _summary(summary),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Text(AppMessage.businessReportProfitHelp.of(context)),
          ),
        ),
        if (report.excludedUnknownCustomerCostCount > 0)
          Card(
            color: Theme.of(context).colorScheme.tertiaryContainer,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                AppMessage.businessUnknownCustomerCosts.format(context, {
                  'count': MaterialLocalizations.of(context)
                      .formatDecimal(report.excludedUnknownCustomerCostCount),
                }),
              ),
            ),
          ),
        if (report.invoices.isNotEmpty) _invoiceSection(report),
        if (report.cash.isNotEmpty) _cashSection(report),
        if (report.costs.isNotEmpty) _costSection(report),
        if (report.invoices.isEmpty &&
            report.cash.isEmpty &&
            report.costs.isEmpty)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(AppMessage.businessReportEmpty.of(context)),
            ),
          ),
        const SizedBox(height: 16),
        Text(AppMessage.reportCsvAmountExact.of(context)),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          children: [
            OutlinedButton.icon(
              onPressed: _exporting ? null : () => _export(report, pdf: false),
              icon: const Icon(Icons.table_view_outlined),
              label: Text(AppMessage.reportExportCsv.of(context)),
            ),
            OutlinedButton.icon(
              onPressed: _exporting ? null : () => _export(report, pdf: true),
              icon: const Icon(Icons.picture_as_pdf_outlined),
              label: Text(AppMessage.reportExportPdf.of(context)),
            ),
          ],
        ),
      ],
    );
  }

  Widget _summary(BusinessReportSummary value) {
    String amount(String minor) =>
        '${value.currency} ${money(minor, value.digits)}';
    Widget row(String label, String minor) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: Text(label)),
          const SizedBox(width: 12),
          Flexible(
            child: Text(
              amount(minor),
              textAlign: TextAlign.end,
              style: Theme.of(context).textTheme.titleSmall,
            ),
          ),
        ],
      ),
    );
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(value.currency, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            row(
              AppMessage.reportInvoicedSales.of(context),
              value.invoicedSales,
            ),
            row(AppMessage.reportCashReceived.of(context), value.cashReceived),
            row(AppMessage.reportTaxBilled.of(context), value.taxBilled),
            row(AppMessage.reportTaxCollected.of(context), value.taxCollected),
            row(AppMessage.reportOutstanding.of(context), value.outstanding),
            const Divider(),
            row(AppMessage.reportLaborCharges.of(context), value.labor),
            row(AppMessage.reportSuppliesCharges.of(context), value.supplies),
            row(
              AppMessage.reportUnclassifiedCharges.of(context),
              value.unclassified,
            ),
            row(AppMessage.reportDirectCosts.of(context), value.directCosts),
            row(AppMessage.reportOverhead.of(context), value.overhead),
            row(AppMessage.reportGrossProfit.of(context), value.grossProfit),
            row(AppMessage.reportNetProfit.of(context), value.netProfit),
            row(AppMessage.reportCashSurplus.of(context), value.cashSurplus),
            Text(
              AppMessage.reportMarginPair.format(context, {
                'gross': value.grossMarginPercent?.toString() ?? '—',
                'net': value.netMarginPercent?.toString() ?? '—',
              }),
            ),
            Text(
              AppMessage.reportCostAttribution.format(context, {
                'count': '${value.costCount}',
                'unlinked': '${value.unattributedCostCount}',
              }),
            ),
            if (value.legacyInvoiceCount > 0)
              Text(
                AppMessage.businessLegacyInvoiceTaxMissing.format(context, {
                  'count': MaterialLocalizations.of(context)
                      .formatDecimal(value.legacyInvoiceCount),
                }),
              ),
            if (value.unknownTaxInvoiceCount > 0)
              Text(
                AppMessage.businessUnknownTaxInvoices.format(context, {
                  'count': MaterialLocalizations.of(context)
                      .formatDecimal(value.unknownTaxInvoiceCount),
                }),
              ),
          ],
        ),
      ),
    );
  }

  Widget _invoiceSection(BusinessFinancialReport report) => ExpansionTile(
    title: Text(
      AppMessage.reportInvoiceActivity.format(context, {
        'count': '${report.invoices.length}',
      }),
    ),
    children: [
      for (final row in report.invoices.take(_invoiceLimit))
        ListTile(
          title: Text(
            row.kind == 'VOID_ADJUSTMENT'
                ? AppMessage.businessVoidAdjustment.format(context, {
                    'number': row.number,
                  })
                : row.number,
          ),
          subtitle: Text(
            '${row.occurredLabel} · ${row.customer.isEmpty ? row.job : '${row.customer} · ${row.job}'}${row.reason.isEmpty ? '' : '\n${row.reason}'}',
          ),
          trailing: Text(
            row.salesMinor == null
                ? row.legacyTotalMinor == null
                      ? AppMessage.reportAmountUnavailable.of(context)
                      : AppMessage.businessLegacyTotal.format(context, {
                          'amount':
                              '${row.currency} ${money(row.legacyTotalMinor, row.digits)}',
                        })
                : '${AppMessage.businessSalesAmount.format(context, {'amount': '${row.currency} ${money(row.salesMinor, row.digits)}'})}\n${AppMessage.businessTaxAmount.format(context, {'amount': row.taxMinor == null ? AppMessage.reportAmountUnavailable.of(context) : money(row.taxMinor, row.digits)})}',
            textAlign: TextAlign.end,
          ),
          onTap: () => Navigator.push<void>(
            context,
            MaterialPageRoute<void>(
              builder: (_) => InvoiceDetailScreen(invoiceId: row.invoiceId),
            ),
          ),
        ),
      if (_invoiceLimit < report.invoices.length)
        TextButton(
          onPressed: () => setState(() => _invoiceLimit += 100),
          child: Text(AppMessage.businessLoadMoreInvoices.of(context)),
        ),
    ],
  );

  Widget _cashSection(BusinessFinancialReport report) => ExpansionTile(
    title: Text(
      AppMessage.reportCashEvents.format(context, {
        'count': '${report.cash.length}',
      }),
    ),
    children: [
      for (final row in report.cash.take(_cashLimit))
        ListTile(
          title: Text(
            (row.kind == 'TRANSFER'
                    ? AppMessage.businessCreditTransferTitle
                    : row.kind == 'REVERSAL'
                    ? AppMessage.businessPaymentCorrectionTitle
                    : AppMessage.businessPaymentTitle)
                .format(context, {'number': row.number}),
          ),
          subtitle: Text(
            [
              row.occurredLabel,
              if (row.reference.isNotEmpty)
                AppMessage.businessCashReference.format(context, {
                  'reference': row.reference,
                }),
              if (row.kind == 'TRANSFER')
                AppMessage.businessCreditMovementNotice.of(context),
            ].join('\n'),
          ),
          trailing: Text(
            AppMessage.businessCashAmount.format(context, {
              'amount': '${row.currency} ${money(row.cashMinor, row.digits)}',
            }),
            textAlign: TextAlign.end,
          ),
          onTap: () => Navigator.push<void>(
            context,
            MaterialPageRoute<void>(
              builder: (_) => InvoiceDetailScreen(invoiceId: row.invoiceId),
            ),
          ),
        ),
      if (_cashLimit < report.cash.length)
        TextButton(
          onPressed: () => setState(() => _cashLimit += 100),
          child: Text(AppMessage.businessLoadMoreCash.of(context)),
        ),
    ],
  );

  Widget _costSection(BusinessFinancialReport report) => ExpansionTile(
    title: Text(
      AppMessage.reportCostEntries.format(context, {
        'count': '${report.costs.length}',
      }),
    ),
    children: [
      for (final row in report.costs.take(_costLimit))
        ListTile(
          title: Text(
            row.vendor.isEmpty
                ? actualCostCategoryLabel(context, row.category)
                : row.vendor,
          ),
          subtitle: Text(
            '${actualCostCategoryLabel(context, row.category)} · ${row.occurredLabel}${row.job.isEmpty ? '' : '\n${row.job}'}${row.reference.isEmpty ? '' : '\n${AppMessage.businessCashReference.format(context, {'reference': row.reference})}'}${row.note.isEmpty ? '' : '\n${row.note}'}',
          ),
          trailing: Text(
            '${row.currency} ${money(row.amountMinor, row.digits)}',
            textAlign: TextAlign.end,
          ),
        ),
      if (_costLimit < report.costs.length)
        TextButton(
          onPressed: () => setState(() => _costLimit += 100),
          child: Text(AppMessage.businessLoadMoreCosts.of(context)),
        ),
    ],
  );
}
