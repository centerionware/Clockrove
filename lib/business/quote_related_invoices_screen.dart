import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
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
  AppMessage? _error;
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
          _error = AppMessage.quoteRelatedInvoiceReadFailed;
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
      title: Text(AppMessage.quoteRelatedInvoices.of(context)),
      actions: [
        IconButton(
          tooltip: AppMessage.refreshQuoteInvoices.of(context),
          onPressed: _reading ? null : _read,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text(AppMessage.quoteRelatedInvoiceHelp.of(context)),
        if (_reading) const LinearProgressIndicator(),
        if (_error != null)
          Text(
            _error!.of(context),
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_loaded && _rows.isEmpty)
          Text(AppMessage.quoteRelatedInvoiceEmpty.of(context)),
        for (final invoice in _rows)
          Card(
            child: ListTile(
              title: Text(invoice.number),
              subtitle: Text(
                [
                  [
                    AppMessage.quoteRelatedInvoiceRevision.format(context, {
                      'revision': invoice.revision.toString(),
                    }),
                    if (invoice.id != invoice.originalId)
                      AppMessage.quoteRelatedInvoiceReplacement.of(context),
                  ].join(' · '),
                  AppMessage.invoiceIssuedOn.format(context, {
                    'date': invoice.createdLabel,
                  }),
                ].join('\n'),
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
            child: Text(AppMessage.olderQuoteInvoices.of(context)),
          ),
        if (_error != null)
          TextButton(
            onPressed: _reading ? null : () => _read(more: _failedMore),
            child: Text(AppMessage.retryQuoteInvoices.of(context)),
          ),
      ],
    ),
  );
}
