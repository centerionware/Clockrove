import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'invoice_detail_screen.dart';
import 'payment_api.dart';
import 'report_api.dart';

class InvoiceHistory extends ConsumerStatefulWidget {
  const InvoiceHistory({super.key, this.jobId});
  final String? jobId;
  @override
  ConsumerState<InvoiceHistory> createState() => _InvoiceHistoryState();
}

class _InvoiceHistoryState extends ConsumerState<InvoiceHistory> {
  Future<List<PreparedInvoice>> _load() {
    final api = ref.read(reportApiProvider), job = widget.jobId;
    return job == null ? api.allInvoices() : api.invoices(job);
  }

  late Future<List<PreparedInvoice>> _data = _load();
  void _refresh() => setState(() => _data = _load());
  Future<void> _open(PreparedInvoice invoice) async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(
        builder: (_) => InvoiceDetailScreen(invoiceId: invoice.id),
      ),
    );
    if (mounted) _refresh();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.jobId == null ? 'Invoices' : 'Job invoices'),
      actions: [
        IconButton(
          tooltip: 'Refresh invoices',
          onPressed: _refresh,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: FutureBuilder<List<PreparedInvoice>>(
      future: _data,
      builder: (context, data) {
        if (data.hasError) {
          return Center(
            child: TextButton(
              onPressed: _refresh,
              child: const Text('Retry invoices'),
            ),
          );
        }
        if (!data.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        if (data.data!.isEmpty) {
          return Center(
            child: Text(
              widget.jobId == null
                  ? 'No invoices yet. Create one from a job report.'
                  : 'No invoices prepared for this job.',
            ),
          );
        }
        return ListView(
          padding: const EdgeInsets.all(20),
          children: [
            for (final invoice in data.data!)
              Card(
                child: ListTile(
                  title: Text(invoice.number),
                  subtitle: Text(
                    [
                      if (invoice.jobName.isNotEmpty)
                        '${invoice.jobName}${invoice.jobDeleted ? ' · job deleted' : ''}',
                      if (invoice.customer.isNotEmpty) invoice.customer,
                      '${invoice.currency} ${invoice.total}',
                      if (invoice.status != null)
                        invoicePaymentStatus(invoice.status!),
                      if (invoice.due != null)
                        'Balance due ${invoice.currency} ${money(invoice.due, invoice.digits)}',
                      if (invoice.createdLabel.isNotEmpty)
                        'Issued ${invoice.createdLabel}',
                      if (invoice.legacy)
                        invoice.jobDeleted
                            ? 'Historical source records unavailable'
                            : 'Billed work needs review',
                    ].join('\n'),
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => _open(invoice),
                ),
              ),
          ],
        );
      },
    ),
  );
}
