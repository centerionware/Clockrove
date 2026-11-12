import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'report_api.dart';

class InvoiceScreen extends ConsumerStatefulWidget {
  const InvoiceScreen({super.key, required this.invoiceId});
  final String invoiceId;
  @override
  ConsumerState<InvoiceScreen> createState() => _InvoiceScreenState();
}

class _InvoiceScreenState extends ConsumerState<InvoiceScreen> {
  late Future<InvoicePage> _page = ref
      .read(reportApiProvider)
      .preview(widget.invoiceId, 0);
  bool _busy = false;
  String? _message;
  Future<void> _handoff(String kind) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final api = ref.read(reportApiProvider);
      String message;
      if (kind == 'email') {
        await api.email(widget.invoiceId);
        message = 'Email composer opened. Review the PDF attachment and press Send in your email app.';
      } else if (kind == 'share') {
        await api.share(widget.invoiceId);
        message = 'Share dialog opened.';
      } else {
        message = await api.save(widget.invoiceId)
            ? 'PDF saved.'
            : 'Save cancelled. Your prepared invoice is kept.';
      }
      if (mounted) {
        setState(() => _message = message);
      }
    } catch (_) {
      if (mounted) {
        setState(
          () => _message = 'The handoff could not be opened. Try Save PDF or Share. Your prepared invoice is kept.',
        );
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Prepared invoice')),
    body: SafeArea(
      child: Column(
        children: [
          ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.sizeOf(context).height * .42,
            ),
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    'This invoice is a saved snapshot. Later job, rate or company changes will not rewrite it.',
                  ),
                  if (_message != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(_message!),
                    ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 12,
                    runSpacing: 8,
                    children: [
                      FilledButton.icon(
                        onPressed: _busy ? null : () => _handoff('email'),
                        icon: const Icon(Icons.email_outlined),
                        label: const Text('Email invoice'),
                      ),
                      OutlinedButton.icon(
                        onPressed: _busy ? null : () => _handoff('save'),
                        icon: const Icon(Icons.save_alt),
                        label: const Text('Save PDF'),
                      ),
                      IconButton(
                        tooltip: 'Share PDF',
                        onPressed: _busy ? null : () => _handoff('share'),
                        icon: const Icon(Icons.share_outlined),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          Expanded(
            child: FutureBuilder<InvoicePage>(
              future: _page,
              builder: (context, snapshot) {
                if (snapshot.hasError) {
                  return Center(
                    child: TextButton(
                      onPressed: () => setState(
                        () => _page = ref
                            .read(reportApiProvider)
                            .preview(widget.invoiceId, 0),
                      ),
                      child: const Text('Retry PDF preview'),
                    ),
                  );
                }
                if (!snapshot.hasData) {
                  return const Center(child: CircularProgressIndicator());
                }
                final page = snapshot.data!;
                return Column(
                  children: [
                    Expanded(
                      child: InteractiveViewer(
                        minScale: .5,
                        maxScale: 5,
                        child: Center(
                          child: Image.memory(
                            page.png,
                            fit: BoxFit.contain,
                            semanticLabel:
                                'Invoice page ${page.index + 1} of ${page.pages}',
                          ),
                        ),
                      ),
                    ),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        IconButton(
                          tooltip: 'Previous invoice page',
                          onPressed:
                              snapshot.connectionState ==
                                      ConnectionState.waiting ||
                                  page.index == 0
                              ? null
                              : () => setState(
                                  () => _page = ref
                                      .read(reportApiProvider)
                                      .preview(
                                        widget.invoiceId,
                                        page.index - 1,
                                      ),
                                ),
                          icon: const Icon(Icons.chevron_left),
                        ),
                        Text('Page ${page.index + 1} of ${page.pages}'),
                        IconButton(
                          tooltip: 'Next invoice page',
                          onPressed:
                              snapshot.connectionState ==
                                      ConnectionState.waiting ||
                                  page.index + 1 >= page.pages
                              ? null
                              : () => setState(
                                  () => _page = ref
                                      .read(reportApiProvider)
                                      .preview(
                                        widget.invoiceId,
                                        page.index + 1,
                                      ),
                                ),
                          icon: const Icon(Icons.chevron_right),
                        ),
                      ],
                    ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    ),
  );
}

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
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(widget.jobId == null ? 'Invoices' : 'Job invoices'), actions: [
      IconButton(tooltip: 'Refresh invoices', onPressed: _refresh, icon: const Icon(Icons.refresh)),
    ]),
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
            child: Text(widget.jobId == null ? 'No invoices yet. Create one from a job report.' : 'No invoices prepared for this job.'),
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
                      if (invoice.jobName.isNotEmpty) '${invoice.jobName}${invoice.jobDeleted ? ' · job deleted' : ''}',
                      if (invoice.customer.isNotEmpty) invoice.customer,
                      '${invoice.currency} ${invoice.total}',
                      if (invoice.createdLabel.isNotEmpty) 'Issued ${invoice.createdLabel}',
                      if (invoice.legacy) 'Billed work needs review',
                    ].join('\n'),
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute<void>(
                      builder: (_) => InvoiceScreen(invoiceId: invoice.id),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    ),
  );
}
