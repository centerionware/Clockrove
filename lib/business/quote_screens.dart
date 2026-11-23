import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
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
  void initState() {
    super.initState();
    _load(reset: true);
  }

  Future<void> _load({bool reset = false}) async {
    if (_busy) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final page = await ref
          .read(quoteApiProvider)
          .page(before: reset ? null : _next, status: _status);
      if (!mounted) {
        return;
      }
      setState(() {
        if (reset) {
          _entries.clear();
        }
        _entries.addAll(page.entries);
        _next = page.next;
        _today = page.today;
        _timezone = page.timezone;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _error = AppMessage.quoteListLoadFailed.of(context));
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _open(String id) async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(builder: (_) => QuoteDetailScreen(quoteId: id)),
    );
    if (mounted) {
      await _load(reset: true);
    }
  }

  Future<void> _create() async {
    final id = newBusinessId();
    final saved = await Navigator.push<bool>(
      context,
      MaterialPageRoute<bool>(builder: (_) => QuoteEditor(quoteId: id)),
    );
    if (mounted && saved == true) {
      await _open(id);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(AppMessage.quotes.of(context)),
      actions: [
        PopupMenuButton<String>(
          tooltip: AppMessage.quoteFilter.of(context),
          enabled: !_busy,
          onSelected: (value) {
            setState(() {
              _status = value == 'ALL' ? null : value;
              _entries.clear();
              _next = null;
            });
            _load(reset: true);
          },
          itemBuilder: (_) => [
            PopupMenuItem(
              value: 'ALL',
              child: Text(AppMessage.quoteFilterAll.of(context)),
            ),
            for (final status in [
              'DRAFT',
              'ISSUED',
              'ACCEPTED',
              'DECLINED',
              'EXPIRED',
            ])
              PopupMenuItem(
                value: status,
                child: Text(quoteStatusMessage(status).of(context)),
              ),
          ],
        ),
        IconButton(
          tooltip: AppMessage.quoteRefresh.of(context),
          onPressed: _busy ? null : () => _load(reset: true),
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    floatingActionButton: FloatingActionButton.extended(
      onPressed: _busy ? null : _create,
      icon: const Icon(Icons.add),
      label: Text(AppMessage.quoteCreate.of(context)),
    ),
    body: RefreshIndicator(
      onRefresh: () => _load(reset: true),
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 110),
        children: [
          Text(
            _status == null
                ? AppMessage.quoteOverview.of(context)
                : quoteStatusMessage(_status!).of(context),
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          Text(AppMessage.quoteSeparationNotice.of(context)),
          if (_today.isNotEmpty)
            Text(
              AppMessage.quoteStatusAsOf.format(context, {
                'date': _today,
                'timezone': _timezone,
              }),
            ),
          const SizedBox(height: 16),
          if (_busy) const LinearProgressIndicator(),
          if (_error != null) ...[
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
            TextButton(
              onPressed: () => _load(reset: true),
              child: Text(AppMessage.quoteRetry.of(context)),
            ),
          ],
          if (!_busy && _error == null && _entries.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 32),
              child: Text(AppMessage.quoteEmpty.of(context)),
            ),
          for (final quote in _entries)
            Card(
              child: ListTile(
                title: Text(
                  quote.number ?? AppMessage.quoteUnissuedDraft.of(context),
                ),
                subtitle: Text(
                  [
                    quote.title,
                    if (quote.customer.isNotEmpty) quote.customer,
                    '${quote.currency} ${quote.total}',
                    quoteStatusMessage(quote.status).of(context),
                    if (quote.revision != null)
                      AppMessage.quoteLatestIssuedRevision.format(context, {
                        'revision': quote.revision.toString(),
                      }),
                    if (quote.validUntil != null)
                      AppMessage.quoteValidUntil.format(context, {
                        'date': quote.validUntil!,
                      }),
                  ].join('\n'),
                ),
                trailing: const Icon(Icons.chevron_right),
                onTap: _busy ? null : () => _open(quote.id),
              ),
            ),
          if (_next != null)
            OutlinedButton(
              onPressed: _busy ? null : () => _load(),
              child: Text(AppMessage.quoteLoadOlder.of(context)),
            ),
        ],
      ),
    ),
  );
}
