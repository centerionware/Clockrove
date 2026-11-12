import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'business_api.dart';
import 'quote_api.dart';
import 'quote_detail_screen.dart';
import 'quote_editor.dart';


class QuotesScreen extends ConsumerStatefulWidget {
  const QuotesScreen({super.key});
  @override
  ConsumerState<QuotesScreen> createState() => _QuotesScreenState();
}

class _QuotesScreenState extends ConsumerState<QuotesScreen> {
  final _entries = <QuoteSummary>[];
  QuoteCursor? _next;
  String? _status, _error;
  String _today = '', _timezone = '';
  bool _busy = false;
  @override
  void initState() { super.initState(); _load(reset: true); }
  Future<void> _load({bool reset = false}) async {
    if (_busy) { return; }
    setState(() { _busy = true; _error = null; });
    try {
      final page = await ref.read(quoteApiProvider).page(before: reset ? null : _next, status: _status);
      if (!mounted) { return; }
      setState(() {
        if (reset) { _entries.clear(); }
        _entries.addAll(page.entries); _next = page.next; _today = page.today; _timezone = page.timezone;
      });
    } catch (_) { if (mounted) { setState(() => _error = 'Quotes could not be loaded. Retry to refresh their current status.'); } }
    finally { if (mounted) { setState(() => _busy = false); } }
  }
  Future<void> _open(String id) async {
    await Navigator.push<void>(context, MaterialPageRoute<void>(builder: (_) => QuoteDetailScreen(quoteId: id)));
    if (mounted) { await _load(reset: true); }
  }
  Future<void> _create() async {
    final id = newBusinessId();
    final saved = await Navigator.push<bool>(context, MaterialPageRoute<bool>(builder: (_) => QuoteEditor(quoteId: id)));
    if (mounted && saved == true) { await _open(id); }
  }
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Quotes'), actions: [
      PopupMenuButton<String>(tooltip: 'Filter quotes', enabled: !_busy, onSelected: (value) {
        setState(() { _status = value == 'ALL' ? null : value; _entries.clear(); _next = null; }); _load(reset: true);
      }, itemBuilder: (_) => [
        const PopupMenuItem(value: 'ALL', child: Text('All quotes')),
        for (final status in ['DRAFT', 'ISSUED', 'ACCEPTED', 'DECLINED', 'EXPIRED']) PopupMenuItem(value: status, child: Text(quoteStatus(status))),
      ]),
      IconButton(tooltip: 'Refresh quotes', onPressed: _busy ? null : () => _load(reset: true), icon: const Icon(Icons.refresh)),
    ]),
    floatingActionButton: FloatingActionButton.extended(onPressed: _busy ? null : _create, icon: const Icon(Icons.add), label: const Text('New quote')),
    body: RefreshIndicator(onRefresh: () => _load(reset: true), child: ListView(
      physics: const AlwaysScrollableScrollPhysics(), padding: const EdgeInsets.fromLTRB(20, 16, 20, 110), children: [
        Text(_status == null ? 'Estimates and agreements' : quoteStatus(_status!), style: Theme.of(context).textTheme.headlineSmall),
        const Text('Quotes stay separate from worked hours, invoices and payments.'),
        if (_today.isNotEmpty) Text('Status as of $_today · $_timezone'),
        const SizedBox(height: 16),
        if (_busy) const LinearProgressIndicator(),
        if (_error != null) ...[Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          TextButton(onPressed: () => _load(reset: true), child: const Text('Retry quotes'))],
        if (!_busy && _error == null && _entries.isEmpty) const Padding(padding: EdgeInsets.symmetric(vertical: 32), child: Text('No quotes in this view. Create an estimate or choose another filter.')),
        for (final quote in _entries) Card(child: ListTile(
          title: Text(quote.number ?? 'Unissued draft'),
          subtitle: Text([quote.title, if (quote.customer.isNotEmpty) quote.customer,
            '${quote.currency} ${quote.total}', quoteStatus(quote.status),
            if (quote.revision != null) 'Latest issued revision ${quote.revision}',
            if (quote.validUntil != null) 'Valid until ${quote.validUntil}'].join('\n')),
          trailing: const Icon(Icons.chevron_right), onTap: _busy ? null : () => _open(quote.id),
        )),
        if (_next != null) OutlinedButton(onPressed: _busy ? null : () => _load(), child: const Text('Load older quotes')),
      ],
    )),
  );
}
