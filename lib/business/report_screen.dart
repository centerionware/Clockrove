import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'business_command.dart';
import 'invoice_screen.dart';
import 'report_api.dart';
import 'report_map.dart';

String reportDate(DateTime date) =>
    '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
String reportDuration(String millis) {
  final value = BigInt.parse(millis);
  final hour = BigInt.from(3600000);
  final minute = BigInt.from(60000);
  final hours = value ~/ hour;
  final minutes = value.remainder(hour) ~/ minute;
  final remaining = value.remainder(minute);
  final seconds = '${remaining ~/ BigInt.from(1000)}';
  final fraction = remaining
      .remainder(BigInt.from(1000))
      .toString()
      .padLeft(3, '0')
      .replaceFirst(RegExp(r'0+$'), '');
  return '${hours}h ${minutes}m${remaining == BigInt.zero ? '' : ' $seconds${fraction.isEmpty ? '' : '.$fraction'}s'}';
}

class ReportScreen extends ConsumerStatefulWidget {
  const ReportScreen({super.key, this.jobId});
  final String? jobId;
  @override
  ConsumerState<ReportScreen> createState() => _ReportScreenState();
}

class _ReportScreenState extends ConsumerState<ReportScreen>
    with WidgetsBindingObserver {
  ReportContext? _context;
  ReportSelection? _selection;
  String? _error;
  bool _exporting = false;
  bool _invoicePending = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _load();
    }
  }

  Future<void> _load() async {
    if (_invoicePending) return;
    try {
      final data = await ref.read(reportApiProvider).context();
      if (!mounted) return;
      final now = DateTime.now();
      final previous = _selection;
      setState(() {
        _context = data;
        _error = null;
        _selection = ReportSelection(
          previous?.firstDate ?? reportDate(DateTime(now.year, now.month)),
          previous?.lastDate ?? reportDate(now),
          data.timezone,
          previous?.jobs
                  .where((id) => data.jobs.any((j) => j.id == id))
                  .toList() ??
              (widget.jobId == null
                  ? data.jobs.map((j) => j.id).toList()
                  : [widget.jobId!]),
        );
      });
    } catch (_) {
      if (mounted) setState(() => _error = 'Reports could not be loaded.');
    }
  }

  Future<void> _range() async {
    if (_invoicePending) return;
    final selection = _selection!;
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
      setState(
        () => _selection = ReportSelection(
          reportDate(range.start),
          reportDate(range.end),
          selection.timezone,
          selection.jobs,
        ),
      );
    }
  }

  Future<void> _jobs() async {
    if (_invoicePending) return;
    final selected = _selection!.jobs.toSet();
    final result = await showDialog<List<String>>(
      context: context,
      builder: (dialog) => StatefulBuilder(
        builder: (dialog, update) => AlertDialog(
          title: const Text('Report jobs'),
          content: SizedBox(
            width: 360,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final job in _context!.jobs)
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(job.name),
                      subtitle: job.archived ? const Text('Archived') : null,
                      value: selected.contains(job.id),
                      onChanged: (v) => update(() {
                        if (v == true) {
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
            FilledButton(
              onPressed: selected.isEmpty
                  ? null
                  : () => Navigator.pop(dialog, selected.toList()),
              child: const Text('Apply filters'),
            ),
          ],
        ),
      ),
    );
    if (result != null && mounted) {
      setState(
        () => _selection = ReportSelection(
          _selection!.firstDate,
          _selection!.lastDate,
          _selection!.timezone,
          result,
        ),
      );
    }
  }

  Future<void> _csv(WorkReport report, bool gps) async {
    if (_exporting || _invoicePending) return;
    setState(() => _exporting = true);
    try {
      final saved = await ref
          .read(reportApiProvider)
          .saveCsv(_selection!, report.version, gps);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(saved ? 'CSV saved.' : 'Export cancelled.')),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Export could not be saved. Refresh the report and try again.',
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
        title: const Text('Work report'),
        actions: [
          IconButton(
            tooltip: 'Refresh report',
            onPressed: () {
              _load();
              if (selection != null) {
                ref.invalidate(reportProvider(selection));
              }
            },
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: selection == null
          ? Center(
              child: _error == null
                  ? const CircularProgressIndicator()
                  : TextButton(onPressed: _load, child: Text(_error!)),
            )
          : selection.jobs.isEmpty
          ? const Center(child: Text('Create a job to view reports.'))
          : ref
                .watch(reportProvider(selection))
                .when(
                  skipLoadingOnReload: true,
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (_, _) => Center(
                    child: TextButton(
                      onPressed: () =>
                          ref.invalidate(reportProvider(selection)),
                      child: const Text(
                        'Retry report · use a shorter date range if needed',
                      ),
                    ),
                  ),
                  data: (report) => ListView(
                    padding: const EdgeInsets.all(20),
                    children: [
                      Wrap(
                        spacing: 12,
                        runSpacing: 8,
                        children: [
                          OutlinedButton.icon(
                            onPressed: _range,
                            icon: const Icon(Icons.date_range),
                            label: Text(
                              '${selection.firstDate} – ${selection.lastDate}',
                            ),
                          ),
                          OutlinedButton.icon(
                            onPressed: _jobs,
                            icon: const Icon(Icons.filter_list),
                            label: Text(
                              '${selection.jobs.length} job${selection.jobs.length == 1 ? '' : 's'}',
                            ),
                          ),
                        ],
                      ),
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        child: Text(
                          'Report timezone: ${selection.timezone}. Only finalized selected work contributes to hourly totals.',
                        ),
                      ),
                      for (final total in report.totals)
                        Card(
                          child: Padding(
                            padding: const EdgeInsets.all(20),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  total.name,
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleMedium,
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  total.amount == null
                                      ? 'Amount not available'
                                      : '${total.currency} ${money(total.amount, total.digits)}',
                                  style: Theme.of(context)
                                      .textTheme
                                      .headlineSmall,
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  total.mode == 'TASK'
                                      ? 'Completed billable tasks${total.missing > 0 ? ' · ${total.missing} prices missing' : ''}'
                                      : total.rate == null
                                      ? 'Hourly rate not set for ${total.currency}'
                                      : '${reportDuration(total.duration)} × ${total.currency} ${money(total.rate, total.digits)}/hour',
                                ),
                                if (total.mode == 'TASK')
                                  Text(
                                    '${reportDuration(total.duration)} recorded · billed by task',
                                  ),
                                if (total.mode == 'HOURLY')
                                  const Text(
                                    'Rounded once for the selected total, half up to the currency minor unit.',
                                  ),
                              ],
                            ),
                          ),
                        ),
                      const SizedBox(height: 16),
                      Wrap(
                        spacing: 12,
                        runSpacing: 12,
                        crossAxisAlignment: WrapCrossAlignment.start,
                        children: [
                          OutlinedButton.icon(
                            onPressed: _exporting
                                ? null
                                : () => _csv(report, false),
                            icon: const Icon(Icons.file_download_outlined),
                            label: const Text('CSV export'),
                          ),
                          if (report.totals.length == 1)
                            SizedBox(
                              width: 180,
                              child: _InvoiceButton(
                                selection: selection,
                                report: report,
                                onPendingChanged: (value) {
                                  if (mounted) {
                                    setState(() => _invoicePending = value);
                                  }
                                },
                              ),
                            ),
                          OutlinedButton.icon(
                            onPressed: _exporting || report.points.isEmpty
                                ? null
                                : () => _csv(report, true),
                            icon: const Icon(Icons.location_on_outlined),
                            label: const Text('GPS CSV'),
                          ),
                        ],
                      ),
                      if (report.totals.length != 1)
                        const Padding(
                          padding: EdgeInsets.only(top: 12),
                          child: Text(
                            'Select one job to prepare a customer invoice. Currencies remain separate.',
                          ),
                        ),
                      if (selection.jobs.length == 1)
                        TextButton.icon(
                          onPressed: () => Navigator.push(
                            context,
                            MaterialPageRoute<void>(
                              builder: (_) =>
                                  InvoiceHistory(jobId: selection.jobs.single),
                            ),
                          ),
                          icon: const Icon(Icons.receipt_long_outlined),
                          label: const Text('Prepared invoices'),
                        ),
                      const SizedBox(height: 24),
                      OutlinedButton.icon(
                        onPressed: report.points.isEmpty
                            ? null
                            : () => Navigator.push(
                                context,
                                MaterialPageRoute<void>(
                                  builder: (_) => ReportMap(
                                    points: report.points,
                                    timezone: selection.timezone,
                                  ),
                                ),
                              ),
                        icon: const Icon(Icons.map_outlined),
                        label: Text(
                          'Map · ${report.points.length} measured samples',
                        ),
                      ),
                      if (report.excluded.isNotEmpty)
                        Card(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'Excluded from final totals and exports',
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleMedium,
                                ),
                                for (final item in report.excluded)
                                  Text(
                                    '${item.label} · ${item.reason == 'NEEDS_REVIEW' ? 'Needs review' : 'Active or provisional'}',
                                  ),
                              ],
                            ),
                          ),
                        ),
                      const SizedBox(height: 24),
                      Text(
                        'Finalized time',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      if (report.intervals.isEmpty)
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 16),
                          child: Text('No finalized work in this selection.'),
                        ),
                      for (final interval in report.intervals)
                        Card(
                          child: ListTile(
                            title: Text('${interval.date} · ${interval.name}'),
                            subtitle: Text(
                              '${interval.startLabel}\n${interval.endLabel}\n${interval.origin.toLowerCase()}',
                            ),
                            trailing: ConstrainedBox(
                              constraints: const BoxConstraints(maxWidth: 120),
                              child: Text(
                                reportDuration(
                                  (interval.end - interval.start).toString(),
                                ),
                                textAlign: TextAlign.end,
                              ),
                            ),
                          ),
                        ),
                      const SizedBox(height: 24),
                      Text(
                        'Completed billable tasks',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      if (report.tasks.isEmpty)
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 16),
                          child: Text(
                            'No completed billable tasks in this selection.',
                          ),
                        ),
                      for (final task in report.tasks)
                        Card(
                          child: ListTile(
                            title: Text(task.title),
                            subtitle: Text(
                              '${task.date}\n${report.totals.firstWhere((t) => t.jobId == task.jobId).mode == 'HOURLY' ? 'Hourly job · task price kept separately' : 'Included in task billing'}',
                            ),
                            trailing: ConstrainedBox(
                              constraints: const BoxConstraints(maxWidth: 140),
                              child: Text(
                                task.price == null
                                    ? 'Price not set'
                                    : '${task.currency} ${money(task.price, report.totals.firstWhere((t) => t.jobId == task.jobId).digits)}',
                                textAlign: TextAlign.end,
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
    );
  }
}

class _InvoiceButton extends ConsumerStatefulWidget {
  const _InvoiceButton({
    required this.selection,
    required this.report,
    required this.onPendingChanged,
  });
  final ReportSelection selection;
  final WorkReport report;
  final ValueChanged<bool> onPendingChanged;
  @override
  ConsumerState<_InvoiceButton> createState() => _InvoiceButtonState();
}

class _InvoiceButtonState extends ConsumerState<_InvoiceButton> {
  String? _invoiceId;
  @override
  Widget build(BuildContext context) => BusinessCommand(
    label: 'PDF invoice',
    onPendingChanged: widget.onPendingChanged,
    create: () {
      final id = newBusinessId();
      final invoiceId = newBusinessId();
      _invoiceId = invoiceId;
      final version = widget.report.version;
      final selection = widget.selection;
      final api = ref.read(reportApiProvider);
      return () => api.prepare(id, version, invoiceId, selection);
    },
    onCommitted: () => Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => InvoiceScreen(invoiceId: _invoiceId!),
      ),
    ),
  );
}
