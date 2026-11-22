import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
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
        setState(() => _loadError = 'Report filters could not be loaded.');
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
                  const ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text('Leave all unchecked to include every job.'),
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
                  const ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(
                      'Leave all unchecked to include every customer.',
                    ),
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
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              saved ? '${pdf ? 'PDF' : 'CSV'} saved.' : 'Export cancelled.',
            ),
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              pdf
                  ? 'Could not save the PDF. Try a shorter period or export CSV, then refresh and try again.'
                  : 'Could not save the CSV. Refresh and try again.',
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
                          const Text(
                            'This business report could not be loaded. Try a smaller date range or fewer filters.',
                          ),
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
              label: Text('${selection.firstDate} – ${selection.lastDate}'),
            ),
            OutlinedButton.icon(
              onPressed: _jobs,
              icon: const Icon(Icons.work_outline),
              label: Text(
                selection.jobs.isEmpty
                    ? 'All jobs'
                    : '${selection.jobs.length} jobs',
              ),
            ),
            OutlinedButton.icon(
              onPressed: _customers,
              icon: const Icon(Icons.people_outline),
              label: Text(
                selection.customers.isEmpty
                    ? 'All customers'
                    : '${selection.customers.length} customers',
              ),
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Text(
            'Dates use ${selection.timezone}. Invoiced sales use issue and void dates; cash uses payment or correction dates; balances are as of ${selection.lastDate}. Currency totals are kept separate.',
          ),
        ),
        if (summaries.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(20),
              child: Text(
                'No financial activity or job currency was found for these filters.',
              ),
            ),
          ),
        for (final summary in summaries) _summary(summary),
        const Card(
          child: Padding(
            padding: EdgeInsets.all(16),
            child: Text(
              'Profit and margins are estimates using recorded costs only. Missing costs are unknown, not zero. Tax is shown separately and is not profit. Quotes are not revenue. These local records are not a tax return.',
            ),
          ),
        ),
        if (report.excludedUnknownCustomerCostCount > 0)
          Card(
            color: Theme.of(context).colorScheme.tertiaryContainer,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                '${report.excludedUnknownCustomerCostCount} recorded cost entries have no captured customer and are excluded from this customer-filtered view.',
              ),
            ),
          ),
        if (report.invoices.isNotEmpty) _invoiceSection(report),
        if (report.cash.isNotEmpty) _cashSection(report),
        if (report.costs.isNotEmpty) _costSection(report),
        if (report.invoices.isEmpty &&
            report.cash.isEmpty &&
            report.costs.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Text(
                'No issued invoices, payments, or actual costs in this period.',
              ),
            ),
          ),
        const SizedBox(height: 16),
        const Text(
          'CSV amount columns contain exact minor-unit integers as text.',
        ),
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
            row('Invoiced sales · tax exclusive', value.invoicedSales),
            row('Cash received · payment date', value.cashReceived),
            row('Tax billed', value.taxBilled),
            row('Tax collected with payments', value.taxCollected),
            row('Outstanding as of report end', value.outstanding),
            const Divider(),
            row('Labor charges', value.labor),
            row('Supplies charges', value.supplies),
            row('Unclassified charges', value.unclassified),
            row('Recorded direct costs', value.directCosts),
            row('Recorded overhead', value.overhead),
            row('Estimated gross profit', value.grossProfit),
            row('Estimated net profit', value.netProfit),
            row('Estimated cash surplus', value.cashSurplus),
            Text(
              'Gross margin ${value.grossMarginPercent ?? '—'}% · net margin ${value.netMarginPercent ?? '—'}%',
            ),
            Text(
              '${value.costCount} recorded cost entries · ${value.unattributedCostCount} without a job',
            ),
            if (value.legacyInvoiceCount > 0)
              Text(
                '${value.legacyInvoiceCount} legacy invoice(s) lack a reliable tax-exclusive subtotal.',
              ),
            if (value.unknownTaxInvoiceCount > 0)
              Text(
                '${value.unknownTaxInvoiceCount} invoice(s) have no reliable tax snapshot.',
              ),
          ],
        ),
      ),
    );
  }

  Widget _invoiceSection(BusinessFinancialReport report) => ExpansionTile(
    title: Text('Invoice issue and void activity · ${report.invoices.length}'),
    children: [
      for (final row in report.invoices.take(_invoiceLimit))
        ListTile(
          title: Text(
            '${row.kind == 'VOID_ADJUSTMENT' ? 'Void adjustment · ' : ''}${row.number}',
          ),
          subtitle: Text(
            '${row.occurredLabel} · ${row.customer.isEmpty ? row.job : '${row.customer} · ${row.job}'}${row.reason.isEmpty ? '' : '\n${row.reason}'}',
          ),
          trailing: Text(
            row.salesMinor == null
                ? 'Legacy total ${row.legacyTotalMinor == null ? 'unavailable' : '${row.currency} ${money(row.legacyTotalMinor, row.digits)}'}'
                : '${row.currency} ${money(row.salesMinor, row.digits)} sales\nTax ${row.taxMinor == null ? 'unknown' : money(row.taxMinor, row.digits)}',
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
          child: const Text('Load more invoice activity'),
        ),
    ],
  );

  Widget _cashSection(BusinessFinancialReport report) => ExpansionTile(
    title: Text('Cash and credit events · ${report.cash.length}'),
    children: [
      for (final row in report.cash.take(_cashLimit))
        ListTile(
          title: Text(
            '${row.kind == 'TRANSFER'
                ? 'Credit transfer · '
                : row.kind == 'REVERSAL'
                ? 'Payment correction · '
                : 'Payment · '}${row.number}',
          ),
          subtitle: Text(
            '${row.occurredLabel}${row.reference.isEmpty ? '' : '\nReference ${row.reference}'}${row.kind == 'TRANSFER' ? '\nCredit movements do not count as cash received.' : ''}',
          ),
          trailing: Text(
            '${row.currency} ${money(row.cashMinor, row.digits)} cash',
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
          child: const Text('Load more cash events'),
        ),
    ],
  );

  Widget _costSection(BusinessFinancialReport report) => ExpansionTile(
    title: Text('Recorded cost entries · ${report.costs.length}'),
    children: [
      for (final row in report.costs.take(_costLimit))
        ListTile(
          title: Text(row.vendor.isEmpty ? row.category : row.vendor),
          subtitle: Text(
            '${row.category} · ${row.occurredLabel}${row.job.isEmpty ? '' : '\n${row.job}'}${row.reference.isEmpty ? '' : '\nReference ${row.reference}'}${row.note.isEmpty ? '' : '\n${row.note}'}',
          ),
          trailing: Text(
            '${row.currency} ${money(row.amountMinor, row.digits)}',
            textAlign: TextAlign.end,
          ),
        ),
      if (_costLimit < report.costs.length)
        TextButton(
          onPressed: () => setState(() => _costLimit += 100),
          child: const Text('Load more cost entries'),
        ),
    ],
  );
}
