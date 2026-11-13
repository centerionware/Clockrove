import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'invoice_detail_screen.dart';
import 'quote_invoice_api.dart';

class QuoteRelatedInvoicesScreen extends ConsumerStatefulWidget {
  const QuoteRelatedInvoicesScreen({super.key, required this.quoteId});
  final String quoteId;
  @override
  ConsumerState<QuoteRelatedInvoicesScreen> createState() =>
      _QuoteRelatedInvoicesState();
}

class _QuoteRelatedInvoicesState
    extends ConsumerState<QuoteRelatedInvoicesScreen> {
  List<QuoteRelatedInvoice> _rows = [];
  QuoteInvoiceCursor? _next;
  bool _reading = false, _loaded = false, _failedMore = false;
  String? _error;
  @override
  void initState() {
    super.initState();
    _read();
  }

  Future<void> _read({bool more = false}) async {
    if (_reading) {
      return;
    }
    setState(() {
      _reading = true;
      _error = null;
    });
    try {
      final result = await ref
          .read(quoteInvoiceApiProvider)
          .invoices(widget.quoteId, before: more ? _next : null);
      if (mounted) {
        setState(() {
          final byId = {
            for (final row in (more ? _rows : <QuoteRelatedInvoice>[]))
              row.id: row,
          };
          for (final row in result.invoices) {
            byId[row.id] = row;
          }
          _rows = byId.values.toList();
          _next = result.next;
          _loaded = true;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _failedMore = more;
          _error = 'Invoice history could not be read. Retained results are shown; retry to load current history.';
        });
      }
    } finally {
      if (mounted) {
        setState(() => _reading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Related invoices'),
      actions: [
        IconButton(
          tooltip: 'Refresh quote invoices',
          onPressed: _reading ? null : _read,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(24),
      children: [
        const Text(
          'Original invoices and their replacements retain the accepted quote revision they came from. Open an invoice for current payment status, corrections and receipts.',
        ),
        if (_reading) const LinearProgressIndicator(),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_loaded && _rows.isEmpty)
          const Text(
            'No invoices from this quote yet. Prepare one from its eligible actual work.',
          ),
        for (final invoice in _rows)
          Card(
            child: ListTile(
              title: Text(invoice.number),
              subtitle: Text(
                'Quote revision ${invoice.revision}${invoice.id != invoice.originalId ? ' · Replacement with retained original quote provenance' : ''}\nIssued ${invoice.createdLabel}',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () async {
                await Navigator.push<void>(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => InvoiceDetailScreen(invoiceId: invoice.id),
                  ),
                );
                if (mounted) {
                  await _read();
                }
              },
            ),
          ),
        if (_next != null)
          OutlinedButton(
            onPressed: _reading ? null : () => _read(more: true),
            child: const Text('Load older quote invoices'),
          ),
        if (_error != null)
          TextButton(
            onPressed: _reading ? null : () => _read(more: _failedMore),
            child: const Text('Retry quote invoices'),
          ),
      ],
    ),
  );
}
